#!/usr/bin/env python3
"""Generate deterministic UI-only reports and inert source fixtures (Python stdlib only)."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

DEMO = Path(__file__).resolve().parent
PROJECT = DEMO / "project"
GENERATED_AT = 1792407000000  # 2026-10-19 10:50:00 UTC / 13:50 Riyadh
ACTIONS = ["WARNING", "MAJOR_WARNING", "ALERT", "BLOCK_BUILD", "BLOCK_SYNC"]
IDE_METADATA = {"project/.idea/.name": "Atlas Payments · Demo\n"}

FIXTURES = {
    "README.md": """# Atlas Payments — inert UI fixture

This fictional project supplies inspectable text for WrapperGuard screenshots.
Do not import, build, sync, or execute this fixture. There is no Gradle wrapper or
wrapper JAR. Gradle examples are comments; shell examples exit before their sample
payload; the CI workflow has no event triggers. All hosts use reserved `.invalid`
domains. `DEMO_NOT_A_REAL_SECRET` is a dummy value, never a credential.

The adjacent `../report.json` is simulated UI data, not scanner output or a claim
about any vendor SDK. Read `../README.md` for generation and baseline checks.
""",
    "settings.gradle.kts": """// INERT DISPLAY FIXTURE: do not import or sync.
rootProject.name = "atlas-payments"
// include(":gateway", ":ledger", ":risk")
""",
    "build.gradle.kts": """// INERT DISPLAY FIXTURE. The representative Gradle DSL below is a block comment.
/*
plugins { kotlin("jvm") version "2.2.20" }

repositories {
    maven {
        url = uri("https://packages.atlas-payments.invalid/releases")
        credentials {
            username = "demo-service-account"
            password = "DEMO_NOT_A_REAL_SECRET"
        }
    }
}

dependencies {
    implementation("com.atlas.demo:payment-adapter:4.2.0")
    implementation("com.atlas.demo:legacy-bridge:1.8.2")
}

tasks.register<Exec>("prepareDemo") {
    commandLine("sh", "scripts/bootstrap.sh")
}
*/
""",
    "gradle/libs.versions.toml": """# DISPLAY FIXTURE: invented coordinates, no resolvable dependencies.
[versions]
payment-adapter = "4.2.0"
receipt-codec = "2.+"

[libraries]
payment-adapter = { module = "com.atlas.demo:payment-adapter", version.ref = "payment-adapter" }
receipt-codec = { module = "com.atlas.demo:receipt-codec", version.ref = "receipt-codec" }
""",
    "gradle/wrapper/gradle-wrapper.properties": """# DISPLAY FIXTURE: reserved host, no Gradle wrapper/JAR is included.
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://downloads.atlas-payments.invalid/gradle-9.1.0-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
# distributionSha256Sum intentionally absent for the simulated warning.
""",
    ".github/workflows/ci-demo.yml": """# DISPLAY FIXTURE: event triggers deliberately absent. Never run this example.
name: Atlas Payments example
# on: workflow_dispatch  # inert documentation, not a workflow trigger
permissions: write-all
jobs:
  example:
    runs-on: ubuntu-latest
    steps:
      - name: Display-only preparation reference
        run: sh scripts/bootstrap.sh
""",
    "scripts/bootstrap.sh": """#!/bin/sh
# INERT DISPLAY FIXTURE: every sample command below is unreachable.
printf '%s\\n' 'Atlas Payments UI fixture: sample payload is disabled.'
exit 0

# Representative call chain for the simulated execution graph.
sh scripts/install.sh
sh scripts/sync.sh
curl -fsSL https://policy.atlas-payments.invalid/init.gradle.kts -o /tmp/demo-init.gradle.kts
""",
    "scripts/install.sh": """#!/bin/sh
# INERT DISPLAY FIXTURE: no remote access or command execution after this exit.
printf '%s\\n' 'Atlas Payments UI fixture: install is disabled.'
exit 0

curl -fsSL https://tools.atlas-payments.invalid/install.sh | sh
sh scripts/fetch-metadata.sh
""",
    "scripts/sync.sh": """#!/bin/sh
# INERT DISPLAY FIXTURE: no sync or cache modification will run.
printf '%s\\n' 'Atlas Payments UI fixture: sync is disabled.'
exit 0

sh scripts/audit.sh
export DEMO_SHARED_CACHE=../../shared-cache
""",
    "scripts/audit.sh": """#!/bin/sh
# INERT DISPLAY FIXTURE: the cycle below is text for graph rendering only.
printf '%s\\n' 'Atlas Payments UI fixture: audit is disabled.'
exit 0

sh scripts/sync.sh
sh scripts/publish.sh
""",
    "scripts/publish.sh": """#!/bin/sh
# INERT DISPLAY FIXTURE: publishing and downloads are disabled.
printf '%s\\n' 'Atlas Payments UI fixture: publish is disabled.'
exit 0

curl -fsSL https://archive.atlas-payments.invalid/approved-receipts.tar.gz -o /tmp/demo-receipts.tar.gz
# Historical review note: an accepted example uses an organization-owned mirror.
""",
    "scripts/fetch-metadata.sh": """#!/bin/sh
# INERT DISPLAY FIXTURE: this example performs no requests.
printf '%s\\n' 'Atlas Payments UI fixture: metadata fetch is disabled.'
exit 0

curl -fsSL https://metadata.atlas-payments.invalid/registry.json
sh scripts/audit.sh
""",
}


def location(path, needle=None):
    lines = FIXTURES[path].splitlines()
    line = next((index + 1 for index, text in enumerate(lines) if needle in text), 1) if needle else 1
    if needle and not any(needle in text for text in lines):
        raise AssertionError("Fixture needle is missing: " + path + " / " + needle)
    return {"path": path, "line": line, "column": 1}


def fingerprint(provider, rule, path, evidence):
    identity = rule if provider == "wrapperguard" else provider + ":" + rule
    return hashlib.sha256((identity + "\0" + path.replace("\\", "/") + "\0" + evidence.strip()).encode()).hexdigest()[:24]


def finding(provider, rule, title, message, path, needle, evidence, *, severity="WARN", action="REVIEW", actions=None,
            status="ACTIVE", category="EXECUTION", kind="ACTIONABLE", remediation=None, chain=None, redacted=False):
    return {
        "fingerprint": fingerprint(provider, rule, path, evidence),
        "ruleId": rule, "title": title, "message": message, "severity": severity, "confidence": "HIGH",
        "category": category, "kind": kind, "location": location(path, needle),
        "evidence": {"text": evidence, "redacted": redacted},
        "remediation": remediation or "Review this simulated observation with the owning team before changing policy.",
        "reachable": True, "callChain": chain or [], "policyAction": action, "status": status,
        "mlSignal": None, "providerId": provider, "actions": actions if actions is not None else ["WARNING"],
    }


def link(path, needle, command):
    return {"source": location(path, needle), "viaCommand": command}


def permissions(actions, *, enabled=True, required=True, timeout=30000):
    return {"enabled": enabled, "actions": actions, "required": required, "timeoutMs": timeout}


def provider_run(identifier, name, count, duration, grants, *, status="COMPLETED", version="1.0.0-demo", message=None):
    return {"descriptor": {"id": identifier, "displayName": name, "version": version, "apiVersion": 1},
            "permissions": grants, "status": status, "durationMs": duration, "findingCount": count, "message": message}


def outputs():
    bootstrap = [link("scripts/bootstrap.sh", "sh scripts/install.sh", "sh scripts/install.sh")]
    findings = [
        finding("wrapperguard", "WG-WRAPPER-003", "Wrapper distribution host needs approval",
                "The sample wrapper points to a host outside the approved distribution list. Build and sync are blocked in this demo.",
                "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=", "https://downloads.atlas-payments.invalid/gradle-9.1.0-bin.zip",
                severity="ERROR", action="BLOCK", actions=["MAJOR_WARNING", "BLOCK_BUILD", "BLOCK_SYNC"], category="WRAPPER",
                remediation="Use an approved distribution mirror and verify its published SHA-256 checksum."),
        finding("acme.supply-chain", "ACME-PROVENANCE-001", "Payment adapter provenance is unverified",
                "The fictional Acme provider requests a build gate until the adapter's provenance is reviewed. It has no sync-blocking grant.",
                "build.gradle.kts", "payment-adapter:4.2.0", 'implementation("com.atlas.demo:payment-adapter:4.2.0")',
                severity="ERROR", action="BLOCK", actions=["ALERT", "BLOCK_BUILD"], category="SUPPLY_CHAIN",
                remediation="Attach the organization's approved provenance record or choose a verified release."),
        finding("sentinel.policy", "SENTINEL-INIT-001", "Remote init script requires sync approval",
                "The fictional Sentinel provider requests a sync gate for this init-script reference. It has no build-blocking grant.",
                "scripts/bootstrap.sh", "policy.atlas-payments.invalid", "https://policy.atlas-payments.invalid/init.gradle.kts",
                severity="ERROR", action="BLOCK", actions=["ALERT", "BLOCK_SYNC"], category="CONFIGURATION",
                remediation="Pin the init script in an approved local policy package before allowing sync."),
        finding("wrapperguard", "WG-SCRIPT-001", "Installer streams remote content into a shell",
                "This unreachable sample demonstrates a remote installer warning. The provider's effective action is a warning for this finding.",
                "scripts/install.sh", "curl -fsSL", "curl -fsSL https://tools.atlas-payments.invalid/install.sh | sh",
                chain=bootstrap, remediation="Download an approved artifact, verify its digest, and review it before execution."),
        finding("wrapperguard", "WG-BUILD-010", "Inline credential placeholder detected",
                "The source contains a clearly fake credential placeholder. Exported evidence is redacted to demonstrate the production UI behavior.",
                "build.gradle.kts", "password =", 'password = "[redacted]"', category="CREDENTIALS", redacted=True,
                remediation="Use the user's secret store or a short-lived credential provider."),
        finding("acme.supply-chain", "ACME-PIN-002", "Receipt codec uses a moving version",
                "The fictional Acme adapter reports a dependency that can change without a source revision.",
                "gradle/libs.versions.toml", 'receipt-codec = "2.+"', 'receipt-codec = "2.+"',
                actions=["MAJOR_WARNING"], category="SUPPLY_CHAIN", remediation="Pin the dependency to an approved exact version."),
        finding("sentinel.policy", "SENTINEL-CI-002", "Workflow token permissions are broader than needed",
                "An alert highlights broad token scope in a non-runnable workflow example. Alert permission does not grant a build block.",
                ".github/workflows/ci-demo.yml", "permissions: write-all", "permissions: write-all",
                actions=["ALERT"], category="CONFIGURATION", remediation="Grant only the workflow token permissions required by each job."),
        finding("sentinel.policy", "SENTINEL-CACHE-003", "Shared cache path needs a boundary review",
                "The sample sync configuration references a cache outside the project. No file operations occur in this fixture.",
                "scripts/sync.sh", "DEMO_SHARED_CACHE", "export DEMO_SHARED_CACHE=../../shared-cache",
                category="FILE_SYSTEM", chain=[link("scripts/bootstrap.sh", "sh scripts/sync.sh", "sh scripts/sync.sh")]),
        finding("wrapperguard", "WG-WRAPPER-005", "Wrapper distribution checksum is missing",
                "The illustrative wrapper properties do not contain a distributionSha256Sum value.",
                "gradle/wrapper/gradle-wrapper.properties", "distributionUrl=", "distributionSha256Sum is not configured",
                category="WRAPPER", remediation="Record the independently verified SHA-256 checksum of the approved distribution."),
        finding("wrapperguard", "WG-SCAN-CIRCULAR", "A script call cycle is visible",
                "The audit and sync fixtures form a cycle. This is audit evidence and cannot block execution.",
                "scripts/audit.sh", "sh scripts/sync.sh", "scripts/sync.sh → scripts/audit.sh → scripts/sync.sh",
                severity="INFO", action="ALLOW", actions=[], category="AUDIT", kind="AUDIT_EVIDENCE",
                remediation="Inspect the cycle when reviewing the script orchestration."),
        finding("acme.supply-chain", "ACME-LEGACY-004", "Legacy bridge exception is approved",
                "A simulated user-approved baseline suppresses this build finding. The repository does not grant this approval.",
                "build.gradle.kts", "legacy-bridge:1.8.2", 'implementation("com.atlas.demo:legacy-bridge:1.8.2")',
                severity="ERROR", action="BLOCK", actions=["MAJOR_WARNING", "BLOCK_BUILD"], status="SUPPRESSED", category="SUPPLY_CHAIN",
                remediation="Revisit the approved exception before the next dependency upgrade."),
        finding("sentinel.policy", "SENTINEL-MIRROR-004", "Archive mirror review has been accepted",
                "This historical observation is accepted in the simulated UI state and contributes no active warning or blocker.",
                "scripts/publish.sh", "archive.atlas-payments.invalid", "https://archive.atlas-payments.invalid/approved-receipts.tar.gz",
                status="ACCEPTED", category="NETWORK"),
    ]
    runs = [
        provider_run("wrapperguard", "WrapperGuard", 5, 86, permissions(ACTIONS), version="0.4.0"),
        provider_run("acme.supply-chain", "Acme Supply Chain", 3, 243,
                     permissions(["WARNING", "MAJOR_WARNING", "ALERT", "BLOCK_BUILD"])),
        provider_run("sentinel.policy", "Sentinel Policy", 4, 141,
                     permissions(["WARNING", "MAJOR_WARNING", "ALERT", "BLOCK_SYNC"])),
        provider_run("demo.registry-audit", "Registry Audit · demo", 0, 1500,
                     permissions(["WARNING"], required=False, timeout=1500), status="TIMED_OUT",
                     message="DEMO: simulated timeout; no SDK process was started."),
        provider_run("demo.license-check", "License Check · demo", 0, 96,
                     permissions(["WARNING"], required=False), status="FAILED",
                     message="DEMO: simulated unavailable adapter; no SDK process was started."),
        provider_run("legacy.audit", "Legacy Audit · paused demo", 0, 0,
                     permissions(["WARNING"], enabled=False, required=False), status="DISABLED",
                     message="DEMO: this fictional provider is paused in the user-owned settings."),
    ]
    graph = [
        ("scripts/bootstrap.sh", "sh scripts/install.sh", "scripts/install.sh"),
        ("scripts/bootstrap.sh", "sh scripts/sync.sh", "scripts/sync.sh"),
        ("scripts/install.sh", "sh scripts/fetch-metadata.sh", "scripts/fetch-metadata.sh"),
        ("scripts/fetch-metadata.sh", "sh scripts/audit.sh", "scripts/audit.sh"),
        ("scripts/sync.sh", "sh scripts/audit.sh", "scripts/audit.sh"),
        ("scripts/audit.sh", "sh scripts/sync.sh", "scripts/sync.sh"),
        ("scripts/audit.sh", "sh scripts/publish.sh", "scripts/publish.sh"),
    ]
    edges = [{"from": location(source, command), "to": location(target), "viaCommand": command}
             for source, command, target in graph]
    notices = [
        {"code": "DEMO-SIMULATED", "message": "DEMO: simulated findings and fictional providers. No SDK calls or project code were executed.", "location": None},
        {"code": "WG-PROVIDER-TIMEOUT", "message": "DEMO: optional Registry Audit timed out; this scenario intentionally has incomplete coverage.", "location": None},
        {"code": "WG-PROVIDER-FAILED", "message": "DEMO: optional License Check is unavailable. Other provider results remain visible.", "location": None},
    ]
    report = {"schemaVersion": 1, "projectPath": str(PROJECT), "verdict": "EXECUTION_BLOCKED",
              "policyName": "Atlas Payments · Enforce demo", "findings": findings, "edges": edges,
              "scannedFiles": len(FIXTURES), "durationMs": 1532, "complete": False, "stale": False,
              "notices": notices, "generatedAtEpochMs": GENERATED_AT, "providerRuns": runs}
    clean_runs = json.loads(json.dumps(runs))
    for run in clean_runs:
        run["findingCount"] = 0
        run["durationMs"] = 0
        run["message"] = None
        if run["status"] != "COMPLETED":
            run["status"] = "DISABLED"
            run["permissions"]["enabled"] = False
            run["message"] = "DEMO: disabled for the alternate clean UI scenario."
    clean = dict(report, verdict="CLEAN", findings=[], edges=[], complete=True, durationMs=121,
                 policyName="Atlas Payments · Clean demo", providerRuns=clean_runs,
                 notices=[notices[0], {"code": "DEMO-CLEAN-SCENARIO", "message": "DEMO: alternate clean UI state; not an actual scan of the illustrative source fixtures.", "location": None}])
    baseline = [{"fingerprint": findings[10]["fingerprint"], "ruleId": findings[10]["ruleId"],
                 "reason": "DEMO ONLY: fictional user-approved exception for the legacy bridge; never import as a production baseline."}]
    settings = {"allowedHosts": ["services.gradle.org", "downloads.gradle.org", "repo.maven.apache.org"],
                "allowedUrlPatterns": [r"^https://services\.gradle\.org/distributions/gradle-[0-9.]+-(bin|all)\.zip$"],
                "blockedCommands": ["certutil", "bitsadmin"], "maxScriptDepth": 10, "maxFileBytes": 1000000,
                "requireDistributionSha256": True, "enableRecursiveScanning": True,
                "knownSafeRepositoryHosts": ["repo.maven.apache.org"], "trustedWrapperChecksums": {},
                "maxLineLength": 10000, "maxFilesPerScan": 50000, "maxDirectoryDepth": 64}
    manifest = {"demo": True, "projectName": "atlas-payments", "reportSchemaVersion": 1, "providerApiVersion": 1,
                "generatedAt": "2026-10-19T10:50:00Z", "scenarios": {"blocked": "report.json", "clean": "report-clean.json"},
                "expected": {"findings": 12, "active": 10, "activeBlockers": 3, "activeWarnings": 6, "auditEvidence": 1,
                             "suppressed": 1, "accepted": 1, "buildBlockers": 2, "syncBlockers": 2,
                             "fixtureFiles": 12, "graphFiles": 6, "graphEdges": 7, "providers": 6,
                             "providerStatuses": {"COMPLETED": 3, "TIMED_OUT": 1, "FAILED": 1, "DISABLED": 1}},
                "inventory": [{"path": path, "sha256": hashlib.sha256(text.encode()).hexdigest(),
                               "lines": len(text.splitlines())} for path, text in sorted(FIXTURES.items())],
                "notes": ["All report findings, statuses, provider identities and timings are simulated UI data.",
                          "scannedFiles counts the 12 checked-in text fixtures, not measured detector coverage.",
                          "Do not execute/import/sync the illustrative project. No real SDK is called.",
                          "No vulnerability score, coverage percentage or vendor integration claim is fabricated."]}
    validate(report, clean, baseline, manifest)
    return {"report.json": report, "report-clean.json": clean, "settings.json": settings,
            "baseline.json": baseline, "design-manifest.json": manifest}


def validate(report, clean, baseline, manifest):
    findings = report["findings"]
    active = [item for item in findings if item["status"] == "ACTIVE"]
    blockers = [item for item in active if item["policyAction"] == "BLOCK"]
    warnings = [item for item in active if item["policyAction"] == "REVIEW"]
    expected = manifest["expected"]
    assert len(findings) == expected["findings"]
    assert len(active) == expected["active"]
    assert len(blockers) == expected["activeBlockers"]
    assert len(warnings) == expected["activeWarnings"]
    assert sum(item["kind"] == "AUDIT_EVIDENCE" for item in findings) == expected["auditEvidence"]
    for status in ["SUPPRESSED", "ACCEPTED"]:
        assert sum(item["status"] == status for item in findings) == expected[status.lower()]
    for action, key in [("BLOCK_BUILD", "buildBlockers"), ("BLOCK_SYNC", "syncBlockers")]:
        assert sum(action in item["actions"] for item in blockers) == expected[key]
    runs = report["providerRuns"]
    assert len(runs) == expected["providers"]
    assert {status: sum(run["status"] == status for run in runs) for status in expected["providerStatuses"]} == expected["providerStatuses"]
    by_provider = {run["descriptor"]["id"]: run for run in runs}
    assert len({item["fingerprint"] for item in findings}) == len(findings)
    for provider, run in by_provider.items():
        assert sum(item["providerId"] == provider for item in findings) == run["findingCount"]
        assert run["descriptor"]["apiVersion"] == 1
    for item in findings:
        assert set(item["actions"]) <= set(by_provider[item["providerId"]]["permissions"]["actions"])
        assert item["location"]["path"] in FIXTURES
        assert 1 <= item["location"]["line"] <= len(FIXTURES[item["location"]["path"]].splitlines())
        if item["policyAction"] == "BLOCK":
            assert item["kind"] == "ACTIONABLE" and item["severity"] != "INFO" and item["reachable"]
            assert set(item["actions"]) & {"BLOCK_BUILD", "BLOCK_SYNC"}
    suppressed = next(item for item in findings if item["status"] == "SUPPRESSED")
    assert baseline[0]["fingerprint"] == suppressed["fingerprint"]
    graph_files = {edge[endpoint]["path"] for edge in report["edges"] for endpoint in ["from", "to"]}
    assert len(graph_files) == expected["graphFiles"] and len(report["edges"]) == expected["graphEdges"]
    assert report["scannedFiles"] == len(FIXTURES) == expected["fixtureFiles"]
    assert datetime.fromtimestamp(report["generatedAtEpochMs"] / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ") == manifest["generatedAt"]
    assert report["complete"] is False and report["verdict"] == "EXECUTION_BLOCKED"
    assert clean["complete"] is True and clean["verdict"] == "CLEAN" and not clean["findings"]
    assert all(run["status"] in ["COMPLETED", "DISABLED"] for run in clean["providerRuns"])
    assert "DEMO_NOT_A_REAL_SECRET" not in json.dumps(report)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Validate counts and compare all generated artifacts without writing files.")
    args = parser.parse_args()
    artifacts = {"project/" + path: text for path, text in FIXTURES.items()}
    artifacts.update({name: encoded(value) for name, value in outputs().items()})
    artifacts.update(IDE_METADATA)
    mismatches = []
    if args.check and PROJECT.exists():
        ignored = {".idea", ".gradle", "build", "out", ".DS_Store"}
        actual = {path.relative_to(PROJECT).as_posix() for path in PROJECT.rglob("*")
                  if path.is_file() and not ignored.intersection(path.relative_to(PROJECT).parts)}
        mismatches.extend("project/" + path for path in sorted(actual - set(FIXTURES)))
    for relative, content in artifacts.items():
        target = DEMO / relative
        if args.check:
            if not target.is_file() or target.read_bytes() != content.encode("utf-8"):
                mismatches.append(relative)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content.encode("utf-8"))
            if target.suffix == ".sh":
                target.chmod(0o644)
    if mismatches:
        print("Demo artifacts differ: " + ", ".join(mismatches), file=sys.stderr)
        return 1
    print("Demo baseline verified: 12 findings, 3 active blockers, 6 active warnings, 6 providers, 6 graph files / 7 edges.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
