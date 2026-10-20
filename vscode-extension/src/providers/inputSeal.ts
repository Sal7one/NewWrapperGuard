import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { ProviderInputScope, globMatches, segmentMatches, scopeExcludes, validateInputScope } from './inputScope';
import { readProjectFile, checkedProjectDirectory } from '../scanner/safePath';

export interface InputSealLimits { maxFiles:number; maxFileBytes:number; maxExternalFileBytes:number; maxTotalBytes:number; maxDepth:number; maxEntries:number; timeoutMs:number }
export const DEFAULT_SEAL_LIMITS:InputSealLimits={maxFiles:5000,maxFileBytes:1_048_576,maxExternalFileBytes:16_777_216,maxTotalBytes:67_108_864,maxDepth:64,maxEntries:50_000,timeoutMs:30_000};
export interface InputSeal { projectRoot:string; hashes:Record<string,string>; totalBytes:number }
const failure=():Error=>new Error('Declared provider inputs could not be sealed within the configured trust and resource limits.');
function within(file:string,root:string):boolean {const relative=path.relative(root,file);return relative==='' || relative!=='..'&&!relative.startsWith('..'+path.sep)&&!path.isAbsolute(relative);}
/** Does a directory remain a possible prefix of a match? Never follow an unrelated tree. */
function relevant(pattern:string,directory:string):boolean {
  const pieces=pattern.split('/'),parts=directory.split('/');const memo=new Map<string,boolean>();
  const visit=(patternIndex:number,directoryIndex:number):boolean=>{
    const key=patternIndex+':'+directoryIndex;if(memo.has(key))return memo.get(key)!;
    if(directoryIndex===parts.length)return patternIndex<pieces.length;
    if(patternIndex===pieces.length)return false;
    const result=pieces[patternIndex]==='**' ? visit(patternIndex+1,directoryIndex)||visit(patternIndex,directoryIndex+1) : segmentMatches(pieces[patternIndex],parts[directoryIndex])&&visit(patternIndex+1,directoryIndex+1);
    memo.set(key,result);return result;
  };
  return visit(0,0);
}
/** Bounded content equality, including additions and deletions. No command or tool executes. */
export function captureInputSeal(projectRoot:string,scopes:readonly ProviderInputScope[],workspaceRoots:readonly string[]=[projectRoot],options:Partial<InputSealLimits>={}):InputSeal {
  try {
    const limits={...DEFAULT_SEAL_LIMITS,...options};
    if(Object.values(limits).some(value=>!Number.isSafeInteger(value)||value<1))throw failure();
    const root=fs.realpathSync(projectRoot),roots=workspaceRoots.map(file=>fs.realpathSync(file));
    const declared=scopes.map(validateInputScope);const hashes:Record<string,string>=Object.create(null);let totalBytes=0,entries=0;const start=Date.now();
    const check=():void=>{if(Date.now()-start>limits.timeoutMs)throw failure();};
    const add=(key:string,file:string,external:boolean):void=>{
      if(hashes[key]!==undefined)return;
      if(Object.keys(hashes).length>=limits.maxFiles)throw failure();
      check();const bytes=readProjectFile(external?path.parse(file).root:root,file,external?limits.maxExternalFileBytes:limits.maxFileBytes);
      totalBytes+=bytes.length;if(totalBytes>limits.maxTotalBytes)throw failure();
      hashes[key]=createHash('sha256').update(bytes).digest('hex');check();
    };
    const included=(relative:string):boolean=>declared.some(scope=>(scope.includes??[]).some(pattern=>globMatches(pattern,relative))&&!scopeExcludes(scope.excludes??[],relative));
    const mayVisit=(relative:string):boolean=>declared.some(scope=>(scope.includes??[]).some(pattern=>relevant(pattern,relative))&&!scopeExcludes(scope.excludes??[],relative)&&!(scope.excludes??[]).some(pattern=>globMatches(pattern,relative+'/')));
    const walk=(directory:string,depth:number):void=>{
      check();if(!checkedProjectDirectory(root,directory))throw failure();
      const children=fs.readdirSync(directory,{withFileTypes:true}).sort((a,b)=>a.name.localeCompare(b.name));
      for(const entry of children){
        if(++entries>limits.maxEntries)throw failure();check();const file=path.join(directory,entry.name),relative=path.relative(root,file).split(path.sep).join('/');
        if(entry.isSymbolicLink()){if(included(relative)||mayVisit(relative))throw failure();continue;}
        if(entry.isDirectory()){if(!mayVisit(relative))continue;if(depth>=limits.maxDepth)throw failure();walk(file,depth+1);}
        else if(included(relative)){if(!entry.isFile())throw failure();add('project:'+relative,file,false);}
      }
    };
    if(declared.some(scope=>(scope.includes??[]).length))walk(root,0);
    for(const file of new Set(declared.flatMap(scope=>scope.externalFiles??[]))){
      // Canonical paths are stored at setup. Changed aliases/links never expand the read set.
      const original=path.resolve(file);if(roots.some(base=>within(original,base)) || roots.some(base=>within(fs.realpathSync(file),base)))throw failure();
      add('external:'+original,original,true);
    }
    return{projectRoot:root,hashes,totalBytes};
  }catch{throw failure();}
}
export function sealsEqual(before:InputSeal,after:InputSeal):boolean {
  return before.projectRoot===after.projectRoot && before.totalBytes===after.totalBytes && Object.keys(before.hashes).length===Object.keys(after.hashes).length && Object.entries(before.hashes).every(([key,value])=>after.hashes[key]===value);
}
