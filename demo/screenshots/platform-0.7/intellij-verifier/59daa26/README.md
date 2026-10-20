# Candidate 59daa26 host verification

Source: `59daa2615c5c15654fc43847df1e6482d73edafb`. [Main CI 39478013449](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39478013449) passed. [Host matrix 39478013336](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39478013336) failed on one actual assertion per newer target. No failures were skipped.

| Target | Binary verifier | Actual runtime cases |
| --- | --- | --- |
| IDEA 2025.2 / IU-252.23892.409 | Compatible; 8 deprecated, 27 experimental, 6 internal | 6 passed; no failures/errors/skips |
| IDEA 2026.2.2 / IU-262.10315.125 | Compatible; 88 deprecated, 18 experimental, 0 internal | 6 executed: 5 passed, 1 failed; no errors/skips |
| IDEA 2026.2.3 / IU-262.10968.63 | Compatible; 88 deprecated, 18 experimental, 0 internal | 6 executed: 5 passed, 1 failed; no errors/skips |

The prior Java11Shim initialization failure is resolved: every target framework artifact matches its selected build in both custom dependency/runtime configurations. Those exact resolutions are preserved in each runtime/test-framework.txt.

Both newer targets create an actual Jewel component successfully through the public compatibility boundary. The 2026.2.2 test also asserts that the real three-argument bridge is selected; the endpoint tests assert their two-argument bridge. All four old/new/missing/failure-boundary cases pass.

The enabled-plugin assertion remains red at GuardSmokeTest.kt:43. The actual plugin-loader log states that `intellij.platform.smRunner` is absent from the custom test environment, excluding Gradle and therefore WrapperGuard. This is an isolated test-runtime module assembly gap, not permission to accept a disabled plugin. Official [smRunner module declaration](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/smRunner/resources/intellij.platform.smRunner.xml) also requires `intellij.platform.testRunner`; the [testRunner declaration](https://github.com/JetBrains/intellij-community/blob/idea/262.10968.63/platform/testRunner/resources/intellij.platform.testRunner.xml) has its other dependencies already present in the actual resolved-module log. The narrow follow-up adds those two bundled product modules to custom 2026 test runtimes. The shipped descriptor and minimum compile target are unchanged. A new all-green run is still required.

Raw XML, failed-step logs, verifier reports and hashed result summaries are retained per target. Actual Android Studio startup/Tools/PAUSED automation acceptance used the minimum-built ZIP whose SHA-256 is identical across the prior 763 and this 59 main-CI bundle; see [actual host evidence](../../android-studio/minimum-76312ac/evidence.json).
