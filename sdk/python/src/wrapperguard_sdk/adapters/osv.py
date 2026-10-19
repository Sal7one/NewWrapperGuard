"""OSV-Scanner JSON source/package/advisory records (not OSV API responses)."""
from . import required_object, required_list, text


def parse(document, ctx, scope=None):
    report = required_object(document, "OSV-Scanner")
    records = required_list(report.get("results"), "OSV-Scanner results")
    for record in records:
        if not isinstance(record, dict) or not isinstance(record.get("source"), dict) or not isinstance(record.get("packages"), list):
            ctx.problem()
            continue
        source = record["source"]
        if not isinstance(source.get("type"), str) or source["type"] not in {"lockfile", "sbom", "git"}:
            ctx.problem("WG-REPORT-UNSUPPORTED", "An OSV source has no supported local source-file location.")
            continue
        path = ctx.location(source.get("path"))
        if path is None:
            continue
        for entry in record["packages"]:
            if not isinstance(entry, dict) or not isinstance(entry.get("package"), dict) or not isinstance(entry.get("vulnerabilities", []), list):
                ctx.problem()
                continue
            if entry.get("license_violations"):
                ctx.problem("WG-REPORT-UNSUPPORTED", "OSV license violations are outside this dependency-vulnerability adapter.")
            groups = entry.get("groups", [])
            if not isinstance(groups, list):
                groups = []
                ctx.problem()
            package = entry["package"]
            name = text(package.get("name"), "dependency")
            version = text(package.get("version"), "unspecified")
            for vuln in entry.get("vulnerabilities", []):
                if not isinstance(vuln, dict) or not isinstance(vuln.get("id"), str) or not vuln["id"]:
                    ctx.problem()
                    continue
                database = vuln.get("database_specific") or {}
                if not isinstance(database, dict):
                    database = {}
                    ctx.problem()
                raw_severity = database.get("severity")
                if raw_severity is None:
                    # OSV CVSS score strings can be full vectors, not numeric scores.
                    # Do not silently misread them or manufacture a CVSS evaluation.
                    severity = "WARN"
                    for group in groups:
                        if not isinstance(group, dict) or not isinstance(group.get("ids"), list) or vuln["id"] not in group["ids"]:
                            continue
                        score = group.get("max_severity")
                        if isinstance(score, (str, int, float)) and not isinstance(score, bool):
                            try:
                                number = float(score)
                                if 0 <= number <= 10:
                                    severity = "ERROR" if number >= 7 else "WARN" if number > 0 else "INFO"
                                else:
                                    ctx.problem()
                            except ValueError:
                                ctx.problem()
                else:
                    severity = ctx.severity(raw_severity, {"CRITICAL": "ERROR", "HIGH": "ERROR", "MODERATE": "WARN", "MEDIUM": "WARN", "LOW": "WARN", "UNKNOWN": "INFO"})
                summary = text(vuln.get("summary"), "Known dependency vulnerability")
                ctx.add(rule_id=vuln["id"], title=summary,
                        message=f"Dependency {name}@{version}: {summary} ({text(vuln['id'])}).",
                        severity=severity, category="SUPPLY_CHAIN", path=path,
                        remediation="Review the advisory's affected ranges and upgrade the dependency to a fixed version.")
