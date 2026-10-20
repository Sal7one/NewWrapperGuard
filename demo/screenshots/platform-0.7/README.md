# WrapperGuard 0.7 acceptance evidence

Archived pre-fix checkpoint: branch **`a97dcd19133e9b70b64bc88ae30835822712dbed`**, tested PR merge **`9b48d88d2bd70deb6e8f39f43e4e1dc97f2344c2`**. Every record keeps its actual source/package identity. Canonical release metadata and `SOURCE-COMMIT.txt` determine publication; this gallery is acceptance evidence, not fictional findings or a release announcement.

| Evidence | Recorded scope |
| --- | --- |
| [Accepted a97 Android emulator](android-device/a97dcd1/README.md) | Actual Android 16/API 36 rendered owned activity; foreground/UI/HTTP checks; unmodified PNG directly inspected and APK hash independently matched. Secure0 findings, weak 3 |
| [Actual a97 Studio installation](android-studio/minimum-a97dcd1/evidence.json) | Exact minimum-built CI ZIP in real Studio 261: startup, Tools and persisted PAUSED automation review, with original window captures/hashes |
| [a97 native verifier/runtime reports](intellij-verifier/a97dcd1/README.md) | All binary verifiers compatible; minimum 2025.2 passed 6/6 actual cases; 2026.2.2 and 2026.2.3 each passed 5/6 and failed enabled-plugin registration. Failures remain visible |
| [VS Code actual hosts and UI](../flexible-provider-platform/vscode/README.md) | [a97 matrix](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39480826114): six VS Code jobs / 48 cases passed. Gallery retains installed-package, keyboard/AX/theme/setup/automation/preset/HTTP evidence; separate native jobs made the workflow red |
| [Earlier native/Studio evidence](intellij-verifier/README.md) / [Studio captures](android-studio/) | Historical framework-startup diagnoses, binary verdicts and actual UI/package checkpoints; none is relabeled as a later source |
| [Earlier 0.6 acceptance](../platform-0.6/README.md) | Actual build/sync guards, package lifecycle and explicitly labeled synthetic interface examples |

The follow-up in this source selects IPGP 2.18.1 for the upstream TestIdeTask product-info/module-alias classpath fix and uses a Java factory to inherit platform JVM defaults without Kotlin-generated forwarding. Exact target-framework checks and all six runtime assertions remain required. Source-matched green matrix results and released evidence determine closure. The [cached 253 local preflight](intellij-verifier/local-253-ipgp2181/README.md) passed all six cases with zero failures/errors/skips; it does not establish minimum 252 or 262 acceptance.

Rejected black ATD and SystemUI ANR captures remain under [research diagnostics](../../../research/diagnostics/2026-10-20/) and are excluded from accepted device evidence. The accepted a97 PNG is unmodified and directly inspected by an agent; human visual review and physical-phone testing were not performed. Automated keyboard/AX checks do not replace human screen-reader/usability acceptance.

[Verification](../../../docs/verification.md) and [handover](../../../HANDOVER.md) give current release gates and distinguish standalone acceptance-bundle checks from full-checkout source/Android driver commands. Preserve historical raw logs, reports and hashes.
