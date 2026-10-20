# Research ideas and implementation boundaries

WrapperGuard uses research to guide optional, reviewable methods. It does not reproduce
an academic detector, reuse an evaluation dataset as a product benchmark, or inherit a
paper's accuracy numbers. The current implementation contains no trained malware model.

## Ideas used now

| Primary source | Useful idea | WrapperGuard implementation | Boundary |
|---|---|---|---|
| [PowerPeeler, CCS 2024](https://duanhaixin.cn/papers/2024_PowerPeeler-A-Precise-and-General-Dynamic-Deobfuscation-Method-for-Pow.pdf), China-based collaboration | Inspect hidden script behavior after deobfuscation | Bounded literal Base64 inspection in the optional script behavior method | Static literal handling only; no customized PowerShell runtime or dynamic instruction tracing |
| [DONAPI, USENIX Security 2024](https://www.usenix.org/conference/usenixsecurity24/presentation/huang-cheng), Sichuan University / Huawei | Associate sensitive APIs with behavior sequences | Simple Python relationships such as download → dynamic execution and environment data → HTTP sending | No npm package reconstruction, runtime monitoring or reproduction of DONAPI's knowledge base |
| [PyGuard, USENIX Security 2026](https://www.usenix.org/conference/usenixsecurity26/presentation/guo-wenbo), Singapore/China collaboration | Review behavioral context and learn from detection mistakes | Alias/assignment-aware advisory signals, benign counterexamples and separate local AI explanations | No mined PyGuard rules, learned detector or claimed contextual reasoning accuracy |

These mappings describe our engineering choices inspired by the papers. The upstream
systems are substantially larger than the bounded checks implemented here. PowerShell
signals are lexical after literal decoding; Python signals cover selected AST patterns
and simple def/use relationships. Unsupported syntax, aliasing, runtime behavior and
multi-file flows can remain uncovered. Outputs identify a reason to review source.

The local AI feature explains existing findings using minimized metadata. It does not
analyze a whole application, deobfuscate a runtime, replace a detector, approve code or
change enforcement. No model server or weights are bundled. Users choose a local model
and can remove the connection independently of every other method.

## Evaluation before stronger claims

[Beyond the TESSERACT, IEEE SaTML 2026](https://discovery.ucl.ac.uk/id/eprint/10220473/)
examines dataset quality and evaluation discrepancies in Android malware classification.
It motivates testing beyond a small, controlled collection. This is evaluation guidance,
not a classifier installed in WrapperGuard.

A future learned detector should be evaluated on independently sourced, licensed data
with time/source/family separation, deduplication, long mixed scripts, benign administrative
code and prospective real projects. Report false alarms per project, missed behaviors,
latency, unsupported inputs and drift alongside detection metrics. Tiny synthetic fixtures
can verify parser behavior and regressions; they cannot establish real-world detection quality.

Current regressions use inert fixtures and generated examples. A separately pinned 77-source real corpus and two actual local-model diagnostic runs now have [reproducible records](../research/README.md), including substantial misses and incorrect/injection-contaminated explanations. Real Bandit and Semgrep smoke tests
verify their integration and selected rules, rather than measuring malware-classification
accuracy. No corpus payloads or models are bundled, and there is no population false-positive rate or claim of research-paper parity. The [verification record](verification.md) separates those checks.

## Extending the platform

A new analyzer can be a Kotlin/TypeScript provider or an any-language command adapter.
Add a catalog entry for guided setup, keep the tool optional, retain provider attribution
and let the host cap requested actions. Models should publish separate advisory evidence
until their behavior and evaluation support a more specific product contract.

Keep deterministic findings independent of model annotations: a shadow score must never
downgrade an existing rule blocker. The old `MlScorer` seam is inactive; the deterministic
scanner does not invoke it or attach its output to rule findings.

[Provider contract](providers.md) · [Method setup](integrations.md)

The optional `research.container-python` provider adds selected-entry runtime observations inside a separately reviewed local Docker boundary. It uses explicit resource/network/privilege controls and remains bypassable advisory evidence. It does not reproduce a paper's runtime or execute payloads in the IDE. [Setup and exact limits](integrations.md#optional-local-container-backend).
