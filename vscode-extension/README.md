# WrapperGuard for VS Code — 0.4.0 (local, unreleased)

Security vetting with a built-in, in-process TypeScript scanner for Gradle wrappers, build files, and bootstrap scripts. The built-in scanner only reads text and never executes project code. Users can additionally authorize installed extension providers or explicitly configured external command providers. Credential evidence is redacted.

- Scans on workspace open (configurable).
- Sidebar app: Overview, Findings (grouped + detail pane), Execution Graph (layered call graph with hover tracing), Policy.
- Suppress (inline marker + trusted baseline), baseline, restore.
- Export canonical JSON and SARIF.
- Settings: `wrapperGuard.allowedHosts`, `wrapperGuard.blockedCommands`, `wrapperGuard.requireDistributionSha256`, `wrapperGuard.scanOnOpen`.

Security model: repository-shipped `wrapperguard:ignore` markers are untrusted until you approve the finding — your baseline lives outside the repo in VS Code's global storage.

License: Apache-2.0.

## Provider permissions

The Policy pane lists each provider's identity/version, completion status, runtime, finding count, required/optional setting, timeout, and permitted actions. **Configure permissions** opens accessible VS Code controls and saves your selection to User Settings. Findings show their provider and effective actions.

Actions are `WARNING`, `MAJOR_WARNING`, `ALERT`, `BLOCK_BUILD`, and `BLOCK_SYNC`. The built-in provider (`wrapperguard`) defaults to all actions and is required; an externally registered extension defaults to disabled, no actions, and optional. Set `enabled:false` to disable any provider. Workspace-controlled security settings are rejected. Changes to grants trigger a new scan.

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

`evaluate('SCAN'|'BUILD'|'SYNC')` returns the host report. `gate('BUILD'|'SYNC')` returns `{allowed,report}` and rejects if configuration is invalid or grants change during evaluation. VS Code does not provide interception for arbitrary builds or Gradle sync: only integrations calling and honoring this API are gated. An incomplete optional provider does not itself block. Integrators can separately require a complete report. The API evaluates a point-in-time snapshot; the integrating extension is responsible for executing against the same inputs after approval.

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

Optional top-level `builtIn` accepts the same permission object. An empty configuration preserves built-in defaults; a command with omitted permissions defaults to disabled with no actions. The file is authoritative for built-in and command-provider grants. The Policy control opens this file for those providers; `wrapperGuard.providerPermissions` User Settings control installed SDK providers. Configuration is limited to 64 KiB. No command runs through a shell: arguments are literal, the executable must be an absolute executable file, and unknown configuration keys/actions or duplicate IDs fail validation. These commands are user-authorized tools and can execute code; the built-in scanner remains static.

The process receives one JSON line on stdin: `{"apiVersion":1,"projectPath":"/absolute/project","operation":"SCAN"}`. Only this metadata is sent; source contents, policy, approvals, and credentials are excluded. Return one JSON ProviderResult on stdout and exit 0. Stdout is limited to 4 MiB; stderr is discarded. Nonzero exits, malformed output, cancellation, and timeouts are isolated. The host terminates the command's process tree on completion or cancellation. Permissions allow timeouts from 100 to 300000 ms and at most 32 configured command providers.

## Build and package

Use Node 22 or newer. Run `npm ci`, `npm test`, `npm run hardening`, and `npm run package`. The pinned VSCE build creates `wrapperguard-0.4.0.vsix` locally without publishing. The package includes the public declarations, Apache-2.0 license, and activity-bar SVG.
