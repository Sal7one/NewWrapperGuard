# Reusable UI Components

Keep components dumb. They render data and emit callbacks; project services own scanning and state.

## Shared concepts

- `ScanVerdict`: the main product state.
- `Finding`: canonical finding shown by every integration.
- `ScanReport`: one immutable result.
- `ScanPolicy`: determines warn/block behavior.

## IntelliJ components

- `VerdictPanel(report, onReview)` — verdict, last scan, policy, short explanation.
- `MetricTile(title, value, tone, onClick)` — blockers, warnings, suppressed, files.
- `StatusBadge(text, tone)` — compact semantic pill.
- `FindingTableModel` — stable columns: severity, issue, location, reachability, action.
- `FindingDetailsPanel` — evidence, execution path, remediation, actions.
- `CallChainTreePanel` — first version of Execution Graph using `JBTree`.
- `EmptyStatePanel(title, body, action)` — not scanned, clean filter, unsupported project.
- `ScanStateBanner` — scanning, stale, incomplete, failed.

Use `JBColor`, `JBUI`, `JBLabel`, `JBTable`, `JBScrollPane`, `JBTextField`, `JBTabbedPane`, and IDE icons. Do not hardcode the HTML colors directly into every component.

## VS Code components

- `ReportStore` — current immutable report.
- `FindingsTreeProvider` — groups findings without scanner logic.
- `WrapperGuardStatusBar` — verdict and blocker count.
- `CliRunner` — the only process boundary.
- `FindingDecoration` — maps severity/policy action to ThemeIcon.

## Component states

Every main view supports:

- not scanned
- scanning
- clean
- findings available
- incomplete
- stale
- failed
- no filter matches
