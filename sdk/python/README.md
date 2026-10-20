# WrapperGuard Python SDK

`wrapperguard-sdk` **0.3.0** connects optional analysis methods to provider **API 1**.
It requires **Python 3.10+**, uses only the standard library at runtime, and ships as a
wheel in both IDE packages. Vendor scanners and local model servers are separate tools.

- Eight report importers: SARIF, Bandit, PSScriptAnalyzer, Trivy, OSV-Scanner, scoped ZAP, CycloneDX and Sonar generic issues.
- Five fixed external runners: Bandit, Semgrep, PSScriptAnalyzer, Trivy and OSV-Scanner.
- Five selectable local Semgrep packs; legacy six-rule selection remains the default.
- Local advisory script behavior analysis and loopback-only AI finding explanations.
- Explicit outside-project profiles, operation limits, bounded inputs/output and host-owned grants.

Use **Tools → Install SDK… / Connect** in the IDE for guided setup. The bundled SDK installs
offline into a dedicated environment. Optional pinned Bandit/Semgrep installation is an
explicit network download. POSIX is required for direct snapshot runners; Windows supports
existing-report imports and local model report review.

From a trusted repository checkout, manual installation is:

```sh
python3 -m venv "$HOME/.local/share/wrapperguard/sdk-venv"
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m pip install ./sdk/python
```

Copy a [profile template](../../examples/integrations/README.md) outside the project and
replace the paths. Use `python -I -m wrapperguard_sdk --capabilities` for passive format/pack discovery.
Review without report access or execution:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -I -m wrapperguard_sdk \
  --profile /absolute/user-config/semgrep-runner.profile.json \
  --plan --project /absolute/project
```

Normal operation receives one host request on stdin and returns one API 1 result on stdout.
The host should use the installed absolute Python with `-I -m wrapperguard_sdk`; project
files must not supply the SDK module or user configuration. Profiles request actions;
the host caps them. Every optional method starts disabled until the user connects/enables it.

Research output and local AI explanations are advisory and cannot block. No trained model
is bundled or reproduced. Report imports do not establish source freshness. Snapshotting
and action grants do not sandbox trusted scanner code. ZAP is report-only and sends no
active target requests.

[Setup and coverage](../../docs/integrations.md) · [Provider API](../../docs/providers.md) ·
[Research rationale](../../docs/research.md) · [Verification](../../docs/verification.md)
