# Weekend Backlog

## Saturday — make the architecture real

- [ ] Run and preserve `old_project` tests.
- [ ] Finish pure models in `wrapperguard-core`; no IntelliJ imports.
- [ ] Add stable fingerprints, confidence, verdict, policy action, notices, and audit-evidence type.
- [ ] Port existing rules one group at a time from `old_project` into `wrapperguard-rules`.
- [ ] Port recursive call-chain behavior and safe path resolution.
- [ ] Make CLI scan a directory and output canonical JSON.
- [ ] Add regression tests using `old_project/test-samples`.

## Saturday evening — IntelliJ UI

- [ ] Wire `WrapperGuardProjectService` to the shared scanner.
- [ ] Implement Overview with `VerdictPanel` and `MetricTile`.
- [ ] Implement Findings with `FindingTableModel` and `FindingDetailsPanel`.
- [ ] Implement execution-path tree as the first graph view.
- [ ] Add rescan, open-file, search, and filters.
- [ ] Fix run guard: only actionable findings selected by policy may block.

## Sunday — VS Code and polish

- [ ] Build the TypeScript extension.
- [ ] Run the CLI from VS Code and parse the canonical report.
- [ ] Show verdict in the status bar and findings in a TreeView.
- [ ] Add commands: scan workspace, open finding, export report.
- [ ] Add stale/incomplete/failed states in both IDEs.
- [ ] Add JSON schema validation and sample reports.
- [ ] Update OSS docs, screenshots, and release checklist.

## After the weekend

- [ ] Parser-aware PowerShell/Bash adapters.
- [ ] Better execution graph canvas.
- [ ] SARIF/CI adapter.
- [ ] Fingerprint-based baseline migration.
- [ ] Local multi-project console.
- [ ] Optional PowerShell ML scorer in shadow mode.
- [ ] Rule packs and community rule SDK.


### Research-backed PowerShell detection — BLOCKED

**Status:** Waiting for researcher
**Dependency:** MPSAutodetect follow-up paper, model artifacts, preprocessing specification, dataset guidance, licensing confirmation, and direct integration support.

Keep the extension interfaces only. Do not implement the model or make architectural guesses before the dependency is resolved.
