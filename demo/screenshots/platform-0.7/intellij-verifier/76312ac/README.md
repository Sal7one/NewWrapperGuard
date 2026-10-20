# Candidate 76312ac host verification

Source: `76312ace4306c906c329e408346a59d2e893f3c7`. Main CI [39459625691](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625691) passed. Host matrix [39459625955](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625955) failed because the two 262 runtime test executors could not initialize.

| Target | Binary verifier | Actual bridge runtime cases |
| --- | --- | --- |
| IDEA 2025.2 / IU-252.23892.409 | Compatible; 8 deprecated, 27 experimental, 6 internal API usages | 6 passed; no failures/errors/skips |
| IDEA 2026.2.2 / IU-262.10315.125 | Compatible; 88 deprecated, 18 experimental, 0 internal API usages | 0 executed; harness startup failure |
| IDEA 2026.2.3 / IU-262.10968.63 | Compatible; 88 deprecated, 18 experimental, 0 internal API usages | 0 executed; harness startup failure |

Both 262 targets failed with `NoClassDefFoundError: com/intellij/util/Java11Shim` in IntelliJ test-environment initialization, reached through `UsefulTestCase` and `JUnit5TestSessionListener`. The failure occurs before test discovery or the WrapperGuard factory invocation. The XML contains one synthetic Gradle executor failure, not one executed product test. These records establish a test-harness startup problem; they do not establish runtime compatibility for either 262 target.

Next: inspect the custom task's resolved test-framework artifact and inherited base test-runtime classpath; use a framework/runtime matched to the target IDE, then rerun the six real bridge cases on each target. IPGP 2.11's custom test-runtime configuration inherits the base runtime, and its default test-framework dependency uses the dependency helper; target/base mixing is a hypothesis requiring resolved-dependency confirmation.

Per-target directories retain raw verifier text, runtime XML, failed-step logs where applicable, and JSON summaries with SHA-256 evidence hashes. No production source was changed during this diagnosis. The final 763 minimum-built ZIP has not received another actual Android Studio startup smoke; the prior a7 minimum-built ZIP did, as recorded in the Android Studio evidence gallery.
