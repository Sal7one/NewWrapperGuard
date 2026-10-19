"""Trivy SchemaVersion 2 SCA results. Other scanners are explicitly incomplete."""
from . import required_object, required_list, text
from ..paths import normalize_location


def parse(document, ctx, scope=None):
    report = required_object(document, "Trivy")
    if type(report.get("SchemaVersion")) is not int or report["SchemaVersion"] != 2:
        if "SchemaVersion" not in report:
            raise ValueError("Invalid Trivy report: missing schema version.")
        ctx.problem("WG-REPORT-VERSION", "Unsupported Trivy report schema version.")
        return
    # Results is tagged omitempty in Trivy's official Go report model.
    if "Results" not in report:
        if report.get("ArtifactType") in ("filesystem", "repository") and isinstance(report.get("ArtifactName"), str) and report["ArtifactName"]:
            records = []
        else:
            ctx.problem("WG-REPORT-PARTIAL", "Trivy report has no supported result collection or local artifact metadata.")
            return
    else:
        records = required_list(report["Results"], "Trivy results")
    for record in records:
        if not isinstance(record, dict):
            ctx.problem()
            continue
        if any(record.get(k) for k in ("Misconfigurations", "Secrets", "Licenses", "CustomResources", "CryptoAssets", "ExperimentalModifiedFindings")):
            ctx.problem("WG-REPORT-UNSUPPORTED", "This Trivy adapter imports dependency vulnerabilities only; other scanner findings were omitted.")
        # Image OS package targets are labels rather than local source files.
        if record.get("Class") is not None and record.get("Class") != "lang-pkgs":
            ctx.problem("WG-REPORT-UNSUPPORTED", "A Trivy result has no supported local dependency-file location.")
            continue
        target = record.get("Target")
        # Some Trivy language targets are labels such as "Java", not files.
        # Confirm local files before attributing source or counting coverage.
        target_path = normalize_location(ctx.root, target) if isinstance(target, str) else None
        if target_path is not None and not (ctx.root / target_path).is_file():
            target_path = None
        if target_path is not None:
            ctx.paths.add(target_path)
        vulns = record.get("Vulnerabilities", [])
        if vulns is None:
            vulns = []
        if not isinstance(vulns, list):
            ctx.problem()
            continue
        if not vulns and target_path is None:
            ctx.problem("WG-REPORT-LOCATION", "A Trivy result has no existing in-project dependency-file location.")
        for vuln in vulns:
            if not isinstance(vuln, dict) or not isinstance(vuln.get("VulnerabilityID"), str) or not vuln["VulnerabilityID"]:
                ctx.problem()
                continue
            path = target_path
            if path is None and isinstance(vuln.get("PkgPath"), str):
                path = normalize_location(ctx.root, vuln["PkgPath"])
                if path is not None and not (ctx.root / path).is_file():
                    path = None
            if path is None:
                ctx.problem("WG-REPORT-LOCATION", "A Trivy result has no existing in-project dependency-file location.")
                continue
            ctx.paths.add(path)
            severity = ctx.severity(vuln.get("Severity"), {"CRITICAL": "ERROR", "HIGH": "ERROR", "MEDIUM": "WARN", "LOW": "WARN", "UNKNOWN": "INFO"})
            name = text(vuln.get("PkgName"), "dependency")
            version = text(vuln.get("InstalledVersion"), "unspecified")
            fixed = text(vuln.get("FixedVersion"))
            ctx.add(rule_id=vuln["VulnerabilityID"], title=text(vuln.get("Title"), vuln["VulnerabilityID"]),
                    message=f"Dependency {name}@{version}: {text(vuln.get('Title'), vuln['VulnerabilityID'])}",
                    severity=severity, category="SUPPLY_CHAIN", path=path,
                    remediation=f"Review advisory and update to a fixed version: {fixed}." if fixed else "Review the advisory and available dependency updates.")
