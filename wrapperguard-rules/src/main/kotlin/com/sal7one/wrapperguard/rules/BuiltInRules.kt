package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.spi.Rule

object BuiltInRules {
    val all: List<Rule> = listOf(
        RegexRule(
            id = "WG-SUPPLY-001",
            title = "Remote content is piped into a shell",
            message = "Downloaded content is passed directly to an interpreter.",
            pattern = Regex("(?i)(curl|wget|invoke-webrequest|irm|iwr).*(\\||invoke-expression|iex|bash|sh|powershell|pwsh)"),
            severity = Severity.ERROR,
            confidence = Confidence.HIGH,
            category = FindingCategory.SUPPLY_CHAIN,
            remediation = "Download a pinned artifact, verify its checksum, then execute the verified local file."
        ),
        RegexRule(
            id = "WG-OBFUSCATION-001",
            title = "Encoded PowerShell command",
            message = "PowerShell is invoked with an encoded command.",
            pattern = Regex("(?i)(powershell|pwsh).*(-enc|-encodedcommand)\\b"),
            severity = Severity.ERROR,
            confidence = Confidence.HIGH,
            category = FindingCategory.OBFUSCATION,
            remediation = "Replace the encoded command with readable, reviewed source."
        ),
        RegexRule(
            id = "WG-NETWORK-001",
            title = "Insecure HTTP URL",
            message = "A network resource uses unencrypted HTTP.",
            pattern = Regex("(?i)http://[^\\s\\\"']+"),
            severity = Severity.WARN,
            confidence = Confidence.HIGH,
            category = FindingCategory.NETWORK,
            remediation = "Use HTTPS and pin or verify the downloaded artifact."
        )
    )
}
