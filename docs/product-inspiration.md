# Product and open-source design references

Reviewed **2026-10-20**. These are workflow and interoperability references, not a claim
that WrapperGuard provides each product's coverage. No implementation or UI was copied.
External scanners retain their own licenses, installation and trust requirements.

| Reference | Useful idea | WrapperGuard implementation | Boundary |
|---|---|---|---|
| [Trunk custom tools](https://trunk.io/blog/integrating-your-own-custom-tools-with-trunk-check) and [plugins](https://github.com/trunk-io/plugins) | Explicit tool definitions, inputs and parsers | Generic SDK/command setup, literal argv, declared input scopes, reviewed presets | A command is trusted user code; action grants do not sandbox it |
| [secureCodeBox](https://github.com/secureCodeBox/secureCodeBox) | Separate runner, parser and orchestration lifecycle | Hosts coordinate API 1 providers concurrently; SDK adapters normalize reports independently of launchers | No Kubernetes/operator deployment is required or bundled |
| [Qodana profiles](https://www.jetbrains.com/help/qodana/inspection-profiles.html) | Reviewable inspection selection and scope | Independent method flags, source rule packs and data-only presets | Presets grant no execution or blocking authority |
| [Snyk language-server documentation](https://github.com/snyk/user-docs/blob/main/developer-tools/snyk-ide-plugins-and-extensions/snyk-language-server/README.md) | Keep workspace trust in the editor | Explicit project selection and project-bound connections; external tools remain opt-in | No automatic trust from repository settings |
| [MobSF](https://mobsf.github.io/Mobile-Security-Framework-MobSF/) | Distinguish static and dynamic analysis | Separate SAST, supply-chain, DAST, research and AI methods in the catalog/UI; passive ZAP import and a separately authorized HTTP response audit | HTTP audit sends bounded GETs to owned numeric-IP URLs; neither connection starts a target or performs a full mobile dynamic assessment |
| [DefectDojo reimport](https://docs.defectdojo.com/import_data/import_intro/reimport/) and [deduplication](https://docs.defectdojo.com/triage_findings/finding_deduplication/about_deduplication/) | Match findings within a known test context and preserve triage | Opt-in per-project history; compare provider identity/fingerprint across the same operation and known scope | “No longer reported” is not proof of remediation. Approval status changes are not disappearance |
| [Dependency-Track](https://github.com/DependencyTrack/dependency-track) and [CycloneDX VEX](https://cyclonedx.org/capabilities/vex/) | Separate inventory, vulnerability assessment and exploitability assertions | CycloneDX 1.4–1.7 JSON vulnerability import; unresolved component references and inventory-only inputs are visibly incomplete | VEX states supply context; they cannot approve or suppress a host finding |
| [Sonar generic issues](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/importing-external-issues/generic-issue-data) | Open report interchange for additional producers | Legacy and current generic-issue JSON adapters, with severity/path validation | Import does not run Sonar or prove current source coverage |
| [Semgrep policy API](https://semgrep.dev/blog/2025/automating-security-workflows-with-the-semgrep-policy-management-api/) | Separate rule selection from enforcement decisions | Five independently selected local packs; host warning/build/sync grants stay separate | Targeted review rules, not complete Android or backend auditing |
| [ZAP Automation Framework](https://www.zaproxy.org/docs/automate/automation-framework/) | Explicit environments, contexts, authentication, jobs and budgets | Exact origin/path boundaries for passive ZAP import; a separate owned-target HTTP audit reviews URLs, authentication environment references and request/time/response budgets | Report import sends no requests; HTTP audit has no crawling, redirect following, exploits or application startup |
| [Ollama structured outputs](https://docs.ollama.com/capabilities/structured-outputs) and [LM Studio structured output](https://lmstudio.ai/docs/developer/openai-compat/structured-output) | A machine-validated response contract | Strict review JSON schema by default, explicit JSON-object compatibility mode | Loopback only, minimized finding metadata, advisory INFO output; no source/evidence upload or model-selected location/action |

## Shared contract, independent methods

A provider owns how it analyzes. The host owns project selection, enablement, effective
permissions, verdicts, build/sync gates, freshness and presentation. Metadata is additive:
category, summary, tags and declared inputs enrich generic UI without requiring a new
host branch for each vendor. Unknown categories use the custom-provider presentation.

`BUILT_IN` remains reserved. A missing enabled method is incomplete, rather than silently
clean. Existing API 1 integrations and compiled Kotlin provider descriptors retain their
original constructor/copy ABI. Capability discovery and configuration checks do not run
analysis. The CLI conformance command is explicitly an execution check.

Declared source inputs, actual first-party file reads, selected reports, profiles and
external rule/module files participate in host freshness checks. Unknown custom inputs
remain visibly unverified. Direct host scanner programs still run with OS user privileges;
content seals detect changes. The optional Docker backend adds a separate execution
boundary for supported tools, while still trusting the CLI, daemon, image and host kernel.

## Implemented follow-through and remaining limits

Reviewed source-change debounce and interval SCAN automation are implemented. Saving or
enabling leaves automation paused; explicit Start reviews selected providers and their
network/cost declarations. It grants no new authority, runs nothing immediately and does
not launch build/sync operations. Organization preset import/export, offline SDK repair,
connection removal and owned-cache cleanup also have GUI review controls.

The owned-target HTTP audit is implemented with explicit ownership, numeric-IP URLs,
authentication environment-variable names and bounded requests, time and response size.
Actual IDE acceptance recorded zero requests on cancellation and real bounded requests
after authorization. The stricter [Android acceptance](../examples/android-acceptance/README.md#acceptance-status)
now passes runtime/header, owned-UI and rendered-image checks with a recorded agent image
review; human review and physical-phone acceptance remain unperformed.
This is a response-header/cookie audit, not a general active DAST engine.

Five fixed source runners have Windows snapshot/process-boundary CI coverage, and the
GUI checks selected-SDK capabilities before offering a runner. Optional Docker execution
for Bandit/Semgrep and separately authorized advisory container-Python execution are
implemented and have real Linux isolation acceptance. No image is pulled automatically,
and a container failure does not silently fall back to host execution. See
[verification](verification.md) for the exact platform/tool scope.

The [research corpus](../research/README.md) pins 77 malicious, benign and real-project
sources. Static results preserve misses, incomplete/unsupported inputs and selection
bias; local model runs preserve incorrect and injected responses. These measurements
do not establish population accuracy or reproduce a published classifier.

The [VS Code evidence gallery](../demo/screenshots/flexible-provider-platform/vscode/README.md#six-final-ci-host-jobs)
records six passing minimum/current editor jobs and 48 host cases at source `76312ac`.
Formal human screen-reader acceptance, physical-phone evidence, broader independent
detection evaluation and public distribution readiness remain separate acceptance work;
see [roadmap.md](roadmap.md) for current status.
