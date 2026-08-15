package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.*
import com.sal7one.wrapperguard.core.util.Fingerprints
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * The recursive repository scanner. Ports old_project's ScriptGraphScanner.scanFile DFS engine
 * onto pure [java.nio.file.Path] (no IntelliJ VFS).
 *
 * Flow:
 *  1. Validate root, run [ProjectStructureAnalyzer].
 *  2. Walk the tree to find scan roots (wrapper scripts, settings, build scripts, catalog).
 *  3. DFS from each root with cycle detection (active set) and memoization (completed set).
 *     Each analyzed file is routed by [FileTypeRouter] to the right [Rule]s.
 *  4. Recurse into called scripts via [CalledScriptsResolver] + [SafePathResolver], recording
 *     [ScriptReference] edges and attaching call chains to findings.
 *  5. Apply [ScanPolicy] to compute each finding's policyAction, then derive the verdict.
 *
 * Suppression (`wrapperguard:ignore`) is handled inside the rules; the scanner only adds
 * recursion notices (circular dependency, depth limit, unresolved reference).
 */
class RepositoryScanner(
    private val mlScorer: MlScorer = NoopMlScorer
) : ScannerEngine {

    private val excludedDirectories = setOf(".git", ".gradle", ".idea", "build", "out", ".intellijPlatform", ".kotlin", "node_modules")

    override fun scan(
        projectRoot: Path,
        policy: ScanPolicy,
        settings: ScanSettings,
        baseline: List<BaselineEntry>
    ): ScanReport {
        val started = System.currentTimeMillis()
        val findings = mutableListOf<Finding>()
        val edges = mutableListOf<ScriptReference>()
        val notices = mutableListOf<ScanNotice>()
        val scannedFiles = linkedSetOf<Path>()

        if (!Files.exists(projectRoot) || !Files.isDirectory(projectRoot)) {
            return report(
                projectRoot, ScanVerdict.FAILED, policy, emptyList(), emptyList(),
                0, 0, complete = false,
                notices = listOf(ScanNotice(RuleIds.SCAN_ROOT, "Project path does not exist or is not a directory."))
            )
        }

        val normalizedRoot = projectRoot.toAbsolutePath().normalize()
        val relativeRoot = normalizedRoot.fileName?.toString() ?: normalizedRoot.toString()

        // Scanner-level structure findings.
        findings += ProjectStructureAnalyzer.analyze(normalizedRoot, relativeRoot, settings.trustedWrapperChecksums)

        // DFS state.
        val completed = mutableSetOf<Path>()
        val active = linkedSetOf<Path>()

        // Seed roots (wrapper scripts, settings, build scripts, catalog, init scripts).
        val roots = findScanRoots(normalizedRoot)
        if (roots.isEmpty()) {
            notices += ScanNotice(RuleIds.SCAN_ROOT, "No Gradle wrapper or build scripts found to scan.")
        }

        roots.forEach { root -> scanFile(normalizedRoot, root, settings, policy, depth = 0, callChain = emptyList(), findings, edges, notices, scannedFiles, completed, active) }

        // Apply policy action to every finding (also attaches ML signal for ps1 if present),
        // then apply the trusted baseline: matching fingerprints become SUPPRESSED and stop
        // blocking. Only baselines supplied by the caller (outside the repo) are honored.
        val withPolicy = findings.map { finding ->
            val withMl = finding
            withMl.copy(policyAction = policy.actionFor(withMl))
        }
        val baselineFingerprints = baseline.map { it.fingerprint }.toSet()
        val finalFindings = if (baselineFingerprints.isEmpty()) withPolicy else withPolicy.map { finding ->
            if (finding.fingerprint in baselineFingerprints) finding.copy(status = FindingStatus.SUPPRESSED)
            else finding
        }

        val durationMs = System.currentTimeMillis() - started
        val complete = notices.none { it.code == RuleIds.SCAN_FILE_SKIPPED || it.code == RuleIds.SCAN_FILE_READ }
        val verdict = when {
            !complete -> ScanVerdict.INCOMPLETE
            finalFindings.any { it.policyAction == PolicyAction.BLOCK && it.status == FindingStatus.ACTIVE } -> ScanVerdict.EXECUTION_BLOCKED
            finalFindings.any { it.policyAction == PolicyAction.REVIEW && it.status == FindingStatus.ACTIVE } -> ScanVerdict.REVIEW_REQUIRED
            else -> ScanVerdict.CLEAN
        }

        return report(projectRoot, verdict, policy, finalFindings, edges, scannedFiles.size, durationMs, complete, notices)
    }

    private fun scanFile(
        root: Path, file: Path, settings: ScanSettings, policy: ScanPolicy,
        depth: Int, callChain: List<CallChainLink>,
        findings: MutableList<Finding>, edges: MutableList<ScriptReference>,
        notices: MutableList<ScanNotice>, scannedFiles: MutableSet<Path>,
        completed: MutableSet<Path>, active: MutableSet<Path>
    ) {
        val normalized = file.toAbsolutePath().normalize()
        val relative = relativize(root, normalized)

        // Cycle detection.
        if (normalized in active) {
            findings += circularFinding(relative, callChain)
            return
        }
        // Memoization: each file analyzed at most once.
        if (normalized in completed) return

        active.add(normalized)
        scannedFiles.add(normalized)

        analyze(normalized, relative, settings, findings, callChain, notices)

        // Recurse into called scripts.
        if (settings.enableRecursiveScanning && depth < settings.maxScriptDepth) {
            val content = runCatching { Files.readString(normalized) }.getOrNull()
            if (content != null) {
                val source = TextSource(normalized, relative, content)
                CalledScriptsResolver.findCalledScripts(source).forEach { called ->
                    val resolved = SafePathResolver.resolve(root, normalized, called.path, settings.maxFileBytes)
                    val targetRelative = resolved?.let { relativize(root, it.toAbsolutePath().normalize()) }
                    if (resolved == null || targetRelative == null) {
                        findings += unresolvedFinding(relative, called)
                        return@forEach
                    }
                    edges += ScriptReference(
                        from = SourceLocation(relative, called.lineNumber),
                        to = SourceLocation(targetRelative),
                        viaCommand = called.rawLine
                    )
                    val nextChain = callChain + CallChainLink(SourceLocation(relative, called.lineNumber), called.rawLine)
                    scanFile(root, resolved, settings, policy, depth + 1, nextChain, findings, edges, notices, scannedFiles, completed, active)
                }
            }
        } else if (settings.enableRecursiveScanning && depth >= settings.maxScriptDepth) {
            findings += Finding(
                fingerprint = Fingerprints.finding(RuleIds.SCAN_DEPTH_LIMIT, relative, depth.toString()),
                ruleId = RuleIds.SCAN_DEPTH_LIMIT,
                title = "Maximum recursive script depth reached",
                message = "Recursive scan stopped at depth $depth.",
                severity = Severity.INFO, confidence = Confidence.HIGH,
                category = FindingCategory.AUDIT, kind = FindingKind.SCANNER_NOTICE,
                location = SourceLocation(relative),
                evidence = Evidence("depth=$depth"),
                callChain = callChain
            )
        }

        active.remove(normalized)
        completed.add(normalized)
    }

    /** Runs the file-type-routed rules, attaches call chains + ML signal, appends findings. */
    private fun analyze(
        file: Path, relative: String, settings: ScanSettings,
        findings: MutableList<Finding>, callChain: List<CallChainLink>,
        notices: MutableList<ScanNotice>
    ) {
        val rules = FileTypeRouter.rulesFor(file)
        if (rules.isEmpty()) return
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size > settings.maxFileBytes) {
            notices += ScanNotice(RuleIds.SCAN_FILE_SKIPPED, "File exceeds the configured size limit.", SourceLocation(relative))
            return
        }
        val content = runCatching { Files.readString(file) }.getOrElse {
            notices += ScanNotice(RuleIds.SCAN_FILE_READ, "Could not read file: ${it.message}", SourceLocation(relative))
            return
        }
        val source = TextSource(file, relative, content)
        val isPs1 = file.fileName.toString().endsWith(".ps1", ignoreCase = true)
        val mlSignal = if (isPs1) mlScorer.scorePowerShell(source) else null

        rules.forEach { rule ->
            rule.analyze(source, settings).forEach { finding ->
                val withChain = if (callChain.isEmpty()) finding else finding.copy(callChain = callChain)
                findings += if (mlSignal == null) withChain else withChain.copy(mlSignal = mlSignal)
            }
        }
    }

    private fun findScanRoots(root: Path): List<Path> {
        // Seed with the canonical Gradle entry points at the project root.
        val seeds: MutableList<Path> = listOf(
            "gradlew", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.properties",
            "settings.gradle", "settings.gradle.kts",
            "build.gradle", "build.gradle.kts",
            "gradle/libs.versions.toml",
            "init.gradle", "init.gradle.kts"
        ).map { root.resolve(it) }.filter { Files.exists(it) && Files.isRegularFile(it) }.toMutableList()

        // Walk the whole tree (excluding build/VCS dirs) for anything router-recognizable.
        // This catches fixtures and projects that keep gradlew/scripts in subdirectories, and
        // any standalone .sh/.bat/.ps1 wrapper/bootstrap scripts.
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && !isExcluded(root, it) }
                .filter { path -> FileTypeRouter.rulesFor(path).isNotEmpty() }
                .filter { path -> path !in seeds }
                .sorted() // deterministic order — must match the TypeScript port
                .forEach { seeds.add(it) }
        }
        return seeds.distinct()
    }

    private fun isExcluded(root: Path, file: Path): Boolean {
        val parts = root.relativize(file).map { it.toString() }
        return parts.any { it in excludedDirectories }
    }

    private fun circularFinding(relative: String, callChain: List<CallChainLink>): Finding = Finding(
        fingerprint = Fingerprints.finding(RuleIds.SCAN_CIRCULAR, relative, callChain.joinToString("→") { it.source.display }),
        ruleId = RuleIds.SCAN_CIRCULAR,
        title = "Circular script dependency detected",
        message = "A script dependency cycle was detected during recursive scanning.",
        severity = Severity.ERROR, confidence = Confidence.HIGH,
        category = FindingCategory.EXECUTION,
        location = SourceLocation(relative),
        evidence = Evidence(callChain.joinToString("\n") { "${it.source.display} → ${it.viaCommand}" }),
        remediation = "Break the cycle by removing one of the script references.",
        callChain = callChain
    )

    private fun unresolvedFinding(relative: String, called: CalledScript): Finding = Finding(
        fingerprint = Fingerprints.finding(RuleIds.SCAN_UNRESOLVED, relative, called.path),
        ruleId = RuleIds.SCAN_UNRESOLVED,
        title = "Referenced script could not be resolved safely",
        message = "A referenced script could not be resolved within the project or failed safety checks.",
        severity = Severity.WARN, confidence = Confidence.MEDIUM,
        category = FindingCategory.FILE_SYSTEM,
        location = SourceLocation(relative, called.lineNumber),
        evidence = Evidence(called.rawLine),
        remediation = "Check that the referenced path exists and stays inside the project."
    )

    private fun relativize(root: Path, file: Path): String {
        val normalized = file.toAbsolutePath().normalize()
        val normalizedRoot = root.toAbsolutePath().normalize()
        return if (normalized.startsWith(normalizedRoot)) {
            normalizedRoot.relativize(normalized).toString().replace('\\', '/')
        } else {
            normalized.toString()
        }
    }

    private fun report(
        projectRoot: Path, verdict: ScanVerdict, policy: ScanPolicy,
        findings: List<Finding>, edges: List<ScriptReference>,
        scannedFiles: Int, durationMs: Long, complete: Boolean,
        notices: List<ScanNotice>
    ): ScanReport {
        val mappedEdges = edges.map { ScriptEdge(it.from, it.to, it.viaCommand) }
        val sorted = findings.sortedWith(
            compareByDescending<Finding> { it.severity }
                .thenBy { it.location.path }
                .thenBy { it.location.line }
        )
        return ScanReport(
            projectPath = projectRoot.toAbsolutePath().normalize().toString(),
            verdict = verdict,
            policyName = policy.name,
            findings = sorted,
            edges = mappedEdges,
            scannedFiles = scannedFiles,
            durationMs = durationMs,
            complete = complete,
            stale = false,
            notices = notices
        )
    }
}
