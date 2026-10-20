# Candidate a97dcd1 host verification

Source `a97dcd19133e9b70b64bc88ae30835822712dbed`. [Host matrix 39480826114](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826114) remains red. Main CI 39480825937 passed.

| Target | Binary verifier | Six actual runtime cases |
| --- | --- | --- |
| 2025.2 / 252.23892.409 | Compatible; 8 deprecated / 27 experimental / 6 internal API usages | 6 passed; zero failures/errors/skips |
| 2026.2.2 / 262.10315.125 | Compatible; 88 deprecated / 18 experimental / 0 internal | 5 passed; enabled-plugin assertion failed; zero errors/skips |
| 2026.2.3 / 262.10968.63 | Compatible; 88 deprecated / 18 experimental / 0 internal | 5 passed; enabled-plugin assertion failed; zero errors/skips |

Every recorded framework artifact matches the target build in both custom configurations. The actual Jewel factory creates a component in all targets, including the real three-argument factory in 2026.2.2. The enabled-plugin check remains deliberately required.

Adding smRunner/testRunner jars did not fix product-module registration: both newer plugin-loader logs still exclude Gradle and WrapperGuard because smRunner is unresolved. Official [upstream issue2165](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/2165) reports this module-resolution failure. [IPGP2.18 release notes](https://github.com/JetBrains/intellij-platform-gradle-plugin/releases/tag/2.18.0) identify the TestIdeTask product-info bundled-plugin classpath/module-alias fix; the narrow follow-up selects patched2.18.1 and retains minimum252 compilation, exact framework checks and all six runtime assertions. No pass is claimed until that run completes.

Minimum252's six internal API usages are compiler-generated inherited ToolWindowFactory forwarding overrides. A focused Java factory boundary can inherit the verified Java defaults directly, preserving its public constructor/content ABI without verifier exclusions. The next runtime descriptor assertion checks this too.

The exact fresh a97 installer also passed actual signed Android Studio261 startup, Tools and persisted PAUSED automation review. [UI evidence](../../android-studio/minimum-a97dcd1/evidence.json) records SHA256 `2e7c22420d7a9cc9a09187095b568dac1b021d76988ad769f8fa61f0bd8625fb`, unedited selected-window screenshots and actual Ignore notification dismissal. The desktop process is closed; these records do not turn the red262 matrix into a runtime pass.
