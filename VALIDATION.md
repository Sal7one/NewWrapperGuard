# Validation Notes

- Pure Kotlin `wrapperguard-core` and `wrapperguard-rules` sources were syntax-compiled locally with Kotlin 1.9 using a small serialization annotation stub.
- The starter `RepositoryScanner` was executed against `old_project/test-samples`: it found 6 findings across 13 scanned files and produced an `EXECUTION_BLOCKED` verdict in enforce mode.
- A full Gradle build could not run in the packaging environment because the Gradle wrapper distribution could not be downloaded (network unavailable).
- The VS Code TypeScript source was parsed with the local TypeScript compiler; full type checking requires `npm install` for the VS Code and Node type packages.
