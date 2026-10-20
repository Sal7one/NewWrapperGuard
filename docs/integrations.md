# Set up optional analysis methods

WrapperGuard combines tools in one workspace. The host owns user permissions, findings
and enforcement; each analyzer owns its detection method. The Python SDK **0.4.0** uses
Python **3.10+**, has no runtime dependencies, and communicates over provider **API 1**.
Scanner packages and model servers are separate installations.

## Start with the GUI

Open **Tools** in the WrapperGuard workspace in Android Studio, IntelliJ IDEA or VS Code.
Choose **Built-in**, **SAST**, **Supply chain** or **DAST**. The separate **Research** tab
(**Research & AI** in VS Code) contains script behavior signals and local AI review. These categories come from the
shared [19-method catalog](../catalog/security-methods.json).

1. Native: choose **Install SDK…**. VS Code: choose a method’s **Set up → Create a managed SDK environment**. Select a trusted Python 3.10+ executable outside your projects. The installer creates a dedicated user environment and installs the bundled
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

Use native **Tools → Add SDK / custom command** for a vendor adapter or discovered SDK. In VS Code, use **Providers → Add SDK** for command adapters; installed extension SDKs register through API 1. Enter literal argv, descriptive category/tags and declared inputs, then review its independent permissions. **Presets** apply reviewed method flags without installing or enabling tools. Existing provider files remain available. Native same-ID managed precedence requires an explicit choice. VS Code rejects duplicate provider IDs across connection sources; repair the duplicate rather than silently overriding it. Disconnecting or
disabling a method does not uninstall your tools or alter another provider's permissions.

Windows direct runners use tested handle-based snapshots, bounded pipes and a job object for descendant cleanup. Real Bandit, Semgrep, PSScriptAnalyzer, Trivy and OSV acceptance runs in Windows CI; Python 3.10/3.12/3.14 exercise process/file boundaries. Imports and loopback review remain independent alternatives. Scanner availability and its platform support still matter.

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
| SAST · Sonar generic issues | Current/legacy external-issue JSON from a chosen producer | Off |
| SAST · SARIF | Existing SARIF 2.1.0 reports from a chosen producer | Off |
| Supply chain · Trivy | Installed scanner over selected dependency inputs, or Trivy JSON import | Off |
| Supply chain · OSV-Scanner | Installed scanner over selected dependency inputs, or OSV-Scanner JSON import | Off |
| Supply chain · CycloneDX | CycloneDX 1.4–1.7 vulnerability assessments; inventory alone is incomplete | Off |
| DAST · ZAP | Existing traditional JSON report with explicit target scope | Off |
| DAST · HTTP audit | Explicit owned numeric-IP endpoint; bounded GET header/cookie checks | Off |
| Research · Script behavior | Local bounded literal decoding and simple def/use signals | Off |
| Research · Container Python | Explicit selected entry executed in reviewed local Docker isolation | Off |
| AI · Local review | Selected finding metadata sent to an explicit loopback model server | Off |

The SDK recognizes **twelve format identifiers**: eight passive report imports (`sarif`,
`bandit`, `psscriptanalyzer`, `trivy`, `osv`, `zap`, `cyclonedx`, `sonar`) and two controlled advisory outputs
(`script-semantics`, `ai-review`, `http-audit`, `container-python`). Semgrep uses the existing SARIF adapter.

The six included static groups can be disabled separately. Intentional exclusions produce
coverage notices; completion describes the configured scope. Actual read errors, parsing
failures and exhausted limits remain incomplete. With every built-in method off, an enabled
external analysis provider can replace them. Zero detectors, or AI/research alone, cannot
establish complete analysis coverage. Unknown organization SDKs remain supported.

## Android and Ktor source analysis

The optional Semgrep runner offers five independently selected local packs:

| Pack | Review checks |
|---|---|
| `legacy-android-ktor` (default) | Original six CORS, bridge, SQL and TLS checks |
| `android-manifest` | Explicit debuggable, cleartext and backup settings |
| `android-webview` | File-origin access, universal-origin access and debugging |
| `jvm-crypto` | Selected weak cipher/digest calls and fixed numeric random seeds |
| `ktor-runtime` | Stack traces in responses and unrestricted credentialed CORS |

The original six rules remain the default. Selecting more packs is explicit. Kotlin/Java
packs read `.kt`, `.kts` and/or `.java`; the manifest pack adds only exact
`AndroidManifest.xml` files, not every XML resource. An external reviewed rule file replaces
bundled pack selection. `rulePacks` and `rulesPath` are mutually exclusive in profiles.

These targeted checks require context; they do not establish full Android/Ktor coverage.
They do not build an app, resolve plugins or execute Gradle/Maven. Local rules disable
metrics/version checks, and repository `.semgrepignore` / source `nosemgrep` markers cannot
approve findings. Real positive and negative fixtures are exercised by
`scripts/sdk-semgrep-smoke.py`.

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
| Semgrep | Kotlin/Java and exact manifests from selected packs; installed executable; optional external rule file | Local rules; metrics/version checks disabled |
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
the current report. No model is downloaded or bundled. Strict `json_schema` responses are the default; select `json_object` explicitly only for compatible servers that lack schema support. Output is validated in either mode.

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
silently become approved, clean coverage. CycloneDX component references must resolve; VEX annotations remain context and cannot create host approvals. A component inventory without vulnerability assessment is incomplete. Sonar imports support both legacy severities and current impact-based issues.

The producing tool still owns input selection,
configuration, vulnerability data, freshness and analysis quality.

ZAP imports need exact HTTP(S) origins and optional segment-bounded path prefixes. Origin
`https://owned-application.example.invalid` plus `/app` permits `/app` and `/app/profile`,
while excluding another origin and `/application`. Userinfo, ambiguous encoded paths,
fragments and traversal are rejected. Every imported URL must match the scope.

ZAP remains passive import. The separate **HTTP audit** makes explicitly configured GET requests to an already-running owned target. It is a limited header/cookie audit, without crawling or exploit testing. Choose a numeric IPv4/IPv6 HTTP(S) URL, exact segment-bounded path, ownership confirmation, request/time budgets and authentication environment-variable **names**. Values are read at invocation and never entered into the setup UI or stored in profiles. DNS, redirects, userinfo, proxy inheritance and target startup are rejected. Canceling ownership/auth review saves nothing and makes no request. Results use project location `.` without inventing source lines. This connection is SCAN-only; managed BUILD/SYNC gates skip it before launch.

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

Use `python -I -m wrapperguard_sdk --capabilities` to list supported formats and local packs without a profile, project, scan, installation or network request. Optional profile `projectRoot` binds report/AI input to one canonical project; GUI-created report and AI connections use this binding.

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

## History, multiple projects and managed storage

VS Code's project selector chooses which workspace root a scan, gate, connection or
finding navigation uses. A root-bound command independently refuses other projects.
Reusable source runners and explicit all-project connections remain possible.

**History** is opt-in in both hosts. It stores bounded issue summaries (including titles
and relative paths), provider versions, operation and scope identity outside source. It
excludes messages, evidence and source bytes. Retention defaults to 20 (maximum 100).
Comparisons require the same project/operation/known scope and complete current reports.
Imported producer provenance and opaque SDK/package/model semantics can make scope unknown; those saved runs remain visible with comparisons disabled. Approval changes do not look like remediation. Pause and clear are separate controls.

**Managed storage** inventories owned SDK/profile/input data. Cleanup requires an explicit selection and preserves original user files, active environments and referenced artifacts. The connection maintenance UI can repair the SDK offline, explicitly update separately selected tools, clear owned caches and remove only unused generated environments. It preserves legacy and user-owned installations. Disconnecting does not automatically delete or uninstall tools.

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

## Provenance and dependency coverage

Report connection setup can select a producer/input manifest and maximum report age. The optional `reportProvenance` envelope binds exact report/input SHA-256 values and shows producer/version, generation time, source revision, database/update identity and declared scope. Missing metadata remains unverified; an input match is not a signature or proof that the producer inspected everything. Expired/mismatched inputs cannot become current clean coverage.

Supply-chain setup exposes `dependencyCoverage.requireResolvedVersions`. Resolved and unresolved package/version inventory and producer database age remain visible. Gradle/Maven manifest input alone cannot prove a resolved transitive graph. No connection silently runs dependency resolution, Gradle, Maven or project install hooks.

## Optional local container backend

Choose **Container** explicitly for Bandit/Semgrep, or configure **Research → Container Python**. Use a reviewed local Unix-socket Docker engine and preinstalled immutable image from `containers/runtime.lock.json`; the product never starts the daemon or pulls an image. Rootless is preferred; a rootful engine requires its separate review.

The backend copies bounded input bytes into owned staging; it never bind-mounts the original project. Containers use no network, read-only rootfs, explicit memory/swap/CPU/PID limits and no-new-privileges. A trusted root bootstrap/timer retains only KILL/SETUID/SETGID to establish a hard worker deadline; the analyzer/payload worker drops to UID/GID65532 with no effective/permitted/ambient capabilities before reading data. Cleanup removes the known container and verifies absence, including when the SDK is killed. These controls trust the kernel, daemon and bootstrap, and do not constitute VM isolation.

The Python research provider executes only the selected entry in that environment. Its observations are bypassable, advisory INFO audit evidence with no actions, and SCAN-only. It has no host fallback. Inert adversarial acceptance tests cover network, privileges, root-clock stop denial, resource limits and cleanup; there is no claim of safely containing arbitrary kernel/container escapes or a reproduction of PowerPeeler/DONAPI.

## Automation and organization presets

Open **Automation** in the Tools UI. Select providers, change debounce (500–60000 ms), change-trigger and optional interval (5–1440 minutes; 0 disables it), then review each provider's network/cost declaration. Saving stores preferences and leaves the session paused. **Review & start** arms the selected project for future events; it makes no immediate or catch-up scan. Runs do not overlap. Project/trust/provider configuration changes pause the session; **Pause** cancels an automatic run. Declarations are review information, not sandbox guarantees. Only SCAN runs automatically.

Organization preset import/export carries schema version, issuer/version, method flags and canonical SHA-256. Review identity/digest/changes before applying. Presets contain no commands, credentials, enabled connections, grants or automatic execution. A digest detects changed data; it does not authenticate an issuer. Signed enterprise distribution can wrap this data contract without changing host permissions.

Reviewed [CI/build examples](../examples/ci/README.md) use the same CLI and exit codes. The [shared conformance adapter](../examples/provider-conformance/README.md) exercises malformed/partial responses, deadlines, cancellation, attribution and per-operation grants through both hosts.
