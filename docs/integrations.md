# Set up optional analysis methods

WrapperGuard combines tools in one workspace. The host owns user permissions, findings
and enforcement; each analyzer owns its detection method. The Python SDK **0.2.0** uses
Python **3.10+**, has no runtime dependencies, and communicates over provider **API 1**.
Scanner packages and model servers are separate installations.

## Start with the GUI

Open **Tools** in the WrapperGuard workspace in Android Studio, IntelliJ IDEA or VS Code.
Choose **Built-in**, **SAST**, **Supply chain** or **DAST**. The separate **Research** tab
(**Research & AI** in VS Code) contains script behavior signals and local AI review. These categories come from the
shared [15-method catalog](../catalog/security-methods.json).

1. Choose **Install SDK…** and browse to a trusted Python 3.10+ executable outside your
   projects. The installer creates a dedicated user environment and installs the bundled
   SDK wheel offline. It does not modify the chosen Python environment.
2. Optionally select **Bandit 1.9.4** or **Semgrep 1.181.0**. These explicit choices download
   the named scanner and its dependencies from PyPI. Other analyzers use your existing
   installations; their setup guides open from the connection dialog.
3. Choose **Connect** for a method. Select an existing report or the supported installed
   analyzer. Browse for its executable and any required module/rule file. The UI generates
   the profile and exact SDK command in user configuration outside project source.
4. In IntelliJ, **Verify and save** checks the installed SDK without starting a scan or
   reading the selected report; then review **Permissions**, enable and scan. VS Code's
   reviewed **Connect and run a scan** action completes its setup wizard. Optional methods
   start off; connection defaults are warning-only and limited to explicit SCAN.
5. Results identify their provider and last-run status. Connection and permission changes
   require another scan; an old report is not a new assessment.

Custom SDK registrations and existing provider files remain available. A managed connection
with the same provider ID requires an explicit choice to take precedence. Disconnecting or
disabling a method does not uninstall your tools or alter another provider's permissions.

On Windows, use existing-report imports for source/dependency tools. Controlled file-snapshot
runners currently require POSIX no-follow file access. Local AI report review can use a
Windows loopback server. The GUI does not offer unsupported direct scanner downloads there.

## Available methods

| Category / method | Input and execution | Default |
|---|---|---|
| Built-in wrapper integrity | Gradle wrapper URLs, configured checksum trust and launcher structure | On |
| Built-in script rules | Static shell, batch and PowerShell checks | On |
| Built-in Gradle rules | Repositories, execution patterns and changing versions | On |
| Built-in version catalogs | Changing dependency versions | On |
| Built-in script graph | Bounded local script references and call chains | On |
| Built-in Maven rules | Static POM repository URLs and changing versions; no Maven execution | On |
| SAST · Semgrep | Installed scanner over Kotlin/Java source; SARIF output | Off |
| SAST · Bandit | Installed scanner over Python source, or Bandit JSON import | Off |
| SAST · PSScriptAnalyzer | Installed PowerShell/module over source, or diagnostic JSON import | Off |
| SAST · SARIF | Existing SARIF 2.1.0 reports from a chosen producer | Off |
| Supply chain · Trivy | Installed scanner over selected dependency inputs, or Trivy JSON import | Off |
| Supply chain · OSV-Scanner | Installed scanner over selected dependency inputs, or OSV-Scanner JSON import | Off |
| DAST · ZAP | Existing traditional JSON report with explicit target scope | Off |
| Research · Script behavior | Local bounded literal decoding and simple def/use signals | Off |
| AI · Local review | Selected finding metadata sent to an explicit loopback model server | Off |

The SDK recognizes **eight format identifiers**: six passive report imports (`sarif`,
`bandit`, `psscriptanalyzer`, `trivy`, `osv`, `zap`) and two controlled advisory outputs
(`script-semantics`, `ai-review`). Semgrep uses the existing SARIF adapter.

The six included static groups can be disabled separately. Intentional exclusions produce
coverage notices; completion describes the configured scope. Actual read errors, parsing
failures and exhausted limits remain incomplete. With every built-in method off, an enabled
external analysis provider can replace them. Zero detectors, or AI/research alone, cannot
establish complete analysis coverage. Unknown organization SDKs remain supported.

## Android and Ktor source analysis

The optional Semgrep runner reads regular `.kt`, `.kts` and `.java` files from a bounded
snapshot. It uses six [bundled review rules](../sdk/python/src/wrapperguard_sdk/rules/android-ktor.yml):
unrestricted Ktor CORS, Android JavaScript bridges, concatenated raw SQL, empty Java and
Kotlin server-certificate checks, and a Kotlin hostname verifier that always accepts.

These targeted checks need context and do not cover all Android, Ktor or Java security
issues. They do not build an app, resolve plugins or execute Gradle/Maven. You can select
an explicitly reviewed rule file outside the project to change or expand coverage. The
runner uses local rules, disables metrics/version checks, and ignores repository
`.semgrepignore` and source `nosemgrep` suppression authority.

Gradle/Maven configuration checks review build inputs. Dependency scanners inspect
supported manifest/lock data. Neither establishes that application authorization,
cryptography, storage or business logic has been comprehensively analyzed.

## Controlled scanner runners

All five external runners use fixed argument arrays, bounded non-executable snapshots,
owned tool configuration and a cleaned environment. Profiles cannot inject arbitrary
shell commands or scanner flags. Repository configuration cannot select a tool executable,
grant actions or approve a finding. Symlinks, unsupported source data and exhausted budgets
remain visible when they prevent complete inspection.

| Runner | Inputs / additional setup | Network behavior |
|---|---|---|
| Bandit | Python source; installed absolute Bandit executable | No network requested |
| Semgrep | Kotlin/Java source; installed executable; optional reviewed external rule file | Local rules; metrics/version checks disabled |
| PSScriptAnalyzer | PowerShell source; installed `pwsh` and trusted PSScriptAnalyzer module | No network requested |
| Trivy | Allowlisted manifests/locks; optional user cache outside projects | May download vulnerability/index databases; dependency API resolution disabled |
| OSV-Scanner | Allowlisted manifests/locks; `--no-resolve`, `--all-packages` | Sends dependency names/versions to the tool's OSV service |

The dependency allowlist includes POMs, Gradle lock/verification metadata, Python locks,
requirements and selected npm/other ecosystem locks. Resolved versions and upstream support
matter: an unresolved POM is not proof of complete transitive coverage. The runner does not
run builds, install project dependencies, or reconstruct a fully resolved dependency graph.
Trivy/OSV default to 120-second SDK budgets; host timeout must allow that work too.

Installed scanners and plugins remain trusted OS-user code. Snapshotting limits accidental
repository influence; it is not OS sandboxing. See [provider lifecycle and cancellation](providers.md).

## Research and local AI

**Script behavior signals** run locally without spawning an analyzer process or using a network connection.
Python AST analysis tracks a limited set of Base64-decoding, HTTP-download, environment
and execution relationships, including simple aliases and assignments. PowerShell checks
inspect bounded literal decoding and lexical behavior tokens. Decoded payloads are not
executed or exported. Unsupported syntax and parsing/decoding budgets reduce coverage.
The output is advisory evidence, not a trained malware classifier or a paper reproduction.
See [the research mapping](research.md).

**Local AI review** uses an already running OpenAI-compatible server at an explicit numeric
loopback endpoint such as `http://127.0.0.1:1234/v1`. Choose its model identifier and a
WrapperGuard JSON report outside the project. The dialog can prepare minimized input from
the current report. No model is downloaded or bundled.

At most five findings contribute rule ID, title, message and severity. The SDK redacts and
truncates metadata, removes common path/credential forms, and excludes source files,
evidence, call chains and project paths from requests. Treat upstream messages as untrusted;
redaction cannot identify every arbitrary secret. Model requests bypass proxy environment
variables, reject remote/DNS endpoints and redirects, and have bounded time and output.

Model prose remains untrusted plain advisory text. Output locations come from the selected
report; the model cannot choose paths, severity, policy, approvals or actions. AI output is
always INFO audit evidence with no actions. Research evidence also cannot create build/sync
blockers. Empty inputs and partial reviews are explicit; AI does not produce a safety verdict.

## Existing reports and DAST

Import a current report from a tool you trust. Supported subsets use project-local physical
locations and discard source snippets, matched secrets and HTTP bodies. Invalid paths,
unsupported sections, malformed records, tool errors and report-level suppressions cannot
silently become approved, clean coverage. The producing tool still owns input selection,
configuration, vulnerability data, freshness and analysis quality.

ZAP imports need exact HTTP(S) origins and optional segment-bounded path prefixes. Origin
`https://owned-application.example.invalid` plus `/app` permits `/app` and `/app/profile`,
while excluding another origin and `/application`. Userinfo, ambiguous encoded paths,
fragments and traversal are rejected. Every imported URL must match the scope.

There is **no active DAST runner**: WrapperGuard does not start an application, discover
endpoints or contact a target. ZAP results use project location `.` without an invented
source line. Choose reports from targets you are authorized to assess.

## Manual setup and CI

The GUI performs setup without requiring JSON editing. For CI or organization-managed
configuration, create an SDK environment outside scanned projects from a trusted checkout:

```sh
python3 -m venv "$HOME/.local/share/wrapperguard/sdk-venv"
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m pip install ./sdk/python
```

Copy a [profile example](../examples/integrations/README.md) to user configuration outside
the project and replace every placeholder path. The profile chooses identity, format,
allowed operations, input and requested actions. Exactly one `reportPath` or `runner` is
required. Profiles and executable/module/rule paths must respect their documented trust
boundaries. A symlink into project-controlled configuration is rejected.

Review the plan without executing a tool or reading its report:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -I -m wrapperguard_sdk \
  --profile /absolute/user-config/semgrep-runner.profile.json \
  --plan --project /absolute/project
```

The host invokes that absolute SDK Python with `-I -m wrapperguard_sdk`. Isolation prevents
project-directory or `PYTHONPATH` module substitution. `wrapperguard-sdk` is the equivalent
installed console entry point.

Copy [host-providers.example.json](../examples/integrations/host-providers.example.json)
outside the project, fill its paths, then explicitly enable and grant each provider:

```sh
wrapperguard-cli /absolute/project --providers /absolute/user/providers.json --operation scan --json
```

Method choices use another explicit user file:

```json
{"methodFlags":{"builtin.maven-rules":false,"sast.bandit":true}}
```

```sh
wrapperguard-cli /absolute/project --methods /absolute/user/methods.json --json
```

A true flag does not install or connect a tool, enable a disabled provider, or grant actions.
An explicitly enabled method without its provider is reported as not connected. Absent
optional flags preserve existing user-configured providers. An explicit false flag disables
only the corresponding catalog provider; it does not change another SDK's grant.

## Coverage and trust

A profile defaults to SCAN and WARNING. BUILD/SYNC require explicit operation scope and
separate host grants. Optional failures make coverage incomplete; required failures can
block only when the relevant block action and enforcement policy allow it. INFO/audit
output cannot block. A report can be complete for a deliberately limited scope and still
miss issues outside that scope.

Limits include 64 KiB profiles, 16 MiB report input, 4 MiB protocol output, 5,000 snapshot
files, 1 MiB per source file, 32 MiB total source and depth 64. Host and SDK deadlines are
separate. Installed programs retain OS privileges, and imported reports need provenance.
No setting promises complete application security or universal interception of execution.

[Verification commands and current evidence](verification.md) · [Provider API](providers.md)
