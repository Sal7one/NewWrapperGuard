# Runtime third-party notices

WrapperGuard and its first-party Python SDK are Apache-2.0. The SDK has no runtime dependencies. Optional analyzers, model weights, IDE platforms, Python/Java/Node runtimes and container images are separately installed and retain their own licenses; enabling a connection does not relicense them.

The packaged CLI/native plugin include JetBrains Kotlin standard library 2.2.20, kotlinx-serialization-core-jvm/json-jvm 1.8.0 and JetBrains annotations 13.0, under Apache-2.0. Copyright remains with their respective JetBrains/contributor owners. The included Apache license supplies the terms; upstream notices embedded in artifacts are preserved.

The VS Code extension includes Isaac Z. Schlueter and contributors' SAX parser, `sax` 1.6.1, under BlueOak-1.0.0. Its original LICENSE is included beside the packaged library. The development/build dependency graph is recorded in package-lock.json and is not part of this runtime inventory.

Run `scripts/distribution-audit.py` after packaging to inventory actual runtime JARs, the first-party wheel and SAX version. Unknown JARs fail that review rather than acquiring an assumed license. This inventory is engineering attribution, not a legal opinion. Research acquisition licenses and label limitations are recorded separately in research/corpus.lock.json; no malware samples or model weights are redistributed.
