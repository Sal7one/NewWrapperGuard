import * as path from 'node:path';
import { Finding, ScanReport, SourceLocation } from '../models';
import { actionFor } from '../scanner/policy';
import { findingFingerprint } from '../scanner/fingerprints';
import { maskSecretValues } from '../scanner/redaction';
import { DEFAULT_PERMISSIONS, EXTERNAL_PERMISSIONS, ProviderPermissions, ProviderResult, ProviderRun, VettingAction, VettingProvider, VettingRequest, validatePermissions } from './sdk';

let activeCallbacks = 0;
const quarantined = new Map<string,Promise<ProviderResult>>();
type NormalizedProviderResult = Omit<ProviderResult,'findings'> & {findings:Finding[]};

const clean = (s: string): string => {
  if (typeof s !== 'string' || s.length > 65_536) throw new Error('Provider text exceeds its limit.');
  return maskSecretValues(s).masked;
};
function location(value: SourceLocation, root?: string): SourceLocation {
  if (!value || typeof value.path !== 'string' || value.path.length > 4096 || value.path.includes('\0')) throw new Error('Invalid provider location.');
  if ((value.line !== undefined && (!Number.isInteger(value.line) || value.line < 1)) ||
      (value.column !== undefined && (!Number.isInteger(value.column) || value.column < 1))) throw new Error('Invalid provider location.');
  let file = value.path.replace(/\\/g, '/');
  if (root) {
    if (!file.trim() || /^[a-z]:/i.test(file) && !path.isAbsolute(file)) throw new Error('Invalid provider location.');
    const relative = path.relative(path.resolve(root),path.resolve(root,file));
    if (relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) throw new Error('Provider location is outside the workspace.');
    file = relative.replace(/\\/g, '/') || '.';
  }
  return { path: file, line: value.line, column: value.column };
}
function normalize(result: ProviderResult, id: string, permissions: ProviderPermissions, request: VettingRequest): NormalizedProviderResult {
  if (!result || !Array.isArray(result.findings) || result.findings.length > 50_000 ||
      (result.complete !== undefined && typeof result.complete !== 'boolean') ||
      (result.scannedFiles !== undefined && (!Number.isSafeInteger(result.scannedFiles) || result.scannedFiles < 0))) throw new Error('Invalid provider result.');
  const findings = result.findings.map(providerInput => {
    if (!providerInput || typeof providerInput !== 'object') throw new Error('Invalid provider finding.');
    const input:Finding={
      ...providerInput,
      fingerprint:providerInput.fingerprint ?? '',
      kind:providerInput.kind === undefined ? 'ACTIONABLE' : providerInput.kind,
      reachable:providerInput.reachable === undefined ? true : providerInput.reachable,
      callChain:providerInput.callChain === undefined ? [] : providerInput.callChain,
      policyAction:'REVIEW',status:'ACTIVE'
    };
    if (!input || typeof input.ruleId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}$/.test(input.ruleId) ||
        !['INFO', 'WARN', 'ERROR'].includes(input.severity) || !['LOW', 'MEDIUM', 'HIGH'].includes(input.confidence) ||
        !['ACTIONABLE', 'AUDIT_EVIDENCE', 'SCANNER_NOTICE'].includes(input.kind) || typeof input.reachable !== 'boolean' ||
        [input.title,input.message,input.category].some(v => typeof v !== 'string')) throw new Error('Invalid provider finding.');
    if (input.actions !== undefined && (!Array.isArray(input.actions) || input.actions.some(a => !['WARNING','MAJOR_WARNING','ALERT','BLOCK_BUILD','BLOCK_SYNC'].includes(a)))) throw new Error('Invalid requested actions.');
    const locate = (value:SourceLocation):SourceLocation => location(value,id === 'wrapperguard' ? undefined : request.projectRoot);
    const loc = locate(input.location);
    const ruleId = input.ruleId;
    const evidenceText = input.evidence ? clean(input.evidence.text) : undefined;
    const evidence = input.evidence ? { text: evidenceText!, redacted:input.evidence.redacted || evidenceText !== input.evidence.text } : undefined;
    if (input.mlSignal && (!Number.isFinite(input.mlSignal.score) || !Array.isArray(input.mlSignal.labels) || input.mlSignal.labels.length > 1000)) throw new Error('Invalid ML observation.');
    if (!Array.isArray(input.callChain ?? []) || (input.callChain ?? []).length > 1000) throw new Error('Invalid provider call chain.');
    const finding: Finding = {
      fingerprint: id === 'wrapperguard' ? input.fingerprint : findingFingerprint(id + ':' + ruleId, loc.path, input.evidence?.text ?? input.title + '\n' + input.message),
      ruleId, title: clean(input.title), message: clean(input.message), severity: input.severity, confidence: input.confidence,
      category: clean(input.category), kind: input.mlSignal || /(^|[^a-z])(ml|machine.learning|shadow)([^a-z]|$)/i.test(input.category) ? 'AUDIT_EVIDENCE' : input.kind,
      location: {...loc,path:clean(loc.path)}, evidence, remediation: input.remediation ? clean(input.remediation) : undefined,
      mlSignal:input.mlSignal ? {score:input.mlSignal.score,modelId:clean(input.mlSignal.modelId),labels:input.mlSignal.labels.map(clean)} : undefined,
      reachable: input.reachable, callChain: (input.callChain ?? []).map(c => {const source=locate(c.source);return { source:{...source,path:clean(source.path)},viaCommand:clean(c.viaCommand) };}),
      policyAction: 'ALLOW', status: 'ACTIVE', providerId: id, actions: []
    };
    // A provider cannot forge a baseline approval, provenance, policy action, or another provider's identity.
    const approved = request.baseline.some(b => b.fingerprint === finding.fingerprint);
    finding.status = approved ? 'SUPPRESSED' : 'ACTIVE';
    const hostAction = actionFor(request.policy, finding);
    const defaultRequests: VettingAction[] = finding.kind === 'ACTIONABLE' && input.severity !== 'INFO' ?
      [...(input.severity === 'ERROR' ? ['MAJOR_WARNING' as const] : ['WARNING' as const]),...(hostAction === 'BLOCK' ? ['BLOCK_BUILD' as const,'BLOCK_SYNC' as const] : [])] : [];
    const requested = input.mlSignal ? [] : input.actions ?? (id === 'wrapperguard' ? defaultRequests : []);
    finding.actions = [...new Set(requested.filter(a => permissions.actions.includes(a)))].filter(a => {
      if (a === 'BLOCK_BUILD') return !approved && hostAction === 'BLOCK' && request.operation !== 'SYNC';
      if (a === 'BLOCK_SYNC') return !approved && hostAction === 'BLOCK' && request.operation !== 'BUILD';
      return true;
    });
    const blocksOperation = finding.actions.some(a => a === 'BLOCK_BUILD' && request.operation !== 'SYNC' || a === 'BLOCK_SYNC' && request.operation !== 'BUILD');
    finding.policyAction = approved ? 'ALLOW' : blocksOperation ? 'BLOCK' : hostAction === 'ALLOW' ? 'ALLOW' : finding.actions.length ? 'REVIEW' : 'ALLOW';
    return finding;
  });
  const edges = result.edges ?? [];
  if (!Array.isArray(edges) || edges.length > 100_000) throw new Error('Invalid provider edges.');
  const notices = result.notices ?? [];
  if (!Array.isArray(notices) || notices.length > 1000) throw new Error('Invalid provider notices.');
  const safeLocation=(value:SourceLocation):SourceLocation => { const loc=location(value,id === 'wrapperguard' ? undefined : request.projectRoot);return {...loc,path:clean(loc.path)}; };
  return { findings, scannedFiles: result.scannedFiles ?? 0, complete: result.complete ?? true,
    edges: edges.map(e => ({ from:safeLocation(e.from),to:safeLocation(e.to),viaCommand:clean(e.viaCommand) })),
    notices: notices.map(n => ({ code:clean(n.code),message:clean(n.message),location:n.location ? safeLocation(n.location) : undefined })) };
}
export interface CoordinatorOptions {
  providers: VettingProvider[]; permissions?: Record<string, ProviderPermissions>; concurrency?: number;
}
/** Bounded provider work with cooperative SDK cancellation and isolated failures. Native analysis stays in process. */
export async function evaluateProviders(request: VettingRequest, options: CoordinatorOptions): Promise<ScanReport> {
  if (!['SCAN','BUILD','SYNC'].includes(request.operation)) throw new Error('Invalid vetting operation.');
  const start = Date.now();
  const providers = [...options.providers];
  if (providers.length > 33 || providers.filter(p => p.descriptor.id !== 'wrapperguard').length > 32) throw new Error('Maximum provider count reached.');
  if (new Set(providers.map(p => p.descriptor.id)).size !== providers.length) throw new Error('Duplicate provider identity.');
  const results: Array<{run: ProviderRun; result?: NormalizedProviderResult}> = new Array(providers.length);
  let next = 0;
  async function worker(): Promise<void> {
    for (;;) {
      const index = next++;
      if (index >= providers.length) return;
      const provider = providers[index];
      const permissions = validatePermissions(options.permissions?.[provider.descriptor.id] ?? (provider.descriptor.id === 'wrapperguard' ? DEFAULT_PERMISSIONS : EXTERNAL_PERMISSIONS));
      const run: ProviderRun = { descriptor: {...provider.descriptor,displayName:clean(provider.descriptor.displayName),version:clean(provider.descriptor.version)}, permissions, status: 'DISABLED', durationMs: 0, findingCount: 0 };
      const entry: {run: ProviderRun; result?: NormalizedProviderResult} = {run}; results[index] = entry;
      if (!permissions.enabled) continue;
      const remaining = permissions.timeoutMs - (Date.now() - start);
      if (remaining <= 0) {
        run.status='TIMED_OUT';run.durationMs=Date.now()-start;run.message='Provider deadline expired while waiting for execution capacity.';
        continue;
      }
      const controller = new AbortController();
      let timedOut = false;
      let timer: NodeJS.Timeout | undefined;
      let invocation: Promise<ProviderResult> | undefined;
      let invocationFinished = false;
      const began = start;
      try {
        const cancellation = new Promise<never>((_, reject) => {
          timer = setTimeout(() => { timedOut = true; controller.abort(); reject(new Error('timeout')); }, remaining);
          controller.signal.addEventListener('abort',() => { reject(new Error('cancelled')); },{once:true});
        });
        const cancel = (): void => controller.abort();
        request.signal.addEventListener('abort', cancel, { once: true });
        try {
          if (request.signal.aborted) controller.abort();
          const safeRequest = { ...structuredClone({ ...request, signal: undefined }), signal: controller.signal } as VettingRequest;
          if (provider.descriptor.id !== 'wrapperguard') safeRequest.baseline = [];
          // Scheduling each provider makes the concurrency cap deterministic; synchronous SDK code must honor its own budgets.
          invocation = Promise.resolve().then(() => {
            if (controller.signal.aborted) throw new Error('cancelled');
            if (quarantined.has(provider.descriptor.id) || activeCallbacks >= 32) throw new Error('Provider execution capacity is unavailable.');
            activeCallbacks++;
            return Promise.resolve().then(() => provider.scan(safeRequest)).finally(() => { activeCallbacks--; });
          }).finally(() => {
            invocationFinished = true;
            if (quarantined.get(provider.descriptor.id) === invocation) quarantined.delete(provider.descriptor.id);
          });
          const result = await Promise.race([invocation,cancellation]);
          if (Date.now() - began > permissions.timeoutMs) { timedOut = true; controller.abort(); throw new Error('timeout'); }
          entry.result = normalize(result, provider.descriptor.id, permissions, request);
          run.status = 'COMPLETED'; run.findingCount = entry.result.findings.length;
          if (entry.result.complete === false) run.message = 'Provider reported an incomplete scan.';
        } finally { request.signal.removeEventListener('abort', cancel); }
      } catch (error) {
        if (error instanceof Error && error.name === 'TimeoutError') timedOut = true;
        run.status = timedOut ? 'TIMED_OUT' : 'FAILED';
        run.message = timedOut ? 'Provider exceeded its timeout.' : 'Provider failed or returned an invalid result.';
        controller.abort();
        if (invocation && !invocationFinished) quarantined.set(provider.descriptor.id,invocation);
      } finally { clearTimeout(timer); run.durationMs = Date.now() - began; }
    }
  }
  await Promise.all(Array.from({length: Math.min(providers.length, Math.max(1,Math.min(8, options.concurrency ?? 4)))}, () => worker()));
  const findings = results.flatMap(r => r.result?.findings ?? []);
  const notices = results.flatMap(r => r.result?.notices ?? []);
  const complete = results.some(r => r.run.status === 'COMPLETED') && results.every(r => r.run.status === 'DISABLED' || r.run.status === 'COMPLETED' && r.result?.complete !== false);
  for (const {run,result} of results) {
    if (run.status !== 'FAILED' && run.status !== 'TIMED_OUT' && result?.complete !== false) continue;
    notices.push({ code: result?.complete === false ? 'WG-PROVIDER-INCOMPLETE' : run.status === 'TIMED_OUT' ? 'WG-PROVIDER-TIMEOUT' : 'WG-PROVIDER-FAILED', message: run.descriptor.id + ': ' + run.message });
    const granted = run.permissions.actions.filter(a => a === 'BLOCK_BUILD' && request.operation !== 'SYNC' || a === 'BLOCK_SYNC' && request.operation !== 'BUILD');
    if (run.permissions.required && request.policy.mode === 'ENFORCE' && granted.length) findings.push({
      fingerprint: findingFingerprint(run.descriptor.id + ':WG-PROVIDER-UNAVAILABLE', '.', 'required-provider-unavailable'),
      providerId: run.descriptor.id, ruleId:'WG-PROVIDER-UNAVAILABLE',title:'Required vetting provider unavailable',
      message:run.descriptor.displayName + ' could not complete the required vetting operation.',
      severity:'ERROR',confidence:'HIGH',category:'CONFIGURATION',kind:'ACTIONABLE',location:{path:'.'},reachable:true,
      policyAction: 'BLOCK', status: 'ACTIVE', actions: granted
    });
  }
  if (!results.some(r => r.run.status !== 'DISABLED')) notices.push({ code: 'WG-NO-PROVIDERS', message: 'No vetting providers are enabled.' });
  const blocked = findings.some(f => f.status === 'ACTIVE' && f.policyAction === 'BLOCK');
  const review = findings.some(f => f.status === 'ACTIVE' && f.policyAction === 'REVIEW');
  return { schemaVersion: 1, projectPath: clean(request.projectRoot), policyName: clean(request.policy.name),
    verdict: blocked ? 'EXECUTION_BLOCKED' : !complete ? 'INCOMPLETE' : review ? 'REVIEW_REQUIRED' : 'CLEAN',
    findings, edges: results.flatMap(r => r.result?.edges ?? []), scannedFiles: results.reduce((n,r) => n + (r.result?.scannedFiles ?? 0), 0),
    durationMs: Date.now() - start, complete, stale: false, notices, generatedAtEpochMs: Date.now(), providerRuns: results.map(r => r.run) };
}
