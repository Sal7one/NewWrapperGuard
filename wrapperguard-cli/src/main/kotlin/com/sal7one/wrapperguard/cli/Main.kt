package com.sal7one.wrapperguard.cli

import com.sal7one.wrapperguard.core.model.BaselineEntry
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.rules.RepositoryScanner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val JSON = Json { prettyPrint = true; encodeDefaults = true }

fun main(args: Array<String>) {
    val baselineIndex = args.indexOf("--baseline")

    // Positional path: the first argument that is not a flag and not the baseline value.
    val path = args.firstOrNull { it ->
        !it.startsWith("--") && args.getOrNull(args.indexOf(it) - 1) != "--baseline"
    }?.let { Path.of(it) } ?: Path.of(".")
    val jsonOutput = "--json" in args
    val enforce = "--enforce" in args

    val baseline: List<BaselineEntry> = if (baselineIndex >= 0 && baselineIndex + 1 < args.size) {
        val baselinePath = Path.of(args[baselineIndex + 1])
        if (Files.isRegularFile(baselinePath)) {
            runCatching { JSON.decodeFromString<List<BaselineEntry>>(Files.readString(baselinePath)) }.getOrDefault(emptyList())
        } else {
            emptyList() // a missing baseline file is not an error — just no suppressions
        }
    } else {
        emptyList()
    }

    val policy = ScanPolicy(mode = if (enforce) EnforcementMode.ENFORCE else EnforcementMode.WARN)
    val report = RepositoryScanner().scan(path, policy, ScanSettings(), baseline)

    if (jsonOutput) {
        println(JSON.encodeToString(report))
    } else {
        println("WrapperGuard: " + report.verdict + " — " + report.findings.size + " findings in " + report.scannedFiles + " files")
        report.findings.forEach {
            println(it.severity.toString() + "\t" + it.ruleId + "\t" + it.location.display + "\t" + it.title + "\t" + it.policyAction + "\t" + it.status)
        }
        report.notices.forEach { println("NOTICE\t" + it.code + "\t" + it.message) }
    }

    // Blockers = ACTIVE BLOCK findings only; suppressed/baselined findings never block.
    exitProcess(if (report.findings.any { it.policyAction == PolicyAction.BLOCK && it.status == FindingStatus.ACTIVE }) 2 else 0)
}
