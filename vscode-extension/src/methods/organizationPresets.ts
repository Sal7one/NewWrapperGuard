import { createHash, randomUUID } from 'node:crypto';
import { lstat, realpath, writeFile, rename, unlink } from 'node:fs/promises';
import { readProjectFile } from '../scanner/safePath';
import * as path from 'node:path';
import { validateMethodFlags } from './catalog';

export interface OrganizationPreset {
  schemaVersion:1; id:string; title:string; version:string; issuer:string;
  createdAt:number; methodFlags:Record<string,boolean>; digest:string;
}
/** Cross-host encoding: sorted object keys, ordinary JSON scalars/arrays, no whitespace. */
export function canonicalPresetJson(value:unknown):string {
  if(Array.isArray(value))return '['+value.map(canonicalPresetJson).join(',')+']';
  if(value&&typeof value==='object')return '{'+Object.keys(value).sort().map(key=>JSON.stringify(key)+':'+canonicalPresetJson((value as Record<string,unknown>)[key])).join(',')+'}';
  return JSON.stringify(value);
}
export function presetDigest(value:Omit<OrganizationPreset,'digest'>):string {
  return createHash('sha256').update(canonicalPresetJson(value),'utf8').digest('hex');
}
export function validateOrganizationPreset(input:unknown):OrganizationPreset {
  if(!input||typeof input!=='object'||Array.isArray(input))throw new Error('Choose a versioned WrapperGuard method preset.');
  const value=input as OrganizationPreset;
  const fields=['schemaVersion','id','title','version','issuer','createdAt','methodFlags','digest'];
  if(Object.keys(value).length!==fields.length||Object.keys(value).some(key=>!fields.includes(key))||value.schemaVersion!==1||
    typeof value.id!=='string'||!/^[a-z][a-z0-9._-]{0,79}$/.test(value.id)||
    [value.title,value.issuer].some(text=>typeof text!=='string'||!text.trim()||text.length>160||/[\u0000-\u001f\u007f]/.test(text))||
    typeof value.version!=='string'||!/^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$/.test(value.version)||value.version.length>80||
    !Number.isSafeInteger(value.createdAt)||value.createdAt<0||typeof value.digest!=='string'||!/^[0-9a-f]{64}$/.test(value.digest))throw new Error('Preset identity, version or digest is invalid. Commands, grants and unknown fields are not accepted.');
  const methodFlags=validateMethodFlags(value.methodFlags);
  if(!Object.keys(methodFlags).length||Object.keys(methodFlags).length>128)throw new Error('Select at least one known method in this preset.');
  const {digest,...payload}={...value,methodFlags};
  if(presetDigest(payload)!==digest)throw new Error('Preset digest does not match its contents.');
  return Object.freeze({...payload,methodFlags:Object.freeze({...methodFlags}),digest});
}
export function createOrganizationPreset(identity:Pick<OrganizationPreset,'id'|'title'|'version'|'issuer'>,methodFlags:Record<string,boolean>,createdAt=Date.now()):OrganizationPreset {
  const payload={schemaVersion:1 as const,...identity,createdAt,methodFlags:{...methodFlags}};
  return validateOrganizationPreset({...payload,digest:presetDigest(payload)});
}
export async function readOrganizationPreset(file:string):Promise<OrganizationPreset> {
  if(!path.isAbsolute(file))throw new Error('Choose an absolute preset file.');
  const anchored=path.join(await realpath(path.dirname(file)),path.basename(file));
  return validateOrganizationPreset(JSON.parse(readProjectFile(path.parse(anchored).root,anchored,65536).toString('utf8')));
}
export async function writeOrganizationPreset(file:string,preset:OrganizationPreset):Promise<void> {
  validateOrganizationPreset(preset);if(!path.isAbsolute(file))throw new Error('Choose an absolute preset destination.');
  const anchored=path.join(await realpath(path.dirname(file)),path.basename(file));
  try{const info=await lstat(anchored);if(!info.isFile()||info.isSymbolicLink())throw new Error('Preset destination must be a regular file.');}catch(error){if((error as NodeJS.ErrnoException).code!=='ENOENT')throw error;}
  const temporary=anchored+'.'+randomUUID()+'.tmp';
  try{await writeFile(temporary,JSON.stringify(preset,null,2)+'\n',{flag:'wx',mode:0o600});await rename(temporary,anchored);}finally{try{await unlink(temporary);}catch{/* Already renamed or never created. */}}
}
