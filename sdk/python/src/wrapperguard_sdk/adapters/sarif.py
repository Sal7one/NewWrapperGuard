"""OASIS SARIF 2.1.0 results with local URIs, artifact indices and URI bases.

https://docs.oasis-open.org/sarif/sarif/v2.1.0/os/sarif-v2.1.0-os.html
No snippets, fixes, externally supplied baselines or suppression authority are imported.
"""
from urllib.parse import urljoin, urlsplit
from . import required_object, required_list, text


def _artifact_uri(location, run, seen=()):
    if not isinstance(location, dict):
        return None
    uri = location.get("uri")
    if uri is None:
        index = location.get("index")
        artifacts = run.get("artifacts", [])
        if not isinstance(artifacts, list) or isinstance(index, bool) or not isinstance(index, int) or index < 0 or index >= len(artifacts):
            return None
        artifact = artifacts[index]
        if not isinstance(artifact, dict):
            return None
        return _artifact_uri(artifact.get("location"), run, seen + ("index",)) if len(seen) < 8 else None
    if not isinstance(uri, str) or not uri:
        return None
    base_id = location.get("uriBaseId")
    parsed = urlsplit(uri)
    if base_id is not None and not parsed.scheme:
        bases = run.get("originalUriBaseIds", {})
        if not isinstance(base_id, str) or base_id in seen or len(seen) >= 8 or not isinstance(bases, dict):
            return None
        base = _artifact_uri(bases.get(base_id), run, seen + (base_id,))
        if not base:
            return None
        uri = urljoin(base, uri)
    parsed = urlsplit(uri)
    if parsed.query or parsed.fragment or parsed.scheme not in {"", "file"}:
        return None
    return uri


def parse(document, ctx, scope=None):
    report = required_object(document, "SARIF")
    if report.get("version") != "2.1.0":
        if "version" not in report:
            raise ValueError("Invalid SARIF report: missing version.")
        ctx.problem("WG-REPORT-VERSION", "Only SARIF version 2.1.0 is supported.")
        return
    runs = required_list(report.get("runs"), "SARIF runs")
    for run in runs:
        if not isinstance(run, dict) or not isinstance(run.get("tool"), dict) or not isinstance(run["tool"].get("driver"), dict):
            ctx.problem()
            continue
        driver = run["tool"]["driver"]
        if not isinstance(driver.get("name"), str) or not driver["name"]:
            ctx.problem()
            continue
        rules = driver.get("rules", [])
        if not isinstance(rules, list):
            rules = []
            ctx.problem()
        invocations = run.get("invocations", [])
        if not isinstance(invocations, list):
            ctx.problem()
        else:
            for invocation in invocations:
                if isinstance(invocation, dict) and invocation.get("ruleConfigurationOverrides"):
                    ctx.problem("WG-REPORT-UNSUPPORTED", "SARIF invocation-level rule overrides require review in the original report.")
                if not isinstance(invocation, dict) or invocation.get("executionSuccessful") is False or invocation.get("toolExecutionNotifications"):
                    ctx.problem("WG-REPORT-TOOL-ERROR", "SARIF reports an unsuccessful or diagnostic-producing tool invocation.")
        artifacts = run.get("artifacts", [])
        if not isinstance(artifacts, list):
            ctx.problem()
        else:
            for artifact in artifacts:
                if not isinstance(artifact, dict):
                    ctx.problem()
                    continue
                try:
                    ctx.location(_artifact_uri(artifact.get("location"), run))
                except (ValueError, RecursionError):
                    ctx.problem("WG-REPORT-LOCATION", "A report location is missing, invalid or outside the project.")
        records = run.get("results", [])
        if not isinstance(records, list):
            ctx.problem()
            continue
        for record in records:
            if not isinstance(record, dict):
                ctx.problem()
                continue
            rule_id = record.get("ruleId")
            rule_index = record.get("ruleIndex")
            rule = next((r for r in rules if isinstance(r, dict) and r.get("id") == rule_id), {})
            if not rule and isinstance(rule_index, int) and not isinstance(rule_index, bool) and 0 <= rule_index < len(rules) and isinstance(rules[rule_index], dict):
                rule = rules[rule_index]
                rule_id = rule.get("id")
            message = record.get("message", {})
            if not isinstance(rule_id, str) or not rule_id or not isinstance(message, dict) or not isinstance(message.get("text"), str) or not message["text"]:
                ctx.problem()
                continue
            default = rule.get("defaultConfiguration", {})
            if not isinstance(default, dict):
                default = {}
                ctx.problem()
            severity = ctx.severity(record.get("level", default.get("level", "warning")), {"error": "ERROR", "warning": "WARN", "note": "INFO", "none": "INFO"})
            kind = record.get("kind", "fail")
            if kind != "fail":
                severity = "INFO"
                if kind not in ("pass", "open", "informational", "notApplicable", "review"):
                    ctx.problem()
            if record.get("suppressions") or record.get("baselineState") == "absent":
                ctx.problem("WG-REPORT-SUPPRESSION", "Report baseline/suppression metadata is not a host-approved trust entry.")
            description = rule.get("shortDescription", {})
            title = text(description.get("text"), rule_id) if isinstance(description, dict) else rule_id
            locations = record.get("locations", [])
            if not isinstance(locations, list) or not locations:
                ctx.problem("WG-REPORT-LOCATION", "A report location is missing, invalid or outside the project.")
                continue
            for location in locations:
                physical = location.get("physicalLocation", {}) if isinstance(location, dict) else {}
                if not isinstance(physical, dict):
                    ctx.problem()
                    continue
                try:
                    raw = _artifact_uri(physical.get("artifactLocation"), run)
                except (ValueError, RecursionError):
                    raw = None
                path = ctx.location(raw)
                if path is None:
                    continue
                region = physical.get("region", {})
                if not isinstance(region, dict):
                    region = {}
                    ctx.problem()
                ctx.add(rule_id=rule_id, title=title, message=message["text"], severity=severity,
                        path=path, line=region.get("startLine"), column=region.get("startColumn"))
