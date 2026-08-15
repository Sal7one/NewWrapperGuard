package com.sal7one.wrapperguard.core.export

import com.sal7one.wrapperguard.core.model.FindingStatus
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.model.Severity
import kotlinx.serialization.json.*

/**
 * Exports a scan report as SARIF 2.1.0 (static-analysis interchange format) for CI
 * integrations. Evidence text is redacted at creation time, so exports carry no secrets.
 */
object SarifExporter {

    fun toSarif(report: ScanReport): JsonObject {
        val rules = report.findings.map { it.ruleId }.distinct().map { ruleId ->
            buildJsonObject {
                put("id", ruleId)
                put("name", ruleId)
                put("shortDescription", buildJsonObject { put("text", ruleId) })
            }
        }
        val results = report.findings.map { f ->
            buildJsonObject {
                put("ruleId", f.ruleId)
                put("level", when (f.severity) {
                    Severity.ERROR -> "error"
                    Severity.WARN -> "warning"
                    else -> "note"
                })
                put("message", buildJsonObject { put("text", f.message) })
                put("locations", buildJsonArray {
                    add(buildJsonObject {
                        put("physicalLocation", buildJsonObject {
                            put("artifactLocation", buildJsonObject { put("uri", f.location.path) })
                            f.location.line?.let {
                                put("region", buildJsonObject { put("startLine", it) })
                            }
                        })
                    })
                })
                put("partialFingerprints", buildJsonObject { put("wrapperguard/v1", f.fingerprint) })
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
                    f.evidence?.let { put("evidence", it.text) }
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
                            put("version", "0.3.0")
                            put("rules", buildJsonArray { rules.forEach { add(it) } })
                        })
                    })
                    put("results", buildJsonArray { results.forEach { add(it) } })
                })
            })
        }
    }
}
