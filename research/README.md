# Reproducible research evaluation

This directory contains acquisition metadata and results, never malware payloads, model weights or copied academic implementations. No acquired project/package code was imported, installed, built or executed. The separate optional container research product feature is not used to obtain these static results.

## Corpus and results

`corpus.lock.json` pins 77 sources with upstream revisions, exact artifact byte counts/SHA-256, licenses, label provenance, date meanings, groups and a 2024-01-01 temporal cutoff. It includes 53 vendor-triaged malicious PyPI packages, 20 official benign Python wheels and four real-project baselines (Now in Android, Ktor samples, Requests and PowerShell). The 50 expanded malicious selections are hash-stratified across 2022–2026 discovery years; size filtering and the original three integration samples bias selection. `corpus-smoke.lock.json` preserves the original seven-source integration baseline.

Malware comes from one DataDog-maintained dataset; families are unknown. Temporal metadata mixes discovery, upload and commit dates. Five file hashes occur across groups. There is no family/source-disjoint holdout, trained model or statistically representative population. “Benign” is a source-reputation label, not proof of no vulnerability. Dataset Apache-2.0 terms do not erase original embedded-code licenses. Samples are not redistributed.

Run bounded acquisition outside the checkout:

```sh
python3 scripts/research-corpus.py --fetch --directory /absolute/private/corpus
python3 scripts/research-corpus.py --directory /absolute/private/corpus \
  --sdk-python /absolute/installed-sdk/bin/python --output /absolute/results.json
```

Only regular selected source/configuration files and an exact Gradle wrapper JAR are retained as non-executable bytes. Archive traversal/aliases/links and expanded-size/member limits are enforced. The wrapper JAR is inspected as bytes, never run. `--cli /absolute/installed/cli` additionally measures the built-in scanner on real-project subsets; this does not build those projects. Acquisition/evaluation limits and unsupported/incomplete inputs remain visible.

The recorded SDK 0.4 static signal run in `results/2026-10-20/script-semantics.json` flagged 7 of 53 malicious attempts. Among complete scans with supported inputs it flagged 7/51 malicious packages and 0/20 completed benign inputs (18 wheels, Requests and PowerShell); two malicious and two benign wheel scans were incomplete. These are signal-presence counts, not malware-classification accuracy. Android/Ktor are unsupported by this Python/PowerShell method and excluded from that confusion matrix. The result demonstrates substantial missed behavior. It cannot justify stronger blocking authority.

The built-in Now in Android subset remains incomplete because `kokoro/nightly.sh` invokes an environment-derived external path. Its `sudo apt-get`/hook downloads are legitimate administrative behaviors that require context; a warning is not a malicious label. A color variable named `NC` exposed a false command match; the scanner now distinguishes literal command positions and retains a regression. Real-project review supplements synthetic positive/negative rule fixtures.

## Local model evaluation

`ai-cases.json` contains 12 synthetic explanation, injection and minimization probes. `scripts/ai-evaluation.py` uses an explicitly selected already-running numeric-loopback model endpoint and installed SDK. It neither starts nor downloads models. The measuring relay records request digests/counts and checks source/evidence/project-path canaries. The harness enforces original host locations, INFO/AUDIT_EVIDENCE and empty actions regardless of model prose.

Two task-owned Ollama 0.32.11 runs used SmolLM2:135m F16 and Qwen2.5:0.5b Q4_K_M; exact digests/size are in `results/2026-10-20/models.json`. Both produced 12/12 structured responses and preserved all 12 host/minimization boundaries after a prompt correction. SmolLM2 echoed an injection marker in one case and often repeated boilerplate. Qwen echoed none of the literal markers, yet its prose accepted injected instructions in other cases and incorrectly described an empty TLS trust check as rejecting invalid certificates. It also confused transport with storage and checksum with signatures. Literal-marker absence therefore does not establish injection resistance or explanation quality.

The earlier prompt caused a small model to copy a placeholder as its entire explanation; that example was removed and uninformative placeholders are now rejected. The retained model results still fail a production-quality explanatory standard. Topic-term matches are diagnostic only. There is no blinded human study, calibrated quality score, model recommendation or claim of prompt-injection immunity. AI remains off by default, independently removable and advisory; it cannot approve or downgrade deterministic findings.

Task-owned servers and downloaded weights were stopped/removed after recording results; the user's own model services were untouched. The CI static-corpus job reruns acquisition/parser boundaries and publishes metadata-only results. External source updates require a new reviewed lock rather than floating downloads.

## Measured CLI performance and resource boundaries

[The recorded 0.7.0 results](results/2026-10-20/performance.json) measure the packaged CLI on October 20, 2026, on a local macOS arm64 host (Darwin 25.5.0, 12 logical CPUs, JetBrains Runtime 21.0.8). Each source subset had one warmup and three measured runs in shuffled order. Each run starts a fresh CLI/JVM; wall time includes startup. The warmup warms OS caches. Maximum RSS comes from `/usr/bin/time`, normalized to bytes, and is a process high-water value rather than aggregate concurrent-provider memory. Launcher/JAR hashes and the acquired inventory/file-set hashes are recorded; every selected input digest matched before and after the run.

| Normalized source subset | Median wall time | Maximum RSS | Completed configured scans | Native inputs scanned |
| --- | ---: | ---: | ---: | ---: |
| Now in Android | 675.036 ms | 390.594 MiB | 0/3 | 48 |
| Ktor samples | 817.330 ms | 281.203 MiB | 3/3 | 146 |
| Requests 2.32.5 | 316.350 ms | 97.375 MiB | 3/3 | 1 |
| PowerShell build | 394.368 ms | 108.438 MiB | 3/3 | 1 |

These scans use native wrapper/build/script checks only. The acquired trees retain selected regular source/configuration bytes, not complete upstream distributions. The Requests result covers one native configuration/script input and provides no Python application SAST coverage. Kotlin/Java application analysis likewise requires an explicitly configured source provider. Now in Android remains incomplete in every run because the Kokoro environment-derived script cannot be inspected safely; this limitation is retained rather than treated as clean. Completed scope is not approval or universal security coverage.

All five generated resource checks passed the explicit 30-second/1-GiB budget for finished measurements. Scanning 4,096 inert comment-only shell files completed in 2,662.169 ms with 419.047 MiB maximum RSS. A 1,000,001-byte input remained incomplete with `WG-FILE-SKIPPED` (246.251 ms). The 5,001-file and depth-66 fixtures exited unsuccessfully at the host seal limit (754.663 ms and 272.961 ms); they produced no complete JSON scan report and are recorded as unavailable/incomplete. Forced cancellation stopped the process group in 12.808 ms and remained incomplete; no RSS value was available for that interrupted measurement. No fixture or acquired project code was executed.

Resource acceptance passed while `allProjectScansComplete` remained false. This single developer-host run has uncontrolled background load, three timing samples per subset and no cross-platform performance claim. It establishes a reproducible baseline, not a latency SLA or malware-detection score. `scripts/benchmark-scans.py` reproduces the measurements with explicit roots, and the resource-acceptance workflow repeats the generated boundary checks on a fresh Linux runner.

## Pinned Android and Ktor rule review

[Actual Linux CI](https://github.com/Sal7one/NewWrapperGaurd/actions/runs/39454719789) ran installed SDK 0.4.0 and Semgrep 1.181.0 against the pinned Now in Android and Ktor source subsets, with each of the five local packs selected independently. All ten configured scans completed. [Metadata and contextual review](results/2026-10-20/semgrep-source-review.json) preserve exact rule/input hashes, IDs and relative locations: Now in Android produced two manifest-backup warnings; Ktor produced one backup warning and two overlapping CORS findings in one `httpbin` configuration block. The other pack runs emitted no findings.

All five emitted locations were compared with the acquired, hash-verified source. The three backup settings are accurate declaration matches, but no sensitive-data exposure was demonstrated; [Android's backup settings](https://developer.android.com/guide/topics/manifest/application-element#allowbackup), merged release manifests and actual stored data require context. The CORS declarations match the [documented Ktor configuration](https://ktor.io/docs/server-cors.html) and belong to a demonstration server; deployment, browser and credential behavior were not tested. These are policy-review signals, not five confirmed vulnerabilities or automatically labeled false positives. A concrete coverage gap also remains: the same Ktor sample selects an algorithm through a request-derived variable with an MD5 fallback, then calls `MessageDigest.getInstance` with that variable. The literal crypto rules emitted no finding for that path. No exhaustive review, independent expert labels, precision or recall measurement follows from this small review.

The separate [static-corpus CI summary](results/2026-10-20/script-semantics-ci.json) reproduced seven signaled malicious attempts out of 53. Its 20 completed benign inputs comprise 18 wheels plus Requests and PowerShell; `idna` and `pyparsing` remain incomplete, and Android/Ktor remain unsupported by script semantics. Acquired project/package code was not executed by either static evaluation.
