# Installed VSIX acceptance — 59daa26

Source **`59daa2615c5c15654fc43847df1e6482d73edafb`**, app **0.7.0**, SDK **0.4.0**.
The final VSIX was built through the committed packaging wrapper, installed with the
existing VS Code **1.116.0** CLI into a disposable profile/extension directory, and tested
in the actual editor process. No editor or browser was downloaded; the personal editor
profile was not used. [Manifest](manifest.json) records the evidence hashes.

- [Package checks](package-acceptance.json): 106 installed files matched the archive,
  allowing only VS Code's added package metadata. The current README, compiled runtime,
  declarations, method catalog/presets, Apache license, notices, reviewed SAX runtime/license
  and source-matching zero-dependency wheel passed checks. All five rewritten README links
  resolve to files present at this exact Git commit.
- [Eight actual host cases](host-result.json) passed against the installed
  `sal7one.wrapperguard-0.7.0` directory. Only a separate inert test driver used the
  development-extension option; WrapperGuard loaded from the installed package.
  Cases cover API 1, no implicit execution, attribution/action clipping, independent
  BUILD/SYNC grants, two roots, missing-tool isolation, changed-input gate rejection
  and the actual contributed webview. [Install log](install.log), [editor log](host.log).
- [Installed runtime check](installed-runtime.json): loading the packaged scanner and
  bundled SAX parser produced `WG-MAVEN-001` from deliberately HTTP Maven XML. No project
  build or code ran. The 158 Node tests, webview checks and five hardening checks also passed.

VSIX SHA-256: `1ec482cd413bfc2a17450dd90fc825fc176b326d3697f2f8623d02e183f7503b`.
Bundled wheel SHA-256: `ad9f000207411b362e0f93a02b9c0953429874a093a3ff84aedefd4fc43d7765`.
These hashes identify this package; rebuilt archives can have different metadata/bytes.

An initial local preparation copied an already-used disposable settings file containing
provider grants, so its clean-default assertion correctly failed. The
[diagnosis](preparation-diagnostic/reason.json) and rejected result are retained separately.
The final run used a brand-new settings directory with no grants and passed unchanged
production code and host tests. Updater checks were disabled.

This is local installed-package acceptance, separate from the earlier cross-platform
matrix and GUI screenshots. It is not formal human accessibility review or a measurement
of analyzer accuracy. It does not establish results for native IDEs or physical phones.
