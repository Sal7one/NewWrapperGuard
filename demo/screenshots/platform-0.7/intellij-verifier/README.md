# IntelliJ verifier evidence

The a7f3c7f baseline compiles against IntelliJ2025.2. Both actual verifier reports are compatible:

| Target | Deprecated usages | Experimental usages | Internal usages |
| --- | ---: | ---: | ---: |
| 2025.2 (252.23892.409) | 8 | 27 | 6 |
| 2026.2.3 (262.10968.63) | 88 | 18 | 0 |

The six minimum-platform internal usages are Kotlin-generated forwarding of inherited ToolWindowFactory defaults. Raw verdicts, dependency reports, usage reports and their SHA256 values are preserved in a7f3c7f/. These endpoint checks do not prove every intermediate or future IDE release.

The followup adds an exact public-signature compatibility boundary and2026.2.2, whose Jewel0.40 temporarily removed the legacy bridge overload. Its matrix also creates an actual Jewel component in each target runtime, using minimum-compiled code and a separate test sandbox. Final followup results will be recorded separately.

Sources: [official Jewel release notes](https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/RELEASE%20NOTES.md), [exact2026.2.2 bridge source](https://github.com/JetBrains/intellij-community/blob/idea/262.10315.125/platform/jewel/ide-laf-bridge/src/main/kotlin/org/jetbrains/jewel/bridge/JewelComposePanelWrapper.kt), [baseline Actions run](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39456452413).
