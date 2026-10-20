# Packaged dependencies and distribution review

App 0.7.0 and SDK 0.4.0 are Apache-2.0. The SDK has no runtime dependencies. The CLI/native runtime closure contains Kotlin standard library 2.2.20, kotlinx-serialization core/json 1.8.0 and annotations 13.0 (Apache-2.0). VS Code contains SAX 1.6.1 (BlueOak-1.0.0), whose original license is packaged beside its code. [Notices](../THIRD_PARTY_NOTICES.md) are included in native/CLI/VS Code packages.

After building all packages:

```sh
python3 scripts/distribution-audit.py --output /absolute/new/audit --query-osv
npm --prefix vscode-extension audit --omit=dev
```

The audit inventories actual packaged JARs, first-party wheel and Node runtime, rejects unknown runtime additions, verifies embedded wheel identity/licenses and emits CycloneDX 1.6 plus artifact hashes. The optional OSV query sends only five dependency names/versions to the official service; it fails on reported advisories. The npm runtime audit and the five-dependency live OSV query both returned zero known advisories on 2026-10-20. Database results are time-sensitive and are not proof of no vulnerabilities.

Build/development dependencies remain in Gradle configuration and package-lock.json and are outside this runtime SBOM. IDE/Java/Python/Node runtimes, optional analyzer packages/databases, model weights and container images are separately installed components with separate licenses, trust and update requirements. The product neither bundles nor relicenses them. Research source licenses/label caveats are pinned in the acquisition lock and payloads are not redistributed.

[Security reporting](../SECURITY.md) · [Acceptance and exact release evidence](verification.md)

An updated extension README or source permalink changes VSIX bytes. Older audits and gallery inventories retain their recorded artifact hashes. Rebuild final artifacts and regenerate the audit before packaging; the bundle extracts its CLI from the exact audited ZIP and validates offline documentation links. Use the [ordered handover](../HANDOVER.md#resume-in-this-order) and published release metadata for the current source/artifact set.
