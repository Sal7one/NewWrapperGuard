import { BaselineEntry, Finding, ScanNotice, ScanPolicy, ScanReport, ScanSettings, ScriptEdge } from '../models';
import { ProviderInputScope, validateInputScope } from './inputScope';

export const VETTING_ACTIONS = ['WARNING', 'MAJOR_WARNING', 'ALERT', 'BLOCK_BUILD', 'BLOCK_SYNC'] as const;
export type VettingAction = typeof VETTING_ACTIONS[number];
export type VettingOperation = 'SCAN' | 'BUILD' | 'SYNC';
export interface ProviderDescriptor { id: string; displayName: string; version: string; apiVersion: 1; category?:string; summary?:string; tags?:string[]; inputScope?:ProviderInputScope }
export interface ProviderPermissions { enabled: boolean; actions: VettingAction[]; required: boolean; timeoutMs: number }
export interface VettingRequest {
  projectRoot: string; policy: ScanPolicy; settings: ScanSettings; baseline: BaselineEntry[];
  operation: VettingOperation; signal: AbortSignal;
}
/** Provider input omits host-owned report state; supplied host fields are ignored for external providers. */
export type ProviderFinding = Pick<Finding,'ruleId'|'title'|'message'|'severity'|'confidence'|'category'|'location'> &
  Partial<Omit<Finding,'ruleId'|'title'|'message'|'severity'|'confidence'|'category'|'location'>>;
export interface ProviderResult {
  findings: ProviderFinding[]; edges?: ScriptEdge[]; scannedFiles?: number; complete?: boolean; notices?: ScanNotice[];
}
export interface VettingProvider {
  descriptor: ProviderDescriptor;
  scan(request: VettingRequest): ProviderResult | Promise<ProviderResult>;
}
export interface ProviderRun {
  descriptor: ProviderDescriptor; permissions: ProviderPermissions;
  status: 'COMPLETED' | 'FAILED' | 'TIMED_OUT' | 'DISABLED';
  durationMs: number; findingCount: number; message?: string;
}
export interface VettingApi {
  readonly apiVersion: 1;
  registerProvider(provider: VettingProvider): { dispose(): void };
  evaluate(operation?: VettingOperation, projectRoot?:string): Promise<ScanReport>;
  gate(operation: 'BUILD' | 'SYNC', projectRoot?:string): Promise<{ allowed: boolean; report: ScanReport }>;
}
export const DEFAULT_PERMISSIONS: ProviderPermissions = {
  enabled: true, actions: [...VETTING_ACTIONS], required: true, timeoutMs: 30_000
};
export const EXTERNAL_PERMISSIONS: ProviderPermissions = {
  enabled: false, actions: [], required: false, timeoutMs: 30_000
};
export function validateDescriptor(value: ProviderDescriptor): ProviderDescriptor {
  if (!value || typeof value !== 'object' || Object.keys(value).some(k => !['id', 'displayName', 'version', 'apiVersion','category','summary','tags','inputScope'].includes(k)) ||
      typeof value.id !== 'string' || !/^[a-z][a-z0-9._-]{0,79}$/.test(value.id) || value.apiVersion !== 1 ||
      typeof value.displayName !== 'string' || !value.displayName.trim() || value.displayName.length > 200 || /[\u0000-\u001f\u007f-\u009f]/.test(value.displayName) ||
      typeof value.version !== 'string' || !value.version.trim() || value.version.length > 80 || /[\u0000-\u001f\u007f-\u009f]/.test(value.version)) {
    throw new Error('Invalid provider descriptor (API version 1 required).');
  }
  if (value.category !== undefined && (typeof value.category !== 'string' || !/^[A-Z][A-Z0-9_]{0,31}$/.test(value.category) || value.category === 'BUILT_IN')) throw new Error('Invalid provider category.');
  if (value.summary !== undefined && (typeof value.summary !== 'string' || value.summary.length>512 || /[\u0000-\u001f\u007f]/.test(value.summary))) throw new Error('Invalid provider summary.');
  if (value.tags !== undefined && (!Array.isArray(value.tags) || value.tags.length>16 || new Set(value.tags).size!==value.tags.length || value.tags.some(tag=>typeof tag !== 'string' || !/^[a-z0-9][a-z0-9._-]{0,63}$/.test(tag)))) throw new Error('Invalid provider tags.');
  const scope=value.inputScope===undefined?undefined:validateInputScope(value.inputScope);
  if(scope){for(const list of Object.values(scope))Object.freeze(list);Object.freeze(scope);}
  return Object.freeze({ ...value,...(value.tags ? {tags:Object.freeze([...value.tags]) as unknown as string[]} : {}),...(scope ? {inputScope:scope} : {}) });
}
export function validatePermissions(value: ProviderPermissions): ProviderPermissions {
  if (!value || typeof value !== 'object' || Object.keys(value).some(k => !['enabled', 'actions', 'required', 'timeoutMs'].includes(k)) ||
      typeof value.enabled !== 'boolean' || typeof value.required !== 'boolean' || !Array.isArray(value.actions) ||
      value.actions.some(a => !VETTING_ACTIONS.includes(a)) || new Set(value.actions).size !== value.actions.length ||
      !Number.isInteger(value.timeoutMs) || value.timeoutMs < 100 || value.timeoutMs > 300_000) {
    throw new Error('Invalid provider permissions (timeout must be 100–300000 ms).');
  }
  return { ...value, actions: [...value.actions] };
}
/** Registration conveys identity, never permission. Permission changes belong to the user. */
export class ProviderRegistry {
  private readonly providers = new Map<string, VettingProvider>();
  constructor(builtIn?: VettingProvider) { if (builtIn) this.add(builtIn, true); }
  private add(provider: VettingProvider, builtIn: boolean): { dispose(): void } {
    const descriptor = validateDescriptor(provider.descriptor);
    if (typeof provider.scan !== 'function' || (!builtIn && descriptor.id === 'wrapperguard')) throw new Error('Reserved or invalid provider.');
    if (this.providers.has(descriptor.id)) throw new Error('Provider ID already registered: ' + descriptor.id);
    if (this.providers.size >= 33 || !this.providers.has('wrapperguard') && this.providers.size >= 32) throw new Error('Maximum provider count reached.');
    const registered = { descriptor, scan: provider.scan.bind(provider) };
    this.providers.set(descriptor.id, registered);
    return { dispose: () => { if (this.providers.get(descriptor.id) === registered) this.providers.delete(descriptor.id); } };
  }
  registerProvider(provider: VettingProvider): { dispose(): void } { return this.add(provider, false); }
  list(): VettingProvider[] { return [...this.providers.values()]; }
}
