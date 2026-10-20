import * as path from 'node:path';

/** A declared read set improves freshness checks; it is not a process sandbox. */
export interface ProviderInputScope { includes?: string[]; excludes?: string[]; externalFiles?: string[] }
export const GENERATED_EXCLUDES=['**/.git/**','**/node_modules/**','**/.gradle/**','**/build/**','**/dist/**','**/.venv/**','**/venv/**','**/__pycache__/**'];
export function validateGlob(value: string): string {
  if (typeof value !== 'string' || !value || value.length > 512 || value.includes('\\') || /^[\/]|^[A-Za-z]:/.test(value) || /[\u0000-\u001f\u007f:\[\]{}!]/.test(value) || value.split('/').some(part=>!part || part === '.' || part === '..' || part.includes('**') && part !== '**')) throw new Error('Use relative forward-slash globs with *, ? or a whole ** segment.');
  return value;
}
export function validateInputScope(value: unknown): ProviderInputScope {
  if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(key=>!['includes','excludes','externalFiles'].includes(key))) throw new Error('Invalid provider input scope.');
  const input=value as Record<string,unknown>;
  const output:ProviderInputScope={};
  for (const key of ['includes','excludes','externalFiles'] as const) {
    if (input[key] === undefined) continue;
    const list=input[key];
    if (!Array.isArray(list) || list.length>128 || new Set(list).size!==list.length || list.some(item=>typeof item !== 'string')) throw new Error('Input lists require at most 128 distinct paths or patterns.');
    output[key]=list.map(item=>{
      if (key !== 'externalFiles') return validateGlob(item);
      if (!path.isAbsolute(item) || item.length>4096 || /[\u0000-\u001f\u007f]/.test(item)) throw new Error('External inputs require absolute file paths.');
      return item;
    });
  }
  return output;
}
export function globMatches(pattern:string,relative:string):boolean {
  validateGlob(pattern);
  const segments=pattern.split('/'),parts=relative.split('/');const memo=new Map<string,boolean>();
  const visit=(i:number,j:number):boolean=>{
    const key=i+':'+j;if(memo.has(key))return memo.get(key)!;
    const result=i===segments.length ? j===parts.length : segments[i]==='**' ? visit(i+1,j)||(j<parts.length&&visit(i,j+1)) : j<parts.length&&segmentMatches(segments[i],parts[j])&&visit(i+1,j+1);
    memo.set(key,result);return result;
  };
  return visit(0,0);
}
export function segmentMatches(pattern:string,text:string):boolean {
  let previous=new Uint8Array(text.length+1);previous[0]=1;
  for(const char of pattern){const current=new Uint8Array(text.length+1);if(char==='*')current[0]=previous[0];for(let j=1;j<=text.length;j++)current[j]=char==='*' ? Number(!!(previous[j]||current[j-1])) : Number(!!previous[j-1]&&(char==='?'||char===text[j-1]));previous=current;}
  return !!previous[text.length];
}
export function scopeExcludes(patterns:readonly string[],relative:string):boolean {
  const parts=relative.split('/');return parts.some((_,index)=>patterns.some(pattern=>globMatches(pattern,parts.slice(0,index+1).join('/'))));
}
