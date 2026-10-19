# Integration profile examples

These are templates, not automatically discovered configuration. Copy chosen profiles
and the host configuration into a user-owned directory **outside the scanned project**.
Replace every `/absolute/...` executable, profile, and report path. Keep installed SDKs
and scanner executables outside project-controlled source as well.

| File | Purpose |
|---|---|
| `bandit-report.profile.json` | Import Python SAST findings from Bandit JSON |
| `bandit-runner.profile.json` | Run an explicitly installed Bandit with SDK-controlled arguments |
| `psscriptanalyzer.profile.json` | Import PowerShell static-analysis JSON |
| `sarif.profile.json` | Import SARIF 2.1.0 findings from an existing scanner |
| `trivy.profile.json` | Import Trivy dependency-vulnerability JSON |
| `osv.profile.json` | Import OSV-Scanner dependency-vulnerability JSON |
| `zap.profile.json` | Import ZAP JSON within the declared fictional target scope |
| `host-providers.example.json` | Connect two profiles to a host with external providers disabled initially |

All profiles allow SCAN and request WARNING only. The host independently grants actions.
The DAST origin uses `.invalid`; it names no actual target and no active DAST runner exists.
The SDK performs no tool installation. A report import does not execute its producing tool
or prove current coverage. See [the integration guide](../../docs/integrations.md) for
installation, review plans, host settings, limits, and trust boundaries.
