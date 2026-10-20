# Actual VS Code roadmap acceptance

These are captures of the installed **VS Code 1.116.0** extension-development host on macOS using a disposable profile, extension directory and inert project. Earlier captures display WrapperGuard 0.6.0; the final preset review displays 0.7.0 after the version bump. [manifest.json](manifest.json) records source checkpoint, file hashes and limits. The screenshots use actual editor controls rather than synthetic webview state.

The project and missing-tool provider are controlled test fixtures. Built-in analysis, the offline SDK 0.4.0 installation, and the HTTP requests actually ran. This is integration evidence, not a security assessment of a production project.

| Workflow | Actual evidence |
| --- | --- |
| Reviewed automation | [Native Start review](automation-native-review.png), [preference save](automation-native-save-review.png), [paused after real scan](automation-paused-after-real-scan.png). Start ran nothing immediately; a subsequent source change triggered the selected built-in scanner; Pause suppressed another change. [Assertions](automation-native.json). |
| Native wizard and keyboard recovery | The provider/network/cost/trigger/budget/save controls were exercised. Saving ran no analyzer and restored the invoking control. [Assertions](wizard-native.json), [native provider picker AX](wizard-providers-native-ax.json). |
| SDK install/repair | [Offline repair review](sdk-offline-install-review.png). The real managed interpreter installed the bundled SDK without network access and passively reported version 0.4.0 and capabilities. No scanner ran. [Assertions](sdk-offline-native.json). |
| Owned-target HTTP audit | [Authentication environment reference review](http-auth-env-reference-review.png), [explicit Connect review](http-final-connect-review.png), [real local fixture result](http-owned-target-real-findings.png). Both cancellation paths sent zero requests. Explicit Connect sent one GET to our ephemeral owned loopback server. Only warning actions were granted. [Assertions and profile](http-native.json). |
| Organization presets | [Actual identity/digest review](preset-import-identity-review.png). Export contained only passive method data. Import changed the reviewed flag, preserved grants, ran no analysis, and restored keyboard focus. [Export assertions](preset-export-native.json), [import assertions](preset-import-native.json). A digest verifies integrity; it does not authenticate the issuer. |
| Missing-tool recovery | [Repair builder](missing-tool-native-repair-builder.png), [isolated failure](missing-tool-isolated-failure.png). The enabled missing tool failed individually and made coverage incomplete; the built-in scanner completed. Cancel returned focus to that provider's Edit button. [Assertions](missing-tool-native.json). |
| Real editor API host | Microsoft's official harness activated the extension in a separate disposable two-root workspace and exercised API v1 attribution, grants, root binding, input changes, isolated missing-tool failure and the contributed view. [Eight-case result](official-host-1.116.0.json). |

The 300px panel passed document overflow checks and Tab/Enter activation across all eight views, Home/End navigation, accessible names and dialog focus recovery. [Keyboard assertions](keyboard.json), [AX tree](automation-keyboard-ax.json). Actual editor themes were applied: [light](automation-narrow-light.png), [dark](automation-narrow-dark.png), [high contrast](automation-narrow-high-contrast.png), and [high contrast light](automation-narrow-high-contrast-light.png). [Theme assertions](themes.json).

These checks are CDP accessibility-tree and keyboard sanity tests, not a formal human VoiceOver audit. They do not prove local Docker daemon/image readiness, release-package installation, or minimum/current editor compatibility; those have separate acceptance and CI evidence. No personal editor profile was used.

Recorded 0.7.0 archive checkpoint checks loaded the packaged scanner with its bundled SAX runtime and produced the expected static Maven finding without executing project code. The [strict distribution audit](distribution-audit.json) checks runtime closure, matching SDK bytes, license files, and CLI ZIP contents; [CycloneDX inventory](runtime.cdx.json) and [packaged-runtime validation](packaged-runtime-validation.json) record that scope. Seven audit regressions passed. This offline inventory does not claim that dependency databases have no advisories. Those files retain their original checkpoint hashes; rerun the audit against a rebuilt final artifact set.

## Six final CI host jobs

All six VS Code jobs in [run 39459625955](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39459625955)
passed at source **`76312ace4306c906c329e408346a59d2e893f3c7`**. The
[acceptance summary](host-matrix-76312ac/acceptance-summary.json) records job conclusions,
artifact IDs, artifact ZIP hashes and the individual evidence hashes. The seven JSON
files were copied byte for byte from the verified downloads; ZIPs are not duplicated here.

| CI platform | Minimum 1.95.0 | Current 1.142.0 |
| --- | --- | --- |
| macOS | [8 passed cases](host-matrix-76312ac/vscode-host-macos-latest-1.95.0.json) | [8 passed cases](host-matrix-76312ac/vscode-host-macos-latest-1.142.0.json) |
| Linux | [8 passed cases](host-matrix-76312ac/vscode-host-ubuntu-latest-1.95.0.json) | [8 passed cases](host-matrix-76312ac/vscode-host-ubuntu-latest-1.142.0.json) |
| Windows | [8 passed cases](host-matrix-76312ac/vscode-host-windows-latest-1.95.0.json) | [8 passed cases](host-matrix-76312ac/vscode-host-windows-latest-1.142.0.json) |

The **48 host cases** cover activation/API 1 with two roots, disabled/default grants and
no implicit execution, SDK attribution/action clipping, independent build/sync authority,
explicit root binding, isolated repairable missing tools, before/after declared-input
seals and opening the contributed webview. The matching local Node suite passed 156
tests. These are actual editor processes using inert fixtures; they do not measure
analyzer accuracy or formal human accessibility acceptance.

The earlier local GUI screenshots and archive audits above keep their own source
checkpoints. This matrix does not relabel them as captures from `76312ac` or assert results
for the workflow's separate native-IDE jobs. The separate
[Android acceptance record](../../../../examples/android-acceptance/README.md#acceptance-status)
now includes a passing emulator UI/rendering gate and agent image review; human review
and physical-phone acceptance remain unperformed.

The later [installed VSIX acceptance at `59daa26`](installed-vsix-59daa26/README.md) rebuilt
the README-bearing package with exact source permalinks, verified its installed bytes and
passed the eight actual local editor cases. It retains its own package/source hashes and
does not relabel these earlier screenshots or the `76312ac` cross-platform results.
