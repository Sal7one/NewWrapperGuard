# Changelog

## Unreleased

- Rebuild the IntelliJ tool window: compact toolbar, actual scan summaries, responsive findings browser with persistent filters and selection, and selectable evidence.
- Execution graph: retain cyclic/disconnected call chains, bound scrolling, provide a file-list fallback and link file findings to their inspector.
- Fix scan progress/failure/cancellation display, stale-status handling, saved Policy settings, summary navigation, and reversed theme fallback colors.
- Keep secondary text readable in custom dark themes and increase graph-arrow contrast in light themes; verified in a live Android Studio smoke pass.
- Remove the arbitrary risk score and fabricated coverage percentage. Add seven UI presentation/lifecycle regression tests.

## 0.3.0

### Guard (IntelliJ / Android Studio)
- **Gradle-sync gate**: sync launches run through the before-run guard (ExternalSystemRunConfiguration) and are hard-blocked on BLOCK findings; post-import ENFORCE re-validation.
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
