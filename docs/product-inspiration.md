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
| [MobSF](https://mobsf.github.io/Mobile-Security-Framework-MobSF/) | Distinguish static and dynamic analysis | Separate SAST, supply-chain, DAST, research and AI methods in the catalog/UI | Current DAST integration imports scoped ZAP reports; it does not launch a target or active scan |
| [DefectDojo reimport](https://docs.defectdojo.com/import_data/import_intro/reimport/) and [deduplication](https://docs.defectdojo.com/triage_findings/finding_deduplication/about_deduplication/) | Match findings within a known test context and preserve triage | Opt-in per-project history; compare provider identity/fingerprint across the same operation and known scope | “No longer reported” is not proof of remediation. Approval status changes are not disappearance |
| [Dependency-Track](https://github.com/DependencyTrack/dependency-track) and [CycloneDX VEX](https://cyclonedx.org/capabilities/vex/) | Separate inventory, vulnerability assessment and exploitability assertions | CycloneDX 1.4–1.7 JSON vulnerability import; unresolved component references and inventory-only inputs are visibly incomplete | VEX states supply context; they cannot approve or suppress a host finding |
| [Sonar generic issues](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/importing-external-issues/generic-issue-data) | Open report interchange for additional producers | Legacy and current generic-issue JSON adapters, with severity/path validation | Import does not run Sonar or prove current source coverage |
| [Semgrep policy API](https://semgrep.dev/blog/2025/automating-security-workflows-with-the-semgrep-policy-management-api/) | Separate rule selection from enforcement decisions | Five independently selected local packs; host warning/build/sync grants stay separate | Targeted review rules, not complete Android or backend auditing |
| [ZAP Automation Framework](https://www.zaproxy.org/docs/automate/automation-framework/) | Explicit environments, contexts, authentication, jobs and budgets | Exact origin/path boundaries for passive report import; active automation remains a separate roadmap feature | Do not start an active scan from a report-import connection |
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
remain visibly unverified. Installed scanner programs still run with OS user privileges;
content seals are change detectors, not operating-system isolation or signatures.

## Deliberate next steps

Active DAST needs a reviewed environment, authentication/secrets handling, explicit target
ownership, request/time budgets, redirect containment and cancellation acceptance before
it joins the launcher GUI. Stronger process isolation, safe Windows snapshot runners,
real-world corpus evaluation and optional scheduling remain independent deliverables.
These are tracked in [roadmap.md](roadmap.md), not implied by the references above.
