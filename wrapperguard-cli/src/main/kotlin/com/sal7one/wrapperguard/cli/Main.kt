package com.sal7one.wrapperguard.cli

import com.sal7one.wrapperguard.core.methods.MethodConfiguration
import com.sal7one.wrapperguard.core.export.SarifExporter
import com.sal7one.wrapperguard.core.model.BaselineEntry
import com.sal7one.wrapperguard.core.model.ScanNotice
import com.sal7one.wrapperguard.core.model.ScanVerdict
import com.sal7one.wrapperguard.core.inputs.ProviderInputSealer
import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.vetting.ProviderConfiguration
import com.sal7one.wrapperguard.core.vetting.ProviderConformance
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
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
        if (options.version) { output.println("WrapperGuard 0.6.0"); return 0 }
        val root = options.project.toRealPath()
        require(Files.isDirectory(root))
        // Snapshot trusted settings before parsing: changes between load and first scope seal
        // must not let the loaded settings diverge from the files the host later approves.
        require(options.providers?.isAbsolute != false && options.methods?.isAbsolute != false)
        val providerFile = options.providers?.toRealPath()
        val methodFile = options.methods?.toRealPath()
        val baselineFile = options.baseline?.toRealPath()
        val trustedFiles = listOfNotNull(providerFile, methodFile, baselineFile)
        val trustedBefore = ProviderInputSealer.capture(root, emptyList(), externalFiles = trustedFiles)
        val configuration = providerFile?.let { ProviderConfiguration.load(it, root) } ?: ProviderConfiguration()
        val baseline = baselineFile?.let { loadBaseline(it, root) } ?: emptyList()
        val methods = methodFile?.let { MethodConfiguration.load(it, root) } ?: MethodConfiguration()
        val settings = ScanSettings(methodFlags = methods.methodFlags)
        val registrations = configuration.registrations(RepositoryScanner(), root)
        if (options.checkProviders || options.conformance) {
            var result = ProviderConformance.check(registrations, root, settings, execute = options.conformance)
            val trustedStable = runCatching { ProviderInputSealer.capture(root, emptyList(), externalFiles = trustedFiles).hashes == trustedBefore.hashes }.getOrDefault(false)
            if (!trustedStable) result = result.copy(valid = false, notices = result.notices + ScanNotice("WG-INPUT-CHANGED", "Trusted user settings changed during provider checks."))
            if (options.json) output.println(JSON.encodeToString(result)) else {
                output.println("WrapperGuard provider ${result.mode}: ${if (result.valid) "VALID" else "FAILED"}; ${result.sealedFileCount} declared files sealed")
                result.providers.forEach { output.println("PROVIDER\t${it.id}\t${it.status ?: if (it.enabled) "CONFIGURED" else "DISABLED"}\tscope=${if (it.scopeDeclared) "declared" else "unknown"}") }
                result.notices.forEach { output.println("NOTICE\t${it.code}\t${it.message}") }
            }
            return if (result.valid) 0 else 1
        }
        val providerScopes = registrations.filter { registration -> registration.permissions.enabled && SecurityMethodCatalog.methods.none {
            it.providerId == registration.provider.descriptor.id && settings.methodFlags[it.id] == false
        } }.mapNotNull { it.provider.descriptor.inputScope?.snapshot() }
        val hasBuiltIn = configuration.builtIn.enabled && SecurityMethodCatalog.builtInMethods.any { SecurityMethodCatalog.isEnabled(it.id, methods.methodFlags) }
        val scopes = providerScopes + if (hasBuiltIn) listOf(HOST_KNOWN_SCOPE) else emptyList()
        val inputsBefore = ProviderInputSealer.capture(root, scopes)
        val policy = ScanPolicy(mode = if (options.enforce) EnforcementMode.ENFORCE else EnforcementMode.WARN)
        var report = VettingCoordinator(registrations).scan(root, policy, settings, baseline, options.operation)
        val freshnessNotice = try {
            val inputsAfter = ProviderInputSealer.capture(root, scopes)
            val trustedAfter = ProviderInputSealer.capture(root, emptyList(), externalFiles = trustedFiles)
            if (inputsBefore.hashes != inputsAfter.hashes || trustedBefore.hashes != trustedAfter.hashes)
                ScanNotice("WG-INPUT-CHANGED", "Declared inputs or trusted user settings changed during the scan; rerun vetting before the operation.") else null
        } catch (_: Exception) {
            ScanNotice("WG-INPUT-SEAL-FAILED", "Declared inputs could not be rechecked safely; rerun vetting before the operation.")
        }
        if (freshnessNotice != null) report = report.copy(complete = false, stale = true,
            verdict = if (report.blockers.isNotEmpty()) ScanVerdict.EXECUTION_BLOCKED else ScanVerdict.STALE,
            notices = report.notices + freshnessNotice)
        if (hasBuiltIn && report.edges.any { !ProviderInputSealer.matchesProjectPath(it.to.path, HOST_KNOWN_SCOPE) }) {
            report = report.copy(notices = report.notices + ScanNotice("WG-HOST-SCOPE-UNKNOWN",
                "A recursive script uses a path outside the host-known file patterns; declared freshness does not cover that input."))
        }
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
    val version: Boolean = false,
    val checkProviders: Boolean = false,
    val conformance: Boolean = false
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
                        "--check-providers" -> result.copy(checkProviders = true)
                        "--conformance" -> result.copy(conformance = true)
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
            if (result.checkProviders && result.conformance) throw CliUsageException("Choose configuration checks or runtime conformance.")
            if ((result.checkProviders || result.conformance) && (result.sarif || result.operation != VettingOperation.SCAN || result.enforce))
                throw CliUsageException("Provider checks use scan mode and support text or JSON output.")
            return result
        }
    }
}

private class CliUsageException(message: String) : IllegalArgumentException(message)

/** Conservative known scanner inputs. Providers declare any additional custom inputs explicitly. */
private val HOST_KNOWN_SCOPE = ProviderInputScope(
    includes = listOf("**/gradlew", "**/gradlew.bat", "**/*.sh", "**/*.bash", "**/*.zsh", "**/*.bats", "**/*.bat", "**/*.cmd", "**/*.ps1",
        "**/*.gradle", "**/*.kts", "**/gradle-wrapper.properties", "**/gradle-wrapper.jar", "**/libs.versions.toml", "**/pom.xml"),
    excludes = listOf("**/.git/**", "**/.gradle/**", "**/.idea/**", "**/build/**", "**/out/**", "**/.intellijPlatform/**", "**/.kotlin/**", "**/node_modules/**")
)

private val HELP = """
    WrapperGuard 0.6.0 — provider-based build and sync vetting
    Usage: wrapperguard-cli [options] [project-directory]

      --providers <absolute-file>  User-owned JSON configuration outside the project
      --methods <absolute-file>    User-owned method flags outside the project
      --operation scan|build|sync Evaluate grants for this operation (default: scan)
      --enforce                   Enable granted build/sync blocking
      --check-providers           Validate configurations and declared inputs without execution
      --conformance               Run explicitly enabled trusted providers to check API 1 output
      --baseline <file>           Trusted approval list outside the project
      --json                     Canonical report including provider runs
      --sarif                    SARIF 2.1.0 report
      --version                  Print version
      --help                     Show this help

    The built-in static scanner runs by default. External commands require explicit
    user configuration and execute with your privileges. No commands are auto-discovered.
    Exit codes: 0 complete with no blockers; 2 blocked; 1 invalid input or incomplete scan.
""".trimIndent()
