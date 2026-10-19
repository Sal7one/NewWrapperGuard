# Connect SAST, SCA, and DAST reports

WrapperGuard presents findings and applies user-granted actions. Its built-in scanner
continues to inspect wrappers and build/bootstrap scripts. The optional Python SDK
translates reports from other tools into the same provider API; it does not replace
those tools, install their rules, reproduce their analysis, or execute an application
backend in a sandbox.

The SDK is `wrapperguard-sdk` version **0.1.0**, requires **Python 3.10+**, and has
no runtime dependencies outside the Python standard library. The existing Kotlin and
TypeScript host protocol stays at **API 1**. SDK version and host version are separate.

## Available connections

| Analysis | Language or application | SDK input | How analysis runs |
|---|---|---|---|
| SAST | Python | Bandit JSON | Import an existing report, or use the SDK's fixed Bandit runner |
| SAST | PowerShell | PSScriptAnalyzer JSON | Import a report produced by a separately installed analyzer |
| SAST | Languages supported by the producing tool | SARIF 2.1.0 | Import an existing report, for example from a SARIF-capable SAST tool |
| SCA | Dependency ecosystems represented by the producing tool | Trivy JSON or OSV-Scanner JSON | Import an existing dependency-vulnerability report |
| DAST | An explicitly scoped web application | ZAP traditional JSON | Import an existing report whose target URLs satisfy the profile scope |

Gradle/Maven, npm, and Python dependency coverage depends on the upstream scanner,
the input manifests or lockfiles it inspected, its vulnerability database, and the
content of its report. Selecting an importer does not establish that every dependency
or ecosystem was scanned. SCA findings describe dependencies; SAST findings describe
source analysis; DAST findings describe an application's tested target.

These are defined report subsets: SARIF uses text messages and project-local physical
locations; Trivy imports dependency vulnerabilities with a local target or package file;
OSV input is OSV-Scanner output, not an arbitrary OSV API response. OSV CVSS vectors are
not evaluated by the adapter. ZAP input is the traditional JSON report, not an HTML report.
Unsupported sections, invalid locations, or malformed records make coverage incomplete.
Report suppression/baseline metadata cannot create a host-approved exception.

All six import formats have synthetic regression fixtures. Those fixtures demonstrate
translation and failure handling, not successful runs of the named vendor tools.
The installed host smoke uses synthetic Bandit, SARIF, and ZAP reports; it makes no
scanner calls or network requests. Only the Bandit runner executes a scanner directly.

## Install and review a connection

Create a Python 3.10+ environment outside the projects you will inspect. From a trusted
checkout of this repository:

```sh
python3 -m venv "$HOME/.local/share/wrapperguard/sdk-venv"
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m pip install ./sdk/python
```

Choose a [profile example](../examples/integrations/README.md), copy it into a
user-owned directory such as `~/.config/wrapperguard/integrations/`, and replace all
placeholder paths. The profile path must be absolute and resolve outside the project.
The SDK also rejects a symlink to a profile inside the project. Do not use repository
files as permission or execution configuration.

A profile supplies identity, analysis kind, report format, an absolute report path,
allowed operations, and requested actions. It has either `reportPath` or the supported
`runner` object. It cannot contain arbitrary `command` arguments or host permissions.

Review a profile without starting its runner or reading its report:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -I -m wrapperguard_sdk \
  --profile /absolute/user-config/python-bandit.profile.json \
  --plan --project /absolute/project
```

The installed `wrapperguard-sdk` console exposes the same arguments. The host examples
use the environment's absolute Python executable with `-I -m wrapperguard_sdk`, so a
project's current directory or `PYTHONPATH` cannot substitute a same-named SDK module.

## Produce a report with an installed tool

Run these separately with trusted scanner installations. Start in the project root and
create the user-owned report directory first. Each command produces one importable report;
the SDK does not install or invoke these commands. Review upstream scan errors and
configuration as well as the JSON output. Some tools return a nonzero exit code for findings.

For Python, [Bandit's JSON output](https://bandit.readthedocs.io/en/latest/man/bandit.html):

```sh
bandit -r . --ignore-nosec -f json -o /absolute/user/reports/bandit.json
```

For dependency vulnerabilities, [Trivy filesystem scanning](https://trivy.dev/docs/latest/references/configuration/cli/trivy_filesystem/)
or [OSV-Scanner source scanning](https://google.github.io/osv-scanner/usage/scan-source):

```sh
trivy fs --scanners vuln --pkg-types library --format json --output /absolute/user/reports/trivy.json .
osv-scanner scan source --recursive --no-resolve --format json --output-file /absolute/user/reports/osv.json .
```

Use resolved dependency inputs where the scanner supports them. OSV's `--no-resolve`
disables transitive resolution, so an unresolved manifest can leave dependencies uncovered.
Gradle/Maven coverage depends on the actual files and versions the upstream tool recognizes.
The SDK does not run Gradle, Maven or dependency installation to generate those inputs.

For PowerShell 7 with [PSScriptAnalyzer](https://learn.microsoft.com/en-us/powershell/module/psscriptanalyzer/invoke-scriptanalyzer)
already installed, retain the diagnostic array even when it is empty:

```powershell
$ErrorActionPreference = 'Stop'
$diagnostics = @(Invoke-ScriptAnalyzer -Path '/absolute/project' -Recurse)
ConvertTo-Json -InputObject $diagnostics -Depth 10 -EnumsAsStrings |
    Set-Content -LiteralPath '/absolute/user/reports/psscriptanalyzer.json' -Encoding utf8
```

The [JSON conversion](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/convertto-json)
uses string severities; the importer also accepts numeric diagnostic enums. Set the
chosen profile's `reportPath` to the resulting absolute filename.

## Connect the host

Copy [host-providers.example.json](../examples/integrations/host-providers.example.json)
outside the project. Replace its absolute executable/profile paths and enable the
providers you want. It keeps the built-in scanner enabled and starts external providers
disabled with warning-only grants. One host configuration can include multiple SDK
profiles and existing third-party SDKs at the same time.

For the CLI:

```sh
wrapperguard-cli /absolute/project \
  --providers /absolute/user-config/providers.json --operation scan --json
```

IntelliJ uses the same external configuration through **Settings → Tools → WrapperGuard**.
VS Code uses the user-level `wrapperGuard.providerConfigPath` setting. Workspace-level
configuration cannot grant provider authority. See [the host integration contract](providers.md)
for registration, trusted workspaces, concurrent evaluation, and gate coverage.

The SDK defaults to `allowedOperations: ["SCAN"]` and `requestedActions: ["WARNING"]`.
An attempt to use that profile for BUILD or SYNC fails visibly; it does not return an
empty successful report. To include an integration in those operations, explicitly
review and update its allowed operations. Reimporting an old report does not establish
that it matches the current project contents.

Host grants cap requested actions. A profile asking to block cannot block with only a
WARNING grant. An optional failed integration still makes aggregate coverage incomplete;
`required: false` does not mean a failure disappears from the report. A required failure
can block only under the host's relevant grants and policy. This is an integration layer,
not an activation of the inactive research ML scorer.

## Running Bandit

The optional Bandit runner accepts a separately installed absolute executable, a timeout,
and an output budget. Install Bandit into a trusted environment and point the profile
at that executable. The runner currently requires POSIX no-follow file access; Windows
users can import an existing Bandit JSON report instead. The SDK chooses the scan arguments; profiles cannot add arbitrary
flags or shell commands. It analyzes a staged, non-executable snapshot of project Python
sources and excludes symlinks and workspace tool configuration. Skipped/unsupported input
must remain visible as incomplete coverage. The host's process timeout also bounds the
adapter invocation.

Inspect the plan before enabling [bandit-runner.profile.json](../examples/integrations/bandit-runner.profile.json).
The runner does not execute the Python source it inspects. The installed Bandit executable
and its environment remain trusted software; WrapperGuard does not turn them into an OS
sandbox.

## DAST target boundary

The ZAP connection imports a report. It does not start ZAP, browse a target, discover
endpoints, send requests, or start an application's backend. This release has no active
DAST runner. Active testing needs an explicit target and authorization; a project path
alone does not provide either.

A DAST profile must declare exact HTTP(S) origins and optional segment-bounded path
prefixes. For example, origin `https://owned-application.example.invalid` and prefix
`/app` allow `/app` and `/app/profile`, but exclude another origin or `/application`.
Default ports are normalized. Userinfo, fragments, traversal, and ambiguous encoded
paths are rejected. Only use targets you are authorized to assess; the example `.invalid`
domain is deliberately fictional.

Each imported alert URL must satisfy that scope. An out-of-scope result cannot become a
successful clean assessment. DAST findings use `location.path: "."` as a project-target
association and do not invent a source filename or line number.

## Limits and verification

The SDK bounds profile input to **64 KiB**, imported reports to **16 MiB**, and protocol
output to **4 MiB**. Import errors and truncated/partial coverage are reported explicitly.
Source locations resolving outside the project are rejected with incomplete coverage;
they are not rewritten to look like valid local findings. Provider evidence is minimized
and redacted, but an importer cannot recognize every vendor-specific secret.

Providers and configured executables are trusted code running with the user's privileges
and environment. Host action grants govern warnings and execution gates; they do not
restrict filesystem, network, credential, CPU, or OS access. Imported reports and upstream
scanner installations also need a trustworthy provenance.

Run checks from the repository root with the installed SDK environment. Bandit and
jsonschema below are verification tools, not SDK runtime dependencies:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m pip install \
  'bandit==1.9.4' 'jsonschema==4.25.1'
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" -m unittest discover -s sdk/python/tests -v
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" scripts/validate-integration-profiles.py
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" scripts/sdk-smoke.py \
  --sdk-python "$HOME/.local/share/wrapperguard/sdk-venv/bin/python"
npm --prefix vscode-extension run compile
node vscode-extension/scripts/sdk-smoke.js \
  --sdk-python "$HOME/.local/share/wrapperguard/sdk-venv/bin/python"
```

The JVM smoke requires a built `:wrapperguard-cli:installDist`. It verifies an installed SDK,
two report providers in one evaluation, warning-only host authority, explicit operation
denial, malformed input, external configuration boundaries, source traversal rejection,
and DAST origin/path scope. The TypeScript smoke uses the real command-provider loader
and coordinator to check concurrent providers, distinct grants, redaction, and failures.
Both use labeled synthetic reports and never execute their source fixtures.

The separate POSIX check invokes the real installed Bandit scanner on generated Python
source data. It checks that project code stays unexecuted, project suppression/configuration
does not govern the scan, and skipped symlinks produce incomplete coverage:

```sh
"$HOME/.local/share/wrapperguard/sdk-venv/bin/python" scripts/sdk-bandit-smoke.py \
  --sdk-python "$HOME/.local/share/wrapperguard/sdk-venv/bin/python" \
  --bandit "$HOME/.local/share/wrapperguard/sdk-venv/bin/bandit"
```

That check runs Bandit, not PSScriptAnalyzer, Trivy, OSV-Scanner, or ZAP. It starts no
application backend, contacts no target, and is not a claim of OS-level tool isolation.
