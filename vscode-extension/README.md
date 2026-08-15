# WrapperGuard for VS Code

Static security analysis for Gradle wrappers, build files, and bootstrap scripts — running **fully in-process** (TypeScript port of the WrapperGuard scanner, parity-verified against the Kotlin implementation). Never executes project code; credential evidence is redacted.

- Scans on workspace open (configurable).
- Sidebar app: Overview, Findings (grouped + detail pane), Execution Graph (layered call graph with hover tracing), Policy.
- Suppress (inline marker + trusted baseline), baseline, restore.
- Export canonical JSON and SARIF.
- Settings: `wrapperGuard.allowedHosts`, `wrapperGuard.blockedCommands`, `wrapperGuard.requireDistributionSha256`, `wrapperGuard.scanOnOpen`.

Security model: repository-shipped `wrapperguard:ignore` markers are untrusted until you approve the finding — your baseline lives outside the repo in VS Code's global storage.

License: Apache-2.0.
