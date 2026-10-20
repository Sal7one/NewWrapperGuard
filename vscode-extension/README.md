# WrapperGuard for VS Code — 0.6.0 (local, unreleased)

Security vetting with a built-in, in-process TypeScript scanner for Gradle wrappers, build files, and bootstrap scripts. The built-in scanner only reads text and never executes project code. Users can additionally authorize installed extension providers or explicitly configured external command providers. Credential evidence is redacted.

- Scans on workspace open (configurable).
- Sidebar app: Overview, Findings, Execution Graph, Providers, Tools, Research & AI, History and Automation.
- Suppress (inline marker + trusted baseline), baseline, restore.
- Export canonical JSON and SARIF.
- Settings: `wrapperGuard.allowedHosts`, `wrapperGuard.blockedCommands`, `wrapperGuard.requireDistributionSha256`, `wrapperGuard.scanOnOpen`.

Security model: repository-shipped `wrapperguard:ignore` markers are untrusted until you approve the finding — your baseline lives outside the repo in VS Code's global storage.

License: Apache-2.0.

## Connect tools through the GUI

Open **Tools** in the WrapperGuard sidebar. Six built-in methods are ready without setup: wrapper integrity, script safety, Gradle configuration, version catalogs, script call graphs and Maven POM configuration. Each has its own switch. A disabled method shows reduced coverage; turning all built-ins off cannot establish a clean scan by itself.

Choose **Set up** on an optional method. Select a fixed runner or existing report, choose trusted Python 3.10+ with SDK 0.4.0+, or prepare the bundled SDK in a managed environment. The SDK installs offline; pinned Bandit/Semgrep downloads are separate explicit choices. Other runners use an executable you select. Windows eligibility follows the selected SDK's passive capabilities; see verification for actual platform/tool acceptance. PowerShell accepts its installed module manifest or a folder containing it. Profiles stay outside every open project. No JSON editing is required.

Available connections are Android/Ktor Semgrep, Bandit, PSScriptAnalyzer, SARIF, Sonar generic issues, CycloneDX vulnerability assessments, Trivy, OSV-Scanner and scoped ZAP report import. New connections start optional, SCAN only and warning only. **Permissions** adjusts each connection through native VS Code controls. **Disconnect** removes its saved connection; switching it off preserves its settings. Importing a report does not run its scanner or establish report freshness. Android/Ktor build configuration is checked locally. Optional Semgrep checks Java/Kotlin source using the bundled focused review rules or a trusted rule file you select. Other language scanners can contribute SARIF findings.

Trivy may download vulnerability databases and indexes. OSV sends dependency names and versions to OSV, with resolution/build/install disabled. These data flows appear in the connection summary. ZAP import requires an exact target origin and optional path prefix and sends no requests to that application.

Open **Research & AI** for the optional script behavior method and local model review. Script behavior uses bounded literal decoding and limited Python def/use signals without evaluating source. AI setup accepts an OpenAI-compatible server on numeric loopback, the loaded model ID and a finding report; each run reviews up to five finding summaries. Choosing the current scan copies only active finding summaries, omitting source evidence, call chains and the project root. No model is bundled; model output is advisory evidence with no action grants. A connection failure appears as incomplete coverage. These methods are research inspired; they do not reproduce published classifiers or claim their accuracy.

User-owned `wrapperGuard.methodFlags` records the switches. Workspace overrides are rejected, changes invalidate old gate decisions, and no optional software is discovered or enabled automatically. Existing explicitly granted third-party providers retain their permissions until a user disables them.

## Reviewed automation, sharing and maintenance

**Automation** saves per-provider network/cost declarations, source-change debounce and an optional interval. Saving or enabling leaves the session paused. **Review & start** binds the current project and separately enabled providers; the first future trigger runs SCAN only. It never launches a build/sync, installs tools or replays missed runs. Settings, connection and project changes pause it. Declarations describe your review and do not sandbox a process.

**Tools → Import preset / Export preset** shares data-only method flags with identity, issuer, version and a SHA-256 digest. Import displays the changes before applying them. The digest checks integrity, not publisher authenticity. Presets contain no execution commands, paths, grants or credentials, and applying them does not run anything.

**Providers → SDK maintenance** offers offline bundled-SDK repair and reviewed pinned tool reinstall/update. Removing an SDK environment requires its ownership marker and no saved references, including disabled connections. Unmarked older environments are preserved. **Storage** separately cleans unused owned caches/profiles; it never deletes original reports, source or other tool installations.

Connected import/supply-chain cards expose **Evidence settings** for an optional report provenance manifest/maximum age and required resolved dependency versions. Listed-input hashes and report age do not authenticate the producer or establish complete project coverage. Failed/missing evidence stays incomplete.

**DAST → Owned-target HTTP response audit** explicitly authorizes exact numeric-IP URLs, GET request/response/time budgets and optional environment-variable names for authentication. It performs no crawl, redirect following, exploit, DNS target discovery or application startup. Secrets are never entered into the form.

Bandit/Semgrep can optionally use an already installed digest-pinned Docker image and explicitly selected local Unix socket. No image is pulled. The analysis worker is non-root; a trusted timer retains the capabilities needed to stop it. The optional container-Python research method requires separate payload-execution authorization and a short timeout. It emits advisory evidence only. Container controls still trust the CLI, daemon, image and host kernel; they are not proof that arbitrary malware is safe to run.

## Provider permissions

The Providers pane lists each provider's identity/version, completion status, runtime, finding count, required/optional setting, timeout, and permitted actions. **Configure permissions** opens accessible VS Code controls and saves your selection to its user-owned connection or User Settings. Findings show their provider and effective actions.

Actions are `WARNING`, `MAJOR_WARNING`, `ALERT`, `BLOCK_BUILD`, and `BLOCK_SYNC`. The built-in provider (`wrapperguard`) defaults to all actions and is required; an externally registered extension defaults to disabled, no actions, and optional. Set `enabled:false` to disable any provider. Workspace-controlled security settings are rejected. User Settings changes invalidate reports and refresh controls without launching analyzers; run a new scan explicitly.

Only active, actionable, non-INFO findings meeting the host's Enforce policy can block an operation, after requested actions are intersected with user grants. Build and sync permissions are independent. Audit evidence and ML/shadow findings never block. A required provider failure closes only its granted operation gates; optional failure yields a visible incomplete report. No enabled providers yields an incomplete report. Trusted baseline approvals are applied by the host after namespacing external findings.

## Installed extension SDK (API 1)

Another installed VS Code extension can activate `sal7one.wrapperguard` and use its exports. Public TypeScript declarations ship in `dist/extension.d.ts` and `dist/providers/sdk.d.ts`.

```js
const extension = vscode.extensions.getExtension('sal7one.wrapperguard');
if (!extension) throw new Error('WrapperGuard is required');
const api = await extension.activate();
if (api.apiVersion !== 1) throw new Error('Unsupported vetting API');
context.subscriptions.push(api.registerProvider({
  descriptor: { id: 'example.vetter', displayName: 'Example Vetter', version: '1.0.0', apiVersion: 1 },
  async scan(request) {
    // Read projectRoot; use request.signal for cancellation. Return findings, not a verdict.
    return { findings: [], complete: true };
  }
}));
const { allowed, report } = await api.gate('BUILD');
if (!allowed) return; // The integrating extension must honor this gate before starting its build.
```

`evaluate('SCAN'|'BUILD'|'SYNC', projectRoot?)` returns the host report. `gate('BUILD'|'SYNC', projectRoot?)` returns `{allowed,report}` and rejects if configuration is invalid or grants change during evaluation. VS Code does not provide interception for arbitrary builds or Gradle sync: only integrations calling and honoring this API are gated. An incomplete optional provider does not itself block. Integrators can separately require a complete report. Use the intended absolute project root for multi-root integrations; omission uses the UI-selected root. The API evaluates a point-in-time snapshot; the integrating extension is responsible for executing against the same inputs after approval.

Provider findings use the ordinary Finding fields plus requested `actions`. Host-owned `providerId`, `status`, fingerprint, and policy action are recomputed. External fingerprints are namespaced by provider ID and rule ID; a provider cannot approve its own findings. Result fields are `findings`, optional `edges`, `scannedFiles`, `complete`, and `notices`. SDK code runs in the extension host, as other installed extensions do; timeouts signal cooperative cancellation and cannot preempt synchronous JavaScript. The scheduler permits up to four concurrent providers and returns results in registration order. Deadlines include queue time, and expired queued work is skipped.

## External command providers

Set **User Settings** `wrapperGuard.providerConfigPath` to an absolute JSON file outside the workspace, for example `/Users/you/.config/wrapperguard/providers.json`. Symlinks resolving inside the project are rejected. There is no project-file discovery. Command and installed SDK providers require Workspace Trust. In Safe Mode, the built-in static scanner can run, paused providers are visible, and build/sync gate requests reject.

```json
{
  "apiVersion": 1,
  "providers": [
    {
      "id": "example.command",
      "displayName": "Example Command Vetter",
      "version": "1.0.0",
      "command": ["/absolute/path/to/vetter", "--json"],
      "permissions": {
        "enabled": true,
        "actions": ["WARNING", "ALERT"],
        "required": false,
        "timeoutMs": 30000
      }
    }
  ]
}
```

Optional top-level `builtIn` accepts the same permission object. An empty configuration preserves built-in defaults; a command with omitted permissions defaults to disabled with no actions. The file is authoritative for built-in and command-provider grants. The Providers control edits grants in this file through native controls; `wrapperGuard.providerPermissions` User Settings control installed SDK providers. Configuration is limited to 64 KiB. No command runs through a shell: arguments are literal, the executable path must be absolute, and unknown configuration keys/actions or duplicate IDs fail validation. Readiness is checked before each launch: a missing disabled tool remains editable/removable; an enabled missing tool fails individually and leaves coverage incomplete. These commands are user-authorized tools and can execute code; the built-in scanner remains static.

The process receives one JSON line on stdin: `{"apiVersion":1,"projectPath":"/absolute/project","operation":"SCAN"}`. Only this metadata is sent; source contents, policy, approvals, and credentials are excluded. Return one JSON ProviderResult on stdout and exit 0. Stdout is limited to 4 MiB; stderr is discarded. Nonzero exits, malformed output, cancellation, and timeouts are isolated. The host terminates the command's process tree on completion or cancellation. Permissions allow timeouts from 100 to 300000 ms and at most 32 configured command providers.

## Build and package

Use Node 22 or newer. Run `npm ci`, `npm test`, `npm run hardening`, and `npm run package`. The pinned VSCE build creates `wrapperguard-0.6.0.vsix` locally without publishing. The package includes the public declarations, Apache-2.0 license, shared method catalog, the first-party SDK wheel, the SAX runtime with its license, and activity-bar SVG. The package command builds the wheel using Python/pip before compiling the extension.

The shared catalog supplies removable methods; the host is 0.6.0 and new guided connections require SDK 0.4.0+. See the [integration guide](../docs/integrations.md) for capabilities and data flows, and [verification notes](../docs/verification.md) for tested behavior and limits.

## Actual VS Code host tests

`npm run test:host` uses Microsoft's pinned [@vscode/test-electron harness](https://github.com/microsoft/vscode-test) in a fresh two-project workspace/profile. Set `VSCODE_VERSION=1.95.0` or `1.142.0` for CI; the selected editor bundle is downloaded when no executable is supplied. To reuse an installed editor without downloading, set `VSCODE_EXECUTABLE_PATH` to its absolute executable. `WRAPPERGUARD_HOST_EVIDENCE` optionally saves JSON results.

The actual-host suite verifies activation/API 1, disabled/default grants, explicit enablement without execution, attribution/action clipping, build/sync independence, multi-root binding, changed-input gate rejection, isolated missing tools and opening the contributed webview. It does not replace screen-reader acceptance or analyzer accuracy evaluation. The [provider-author conformance kit](../examples/provider-conformance/README.md) exercises real subprocess failure/cancellation cases separately.
