# Release 0.4.0 — 2026-10-19

Based on `Sal7one/NewWrapperGaurd` master `61aed0b75522abab32f258e335f847663c84252e`
(2026-09-13 00:34:31 UTC). Work was prepared on `codex/vetting-provider-platform`.
The owner authorized pushing version 0.4.0 and creating its GitHub release on 2026-10-19.
The repository remains private. Marketplace submission and a visibility change are outside
this release. [GitHub release target: v0.4.0](https://github.com/Sal7one/NewWrapperGaurd/releases/tag/v0.4.0).
The final package validation is recorded below.

## Architecture review and implemented changes

| Previous constraint | Current implementation |
|---|---|
| IDE hosts invoke their detector directly | Provider coordinator separates host policy/lifecycle/UI from analysis |
| One built-in detector per host | Built-in provider, installed SDK providers and explicit any-language command adapters |
| One blocking policy with no provider authority | Requested actions intersected with user grants and BUILD/SYNC-specific policy |
| Scanner failure could fail open without provider visibility | Provider status, incomplete coverage and permitted required-provider failure blockers |
| Sync claim depended on post-import checks | Actual Gradle pre-resolver veto, separate from the before-run guard |
| Freshness seal covered script/build files only | Also includes user policy, provider grants, config contents, versions and project trust |
| Ignored old-project test fixtures | Tracked static fixtures required by regression and parity checks |
| CI listened to main only | master/main triggers, binary verification, CLI protocol smoke and both installable packages |

The original Kotlin and TypeScript detector implementations remain. Detector corrections
fix the Kotlin word-boundary regex in the missing-ignore-reason audit check and the
TypeScript credential-block regex, plus the replacement cursor used by both hosts when
redacting multiple credential blocks. Usernames and password literals are redacted, with
credential regressions and cross-language fingerprint parity checked. INFO/audit/legacy
ML shadow findings cannot block, including under explicit rule policy. Provider fingerprints
are isolated; built-in approvals keep their existing keys.

## Reproducible validation

JDK 21, Gradle 9.1.0 and Node 22+ are required. The wrapper distribution checksum is pinned
to the upstream Gradle 9.1.0 SHA-256. VSIX tooling is pinned through package-lock.json.

```sh
./gradlew build :wrapperguard-cli:installDist :wrapperguard-cli:distZip :wrapperguard-cli:distTar
./gradlew :wrapperguard-intellij:buildPlugin :wrapperguard-intellij:verifyPlugin
python3 scripts/provider-smoke.py
cd vscode-extension
npm ci
npm test
npm run parity
npm run hardening
npm run package
```

Validation on 2026-10-19 includes JVM unit/integration tests, actual installed CLI
invocation, multiple command adapters, per-operation grants, failed required providers,
untrusted configuration rejection, TypeScript unit/host lifecycle tests, emitted webview
syntax/render checks, hostile-input checks, and Kotlin/TypeScript parity on both tracked
fixtures and the repository root. The complete counts and final result are recorded below.

IntelliJ IDEA 2025.3 binary verification is separate from the local Android Studio test
pass. The verifier reports compatibility together with existing deprecated APIs and
experimental platform/Compose integrations; compatibility does not make those APIs stable.
The Gradle pre-resolver extension is currently an experimental JetBrains API.

## Artifacts

- IntelliJ/Android Studio: `wrapperguard-intellij/build/distributions/wrapperguard-intellij-0.4.0.zip`
- VS Code: `vscode-extension/wrapperguard-0.4.0.vsix`
- CLI ZIP/TAR: `wrapperguard-cli/build/distributions/wrapperguard-cli-0.4.0.zip` / `.tar`
- CLI installation: `wrapperguard-cli/build/install/wrapperguard-cli/`
- SHA-256 asset: `build/release/wrapperguard-0.4.0-SHA256SUMS.txt`
- API 1 integration instructions: [providers.md](providers.md)
- Provider configuration schema: [wrapperguard-provider-config.schema.json](../schema/wrapperguard-provider-config.schema.json)

Generated distributions stay ignored by Git. CI uploads artifacts for review without
publishing them. Existing plugin IDs and the VS Code publisher are preserved.

## Publication boundaries

The implemented host is ready for integration testing with real vendor SDKs. Marketplace
publication still requires owner credentials/listing assets and a live smoke pass using
the final ZIP/VSIX on intended supported IDE versions. This session does not certify every
SDK, every launch/import path, or full screen-reader/keyboard usability. The code adds
labelled native settings and keyboard-accessible webview controls; perform a focused live
accessibility pass on the new provider settings before public distribution.

SDK permission grants govern host actions, not OS resource access. Trusted SDKs/adapters
can access resources available to the user. In-process code must cooperate with interruption
or AbortSignal; synchronous JavaScript cannot be forcibly preempted. JVM process-tree
termination has a documented fallback on hosts without a POSIX launcher. Independently
detached descendants are outside process-group lifecycle cleanup.

Execution seals cover the host's watched build/script files and user provider/policy
configuration. They cannot establish freshness for every arbitrary SDK input, remote
service, or executable/library replacement. Additional user Gradle init-script scanning
and a dedicated SDK input-manifest contract remain future scope. VS Code callers must
honor `gate().allowed`; the host cannot universally intercept arbitrary tasks/terminals.

## Final source and package validation

The complete implementation, including the compact UI/demo pass and single/multiple
credential-block regressions, passed these checks on 2026-10-19. Both IDE packages and
both CLI archives were rebuilt from the final source.

| Gate | Result |
|---|---|
| JVM build and CLI installation | Passed with JDK 21 |
| Core | 45 tests, zero failures/errors/skips |
| Rules | 25 tests, zero failures/errors/skips |
| CLI | 3 tests, zero failures/errors/skips |
| IntelliJ | 34 tests, zero failures/errors/skips on IDEA 2025.3 |
| IntelliJ verifier | Final ZIP compatible with IU-253.28294.334; 6 deprecated and 32 experimental API usages; task passed |
| TypeScript | 52 tests passed, including credential redaction regressions; emitted-webview render checks passed |
| Hostile inputs | 5 checks passed |
| Parity | Tracked fixtures: 18/18 findings, 8/8 active blockers; repository: 44/44 findings, 17/17 active blockers; all fingerprints match |
| Installed CLI protocol | Version entrypoint, multiple command adapters, BUILD/SYNC permissions, required failures, disabled coverage, external-config boundary and built-in compatibility passed |
| Demo fixtures | Golden check passed: 12 findings, 3 active blockers, 6 review findings, 6 providers, 6 graph files, 7 edges |
| Final archive audit | Current JVM JARs and packaged TypeScript verified; packaged multiple-credential-block regression passed |

The **107 JVM tests and 52 Node tests total 159 passing tests**. Both parity targets
intentionally include unsafe static regression inputs, so the expected scan verdict is
EXECUTION_BLOCKED. These findings are test coverage, not a clean security assessment of
the repository. Fixtures must never be executed or imported as projects.

Final package SHA-256:

```text
39535ce6a44cf5c49d648e53e96cff62a40bb4e3ed900eb19de28e9518056dba  wrapperguard-intellij-0.4.0.zip
40c08b7a47fae410f3b06e92d62cf2c03270e06eb27bff94aa4cfb0e6211a14f  wrapperguard-0.4.0.vsix
33e6fc6cd10765f82d0a3dc9de92ba1eacf9575261ce65860a9dc6a51e008078  wrapperguard-cli-0.4.0.zip
1f7e73133bf4d836ca2522de6314adb035b7fadcf501ccd5bbb359e567b90028  wrapperguard-cli-0.4.0.tar
```

Package sizes, in the same order: 3,135,558; 88,225; 2,568,213; 2,782,720 bytes.
These hashes are also in `wrapperguard-0.4.0-SHA256SUMS.txt` for release attachment.

## UI baseline — 2026-10-19

The native Compose workspace and VS Code renderer use compact navigation, a full-width
findings table with a bottom evidence dock, an aligned provider permission matrix, and
fitted execution graphs with separate inspectors. Demo scanner settings come from the same
display-only fixture in both hosts. The fictional Atlas Payments workspace is reproducible
through [the demo guide](../demo/README.md).

Nine real-renderer browser captures passed interaction checks for every finding status,
baseline approval/restore, permissions, search, graph containment, and narrow-screen action
reachability. Four native captures were reviewed in the running IDEA 2025.3 sandbox. All 13
are indexed in [the gallery](../demo/index.html), with source and screenshot hashes in its
manifest. Demo reports do not establish live SDK compatibility or scanner performance.

## Earlier validation evidence

The initial provider-platform pass on 2026-10-19 had 102 JVM tests and 49 Node tests,
151 total. That ZIP passed binary verification against IU-253.28294.334, with 4 deprecated
and 32 experimental API usages. An earlier local Android Studio pass ran 20 tests.
The initial tracked-fixture parity was 18 findings/8 blockers; repository parity was
21 findings/11 blockers, before the added UI/demo and credential test inputs. Schemas,
examples, archives, manifests, license, SDK declarations and sync extension registration
were checked, and the clean npm install reported zero dependency vulnerabilities.

Those initial packages predate the final interface and credential correction. Their
historical hashes, retained only for provenance, are:

```text
wrapperguard-intellij-0.4.0.zip  fd2457027565918194b48453faa847949e61cae8ee57fe83d1c7f4b4c335adeb
wrapperguard-0.4.0.vsix        74638e98b044a02fb17dd903536340e3843aec0410e062ec36aab443cc08e3d1
```

Live vendor SDK, keyboard/screen-reader, minimum-supported IDE and final Marketplace
installation checks are not claimed by these automated results.
