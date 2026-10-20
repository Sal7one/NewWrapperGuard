# Integration profile templates

The IDE **Tools / Research → Connect** dialogs create these profiles for ordinary use.
These JSON files are equivalent templates for CI and organization-managed setup. They
are never automatically discovered or executed from a project.

Copy selected files outside every scanned project and replace all `/absolute/...` paths.
Install the SDK, scanners, PowerShell module and optional rule files in trusted user locations.
Keep host provider grants separate from these analysis profiles.

| Template | Purpose |
|---|---|
| [bandit-report.profile.json](bandit-report.profile.json) | Import Python SAST findings |
| [bandit-runner.profile.json](bandit-runner.profile.json) | Run installed Bandit with fixed arguments |
| [semgrep-runner.profile.json](semgrep-runner.profile.json) | Select Android/Ktor, manifest, WebView, JVM crypto and Ktor runtime packs, or a reviewed local rule file |
| [psscriptanalyzer.profile.json](psscriptanalyzer.profile.json) | Import PowerShell diagnostics |
| [psscriptanalyzer-runner.profile.json](psscriptanalyzer-runner.profile.json) | Use installed PowerShell and a trusted analyzer module |
| [cyclonedx.profile.json](cyclonedx.profile.json) | Import CycloneDX 1.4–1.7 vulnerability assessments (inventory alone is incomplete) |
| [sonar.profile.json](sonar.profile.json) | Import current or legacy Sonar generic issues |
| [sarif.profile.json](sarif.profile.json) | Import SARIF 2.1.0 from a chosen producer |
| [trivy.profile.json](trivy.profile.json) | Import dependency-vulnerability findings |
| [trivy-runner.profile.json](trivy-runner.profile.json) | Inspect selected dependency inputs with installed Trivy |
| [osv.profile.json](osv.profile.json) | Import OSV-Scanner dependency findings |
| [osv-runner.profile.json](osv-runner.profile.json) | Inspect selected dependency inputs without transitive resolution |
| [zap.profile.json](zap.profile.json) | Import ZAP results within exact target scope |
| [script-semantics.profile.json](script-semantics.profile.json) | Run bounded, advisory Python/PowerShell behavior checks |
| [ai-review.profile.json](ai-review.profile.json) | Explain existing findings with a configured loopback model |
| [host-providers.example.json](host-providers.example.json) | Register two profiles; external providers start disabled |

Profiles default to SCAN and WARNING. `projectRoot` optionally binds a profile to one canonical project. GUI-created report and AI connections are project-bound. Bundled Semgrep `rulePacks` and an external `rulesPath` are mutually exclusive. Advisory methods request no blocking authority,
and the host independently caps every action. Review the plan before enabling a connection:

```sh
/absolute/sdk-venv/bin/python -I -m wrapperguard_sdk \
  --profile /absolute/user-config/semgrep-runner.profile.json \
  --plan --project /absolute/project
```

Direct snapshot runners require POSIX; Windows users can import reports. ZAP's `.invalid`
origin is deliberately fictional and must be replaced with an explicitly authorized target.
No active DAST runner exists. AI requires a local server/model and an outside-project host
report; no model download occurs. Report imports do not prove current coverage.

[Full setup guide](../../docs/integrations.md) · [Profile schema](../../schema/wrapperguard-integration-profile.schema.json)
