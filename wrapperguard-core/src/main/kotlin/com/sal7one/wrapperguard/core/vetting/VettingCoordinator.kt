package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.methods.MethodCategory
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import com.sal7one.wrapperguard.core.util.Fingerprints
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs registered SDKs concurrently and owns policy, grants, identity and suppression.
 * Deadlines include queue time, bounding a scan even when an SDK ignores interruption.
 * Such SDKs cannot publish late results; daemon workers never prevent host shutdown.
 */
class VettingCoordinator(registrations: List<ProviderRegistration>, private val maxConcurrency: Int = 4) {
    private data class Registration(
        val provider: VettingProvider,
        val descriptor: ProviderDescriptor,
        val permissions: ProviderPermissions
    )
    private data class TimedResult(val result: ProviderResult, val completedAt: Long)
    private class Invocation {
        val started = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
    }
    private data class Pending(
        val registration: Registration,
        val submittedAt: Long,
        val future: Future<TimedResult>,
        val invocation: Invocation
    )

    private val registrations: List<Registration>

    init {
        require(maxConcurrency in 1..32) { "Provider concurrency must be between 1 and 32." }
        require(registrations.size <= 33) { "At most 32 external providers and the built-in provider may be registered." }
        require(registrations.count { it.provider !is BuiltInVettingProvider } <= 32) { "At most 32 external providers may be registered." }
        val identities = mutableSetOf<String>()
        this.registrations = registrations.map { registration ->
            val descriptor = registration.provider.descriptor
            require(descriptor.id.length <= 80 && ID.matches(descriptor.id)) { "Invalid provider identifier." }
            require(identities.add(descriptor.id)) { "Duplicate provider identifier." }
            require(descriptor.id != "wrapperguard" || registration.provider is BuiltInVettingProvider) {
                "The built-in provider identifier is reserved."
            }
            require(descriptor.apiVersion == 1) { "Unsupported vetting API version." }
            require(validMetadata(descriptor.displayName, 200) && validMetadata(descriptor.version, 80)) {
                "Invalid provider metadata."
            }
            require(registration.permissions.timeoutMs in 100..300_000) { "Provider timeout must be between 100 and 300000 milliseconds." }
            Registration(
                registration.provider,
                descriptor.copy(displayName = mask(descriptor.displayName), version = mask(descriptor.version)),
                registration.permissions.copy(actions = registration.permissions.actions.toSet())
            )
        }
    }

    fun scan(
        projectRoot: Path,
        policy: ScanPolicy,
        settings: ScanSettings,
        baseline: List<BaselineEntry> = emptyList(),
        operation: VettingOperation = VettingOperation.SCAN
    ): ScanReport {
        val started = System.nanoTime()
        val optionalMethods = SecurityMethodCatalog.methods.filter { it.category != MethodCategory.BUILT_IN }
        // Explicit off choices apply to the matching catalog provider only. Missing flags
        // preserve already configured SDKs and never grant or auto-enable a provider.
        val configured = this.registrations.map { registration ->
            val disabled = registration.provider !is BuiltInVettingProvider && optionalMethods.any {
                it.providerId == registration.descriptor.id && settings.methodFlags[it.id] == false
            }
            if (disabled) registration.copy(permissions = registration.permissions.copy(enabled = false)) else registration
        }
        val hasExternalAnalysis = configured.any { registration ->
            registration.permissions.enabled && registration.provider !is BuiltInVettingProvider &&
                optionalMethods.none { it.providerId == registration.descriptor.id && it.category in setOf(MethodCategory.RESEARCH, MethodCategory.AI) }
        }
        val allBuiltInOff = SecurityMethodCatalog.builtInMethods.all { settings.methodFlags[it.id] == false }
        val replaceBuiltIn = allBuiltInOff && hasExternalAnalysis
        val registrations = if (replaceBuiltIn) configured.map { registration ->
            if (registration.provider is BuiltInVettingProvider) registration.copy(permissions = registration.permissions.copy(enabled = false))
            else registration
        } else configured
        val root = projectRoot.toAbsolutePath().normalize()
        // The host keeps its own policy snapshot; providers receive separate copies.
        val hostPolicy = policy.copy(blockedRuleIds = policy.blockedRuleIds.toSet())
        val trustedBaseline = baseline.toList()
        val findings = mutableListOf<Finding>()
        val edges = mutableListOf<ScriptEdge>()
        val notices = mutableListOf<ScanNotice>()
        val runs = mutableListOf<ProviderRun>()
        var complete = true
        if (replaceBuiltIn && configured.any { it.provider is BuiltInVettingProvider && it.permissions.enabled }) {
            notices += ScanNotice("WG-METHOD-REPLACED", "Built-in methods are disabled; configured external analysis providers define the scan scope.")
        }
        val hasBuiltInAnalysis = registrations.any { it.provider is BuiltInVettingProvider && it.permissions.enabled } &&
            SecurityMethodCatalog.builtInMethods.any { SecurityMethodCatalog.isEnabled(it.id, settings.methodFlags) }
        if (!hasExternalAnalysis && !hasBuiltInAnalysis) {
            complete = false
            notices += ScanNotice("WG-NO-ANALYSIS-PROVIDERS", "No security analysis providers are enabled; advisory AI and research methods cannot establish scan coverage.")
        }
        optionalMethods.filter { method ->
            settings.methodFlags[method.id] == true && registrations.none { it.permissions.enabled && it.descriptor.id == method.providerId }
        }.forEach { method ->
            complete = false
            notices += ScanNotice("WG-METHOD-NOT-CONNECTED", "Security method is enabled but no provider is connected: ${method.id}.")
        }
        var scannedFiles = 0L
        val enabledCount = registrations.count { it.permissions.enabled }
        if (enabledCount == 0) {
            complete = false
            notices += ScanNotice("WG-NO-PROVIDERS", "No vetting providers are enabled; the project has not been vetted.")
        }

        val executor = Executors.newFixedThreadPool(minOf(maxConcurrency, maxOf(1, enabledCount))) { runnable ->
            Thread(runnable, "wrapperguard-vetting").apply { isDaemon = true }
        }
        val pending = linkedMapOf<String, Pending>()
        try {
            for (registration in registrations.filter { it.permissions.enabled }) {
                val request = VettingRequest(
                    root,
                    hostPolicy.copy(blockedRuleIds = hostPolicy.blockedRuleIds.toSet()),
                    copySettings(settings),
                    if (registration.provider is BuiltInVettingProvider) trustedBaseline.toList() else emptyList(),
                    operation
                )
                val submittedAt = System.nanoTime()
                val invocation = Invocation()
                val future = executor.submit<TimedResult> {
                    val quarantined = QUARANTINED[registration.descriptor.id]
                    check(quarantined == null || quarantined.completed.get()) { "Provider is still stopping a previous operation." }
                    if (quarantined != null) QUARANTINED.remove(registration.descriptor.id, quarantined)
                    check(ACTIVE_CALLBACKS.tryAcquire()) { "Provider execution capacity is unavailable." }
                    invocation.started.set(true)
                    try {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        TimedResult(snapshot(registration.provider.scan(request)), System.nanoTime())
                    } finally {
                        invocation.completed.set(true)
                        QUARANTINED.remove(registration.descriptor.id, invocation)
                        ACTIVE_CALLBACKS.release()
                    }
                }
                pending[registration.descriptor.id] = Pending(registration, submittedAt, future, invocation)
            }
            // Registration order determines output, never provider completion timing.
            for (registration in registrations) {
                if (!registration.permissions.enabled) {
                    runs += ProviderRun(registration.descriptor, registration.permissions, ProviderRunStatus.DISABLED)
                    continue
                }
                val task = pending.getValue(registration.descriptor.id)
                val budget = TimeUnit.MILLISECONDS.toNanos(registration.permissions.timeoutMs)
                var failureStatus: ProviderRunStatus? = null
                var failureMessage: String? = null
                var accepted: ProviderResult? = null
                var completedAt: Long? = null
                try {
                    val remaining = maxOf(0, budget - (System.nanoTime() - task.submittedAt))
                    val timed = task.future.get(remaining, TimeUnit.NANOSECONDS)
                    completedAt = timed.completedAt
                    if (timed.completedAt - task.submittedAt > budget) throw TimeoutException()
                    accepted = normalize(timed.result, registration, root, hostPolicy, trustedBaseline, operation)
                } catch (_: TimeoutException) {
                    cancelAndQuarantine(task)
                    failureStatus = ProviderRunStatus.TIMED_OUT
                    failureMessage = "Provider exceeded its configured time limit."
                } catch (_: InterruptedException) {
                    pending.values.forEach(::cancelAndQuarantine)
                    Thread.currentThread().interrupt()
                    failureStatus = ProviderRunStatus.FAILED
                    failureMessage = "Provider scan was interrupted."
                } catch (error: ExecutionException) {
                    task.future.cancel(true)
                    failureStatus = if (error.cause is TimeoutException) ProviderRunStatus.TIMED_OUT else ProviderRunStatus.FAILED
                    failureMessage = if (failureStatus == ProviderRunStatus.TIMED_OUT) "Provider exceeded its configured time limit."
                        else "Provider failed or returned an invalid response."
                } catch (_: Exception) {
                    task.future.cancel(true)
                    failureStatus = ProviderRunStatus.FAILED
                    // Provider exceptions may contain source, arguments or credentials.
                    failureMessage = "Provider failed or returned an invalid response."
                }
                val duration = TimeUnit.NANOSECONDS.toMillis((completedAt ?: System.nanoTime()) - task.submittedAt)
                if (accepted != null) {
                    findings += accepted.findings
                    edges += accepted.edges
                    notices += accepted.notices
                    scannedFiles += accepted.scannedFiles
                    complete = complete && accepted.complete
                    val message = if (accepted.complete) null else "Provider reported an incomplete scan."
                    if (!accepted.complete) {
                        notices += ScanNotice("WG-PROVIDER-INCOMPLETE", "${registration.descriptor.displayName}: $message")
                        requiredFailure(registration, hostPolicy, operation)?.let { findings += it }
                    }
                    runs += ProviderRun(registration.descriptor, registration.permissions, ProviderRunStatus.COMPLETED,
                        duration, accepted.findings.size, message)
                } else {
                    complete = false
                    val status = requireNotNull(failureStatus)
                    val message = requireNotNull(failureMessage)
                    runs += ProviderRun(registration.descriptor, registration.permissions, status, duration, message = message)
                    notices += ScanNotice(
                        if (status == ProviderRunStatus.TIMED_OUT) "WG-PROVIDER-TIMEOUT" else "WG-PROVIDER-FAILED",
                        "${registration.descriptor.displayName}: $message"
                    )
                    requiredFailure(registration, hostPolicy, operation)?.let { findings += it }
                }
            }
        } finally {
            pending.values.filter { !it.future.isDone }.forEach(::cancelAndQuarantine)
            executor.shutdownNow()
        }
        val verdict = when {
            findings.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.BLOCK } -> ScanVerdict.EXECUTION_BLOCKED
            !complete -> ScanVerdict.INCOMPLETE
            findings.any { it.status == FindingStatus.ACTIVE && it.policyAction == PolicyAction.REVIEW } -> ScanVerdict.REVIEW_REQUIRED
            else -> ScanVerdict.CLEAN
        }
        return ScanReport(
            projectPath = mask(root.toString()), verdict = verdict, policyName = mask(hostPolicy.name),
            findings = findings, edges = edges, scannedFiles = minOf(scannedFiles, Int.MAX_VALUE.toLong()).toInt(),
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), complete = complete,
            notices = notices, providerRuns = runs
        )
    }

    private fun normalize(
        result: ProviderResult, registration: Registration, root: Path, policy: ScanPolicy,
        baseline: List<BaselineEntry>, operation: VettingOperation
    ): ProviderResult {
        require(result.scannedFiles >= 0 && result.findings.size <= 50_000 && result.edges.size <= 100_000 && result.notices.size <= 1_000)
        val builtIn = registration.provider is BuiltInVettingProvider
        val approved = baseline.map { it.fingerprint }.toSet()
        return result.copy(
            findings = result.findings.map { input ->
                require(RULE_ID.matches(input.ruleId))
                require(input.callChain.size <= 1_000)
                require(input.evidence == null || input.evidence.text.length <= MAX_TEXT)
                require(input.mlSignal == null || (input.mlSignal.score.isFinite() && input.mlSignal.labels.size <= 1_000))
                val location = if (builtIn) input.location else normalizeLocation(input.location, root)
                val fingerprint = if (builtIn) input.fingerprint else Fingerprints.finding(
                    "${registration.descriptor.id}:${input.ruleId}", location.path,
                    input.evidence?.text ?: "${input.title}\n${input.message}"
                )
                val status = when {
                    builtIn -> input.status
                    fingerprint in approved -> FindingStatus.SUPPRESSED
                    else -> FindingStatus.ACTIVE
                }
                val sanitized = input.copy(
                    fingerprint = fingerprint, providerId = registration.descriptor.id, status = status,
                    title = cleanText(input.title), message = cleanText(input.message),
                    location = redactLocation(location),
                    evidence = input.evidence?.let { value ->
                        val redacted = CredentialRedaction.mask(value.text)
                        require(redacted.first.length <= MAX_TEXT)
                        Evidence(redacted.first, value.redacted || redacted.second)
                    },
                    remediation = input.remediation?.let(::cleanText),
                    callChain = input.callChain.map { link ->
                        CallChainLink(redactLocation(if (builtIn) link.source else normalizeLocation(link.source, root)), cleanText(link.viaCommand))
                    },
                    mlSignal = input.mlSignal?.let { signal ->
                        signal.copy(modelId = cleanText(signal.modelId), labels = signal.labels.map(::cleanText))
                    }
                )
                authorize(sanitized, input.actions, registration.permissions, policy, operation)
            },
            edges = result.edges.map { edge ->
                ScriptEdge(
                    redactLocation(if (builtIn) edge.from else normalizeLocation(edge.from, root)),
                    redactLocation(if (builtIn) edge.to else normalizeLocation(edge.to, root)),
                    cleanText(edge.viaCommand)
                )
            },
            notices = result.notices.map { notice ->
                notice.copy(code = cleanText(notice.code), message = cleanText(notice.message),
                    location = notice.location?.let { redactLocation(if (builtIn) it else normalizeLocation(it, root)) })
            }
        )
    }

    private fun authorize(
        finding: Finding, requested: Set<VettingAction>, permissions: ProviderPermissions,
        policy: ScanPolicy, operation: VettingOperation
    ): Finding {
        if (finding.mlSignal != null) {
            return finding.copy(kind = FindingKind.AUDIT_EVIDENCE, policyAction = PolicyAction.ALLOW, actions = emptySet())
        }
        val hostAction = policy.actionFor(finding)
        val allowed = requested.intersect(permissions.actions).filterTo(linkedSetOf()) { action ->
            when (action) {
                VettingAction.BLOCK_BUILD -> hostAction == PolicyAction.BLOCK && operation != VettingOperation.SYNC
                VettingAction.BLOCK_SYNC -> hostAction == PolicyAction.BLOCK && operation != VettingOperation.BUILD
                else -> true
            }
        }
        val action = when {
            hostAction == PolicyAction.ALLOW -> PolicyAction.ALLOW
            allowed.any { it == VettingAction.BLOCK_BUILD || it == VettingAction.BLOCK_SYNC } -> PolicyAction.BLOCK
            allowed.any { it == VettingAction.WARNING || it == VettingAction.MAJOR_WARNING || it == VettingAction.ALERT } -> PolicyAction.REVIEW
            else -> PolicyAction.ALLOW
        }
        return finding.copy(policyAction = action, actions = allowed)
    }

    private fun requiredFailure(registration: Registration, policy: ScanPolicy, operation: VettingOperation): Finding? {
        if (!registration.permissions.required) return null
        val failure = Finding(
            fingerprint = Fingerprints.finding("${registration.descriptor.id}:WG-PROVIDER-UNAVAILABLE", ".", "required-provider-unavailable"),
            ruleId = "WG-PROVIDER-UNAVAILABLE", title = "Required vetting provider unavailable",
            message = "${registration.descriptor.displayName} could not complete the required vetting operation.",
            severity = Severity.ERROR, confidence = Confidence.HIGH, category = FindingCategory.CONFIGURATION,
            location = SourceLocation("."), providerId = registration.descriptor.id
        )
        val authorized = authorize(failure, setOf(VettingAction.BLOCK_BUILD, VettingAction.BLOCK_SYNC), registration.permissions, policy, operation)
        return authorized.takeIf { it.policyAction == PolicyAction.BLOCK }
    }

    private fun normalizeLocation(location: SourceLocation, root: Path): SourceLocation {
        require(location.path.isNotBlank() && location.path.length <= 4_096)
        require(location.line == null || location.line > 0)
        require(location.column == null || location.column > 0)
        val raw = Path.of(location.path.replace('\\', '/'))
        val absolute = (if (raw.isAbsolute) raw else root.resolve(raw)).normalize()
        require(absolute.startsWith(root))
        return location.copy(path = root.relativize(absolute).toString().replace('\\', '/').ifEmpty { "." })
    }

    private fun redactLocation(location: SourceLocation) = location.copy(path = cleanText(location.path))

    private fun cancelAndQuarantine(task: Pending) {
        task.future.cancel(true)
        QUARANTINED.compute(task.registration.descriptor.id) { _, current ->
            if (task.invocation.started.get() && !task.invocation.completed.get()) task.invocation else current
        }
    }

    private fun snapshot(result: ProviderResult): ProviderResult {
        require(result.scannedFiles >= 0 && result.findings.size <= 50_000 && result.edges.size <= 100_000 && result.notices.size <= 1_000)
        return result.copy(
            findings = result.findings.map {
                require(it.callChain.size <= 1_000 && (it.mlSignal?.labels?.size ?: 0) <= 1_000)
                require(it.title.length <= MAX_TEXT && it.message.length <= MAX_TEXT && (it.evidence?.text?.length ?: 0) <= MAX_TEXT)
                it.copy(actions = it.actions.toSet(), callChain = it.callChain.toList(),
                    mlSignal = it.mlSignal?.let { signal -> signal.copy(labels = signal.labels.toList()) })
            },
            edges = result.edges.toList(), notices = result.notices.toList()
        )
    }

    private fun copySettings(settings: ScanSettings) = settings.copy(
        allowedHosts = settings.allowedHosts.toSet(), allowedUrlPatterns = settings.allowedUrlPatterns.toList(),
        blockedCommands = settings.blockedCommands.toSet(), knownSafeRepositoryHosts = settings.knownSafeRepositoryHosts.toSet(),
        trustedWrapperChecksums = settings.trustedWrapperChecksums.toMap(), methodFlags = settings.methodFlags.toMap()
    )

    companion object {
        // An in-process callback cannot safely be killed. Bound surviving callbacks across
        // repeated scans and quarantine timed-out identities until their invocation exits.
        private val ACTIVE_CALLBACKS = Semaphore(32)
        private val QUARANTINED = ConcurrentHashMap<String, Invocation>()
        private val ID = Regex("[a-z][a-z0-9._-]{0,79}")
        private val RULE_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}")
        private const val MAX_TEXT = 65_536
        private fun validMetadata(value: String, limit: Int) = value.isNotBlank() && value.length <= limit && value.none { it.isISOControl() }
        private fun mask(value: String) = CredentialRedaction.mask(value).first
        private fun cleanText(value: String): String {
            require(value.length <= MAX_TEXT)
            return mask(value)
        }
    }
}
