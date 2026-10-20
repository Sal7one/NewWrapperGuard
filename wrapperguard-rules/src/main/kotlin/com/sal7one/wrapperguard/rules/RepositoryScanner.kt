package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.methods.SecurityMethodIds
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.*
import com.sal7one.wrapperguard.core.util.Fingerprints
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
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
    // Retained for source compatibility. Legacy scores must not be attached to rule findings:
    // doing so would turn a deterministic blocker into shadow/audit evidence in the host.
    @Suppress("unused") private val mlScorer: MlScorer = NoopMlScorer
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

        val normalizedRoot = runCatching { projectRoot.toRealPath() }.getOrElse {
            return report(projectRoot, ScanVerdict.FAILED, policy, emptyList(), emptyList(), 0, 0, false,
                listOf(ScanNotice(RuleIds.SCAN_FILE_READ, "Could not inspect a project directory; the scan is incomplete.", SourceLocation("."))))
        }
        val relativeRoot = normalizedRoot.fileName?.toString() ?: normalizedRoot.toString()

        SecurityMethodCatalog.builtInMethods.filterNot { SecurityMethodCatalog.isEnabled(it.id, settings.methodFlags) }.forEach {
            notices += ScanNotice(RuleIds.METHOD_DISABLED, "Security method disabled: ${it.id}. Coverage is reduced.")
        }

        if (SecurityMethodCatalog.builtInMethods.none { SecurityMethodCatalog.isEnabled(it.id, settings.methodFlags) }) {
            notices += ScanNotice(RuleIds.METHOD_NONE, "No built-in security methods are enabled.")
        }

        // Scanner-level structure checks belong to wrapper integrity only.
        if (SecurityMethodCatalog.isEnabled(SecurityMethodIds.WRAPPER_INTEGRITY, settings.methodFlags)) {
            findings += ProjectStructureAnalyzer.analyze(normalizedRoot, relativeRoot, settings.trustedWrapperChecksums, notices, settings.maxFileBytes)
        }

        // DFS state.
        val completed = mutableSetOf<Path>()
        val active = linkedSetOf<Path>()

        // Seed roots (wrapper scripts, settings, build scripts, catalog, init scripts).
        val roots = findScanRoots(normalizedRoot, settings, notices)
        if (roots.isEmpty()) {
            notices += ScanNotice(RuleIds.SCAN_ROOT, "No Gradle wrapper or build scripts found to scan.")
        }

        roots.forEach { root -> scanFile(normalizedRoot, root, settings, policy, depth = 0, callChain = emptyList(), findings, edges, notices, scannedFiles, completed, active) }

        // Apply policy to deterministic findings; optional model output is a separate provider,
        // then apply the trusted baseline: matching fingerprints become SUPPRESSED and stop
        // blocking. Only baselines supplied by the caller (outside the repo) are honored.
        val withPolicy = findings.map { finding -> finding.copy(policyAction = policy.actionFor(finding)) }
        val baselineFingerprints = baseline.map { it.fingerprint }.toSet()
        val finalFindings = if (baselineFingerprints.isEmpty()) withPolicy else withPolicy.map { finding ->
            if (finding.fingerprint in baselineFingerprints) finding.copy(status = FindingStatus.SUPPRESSED)
            else finding
        }

        val durationMs = System.currentTimeMillis() - started
        val complete = notices.none { it.code in setOf(RuleIds.SCAN_FILE_SKIPPED, RuleIds.SCAN_FILE_READ, RuleIds.METHOD_NONE, RuleIds.MAVEN_UNSUPPORTED, RuleIds.SCAN_LIMIT) }
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
        val graphEnabled = SecurityMethodCatalog.isEnabled(SecurityMethodIds.SCRIPT_GRAPH, settings.methodFlags)
        if (FileTypeRouter.rulesFor(file, settings).isEmpty() && !graphEnabled) return

        if (ProjectFileAccess.status(root, normalized) != ProjectFileAccess.Status.REGULAR) {
            rejectedInput(relative, notices)
            return
        }

        // Cycle detection.
        if (normalized in active) {
            findings += circularFinding(relative, callChain)
            return
        }
        // Memoization: each file analyzed at most once.
        if (normalized in completed) return
        if (scannedFiles.size >= settings.maxFilesPerScan) {
            fileLimitNotice(settings, notices)
            return
        }

        active.add(normalized)
        scannedFiles.add(normalized)

        analyze(root, normalized, relative, settings, findings, callChain, notices)

        // Recurse into called scripts.
        if (graphEnabled && settings.enableRecursiveScanning && depth < settings.maxScriptDepth) {
            val content = runCatching { readTextSmart(root, normalized, settings.maxFileBytes) }.getOrElse {
                rejectedInput(relative, notices)
                null
            }
            if (content != null) {
                val source = TextSource(normalized, relative, content)
                CalledScriptsResolver.findCalledScripts(source).forEach { called ->
                    val resolved = SafePathResolver.resolve(root, normalized, called.path, settings.maxFileBytes)
                    val targetRelative = resolved?.let { relativize(root, it.toAbsolutePath().normalize()) }
                    if (resolved == null || targetRelative == null) {
                        notices += ScanNotice(RuleIds.SCAN_FILE_SKIPPED, "A referenced script could not be inspected safely.", SourceLocation(relative, called.lineNumber))
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
        } else if (graphEnabled && settings.enableRecursiveScanning && depth >= settings.maxScriptDepth) {
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

    /** Runs enabled file-type rules and attaches deterministic call chains. */
    private fun analyze(
        root: Path, file: Path, relative: String, settings: ScanSettings,
        findings: MutableList<Finding>, callChain: List<CallChainLink>,
        notices: MutableList<ScanNotice>
    ) {
        val rules = FileTypeRouter.rulesFor(file, settings)
        if (rules.isEmpty()) return
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (size > settings.maxFileBytes) {
            notices += ScanNotice(RuleIds.SCAN_FILE_SKIPPED, "File exceeds the configured size limit.", SourceLocation(relative))
            return
        }
        val content = runCatching { readTextSmart(root, file, settings.maxFileBytes) }.getOrElse {
            notices += ScanNotice(RuleIds.SCAN_FILE_READ, "Could not read a project input safely.", SourceLocation(relative))
            return
        }
        // Hardening: binary/null-byte content is not analyzed as text.
        if (content.indexOf('\u0000') >= 0) {
            notices += ScanNotice(RuleIds.SCAN_FILE_READ, "File contains null bytes (binary content).", SourceLocation(relative))
            return
        }
        // Hardening: reject single oversized lines instead of feeding them to regexes (ReDoS budget).
        if (content.lines().any { it.length > settings.maxLineLength }) {
            notices += ScanNotice(
                RuleIds.SCAN_FILE_SKIPPED,
                "File contains a line longer than ${settings.maxLineLength} characters.",
                SourceLocation(relative)
            )
            return
        }
        val source = TextSource(file, relative, content)
        if (rules.any { it === MavenPomRules } && !MavenPomRules.isSupported(source)) {
            notices += ScanNotice(RuleIds.MAVEN_UNSUPPORTED, "Maven POM could not be analyzed safely; dependency resolution and external entities are never executed.", SourceLocation(relative))
            return
        }

        rules.forEach { rule ->
            rule.analyze(source, settings).forEach { finding ->
                val withChain = if (callChain.isEmpty()) finding else finding.copy(callChain = callChain)
                findings += withChain
            }
        }
    }

    private fun findScanRoots(root: Path, settings: ScanSettings, notices: MutableList<ScanNotice>): List<Path> {
        require(settings.maxFilesPerScan > 0 && settings.maxDirectoryDepth >= 0)
        // Canonical entry points respect the same configured limits as discovered files.
        val canonical = listOf(
            "gradlew", "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.properties",
            "settings.gradle", "settings.gradle.kts",
            "build.gradle", "build.gradle.kts",
            "gradle/libs.versions.toml",
            "init.gradle", "init.gradle.kts", "pom.xml"
        ).map { root.resolve(it) }.filter { root.relativize(it).nameCount <= settings.maxDirectoryDepth }
            .filter { path ->
                val status = ProjectFileAccess.status(root, path)
                if (status == ProjectFileAccess.Status.REJECTED) rejectedInput(relativize(root, path), notices)
                status == ProjectFileAccess.Status.REGULAR
            }
        val seeds = canonical.take(settings.maxFilesPerScan).toMutableList()

        // Visit without following links, skip excluded trees, and retain readable siblings
        // when one directory cannot be inspected. Sorted discovery preserves stable output.
        var capped = canonical.size > settings.maxFilesPerScan
        var depthCapped = false
        val discovered = mutableListOf<Path>()
        fun directoryFailure(path: Path) {
            val notice = ScanNotice(RuleIds.SCAN_FILE_READ, "Could not inspect a project directory; the scan is incomplete.", SourceLocation(relativize(root, path).ifEmpty { "." }))
            if (notice !in notices) notices += notice
        }
        try {
            Files.walkFileTree(root, emptySet(), settings.maxDirectoryDepth, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult =
                    if (isExcluded(root, path)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (isExcluded(root, path)) return FileVisitResult.CONTINUE
                    if (attributes.isSymbolicLink) {
                        rejectedInput(relativize(root, path), notices)
                    } else if (attributes.isDirectory) {
                        try { Files.newDirectoryStream(path).use { entries -> if (entries.any { !isExcluded(root, it) }) depthCapped = true } }
                        catch (_: Exception) { directoryFailure(path) }
                    } else if (FileTypeRouter.rulesFor(path).isNotEmpty() && path !in seeds) {
                        if (ProjectFileAccess.status(root, path) == ProjectFileAccess.Status.REGULAR) discovered.add(path)
                        else rejectedInput(relativize(root, path), notices)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(path: Path, error: IOException): FileVisitResult {
                    directoryFailure(path)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(path: Path, error: IOException?): FileVisitResult {
                    if (error != null) directoryFailure(path)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Exception) { directoryFailure(root) }
        discovered.sorted().forEach { path ->
            if (seeds.size >= settings.maxFilesPerScan) capped = true else seeds.add(path)
        }
        if (capped) fileLimitNotice(settings, notices)
        if (depthCapped) notices += ScanNotice(RuleIds.SCAN_LIMIT,
            "Directory depth limit reached (${settings.maxDirectoryDepth}); the scan is incomplete.")
        return seeds.distinct()
    }

    private fun fileLimitNotice(settings: ScanSettings, notices: MutableList<ScanNotice>) {
        val notice = ScanNotice(RuleIds.SCAN_LIMIT,
            "File count limit reached (${settings.maxFilesPerScan}); the scan is incomplete.")
        if (notice !in notices) notices += notice
    }

    private fun rejectedInput(relative: String, notices: MutableList<ScanNotice>) {
        val notice = ScanNotice(RuleIds.SCAN_FILE_SKIPPED, "File is not a readable regular input inside the project.", SourceLocation(relative))
        if (notice !in notices) notices += notice
    }

    /** Reads text honoring UTF-8 BOM and UTF-16 LE/BE encodings (hostile-input hardening). */
    private fun readTextSmart(root: Path, file: Path, maximumBytes: Long): String {
        val bytes = ProjectFileAccess.read(root, file, maximumBytes)
        return when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.UTF_8)
        }
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
