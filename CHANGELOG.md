# Changelog

## 0.4.0 — 2026-10-19

### Provider platform

- Add a shared provider contract with warnings, major warnings, alerts, and independent build/sync blocking grants.
- Keep the original scanner as the default provider; support concurrent SDK registrations and explicit local JSON command adapters.
- Centralize policy, provider identity, approvals, timeouts, incomplete coverage and required-provider failures in the host.
- Add user-owned provider settings, provenance and run status in both IDEs; external providers require user opt-in and workspace trust.
- Register a Gradle pre-resolver sync veto; retain separate before-run BUILD enforcement and extend the execution seal with user provider/policy inputs.
- Expose VS Code provider registration and cooperative gate APIs, SDK declarations, accessible provider controls and packaging checks.
- Add CLI provider configuration/operation arguments, strict argument parsing and explicit incomplete/error exit1; keep blocked exit2.
- Replace ignored regression inputs with tracked fixtures, enforce parity coverage, fix CI master triggers and package both IDE artifacts.
- Correct the Kotlin ignore-reason word boundary, TypeScript credential-block recognition and both hosts' redaction cursor across multiple blocks; cover username/password redaction and fingerprint parity with regressions.

### Workspace and demo baseline

- Use compact navigation, a full-width findings table and bottom evidence dock in the native workspace and VS Code renderer.
- Align provider permission controls and fit execution graphs with dedicated file and finding inspectors.
- Add a reproducible Atlas Payments demo: 12 simulated findings, six fictional providers, shared fixtures, a browser preview and 13 native/webview screenshots.
- Keep demo data display-only: no SDKs or fixture scripts run, and native demo mode cannot authorize build or sync.
- Fix active-only summary counts and version display, and validate light, narrow and interactive webview states.

### Earlier UI work included in this release

- Consolidate engineering state, contracts, validation and remaining work in HANDOVER.md; remove superseded briefs, duplicate notes and old UI mockups.
- Rebuild the IntelliJ tool window: compact toolbar, actual scan summaries, responsive findings browser with persistent filters and selection, and selectable evidence.
- Execution graph: retain cyclic/disconnected call chains, bound scrolling, provide a file-list fallback and link file findings to their inspector.
- Fix scan progress/failure/cancellation display, stale-status handling, saved Policy settings, summary navigation, and reversed theme fallback colors.
- Keep secondary text readable in custom dark themes and increase graph-arrow contrast in light themes; verified in a live Android Studio smoke pass.
- Remove the arbitrary risk score and fabricated coverage percentage. Add seven UI presentation/lifecycle regression tests.

## 0.3.0

### Guard (IntelliJ / Android Studio)
- **Gradle-sync observation**: post-import ENFORCE re-validation; the before-run hook covered supported run configurations. The 0.4.0 work adds the actual pre-resolver synchronization veto.
- **TOCTOU execution seal**: unsaved documents are saved before the pre-run scan; before/after content-hash seals with one retry; a synthetic WG-SCAN-TOCTOU blocker refuses launches while files keep changing.
- **Trusted/untrusted suppression model**: repo-shipped `wrapperguard:ignore` markers are inert; only user-approved baselines stored outside the repository suppress. Ignore markers are stripped from evidence so fingerprints stay stable.
- **Wrapper-JAR escalation**: WG-WRAPPER-007 ERROR/BLOCK when a trusted checksum list is configured.
- **Trusted Projects hook**: rescans when a project's trust state changes.
- **Settings UI**: Settings → Tools → WrapperGuard (allowed hosts, distribution checksum requirement, scan toggles).
- **Hostile-input hardening**: UTF-16/BOM decoding, null-byte rejection, oversized-line budgets, depth/file caps, symlink-loop safety.

### VS Code extension
- **Full TypeScript scanner port** — in-process scanning, no CLI/JVM/config; parity-verified against the Kotlin scanner (identical verdicts, counts, blockers, and fingerprints).
- Web-app UI: tabs, verdict pill, metric tiles, searchable grouped findings, finding detail pane, execution graph with layered layout + zoom/search/hover tracing, policy pane, status footer.
- Suppress (marker + trusted baseline in one click), baseline, restore, JSON + SARIF export, scan-on-open, editable policy settings.
- Node test suite (19 tests) + parity + hardening scripts.

### CLI
- `--baseline <file>` (trusted external baselines), `--sarif` export, exit codes only count ACTIVE blockers.

## 0.2.0
- Run guard (before-run task, blocked dialog, back-fill), file-change rescan with STALE state and content-hash gating, credential redaction everywhere, CLI + schema validation, Apache-2.0, CI workflow.
