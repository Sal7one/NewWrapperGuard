package com.sal7one.wrapperguard.cli

import com.sal7one.wrapperguard.core.model.PolicyAction
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.rules.RepositoryScanner
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val path = args.firstOrNull { !it.startsWith("--") }?.let { Path.of(it) } ?: Path.of(".")
    val jsonOutput = "--json" in args
    val enforce = "--enforce" in args
    val policy = ScanPolicy(mode = if (enforce) EnforcementMode.ENFORCE else EnforcementMode.WARN)
    val report = RepositoryScanner().scan(path, policy)

    if (jsonOutput) {
        println(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report))
    } else {
        println("WrapperGuard: ${report.verdict} — ${report.findings.size} findings in ${report.scannedFiles} files")
        report.findings.forEach {
            println("${it.severity}\t${it.ruleId}\t${it.location.display}\t${it.title}\t${it.policyAction}")
        }
        report.notices.forEach { println("NOTICE\t${it.code}\t${it.message}") }
    }

    exitProcess(if (report.findings.any { it.policyAction == PolicyAction.BLOCK }) 2 else 0)
}
