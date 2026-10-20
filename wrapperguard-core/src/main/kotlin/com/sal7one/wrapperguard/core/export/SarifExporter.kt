package com.sal7one.wrapperguard.core.export

import com.sal7one.wrapperguard.core.AppVersion
import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.Finding
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.Severity
import com.sal7one.wrapperguard.core.util.CredentialRedaction
import kotlinx.serialization.json.*

/**
 * Exports a scan report as SARIF 2.1.0 (static-analysis interchange format) for CI
 * integrations. Evidence text is redacted at creation time, so exports carry no secrets.
 */
object SarifExporter {

    fun toSarif(report: ScanReport): JsonObject {
        val rules = report.findings.map(::ruleKey).distinct().map { ruleId ->
            buildJsonObject {
                put("id", ruleId)
                put("name", ruleId)
                put("shortDescription", buildJsonObject { put("text", ruleId) })
            }
        }
        val results = report.findings.map { f ->
            buildJsonObject {
                put("ruleId", ruleKey(f))
                put("level", when (f.severity) {
                    Severity.ERROR -> "error"
                    Severity.WARN -> "warning"
                    else -> "note"
                })
                put("message", buildJsonObject { put("text", mask(f.message)) })
                put("locations", buildJsonArray {
                    add(buildJsonObject {
                        put("physicalLocation", buildJsonObject {
                            put("artifactLocation", buildJsonObject { put("uri", mask(f.location.path)) })
                            f.location.line?.let {
                                put("region", buildJsonObject { put("startLine", it) })
                            }
                        })
                    })
                })
                put("partialFingerprints", buildJsonObject { put("wrapperguard/v1", mask(f.fingerprint)) })
                if (f.status != FindingStatus.ACTIVE) {
                    put("suppressions", buildJsonArray {
                        add(buildJsonObject {
                            put("kind", "external")
                            put("status", f.status.name.lowercase())
                        })
                    })
                }
                put("properties", buildJsonObject {
                    put("policyAction", f.policyAction.name)
                    put("category", f.category.name)
                    f.providerId?.let { put("providerId", mask(it)) }
                    if (f.actions.isNotEmpty()) put("actions", buildJsonArray { f.actions.forEach { add(it.name) } })
                    f.evidence?.let { put("evidence", mask(it.text)) }
                })
            }
        }
        return buildJsonObject {
            put("\$schema", "https://json.schemastore.org/sarif-2.1.0.json")
            put("version", "2.1.0")
            put("runs", buildJsonArray {
                add(buildJsonObject {
                    put("tool", buildJsonObject {
                        put("driver", buildJsonObject {
                            put("name", "WrapperGuard")
                            put("version", AppVersion.current)
                            put("rules", buildJsonArray { rules.forEach { add(it) } })
                        })
                    })
                    put("results", buildJsonArray { results.forEach { add(it) } })
                })
            })
        }
    }

    private fun ruleKey(finding: Finding): String = mask(
        if (finding.providerId == null || finding.providerId == "wrapperguard") finding.ruleId
        else "${finding.providerId}/${finding.ruleId}"
    )

    private fun mask(value: String) = CredentialRedaction.mask(value).first
}
