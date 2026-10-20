# Reproducible research evaluation

This directory contains acquisition metadata and results, never malware payloads, model weights or copied academic implementations. No acquired project/package code was imported, installed, built or executed. The separate optional container research product feature is not used to obtain these static results.

## Corpus and results

`corpus.lock.json` pins77 sources with upstream revisions, exact artifact byte counts/SHA-256, licenses, label provenance, date meanings, groups and a2024-01-01 temporal cutoff. It includes53 vendor-triaged malicious PyPI packages,20 official benign Python wheels and four real-project baselines (Now in Android, Ktor samples, Requests and PowerShell). The50 expanded malicious selections are hash-stratified across2022–2026 discovery years; size filtering and the original three integration samples bias selection. `corpus-smoke.lock.json` preserves the original seven-source integration baseline.

Malware comes from one DataDog-maintained dataset; families are unknown. Temporal metadata mixes discovery, upload and commit dates. Five file hashes occur across groups. There is no family/source-disjoint holdout, trained model or statistically representative population. “Benign” is a source-reputation label, not proof of no vulnerability. Dataset Apache-2.0 terms do not erase original embedded-code licenses. Samples are not redistributed.

Run bounded acquisition outside the checkout:

```sh
python3 scripts/research-corpus.py --fetch --directory /absolute/private/corpus
python3 scripts/research-corpus.py --directory /absolute/private/corpus \
  --sdk-python /absolute/installed-sdk/bin/python --output /absolute/results.json
```

Only regular selected source/configuration files and an exact Gradle wrapper JAR are retained as non-executable bytes. Archive traversal/aliases/links and expanded-size/member limits are enforced. The wrapper JAR is inspected as bytes, never run. `--cli /absolute/installed/cli` additionally measures the built-in scanner on real-project subsets; this does not build those projects. Acquisition/evaluation limits and unsupported/incomplete inputs remain visible.

The recorded SDK 0.4 static signal run in `results/2026-10-20/script-semantics.json` flagged7 of53 malicious attempts. Among complete scans with supported inputs it flagged7/51 malicious packages and 0/20 completed benign inputs (18 wheels, Requests and PowerShell); two malicious and two benign wheel scans were incomplete. These are signal-presence counts, not malware-classification accuracy. Android/Ktor are unsupported by this Python/PowerShell method and excluded from that confusion matrix. The result demonstrates substantial missed behavior. It cannot justify stronger blocking authority.

The built-in Now in Android subset remains incomplete because `kokoro/nightly.sh` invokes an environment-derived external path. Its `sudo apt-get`/hook downloads are legitimate administrative behaviors that require context; a warning is not a malicious label. A color variable named `NC` exposed a false command match; the scanner now distinguishes literal command positions and retains a regression. Real-project review supplements synthetic positive/negative rule fixtures.

## Local model evaluation

`ai-cases.json` contains12 synthetic explanation, injection and minimization probes. `scripts/ai-evaluation.py` uses an explicitly selected already-running numeric-loopback model endpoint and installed SDK. It neither starts nor downloads models. The measuring relay records request digests/counts and checks source/evidence/project-path canaries. The harness enforces original host locations, INFO/AUDIT_EVIDENCE and empty actions regardless of model prose.

Two task-owned Ollama 0.32.11 runs used SmolLM2:135m F16 and Qwen2.5:0.5b Q4_K_M; exact digests/size are in `results/2026-10-20/models.json`. Both produced12/12 structured responses and preserved all 12 host/minimization boundaries after a prompt correction. SmolLM2 echoed an injection marker in one case and often repeated boilerplate. Qwen echoed none of the literal markers, yet its prose accepted injected instructions in other cases and incorrectly described an empty TLS trust check as rejecting invalid certificates. It also confused transport with storage and checksum with signatures. Literal-marker absence therefore does not establish injection resistance or explanation quality.

The earlier prompt caused a small model to copy a placeholder as its entire explanation; that example was removed and uninformative placeholders are now rejected. The retained model results still fail a production-quality explanatory standard. Topic-term matches are diagnostic only. There is no blinded human study, calibrated quality score, model recommendation or claim of prompt-injection immunity. AI remains off by default, independently removable and advisory; it cannot approve or downgrade deterministic findings.

Task-owned servers and downloaded weights were stopped/removed after recording results; the user's own model services were untouched. The CI static-corpus job reruns acquisition/parser boundaries and publishes metadata-only results. External source updates require a new reviewed lock rather than floating downloads.
