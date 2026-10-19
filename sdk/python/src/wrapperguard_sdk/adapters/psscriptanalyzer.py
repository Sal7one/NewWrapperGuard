"""Microsoft DiagnosticRecord JSON, including ConvertTo-Json numeric enums."""
from . import required_list


def parse(document, ctx, scope=None):
    records = [document] if isinstance(document, dict) and "RuleName" in document else required_list(document, "PSScriptAnalyzer")
    for record in records:
        if not isinstance(record, dict) or not all(isinstance(record.get(k), str) and record[k] for k in ("RuleName", "Message")):
            ctx.problem()
            continue
        extent = record.get("Extent") or {}
        if not isinstance(extent, dict):
            ctx.problem()
            continue
        path = ctx.location(record.get("ScriptPath") or extent.get("File"))
        if path is None:
            continue
        severity = ctx.severity(record.get("Severity"), {"Information": "INFO", "Warning": "WARN", "Error": "ERROR", "ParseError": "ERROR", 0: "INFO", 1: "WARN", 2: "ERROR", 3: "ERROR"})
        if record.get("Severity") in ("ParseError", 3):
            ctx.problem("WG-REPORT-TOOL-ERROR", "PSScriptAnalyzer reported a parse error; analysis is incomplete.")
        if record.get("IsSuppressed"):
            ctx.problem("WG-REPORT-SUPPRESSION", "Report suppression metadata is not a host-approved trust entry.")
        ctx.add(rule_id=record["RuleName"], title=record["RuleName"], message=record["Message"],
                severity=severity, confidence="HIGH", category="CONFIGURATION", path=path,
                line=extent.get("StartLineNumber"), column=extent.get("StartColumnNumber"))
