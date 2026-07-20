package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.spi.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

class RepositoryScanner(
    private val rules: List<Rule> = BuiltInRules.all,
    private val mlScorer: MlScorer = NoopMlScorer,
    private val maxFileBytes: Long = 1_000_000
) : ScannerEngine {

    private val supportedNames = setOf("gradlew", "gradlew.bat")
    private val supportedExtensions = setOf("sh", "bash", "zsh", "ps1", "psm1", "bat", "cmd", "gradle", "kts", "properties", "toml")
    private val excludedDirectories = setOf(".git", ".gradle", ".idea", "build", "node_modules", "out")

    override fun scan(projectRoot: Path, policy: ScanPolicy): ScanReport {
        val started = System.currentTimeMillis()
        val findings = mutableListOf<Finding>()
        val notices = mutableListOf<ScanNotice>()
        var scanned = 0

        if (!projectRoot.exists() || !projectRoot.isDirectory()) {
            return ScanReport(
                projectPath = projectRoot.toString(),
                verdict = ScanVerdict.FAILED,
                policyName = policy.name,
                findings = emptyList(),
                scannedFiles = 0,
                durationMs = 0,
                complete = false,
                notices = listOf(ScanNotice("WG-SCAN-ROOT", "Project path does not exist or is not a directory."))
            )
        }

        Files.walk(projectRoot).use { paths ->
            paths.filter { it.isRegularFile() && isSupported(projectRoot, it) }.forEach { path ->
                val relative = projectRoot.relativize(path).invariantSeparatorsPathString
                runCatching {
                    val size = path.fileSize()
                    if (size > maxFileBytes) {
                        notices += ScanNotice("WG-FILE-SKIPPED", "File exceeds the configured size limit.", SourceLocation(relative))
                        return@forEach
                    }
                    val source = TextSource(path, relative, path.readText())
                    scanned++
                    val deterministic = rules.flatMap { it.analyze(source) }
                    val mlSignal = if (path.extension.equals("ps1", true)) mlScorer.scorePowerShell(source) else null
                    findings += deterministic.map { finding ->
                        val withMl = if (mlSignal == null) finding else finding.copy(mlSignal = mlSignal)
                        withMl.copy(policyAction = policy.actionFor(withMl))
                    }
                }.onFailure {
                    notices += ScanNotice("WG-FILE-READ", "Could not read file: ${it.message}", SourceLocation(relative))
                }
            }
        }

        val complete = notices.none { it.code == "WG-FILE-SKIPPED" || it.code == "WG-FILE-READ" }
        val verdict = when {
            !complete -> ScanVerdict.INCOMPLETE
            findings.any { it.policyAction == PolicyAction.BLOCK } -> ScanVerdict.EXECUTION_BLOCKED
            findings.any { it.policyAction == PolicyAction.REVIEW } -> ScanVerdict.REVIEW_REQUIRED
            else -> ScanVerdict.CLEAN
        }

        return ScanReport(
            projectPath = projectRoot.toAbsolutePath().normalize().toString(),
            verdict = verdict,
            policyName = policy.name,
            findings = findings.sortedWith(compareByDescending<Finding> { it.severity }.thenBy { it.location.path }.thenBy { it.location.line }),
            scannedFiles = scanned,
            durationMs = System.currentTimeMillis() - started,
            complete = complete,
            notices = notices
        )
    }

    private fun isSupported(root: Path, file: Path): Boolean {
        val relativeParts = root.relativize(file).map { it.toString() }
        if (relativeParts.any { it in excludedDirectories }) return false
        return file.name in supportedNames || file.extension.lowercase() in supportedExtensions
    }
}
