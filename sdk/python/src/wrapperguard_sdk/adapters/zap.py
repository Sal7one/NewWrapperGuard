"""Offline ZAP traditional JSON importer; target scope is mandatory, no requests."""
from . import required_object, required_list, plain_html, safe_url, text


def parse(document, ctx, scope=None):
    if scope is None:
        raise ValueError("ZAP report import requires an explicit target scope.")
    report = required_object(document, "ZAP")
    sites = required_list(report.get("site"), "ZAP sites")
    if report.get("scriptdiagnostics") or report.get("sequences"):
        ctx.problem("WG-REPORT-UNSUPPORTED", "ZAP script diagnostics or sequence results require review in the original report.")
    if not sites:
        ctx.problem("WG-REPORT-TARGET-SCOPE", "The ZAP report contains no explicitly scoped target site.")
    for site in sites:
        if not isinstance(site, dict) or not isinstance(site.get("alerts"), list) or not isinstance(site.get("@name"), str):
            ctx.problem()
            continue
        # A site's origin is not a path-scoped page. Validate every instance URL
        # separately; empty sites still require an explicitly permitted origin.
        from urllib.parse import urlsplit
        try:
            site_url = urlsplit(site["@name"])
            site_origin = (site_url.scheme.lower(), site_url.hostname, site_url.port or (443 if site_url.scheme.lower() == "https" else 80))
            permitted_origin = any((urlsplit(origin).scheme.lower(), urlsplit(origin).hostname, urlsplit(origin).port or (443 if urlsplit(origin).scheme.lower() == "https" else 80)) == site_origin for origin in scope.origins)
            if site_url.username or site_url.password or site_url.query or site_url.fragment or not permitted_origin:
                ctx.problem("WG-REPORT-TARGET-SCOPE", "A ZAP site or instance was outside the explicit target scope and was omitted.")
                continue
        except (ValueError, TypeError):
            ctx.problem("WG-REPORT-TARGET-SCOPE", "A ZAP site or instance was outside the explicit target scope and was omitted.")
            continue
        for alert in site["alerts"]:
            if not isinstance(alert, dict) or not isinstance(alert.get("instances"), list) or not isinstance(alert.get("pluginid"), (str, int)) or isinstance(alert.get("pluginid"), bool):
                ctx.problem()
                continue
            severity = ctx.severity(alert.get("riskcode"), {"0": "INFO", "1": "WARN", "2": "WARN", "3": "ERROR", 0: "INFO", 1: "WARN", 2: "WARN", 3: "ERROR"})
            confidence_values = {"0": "LOW", "1": "LOW", "2": "MEDIUM", "3": "HIGH", "4": "HIGH"}
            raw_confidence = alert.get("confidence")
            confidence = confidence_values.get(str(raw_confidence), "LOW")
            if isinstance(raw_confidence, bool) or str(raw_confidence) not in confidence_values:
                ctx.problem("WG-REPORT-CONFIDENCE", "An unrecognized ZAP confidence was imported as low confidence.")
            if not alert["instances"]:
                ctx.problem("WG-REPORT-TARGET-SCOPE", "A ZAP alert without a scoped instance URL was omitted.")
            for instance in alert["instances"]:
                uri = instance.get("uri") if isinstance(instance, dict) else None
                if not isinstance(uri, str) or not scope.allows(uri):
                    ctx.problem("WG-REPORT-TARGET-SCOPE", "A ZAP site or instance was outside the explicit target scope and was omitted.")
                    continue
                title = plain_html(alert.get("alert") or alert.get("name"))
                if not title:
                    ctx.problem()
                    continue
                # No request/response, cookie, attack, evidence, param or raw HTML
                # from a DAST report is forwarded. This is a target-level finding.
                ctx.add(rule_id="ZAP-" + text(str(alert["pluginid"])), title=title,
                        message=f"Target-level DAST finding at {safe_url(uri)}. Source-file attribution is unavailable.",
                        severity=severity, confidence=confidence, category="NETWORK", path=".",
                        remediation=plain_html(alert.get("solution")) or "Review this alert in the original ZAP report and verify remediation against the scoped target.")
