import { readFileSync } from 'node:fs';
import * as path from 'node:path';

export interface SecurityMethod {
  id: string; title: string; summary: string; category: string; defaultEnabled: boolean;
  providerId?: string; availability: string; maturity: string; format?: string; runner?: string;
  docsUrl?: string; research?: unknown;
}
export interface MethodView extends SecurityMethod {
  enabled: boolean; configured: boolean; status: string;
}
const source = JSON.parse(readFileSync(path.join(__dirname, 'security-methods.json'),'utf8')) as {schemaVersion:number;methods:SecurityMethod[]};
if (source.schemaVersion !== 1 || !Array.isArray(source.methods)) throw new Error('Unsupported security method catalog.');
export const SECURITY_METHODS: readonly SecurityMethod[] = Object.freeze(source.methods.map(method => Object.freeze({...method})));
export const BUILTIN_METHOD_IDS = SECURITY_METHODS.filter(method=>method.category === 'BUILT_IN').map(method=>method.id);
export function methodById(id: string): SecurityMethod | undefined { return SECURITY_METHODS.find(method=>method.id === id); }
export function validateMethodFlags(value: unknown): Record<string,boolean> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Security method flags must be an object.');
  const flags: Record<string,boolean> = Object.create(null);
  for (const [id, enabled] of Object.entries(value)) {
    if (!methodById(id) || typeof enabled !== 'boolean') throw new Error('Unknown security method or invalid method flag.');
    flags[id] = enabled;
  }
  return flags;
}
export function methodEnabled(flags: Record<string,boolean> | undefined, id: string): boolean {
  return flags?.[id] ?? methodById(id)?.defaultEnabled ?? false;
}
/** Existing explicitly granted providers retain their grants until a user disables their method. */
export function providerMethodDisabled(flags: Record<string,boolean> | undefined, providerId: string): boolean {
  return SECURITY_METHODS.some(method=>method.category !== 'BUILT_IN' && method.providerId === providerId && flags?.[method.id] === false);
}
