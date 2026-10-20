import { readFileSync } from 'node:fs';
import * as path from 'node:path';
import { SECURITY_METHODS, validateMethodFlags } from './catalog';
export interface SecurityPreset {id:string;title:string;summary:string;methodFlags:Record<string,boolean>;notice:string}
const source=JSON.parse(readFileSync(path.join(__dirname,'security-presets.json'),'utf8')) as {schemaVersion:number;presets:SecurityPreset[]};
if(source.schemaVersion!==1||!Array.isArray(source.presets)||source.presets.length>32)throw new Error('Unsupported security preset catalog.');
const ids=new Set<string>();
export const SECURITY_PRESETS:readonly SecurityPreset[]=Object.freeze(source.presets.map(preset=>{
  if(!/^[a-z][a-z0-9-]{0,79}$/.test(preset.id)||ids.has(preset.id)||[preset.title,preset.summary,preset.notice].some(value=>typeof value!=='string'||!value||value.length>1024))throw new Error('Invalid security preset.');
  ids.add(preset.id);const flags=validateMethodFlags(preset.methodFlags);if(Object.keys(flags).length!==SECURITY_METHODS.length)throw new Error('A security preset must explicitly select every known method.');
  return Object.freeze({...preset,methodFlags:Object.freeze(flags)});
}));
