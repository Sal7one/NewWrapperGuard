package com.sal7one.wrapperguard.cli

import com.sal7one.wrapperguard.core.methods.MethodConfiguration
import com.sal7one.wrapperguard.core.export.SarifExporter
import com.sal7one.wrapperguard.core.model.BaselineEntry
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.vetting.ProviderConfiguration
import com.sal7one.wrapperguard.core.vetting.VettingCoordinator
import com.sal7one.wrapperguard.core.vetting.VettingOperation
import com.sal7one.wrapperguard.rules.RepositoryScanner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val JSON = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) {
    exitProcess(runCli(args))
}

internal fun runCli(args: Array<String>, output: PrintStream = System.out, error: PrintStream = System.err): Int {
    return try {
        val options = CliOptions.parse(args)
        if (options.help) { output.println(HELP); return 0 }
        if (options.version) { output.println("WrapperGuard 0.5.0"); return 0 }
        val root = options.project.toRealPath()
        require(Files.isDirectory(root))
        val configuration = options.providers?.let { ProviderConfiguration.load(it, root) } ?: ProviderConfiguration()
        val baseline = options.baseline?.let { loadBaseline(it, root) } ?: emptyList()
        val methods = options.methods?.let { MethodConfiguration.load(it, root) } ?: MethodConfiguration()
        val policy = ScanPolicy(mode = if (options.enforce) EnforcementMode.ENFORCE else EnforcementMode.WARN)
        val report = VettingCoordinator(configuration.registrations(RepositoryScanner()))
            .scan(root, policy, ScanSettings(methodFlags = methods.methodFlags), baseline, options.operation)
        when {
            options.sarif -> output.println(JSON.encodeToString(SarifExporter.toSarif(report)))
            options.json -> output.println(JSON.encodeToString(report))
            else -> {
                output.println("WrapperGuard: ${report.verdict} — ${report.findings.size} findings in ${report.scannedFiles} files")
                report.providerRuns.forEach {
                    output.println("PROVIDER\t${it.descriptor.id}\t${it.status}\t${it.findingCount} findings\t${it.durationMs}ms")
                }
                report.findings.forEach {
                    output.println("${it.severity}\t${it.providerId ?: "wrapperguard"}\t${it.ruleId}\t${it.location.display}\t${it.title}\t${it.policyAction}\t${it.status}")
                }
                report.notices.forEach { output.println("NOTICE\t${it.code}\t${it.message}") }
            }
        }
        when {
            report.blockers.isNotEmpty() -> 2
            !report.complete -> 1
            else -> 0
        }
    } catch (exception: CliUsageException) {
        error.println("WrapperGuard: ${exception.message} Use --help for usage.")
        1
    } catch (_: Exception) {
        // Provider errors may contain command arguments or source data. Never echo them.
        error.println("WrapperGuard: scan could not complete. Check the project path and trusted baseline/provider/method configuration.")
        1
    }
}

private fun loadBaseline(path: Path, projectRoot: Path): List<BaselineEntry> {
    val file = path.toRealPath()
    require(!file.startsWith(projectRoot)) { "Trusted baselines must be outside the scanned repository." }
    require(Files.isRegularFile(file) && Files.size(file) <= 2_097_152)
    val bytes = Files.newInputStream(file).use { it.readNBytes(2_097_153) }
    require(bytes.size <= 2_097_152)
    return JSON.decodeFromString<List<BaselineEntry>>(bytes.toString(Charsets.UTF_8))
}

internal data class CliOptions(
    val project: Path = Path.of("."),
    val baseline: Path? = null,
    val providers: Path? = null,
    val methods: Path? = null,
    val operation: VettingOperation = VettingOperation.SCAN,
    val enforce: Boolean = false,
    val json: Boolean = false,
    val sarif: Boolean = false,
    val help: Boolean = false,
    val version: Boolean = false
) {
    companion object {
        fun parse(args: Array<String>): CliOptions {
            var result = CliOptions()
            var projectSeen = false
            var positionalOnly = false
            val seen = mutableSetOf<String>()
            var index = 0
            while (index < args.size) {
                val argument = args[index++]
                if (argument == "--" && !positionalOnly) { positionalOnly = true; continue }
                if (!positionalOnly && argument.startsWith("-")) {
                    if (!seen.add(argument)) throw CliUsageException("Duplicate option.")
                    fun value(): String = args.getOrNull(index++)?.takeUnless { it.startsWith("--") }
                        ?: throw CliUsageException("Missing option value.")
                    result = when (argument) {
                        "--baseline" -> result.copy(baseline = Path.of(value()))
                        "--providers" -> result.copy(providers = Path.of(value()))
                        "--methods" -> result.copy(methods = Path.of(value()))
                        "--operation" -> result.copy(operation = when (value().lowercase()) {
                            "scan" -> VettingOperation.SCAN
                            "build" -> VettingOperation.BUILD
                            "sync" -> VettingOperation.SYNC
                            else -> throw CliUsageException("Operation must be scan, build, or sync.")
                        })
                        "--json" -> result.copy(json = true)
                        "--sarif" -> result.copy(sarif = true)
                        "--enforce" -> result.copy(enforce = true)
                        "--help", "-h" -> result.copy(help = true)
                        "--version" -> result.copy(version = true)
                        else -> throw CliUsageException("Unknown option.")
                    }
                } else {
                    if (projectSeen) throw CliUsageException("Supply exactly one project directory.")
                    result = result.copy(project = Path.of(argument))
                    projectSeen = true
                }
            }
            if (result.json && result.sarif) throw CliUsageException("Choose either JSON or SARIF output.")
            return result
        }
    }
}

private class CliUsageException(message: String) : IllegalArgumentException(message)

private val HELP = """
    WrapperGuard 0.5.0 — provider-based build and sync vetting
    Usage: wrapperguard-cli [options] [project-directory]

      --providers <absolute-file>  User-owned JSON configuration outside the project
      --methods <absolute-file>    User-owned method flags outside the project
      --operation scan|build|sync Evaluate grants for this operation (default: scan)
      --enforce                   Enable granted build/sync blocking
      --baseline <file>           Trusted approval list outside the project
      --json                     Canonical report including provider runs
      --sarif                    SARIF 2.1.0 report
      --version                  Print version
      --help                     Show this help

    The built-in static scanner runs by default. External commands require explicit
    user configuration and execute with your privileges. No commands are auto-discovered.
    Exit codes: 0 complete with no blockers; 2 blocked; 1 invalid input or incomplete scan.
""".trimIndent()
