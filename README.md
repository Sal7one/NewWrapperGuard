# WrapperGuard Next — Weekend Starter

This repository is a clean migration shell around the existing WrapperGuard project.

- `old_project/` is the untouched working IntelliJ plugin and reference implementation.
- `wrapperguard-core/` contains IDE-neutral models and scanner contracts.
- `wrapperguard-rules/` contains small, testable rules and the repository scanner.
- `wrapperguard-cli/` is the single scanner entry point for other integrations.
- `wrapperguard-intellij/` is the Android Studio / IntelliJ adapter and native Swing UI.
- `vscode-extension/` calls the CLI and displays the same canonical report.
- `design_reference/` contains the generated HTML and design tokens, because the coding model may not support vision.

Start with `AI_START_HERE.md`. Keep `old_project/` compiling until each behavior is ported and covered by tests.

## Weekend target

A usable IntelliJ plugin, a working CLI, and a VS Code extension that can launch the CLI and display findings. The local multi-project console and ML detector remain extension points, not weekend deliverables.
