# WrapperGuard UI components

The IntelliJ tool window uses native Jewel/Compose. Swing is limited to the platform host
and existing IDE dialogs. Scanner and enforcement logic remain outside presentation code.

## IntelliJ workspace

- `WrapperGuardApp`: shared toolbar, navigation, scan state, saved settings and persistent
  findings view state. Report notifications enter Compose on the EDT.
- `OverviewTab`: real result counts that open matching findings, scan metadata, notices and
  protection scope. No inferred risk score or coverage percentage.
- `FindingsTab`: virtualized list, expandable filters and sort choices. At 760 dp the
  inspector sits beside the list; below that it becomes a detail route with a Back button.
- `FindingInspector`: selectable evidence, location, status, remediation and editor action.
- `FindingsPresentation`: pure filtering/order/status helpers and immutable view state.
- `ExecutionGraphTab`: scrollable graph and virtualized file browser. Selecting a file
  exposes incoming/outgoing calls and links to findings. Above 200 linked files, the file
  list avoids rendering a crowded canvas.
- `ExecutionPresentation`: deterministic, cycle-safe graph layering.
- `PolicyTab`: current saved settings, an actual settings action, and the distinction
  between background Warn scanning and the pre-run Enforce guard.
- `WorkspacePage`, `WorkspaceSection`, `WorkspaceDivider`, `ChoiceChip`: shared responsive
  layout, scrollbars and selectable controls.
- `StatusBadge`, `LabeledValue`, `EmptyHint`: small presentation components.

Colors follow the platform theme through `WgTheme`. Technical evidence uses a monospace
font. Empty, pending, stale, failed, cancelled and incomplete scans must never imply a
successful clean scan. Presentation ordering does not change reports or fingerprints.

## VS Code

The extension runs the complete TypeScript scanner in process; Kotlin/TypeScript parity
is mandatory. `webviewUi.ts` renders its separate interface and `extension.ts` owns its
state and actions. The IntelliJ redesign does not change that surface.
