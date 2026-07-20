# AI IMPLEMENTATION BRIEF

You are upgrading an existing open-source security scanner. Do not start from zero.

## Read first

1. `old_project/` — working implementation and tests.
2. `design_reference/technical_precision/DESIGN.md` — colors, density, typography, and component rules.
3. `design_reference/*/code.html` — exact visual layouts translated into HTML.
4. `BACKLOG.md` — implementation order.
5. `docs/COMPONENTS.md` — reusable native IDE components.

## Product

WrapperGuard statically scans Gradle wrappers, build files, shell scripts, PowerShell, BAT/CMD, repositories, downloads, dangerous commands, credentials, and recursively called scripts. It never executes scanned project code.

The product surfaces are:

- Android Studio / IntelliJ plugin — primary experience.
- CLI — shared scanner entry point.
- VS Code extension — invokes the CLI; do not duplicate scanner logic in TypeScript.
- Local multi-project console — later; preserve the report schema for it.

## Weekend goal

Deliver a clean migration foundation, not the final enterprise platform:

1. Keep the old project under `old_project/` as reference.
2. Port its models into pure Kotlin under `wrapperguard-core`.
3. Port rules incrementally into `wrapperguard-rules`; preserve old tests and fixtures.
4. Make the CLI emit canonical JSON.
5. Wire the IntelliJ plugin to the shared scanner and recreate the supplied native UI.
6. Make the VS Code extension run the CLI and show findings in a tree and status bar.


## Research Integration Boundary

WrapperGuard plans to integrate **MPSAutodetect and its upcoming follow-up research** with direct help from the researcher.

This work is intentionally reserved for later.

For now:

* Do not implement, reproduce, approximate, retrain, or reverse-engineer either research model.
* Do not invent datasets, preprocessing steps, model weights, thresholds, or performance claims.
* Do not add SdA, XGBoost, Python ML runtimes, ONNX models, or ML-based blocking.
* Keep deterministic scanning as the only active detection system.
* Preserve the existing optional `MlScorer` interface as an inactive extension point.
* `NoopMlScorer` remains the default implementation.
* ML output must not affect verdicts, policy, severity, or execution blocking.

Once the researcher provides the follow-up paper and integration guidance, the ML module can be implemented properly as a separate, optional detector.

Expected future flow:

```text
Script
→ deterministic rules
→ optional research-backed PowerShell detector
→ combined evidence
→ policy decision
```

The research detector should initially run in **shadow mode**:

* Local and optional
* PowerShell-only
* Reports a separate experimental signal
* Includes model version and confidence
* Never blocks execution by itself
* Never replaces deterministic evidence
* Can be disabled without affecting the scanner

Do not touch this integration until explicit research materials and implementation guidance are added to the repository.


## Non-negotiable rules

- Never execute analyzed scripts.
- Never upload source code.
- INFO/audit evidence never blocks execution.
- ML output never blocks by itself.
- Every finding has a stable rule ID.
- Core code must not import IntelliJ classes.
- VS Code must consume CLI JSON instead of reimplementing detection.
- Do not delete old behavior until its replacement has tests.
- Prefer small files and reusable components over one giant panel.

## UI to implement

Use native IDE widgets, not embedded HTML.

Tool-window tabs:

- Overview: verdict, last scan, policy, blockers, warnings, suppressed, files, rescan.
- Findings: filters, compact table, selected finding inspector.
- Execution Graph: start with a readable call-chain tree; graphical canvas can follow.
- Policy: current profile and compact settings summary.

Verdicts:

- Clean
- Review Required
- Execution Blocked
- Scan Incomplete
- Scan Stale
- Scan Failed
- Not Scanned

Finding detail:

- Rule ID and title
- Severity, confidence, policy impact
- File and line
- Evidence/code snippet
- Execution path
- Remediation
- Open in editor
- Suppress / baseline actions

## Architecture rule

```text
shared Kotlin scanner + canonical JSON
        ├── IntelliJ adapter and native UI
        ├── CLI
        ├── VS Code extension
        ├── CI/SARIF
        └── future local console
```

## Definition of done

- Root Gradle modules compile.
- Existing detector behavior is still represented in `old_project/` and migration tests.
- CLI scans a folder and prints JSON.
- IntelliJ tool window uses reusable components, not a 700-line panel.
- IntelliJ run guard blocks only policy-selected actionable findings.
- VS Code runs the CLI and shows findings.
- Empty, scanning, clean, findings, incomplete, failed, and stale states exist.
- README documents local-only static analysis.

Do not build the standalone dashboard or train an ML model this weekend. Add clean extension points only.
