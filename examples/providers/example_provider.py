#!/usr/bin/env python3
"""Working protocol example, not a security detector. Replace checks with your SDK."""
import json
import sys
from pathlib import Path


def main():
    request = json.loads(sys.stdin.readline(65_537))
    if request.get("apiVersion") != 1 or request.get("operation") not in ("SCAN", "BUILD", "SYNC"):
        raise ValueError("Unsupported request")
    root = Path(request["projectPath"])
    if not root.is_absolute() or not root.is_dir():
        raise ValueError("Invalid project path")
    demonstration = "--demonstrate-blocking" in sys.argv[1:]
    findings = []
    if demonstration or not (root / "SECURITY.md").is_file():
        findings.append({
            "ruleId": "EXAMPLE-001",
            "title": "Blocking demonstration" if demonstration else "Security contact is missing",
            "message": "Example provider requested build and sync blocking." if demonstration else "Add a SECURITY.md file describing how to report vulnerabilities.",
            "severity": "ERROR" if demonstration else "WARN",
            "confidence": "HIGH",
            "category": "CONFIGURATION",
            "kind": "ACTIONABLE",
            "location": {"path": "."},
            "reachable": True,
            "actions": ["ALERT", "BLOCK_BUILD", "BLOCK_SYNC"] if demonstration else ["WARNING"],
        })
    json.dump({"findings": findings, "scannedFiles": 0, "complete": True}, sys.stdout)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
