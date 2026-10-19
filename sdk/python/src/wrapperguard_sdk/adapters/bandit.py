"""Bandit JSON formatter: https://bandit.readthedocs.io/en/latest/formatters/json.html"""
from . import required_object, required_list, text


def parse(document, ctx, scope=None):
    report = required_object(document, "Bandit")
    records = required_list(report.get("results"), "Bandit results")
    errors = required_list(report.get("errors"), "Bandit errors")
    if errors:
        ctx.problem("WG-REPORT-TOOL-ERROR", "Bandit reported analysis errors; coverage is incomplete.")
    metrics = report.get("metrics", {})
    if not isinstance(metrics, dict):
        ctx.problem()
    else:
        for path in metrics:
            if path != "_totals":
                ctx.location(path)
    for record in records:
        if not isinstance(record, dict) or not all(isinstance(record.get(k), str) and record[k] for k in ("test_id", "issue_text", "filename")):
            ctx.problem()
            continue
        path = ctx.location(record["filename"])
        if path is None:
            continue
        severity = ctx.severity(record.get("issue_severity"), {"HIGH": "ERROR", "MEDIUM": "WARN", "LOW": "WARN", "UNDEFINED": "INFO"})
        confidence = record.get("issue_confidence", "LOW")
        if not isinstance(confidence, str) or confidence not in {"LOW", "MEDIUM", "HIGH"}:
            confidence = "LOW"
            ctx.problem()
        ctx.add(rule_id=record["test_id"], title=text(record.get("test_name"), record["test_id"]),
                message=record["issue_text"], severity=severity, confidence=confidence,
                category="CONFIGURATION", path=path, line=record.get("line_number"), column=record.get("col_offset", -1) + 1 if isinstance(record.get("col_offset"), int) and not isinstance(record.get("col_offset"), bool) else None)
