# IntelliJ verifier evidence

The a7f3c7f baseline compiles against IntelliJ2025.2. Both actual verifier reports are compatible:

| Target | Deprecated usages | Experimental usages | Internal usages |
| --- | ---: | ---: | ---: |
| 2025.2 (252.23892.409) | 8 | 27 | 6 |
| 2026.2.3 (262.10968.63) | 88 | 18 | 0 |

The six minimum-platform internal usages are Kotlin-generated forwarding of inherited ToolWindowFactory defaults. Raw verdicts, dependency reports, usage reports and their SHA256 values are preserved in a7f3c7f/. These endpoint checks do not prove every intermediate or future IDE release.

The 76312ac followup adds an exact public-signature compatibility boundary and 2026.2.2, whose Jewel 0.40 temporarily removed the legacy bridge overload. [Candidate evidence](76312ac/README.md) preserves the final matrix results: all three binary verifiers passed, and the minimum 2025.2 runtime executed all six bridge cases successfully. Both 262 targets failed before any product test ran because IntelliJ test initialization could not load `com.intellij.util.Java11Shim`. Their runtime compatibility remains unproven until the target test harness is repaired and the six real cases rerun. The main build/test/package CI passed. No final 763 ZIP Android Studio startup smoke is claimed.

Sources: [official Jewel release notes](https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/RELEASE%20NOTES.md), [exact2026.2.2 bridge source](https://github.com/JetBrains/intellij-community/blob/idea/262.10315.125/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/JewelComposePanelWrapper.kt), [baseline Actions run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39456452413).

[The 59daa26 follow-up](59daa26/README.md) resolves target test-framework selection and executes all six cases on each target. Both newer factories pass; their enabled-plugin assertion identifies missing test-runtime modules, retained as a failure until the module follow-up passes.
