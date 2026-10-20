import * as fs from 'node:fs';
import * as path from 'node:path';
import { AsyncLocalStorage } from 'node:async_hooks';

const readObserver=new AsyncLocalStorage<(root:string,file:string,bytes:Buffer)=>void>();
/** Host-only read tracking includes recursive inputs whose extensions are not known in advance. */
export function observeProjectReads<T>(observer:(root:string,file:string,bytes:Buffer)=>void,work:()=>T):T{return readObserver.run(observer,work);}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^$|(){}\[\]\\]/g, '\\$&');
}

function replaceIgnoreCase(text: string, key: string, value: string): string {
  return text.split(new RegExp(escapeRegExp(key), 'i')).join(value);
}

function expandVariables(raw: string, referrer: string): string {
  let expanded = raw.trim().replace(/^["'\u0060]+|["'\u0060]+$/g, '');
  const refDir = path.dirname(referrer);
  const unix: Array<[string, string]> = [
    ['$0', referrer],
    ['$' + '{BASH_SOURCE[0]}', referrer],
    ['$' + '{BASH_SOURCE}', referrer],
    ['$WRAPPER_DIR', refDir],
    ['$BASEDIR', refDir]
  ];
  for (const [k, v] of unix) {
    expanded = expanded.split(k).join(v);
  }
  expanded = replaceIgnoreCase(expanded, '%~dp0', refDir + '\\');
  expanded = replaceIgnoreCase(expanded, '%CD%', refDir);
  expanded = expanded.replace(/\$\((?:cd\s+)?["']?dirname["']?\s+["']?\$0["']?\)/g, refDir);
  return expanded.trim().replace(/^["']+|["']+$/g, '');
}

function isUnderProjectRoot(projectRoot: string, file: string): boolean {
  const root = path.normalize(projectRoot);
  const f = path.normalize(file);
  return f === root || f.startsWith(root.endsWith(path.sep) ? root : root + path.sep);
}

function normalizePath(projectRoot: string, expanded: string, referrer: string): string | null {
  try {
    const cleaned = expanded.replace(/\\/g, '/');
    const p = path.posix.isAbsolute(cleaned) ? cleaned : path.join(path.dirname(referrer), cleaned);
    const resolved = path.normalize(p);
    if (!isUnderProjectRoot(projectRoot, resolved)) return null;
    return resolved;
  } catch {
    return null;
  }
}

function isTextFile(projectRoot: string, file: string, maximum: number): boolean {
  try {
    const bytes = readProjectFile(projectRoot,file,maximum);
    return !bytes.includes(0);
  } catch {
    return false;
  }
}

/**
 * Faithful port of SafePathResolver: expand variables, normalize, require existence,
 * in-root containment, size limit, and text content. Returns absolute path or null.
 */
export function resolveScriptPath(projectRoot: string, referrer: string, rawPath: string, maxFileBytes: number): string | null {
  try {
    const expanded = expandVariables(rawPath, referrer);
    const resolved = normalizePath(projectRoot, expanded, referrer);
    if (!resolved) return null;
    if (!checkedProjectFile(projectRoot,resolved)) return null;
    if (!isUnderProjectRoot(projectRoot, resolved)) return null;
    if (fs.statSync(resolved).size > maxFileBytes) return null;
    if (!isTextFile(projectRoot,resolved,maxFileBytes)) return null;
    return resolved;
  } catch {
    return null;
  }
}

/** Reject links below an explicitly selected root, including contained links. */
function checkedProjectPath(projectRoot:string,file:string,directory:boolean):string|undefined {
  const root=fs.realpathSync(projectRoot);
  const lexicalRoot=path.resolve(projectRoot);
  const relative=path.relative(lexicalRoot,path.resolve(file));
  if (!relative && directory) return root;
  if (!relative || relative === '..' || relative.startsWith('..'+path.sep) || path.isAbsolute(relative)) throw new Error('Unsafe project input.');
  let current=root;
  const parts=relative.split(path.sep);
  for (let index=0;index<parts.length;index++) {
    current=path.join(current,parts[index]);
    let info:fs.Stats;
    try {info=fs.lstatSync(current);}catch(error){if((error as NodeJS.ErrnoException).code === 'ENOENT' || (error as NodeJS.ErrnoException).code === 'ENOTDIR')return undefined;throw error;}
    if (info.isSymbolicLink()) throw new Error('Unsafe project input.');
    if (index<parts.length-1 && !info.isDirectory()) return undefined;
    if (index===parts.length-1 && (directory ? !info.isDirectory() : !info.isFile())) throw new Error('Unsafe project input.');
  }
  if (!isUnderProjectRoot(root,fs.realpathSync(current))) throw new Error('Unsafe project input.');
  return current;
}
export function checkedProjectFile(projectRoot:string,file:string):string|undefined {return checkedProjectPath(projectRoot,file,false);}
export function checkedProjectDirectory(projectRoot:string,directory:string):string|undefined {return checkedProjectPath(projectRoot,directory,true);}

/** Bounded descriptor reads do not interpret file data or follow a replaced leaf link. */
export function readProjectFile(projectRoot:string,file:string,maximum:number):Buffer {
  const checked=checkedProjectFile(projectRoot,file);
  if (!checked) throw new Error('Missing project input.');
  const fd=fs.openSync(checked,fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0) | (fs.constants.O_NONBLOCK ?? 0));
  try {
    const info=fs.fstatSync(fd);
    if (!info.isFile() || info.size>maximum) throw new Error('Project input exceeds its readable file budget.');
    const current=checkedProjectFile(projectRoot,file);
    if (!current) throw new Error('Project input changed.');
    const after=fs.lstatSync(current);
    if (info.dev !== after.dev || info.ino !== after.ino) throw new Error('Project input changed.');
    const buffer=Buffer.alloc(Math.min(info.size+1,maximum+1));let count=0;
    while(count<buffer.length){const bytes=fs.readSync(fd,buffer,count,buffer.length-count,null);if(!bytes)break;count+=bytes;}
    const finished=fs.fstatSync(fd);
    if (count>maximum || count!==info.size || finished.size!==info.size || finished.mtimeMs!==info.mtimeMs) throw new Error('Project input changed or exceeds its budget.');
    const bytes=buffer.subarray(0,count);readObserver.getStore()?.(fs.realpathSync(projectRoot),checked,bytes);return bytes;
  } finally {fs.closeSync(fd);}
}
