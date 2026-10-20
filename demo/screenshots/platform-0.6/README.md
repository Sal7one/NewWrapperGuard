# WrapperGuard 0.6 platform screens

The two groups below have different purposes. [manifest.json](manifest.json) records their provenance and validation.

## Actual VS Code acceptance

These captures show the installed VS Code extension development host with an isolated temporary profile and a controlled Android/Ktor fixture. The SDK wizard, literal argument editor, executable picker, permissions and save action were exercised through the actual interface. Saving a disabled connection did not execute it. Explicitly enabling it ran a controlled Provider API v1 Node adapter; its literal argument retained spaces. Selecting the sibling project paused that bound provider. History was explicitly enabled, then two real scans were saved and compared.

The adapter tests the integration protocol; it does not represent a third-party security analysis tool. No personal VS Code profile was used. [Native validation](native/validation.json).

| Capture | What it demonstrates |
| --- | --- |
| [SDK builder](native/sdk-builder.png) | Identity, executable, arguments, labels, read set, project binding and permissions in the real QuickPick wizard. |
| [Literal arguments](native/sdk-argv.png) | Separate argv entries preserve spaces without shell splitting. |
| [Default permissions](native/sdk-permissions.png) | A new custom connection starts disabled, optional and warning-only. |
| [Saved disabled connection](native/sdk-saved-disabled.png) | The connection is present before any adapter invocation. |
| [Connected provider](native/sdk-connected.png) | The controlled adapter completed after explicit enable. |
| [Another project](native/sdk-bound-other-project.png) | A project binding prevents invocation for the sibling root. |
| [Actual history](native/history.png) | Opt-in local history and comparison for real fixture scans. |

## Synthetic demo views

These are **DEMO data** rendered by the real webview. Atlas project names, company providers, finding counts and history changes are synthetic. They demonstrate layout, labels, keyboard interaction and responsive behavior; they are not security results. [Browser validation](demo/validation.json).

| View | Desktop | Narrow layout |
| --- | --- | --- |
| Custom SDK cards | [Desktop](demo/custom-sdk-desktop.png) | [Narrow](demo/custom-sdk-mobile.png) |
| Demo history differences | [Desktop](demo/history-desktop.png) | [Narrow](demo/history-mobile.png) |
| Tool and preset selection | [Desktop](demo/tools-desktop.png) | [Narrow](demo/tools-mobile.png) |

![Demo history rendered by the real webview](demo/history-desktop.png)

Validation at this source checkpoint: 122 Node tests, embedded webview checks and five scanner hardening checks passed. Browser checks covered Tab order, Enter activation, labelled project and provider controls, disabled scanning states and horizontal overflow. This is keyboard and label sanity checking, not a formal screen-reader audit.
