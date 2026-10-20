# Security reporting and supported scope

Report a suspected vulnerability through this repository's **Security → Report a vulnerability** when that private channel is available. If it is unavailable, contact the repository owner through their GitHub profile to arrange a private report. Do not post credentials, confidential source, exploit payloads or private reports in public issues.

Include the WrapperGuard/SDK and IDE/OS versions, enabled methods, effective grants, a minimal inert reproduction and the expected/observed result. Redact source, tokens, absolute paths and third-party report contents. There is no guaranteed response time or public bounty program. Current release candidates and the latest published release are the primary maintenance targets; older versions have no promised backports.

## Trust boundaries

Providers are interchangeable analyzers; the host controls attribution, action grants, policy, freshness and supported build/sync gates. Installed command/SDK/analyzer programs remain trusted OS-user code. A warning/block grant is not an operating-system sandbox. VS Code gates require a cooperative caller; arbitrary terminal commands are not intercepted.

The optional container backend requires a separately reviewed local engine and pinned image. Its network/resource/privilege controls are separately tested. The host kernel, container daemon and trusted timer/bootstrap remain part of the boundary; a container is not a virtual machine. Research execution is explicit, selected-entry, SCAN-only and advisory. No host fallback executes a payload.

Imported reports, producer metadata, organization preset hashes and declared SDK scopes are not signatures or proof of complete analysis. Missing/partial/expired/unresolved scope remains visible. AI explanations are untrusted advisory prose and cannot approve code, change locations or obtain blockers. Passing a configured scan does not establish that an application is secure.

## Release verification

Use release SHA-256 manifests and the recorded source commit. GitHub checksums establish byte identity with that release; they do not replace signing or independent review. Marketplace/PyPI publication and publisher signing credentials are separate distribution setup. See [verification](docs/verification.md), [dependency inventory](docs/dependencies.md) and [roadmap](docs/roadmap.md).
