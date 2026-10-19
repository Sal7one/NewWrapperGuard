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

These seven finding fields are sufficient. Providers can omit `fingerprint`, `providerId`,
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
