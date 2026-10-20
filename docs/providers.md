# Provider integration, API 1

WrapperGuard owns the workspace, report presentation, trusted approvals and enforcement.
A provider owns its analysis. Register several local SDKs or explicitly configured command
adapters; the host runs enabled providers concurrently and combines their findings.
The built-in Kotlin/TypeScript scanner is a normal provider named `wrapperguard` and can
be disabled through user settings. No particular detector or vendor is required.

## Common contract

Providers have a stable `id`, `displayName`, `version`, and `apiVersion: 1`. A provider
receives the project root and the operation `SCAN`, `BUILD`, or `SYNC`. In-process SDKs
also receive a copy of scan policy/settings; command adapters receive metadata only.
Results contain `findings`, optional `edges`, `scannedFiles`, `complete` and `notices`.

Findings use the existing report model: rule ID, title, message, severity (`INFO`, `WARN`,
`ERROR`), confidence, category and project-relative source location. Add requested
`actions` from this vocabulary:

| Action | Meaning |
|---|---|
| `WARNING` | Present a warning requiring review |
| `MAJOR_WARNING` | Present a prominent warning requiring review |
| `ALERT` | Present an alert requiring review |
| `BLOCK_BUILD` | Prevent a supported build/run operation in Enforce mode |
| `BLOCK_SYNC` | Prevent a supported Gradle synchronization in Enforce mode |

These finding fields are sufficient. Providers can omit `fingerprint`, `providerId`,
`policyAction` and `status`; the host creates them. TypeScript exposes `ProviderFinding`
for this input and keeps the full `Finding` type for stored reports. `kind` defaults to
`ACTIONABLE`, and `reachable` defaults to true.

The host intersects requested actions with user grants. Providers cannot grant themselves
permissions, spoof another provider, pre-approve their own findings, or escalate through
the legacy `policyAction` field. External findings receive host-generated, provider-scoped
fingerprints; built-in fingerprints and existing approved baselines remain stable.
Blocking also requires an active actionable finding meeting host severity/reachability
policy. `INFO`, audit evidence and the legacy experimental `mlSignal` shadow field cannot
block. A vendor's normal SDK findings do not need to use that legacy research field.
During a `BUILD` evaluation only `BLOCK_BUILD` is effective; during `SYNC` only
`BLOCK_SYNC` is effective. `SCAN` can display both granted blocking decisions.

## User permissions and failures

New third-party providers start disabled with no actions granted. The user enables them
and selects actions, a 100–300000 ms timeout, and whether completion is required.
Default built-in scanning is enabled, required, and has all five action grants.

Optional failure produces an incomplete report and a provider notice. A required provider
that fails, times out or reports incomplete coverage also generates a host blocker if
the user granted the relevant block action and Enforce policy allows it. No enabled
providers produces `INCOMPLETE`, never a clean bill of health. CLI exit codes are
`0` for a complete report with no blockers, `2` for blockers, and `1` for invalid input
or an incomplete report without blockers. A scanner finding is not a process failure.

Reports include `providerRuns`: descriptor, user permissions, status (`COMPLETED`,
`FAILED`, `TIMED_OUT`, `DISABLED`), elapsed milliseconds, finding count and a safe message.
Results are ordered by registration, independently of completion order. A required partial
result retains its findings and displays its incomplete status.

## Optional methods and replacement

The shared [method catalog](../catalog/security-methods.json) supplies descriptive categories
and defaults. User-level `ScanSettings.methodFlags` select methods independently of host
grants. Flags never install tools, enable a disabled provider, approve findings or change
another provider's permissions. Existing providers remain compatible when no optional
method flag is set; explicit false suppresses the corresponding catalog provider only.

Disabling an included method emits a reduced-scope notice. A completed configured scope
is distinct from full-project coverage. When every built-in method is explicitly off,
an enabled external analyzer can replace the built-in provider. Unknown organization
providers qualify; catalog AI/research methods alone do not establish analysis coverage.
Zero detectors, missing explicitly requested connections and real scan failures remain
incomplete. Required failure blocking still depends on relevant grants and policy.

The [setup guide](integrations.md) covers GUI-managed connections, SDK 0.3.0 profiles,
source SAST, dependency analysis and advisory research/AI. Custom providers need not use
the Python SDK or adopt a particular algorithm to share the host interface.

## Command adapters: any language or SDK

Copy [the example configuration](../examples/providers/providers.example.json) into a
user-owned directory outside the scanned project, such as `~/.config/wrapperguard/`.
Set absolute executable/adapter paths, enable the provider and grant the actions you want.
The configuration path itself must be absolute; symlinks resolving inside the project
are rejected. Unknown fields, duplicate IDs and unsupported versions fail validation.
The host never discovers command configurations in repository content.

```sh
wrapperguard-cli /path/to/project --providers /home/user/.config/wrapperguard/providers.json --operation build --enforce --json
```

Each invocation starts the exact `command` argv array, without an implicit shell, in the
project directory. The provider receives one UTF-8 JSON line on stdin:

```json
{"apiVersion":1,"projectPath":"/path/to/project","operation":"BUILD"}
```

Return one `ProviderResult` JSON object on stdout and exit **0**, including when reporting
blocking findings. Exit nonzero means the provider failed. Output is limited to 4 MiB;
stderr is discarded, and exception/source/command text is not echoed in error notices.
Use the working [Python protocol example](../examples/providers/example_provider.py)
as an adapter starting point. It checks for a security contact file, not vulnerabilities.
Its explicit `--demonstrate-blocking` option exercises action grants without pretending
to be a detector. Install approved adapters outside project-controlled source where possible.

Command adapters can call an organization's private SDK, a commercial scanner, or a
local script. Wrap its output into the standard finding model. The host does not require
or implement the SDK's analysis, service credentials, network setup, or licensing.

## Kotlin and IntelliJ SDKs

Implement `com.sal7one.wrapperguard.core.vetting.VettingProvider`:

```kotlin
class AcmeProvider : VettingProvider {
    override val descriptor = ProviderDescriptor("acme.vetting", "Acme Vetting", "1.0.0")
    override fun scan(request: VettingRequest): ProviderResult {
        // Call your SDK and translate its findings, including requested actions.
        return ProviderResult(findings = emptyList())
    }
}
```

For an IntelliJ plugin, depend on WrapperGuard and register the implementation:

```xml
<depends>com.sal7one.wrapperguard.next</depends>
<extensions defaultExtensionNs="com.sal7one.wrapperguard.next">
  <vettingProvider implementation="vendor.AcmeProvider"/>
</extensions>
```

Enable it and set grants in **Settings → Tools → WrapperGuard**. A configured external
JSON file is authoritative for built-in and command-provider grants; installed SDK grants
are managed separately in IDE user settings. The native Policy tab shows providers,
status and grants. Safe Mode retains built-in static analysis and does not initialize
third-party SDKs or execute command adapters. BUILD and SYNC require a trusted project;
this host prerequisite cannot be bypassed with Run Once. Background results from a prior
settings/trust generation are marked stale until refreshed. Build/run guarding covers supported IDE
Gradle/shell run configurations. Gradle synchronization uses the pre-resolver hook;
external terminals and other plugins' arbitrary processes remain outside this scope.

## VS Code SDKs

Activate `sal7one.wrapperguard`, then call its exported API `registerProvider(provider)`.
Keep the returned disposable in your extension's subscriptions. SDK providers expose
`descriptor` and an async `scan(request)`; honor the request's `AbortSignal`.
Use the shipped declaration files for exact types. `evaluate(operation)` returns the
combined fresh report; `gate(operation)` returns `{ allowed, report }`. The integrating
extension must abort its build/sync when `allowed` is false. Invalid configuration or
workspace trust causes evaluation to fail. See the extension's [SDK instructions](../vscode-extension/README.md).

Permissions and command configuration paths come from **user/global settings**;
workspace settings cannot silently grant execution or blocking authority. The webview
offers native keyboard-accessible provider controls and displays effective actions.
VS Code has no universal veto for all task/build/sync processes. Integrators must call
the gate; WrapperGuard does not claim that the sidebar intercepts every terminal command.

## Execution and trust limits

Action grants constrain host behavior. They are **not** filesystem, network or credential
sandbox permissions. Installed SDKs and configured commands run trusted code with the
user's privileges and inherited environment. Only enable software you trust. IDE hosts
require workspace trust before loading external providers. CLI execution is explicitly
authorized by its `--providers` argument.

The host defaults to four concurrent providers and deadlines include waiting for a worker.
Timed-out results are discarded. On supported POSIX hosts command adapters use isolated
process groups, terminated on timeout/cancellation. The JVM host uses system `setsid`, or
system Perl/POSIX on macOS when available; unsupported hosts use observed-descendant cleanup.
Providers must not detach descendants into new process groups. This is lifecycle cleanup,
not an isolation boundary.
Kotlin SDKs must cooperate with interruption; JavaScript SDKs must remain asynchronous
and cooperate with cancellation. An extension-host JavaScript callback that blocks the
event loop cannot be preempted by a timer. No in-process SDK system can safely terminate
arbitrary code. Use a command adapter when process termination is needed.

Separate operations/projects can invoke an installed SDK concurrently. Keep providers
stateless or manage the vendor SDK's synchronization inside the adapter. Each command
invocation has its own process and request.

Existing execution seals cover watched build/script files and user policy/provider
configuration, not every possible input read by an arbitrary SDK or its remote services.
SDKs remain responsible for declaring or independently checking their relevant inputs.
Credential masking is applied to known credential patterns; it cannot identify every
possible vendor-specific secret. Providers should return minimal, redacted evidence.

## Generic setup, declared inputs and conformance

Both editors expose **Add SDK / custom command** in Tools. Select a discovered SDK or
enter an executable and individual arguments. The wizard records literal argv: spaces,
quotes and shell syntax are not interpreted by WrapperGuard. New connections are disabled
and have only the reviewed warning grant. Enablement, required completion, timeouts and
build/sync authority remain independent controls. Custom connections can be edited or
removed without adding a catalog method or changing host code.

API 1 descriptors can add `category`, `summary`, `tags` and `inputScope`. Categories are
extensible uppercase identifiers; `BUILT_IN` is reserved for the host. Metadata is optional
for compatibility. An absent input scope is **unknown**, while an explicit empty scope
means metadata-only. In Kotlin the original descriptor constructor/copy ABI is retained;
use the metadata constructor or `copyWithMetadata` to add fields.

```json
{
  "category": "SAST",
  "summary": "Organization-specific source review",
  "tags": ["android", "internal"],
  "inputScope": {
    "includes": ["**/*.kt", "**/AndroidManifest.xml"],
    "excludes": ["build/**"],
    "externalFiles": ["/absolute/user-config/review-rules.json"]
  },
  "projectRoots": ["/absolute/project"]
}
```

`projectRoots` belongs to command configuration, not the SDK descriptor. An omitted or
empty list preserves reusable legacy connections. A nonempty list binds invocation to
those canonical roots; a different selected project displays a disabled provider and does
not start the command. VS Code has an explicit project selector for multi-root workspaces.
GUI report/model connections also bind their integration profile to the selected project.

Scope globs support `*`, `?` and whole-segment `**`; absolute patterns, dot segments,
backslashes, brace expansion and character classes are rejected. Inputs use bounded
no-follow traversal and SHA-256 content comparisons. Core source bounds are 1 MiB/file,
5,000 files, 64 MiB total, 50,000 visited entries, depth 64 and a 30-second capture budget;
external report/config files use a separate 16 MiB/file bound. Trusted native executable
identity has larger explicit limits. Exceeding a bound invalidates coverage. The built-in
scanner additionally records the bytes it actually reads, including recursively reached
files with unusual extensions. These checks detect changes; they do not prove that an
arbitrary SDK declared everything it read or that an imported report describes current code.

Check a user configuration without launching providers:

```sh
wrapperguard-cli /absolute/project --providers /absolute/user/providers.json --check-providers
```

Explicitly execute enabled command providers for API 1 conformance:

```sh
wrapperguard-cli /absolute/project --providers /absolute/user/providers.json --conformance
```

Conformance validates the result contract and reports failures. It is not an audit of the
analyzer's detection accuracy, OS isolation, remote dependencies or licensing. Existing
runtime deadline/output/action clipping and cancellation regressions remain required.

Shared [presets](../catalog/security-presets.json) change method flags only after review.
They never install tools, enable commands, grant blockers or trigger an analysis. An enabled
method with no applicable enabled provider makes coverage incomplete.

## Optional local history

History starts off. Its GUI can enable/pause collection, set retention (20 by default,
1–100), inspect provider versions and clear owned records. Stored summaries include issue
titles and relative paths, but exclude messages, evidence, call chains and source contents.
Treat arbitrary provider titles as potentially sensitive. Storage is outside projects and
is separated by a hash of the canonical root; cleanup preserves unrelated files.

Deltas require the same project, operation and known semantic scope, with complete,
current, untruncated summaries. Rules, method flags, effective grants and provider
configuration affect scope; project source/report bytes do not, so changed code or
reimported findings can be compared. Unverifiable SDK configuration disables comparison. Native currently treats every imported report as unknown producer scope. VS Code can compare SARIF only when explicit versioned producer/rule identity is available; other imports remain unknown. Opaque package wrappers and local model names also disable comparison.
Identity is provider plus fingerprint across all statuses, so approving a finding does not
make it disappear. “Newly reported” and “no longer reported” describe observations,
not confirmed introduction or remediation. History never changes baseline or gate results.
