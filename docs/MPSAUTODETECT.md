# MPSAutodetect Integration Decision

The paper proposes a PowerShell detector using a stacked denoising auto-encoder for learned features and XGBoost for classification. Treat it as research inspiration, not a drop-in library.

## What to add now

- `MlScorer` interface in the pure core.
- `MlSignal` with `score`, `modelId`, and supporting labels.
- `NoopMlScorer` as the default.
- A policy rule that ML cannot block execution by itself.
- A future ONNX or local-process adapter boundary.

## What not to do this weekend

- Do not claim the paper's reported metrics for WrapperGuard.
- Do not train from an unknown or unlicensed dataset.
- Do not ship Python inside the IDE plugin.
- Do not make an opaque score replace deterministic evidence.

## Later research spike

1. Obtain a legally reusable, deduplicated benign/malicious PowerShell corpus.
2. Reproduce a simple character-level baseline.
3. Compare SdA + XGBoost against simpler baselines.
4. Export the final feature model/classifier to a portable local format if practical.
5. Evaluate repository-family and time-based splits, not random duplicate-heavy splits.
6. Run in shadow mode and record false positives per 1,000 benign scripts.
7. Show the score as an experimental supporting signal only.
