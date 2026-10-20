# Manual source-analysis fixture

Open this folder as text/source in Android Studio, IntelliJ IDEA or VS Code. It has no
build configuration, wrapper, application entry point or dependency installation step.
Do not import/build it. The Python file exits before its illustrative call if accidentally
run. These are deliberately unsafe patterns for analyzer review, not a working application.

With optional Semgrep enabled and its bundled rules selected, expect at least:

- `wrapperguard.ktor.unrestricted-cors` in `Application.kt`.
- `wrapperguard.kotlin.empty-server-trust-check` in `Application.kt`.
- `wrapperguard.android.javascript-bridge` in `Browser.java`.
- `wrapperguard.android.raw-query-concatenation` in `Browser.java`.

With optional Bandit enabled, expect `B602` for the `shell=True` call in `app.py`;
`B404` and `B607` may also be reported. Real Semgrep 1.181.0 and Bandit 1.9.4 runs verified
these fixture expectations through SDK 0.2.0 on 2026-10-19.
The inline `nosemgrep` / `nosec` text must not suppress those SDK-runner findings.
Use [the manual test instructions](../../docs/verification.md#install-and-test-the-build)
for connection, feature-switch and permission checks. Findings are test expectations;
this fixture does not measure malware-detection accuracy or complete app coverage.
