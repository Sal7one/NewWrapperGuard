import * as fs from 'node:fs/promises';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { isWithin } from '../providers/customSetup';
import { checkedProjectDirectory, checkedProjectFile } from '../scanner/safePath';

export interface StorageItem {relative:string;bytes:number;modified:number;kind:'cache'|'profile'|'snapshot'}
const owned=(relative:string):StorageItem['kind']|undefined=>relative.startsWith('integrations/cache/')?'cache':/^integrations\/[a-z][a-z0-9._-]{0,79}\.profile\.json$/.test(relative)?'profile':relative==='integrations/ai-input.json'?'snapshot':undefined;
/** Enumerate only fixed extension-owned locations, never follow links or inspect user files. */
export async function managedStorageItems(storage:string,roots:string[]):Promise<StorageItem[]> {
  await fs.mkdir(storage,{recursive:true,mode:0o700});const base=await fs.realpath(storage);
  for(const root of roots)if(isWithin(base,await fs.realpath(root)))throw new Error('Managed storage must be outside every workspace folder.');
  const output:StorageItem[]=[];let visited=0;
  const walk=async(relative:string,depth:number):Promise<void>=>{
    if(depth>32||++visited>10000)throw new Error('Managed storage inventory exceeds its inspection budget.');
    const directory=path.join(base,relative);if(!checkedProjectDirectory(base,directory))return;
    for(const entry of await fs.readdir(directory,{withFileTypes:true})){
      if(entry.isSymbolicLink())continue;const child=path.posix.join(relative,entry.name);
      if(entry.isDirectory()){if(child==='integrations'||child==='integrations/cache'||child.startsWith('integrations/cache/'))await walk(child,depth+1);continue;}
      const kind=owned(child);if(!entry.isFile()||!kind||!checkedProjectFile(base,path.join(base,child)))continue;
      const info=await fs.lstat(path.join(base,child));output.push({relative:child,bytes:info.size,modified:info.mtimeMs,kind});
    }
  };
  await walk('',0);return output;
}
export async function cleanManagedStorage(storage:string,roots:string[],selected:readonly StorageItem[],protectedPaths:readonly string[],olderThan=Infinity):Promise<{removed:number;bytes:number}> {
  // Re-inventory before each cleanup; selections cannot submit arbitrary relative paths.
  const inventory=await managedStorageItems(storage,roots),base=await fs.realpath(storage);const allowed=new Set(selected.map(item=>item.relative));
  const protectedFiles=new Set(protectedPaths.map(file=>path.resolve(file)));let removed=0,bytes=0;
  for(const item of inventory){if(!allowed.has(item.relative)||item.modified>olderThan)continue;const file=path.join(base,item.relative);if(protectedFiles.has(file)||!checkedProjectFile(base,file))continue;await fs.unlink(file);removed++;bytes+=item.bytes;}
  return{removed,bytes};
}
export async function storageDialog(storage:string,roots:string[],protectedPaths:string[]):Promise<boolean> {
  const items=await managedStorageItems(storage,roots);storage=await fs.realpath(storage);const total=items.reduce((sum,item)=>sum+item.bytes,0);
  const retentionFile=path.join(storage,'retention.json');let days=30;
  try{const value=JSON.parse(await fs.readFile(retentionFile,'utf8'));if([0,7,30,90].includes(value.days))days=value.days;}catch{/* Start with a conservative 30-day suggestion. */}
  const choice=await vscode.window.showQuickPick([{label:'Remove expired unused data',description:(days?days+' days':'Keep forever')+' · inactive profiles and cache files only',action:'expired'},{label:'Clear tool caches',description:'Removes downloaded vulnerability databases; tools may download them again',action:'cache'},{label:'Remove unused profiles and snapshots',description:'Active provider inputs and original user-selected files are preserved',action:'unused'},{label:'Retention preference',description:days?days+' days':'Keep forever',action:'retention'}],{title:'Managed storage · '+(total/1048576).toFixed(1)+' MiB in '+items.length+' owned files',ignoreFocusOut:true});
  if(!choice)return false;
  if(choice.action==='retention'){
    const value=await vscode.window.showQuickPick([{label:'7 days',days:7},{label:'30 days',days:30},{label:'90 days',days:90},{label:'Keep forever',days:0}],{title:'Age used by Remove expired unused data',placeHolder:'Cleanup runs only when you explicitly request it.',ignoreFocusOut:true});
    if(value)await fs.writeFile(retentionFile,JSON.stringify({schemaVersion:1,days:value.days})+'\n',{encoding:'utf8',mode:0o600});return false;
  }
  if(choice.action==='expired'&&!days){await vscode.window.showInformationMessage('Retention is set to Keep forever. Choose another retention preference or a specific cleanup action.');return false;}
  const selected=items.filter(item=>choice.action==='cache'?item.kind==='cache':choice.action==='unused'?item.kind!=='cache':true);
  const cutoff=choice.action==='expired'?Date.now()-days*86400000:Infinity;
  const removable=selected.filter(item=>item.modified<=cutoff&&!protectedPaths.some(file=>path.resolve(file)===path.resolve(storage,item.relative)));
  if(!removable.length){await vscode.window.showInformationMessage('There is no unused managed data to remove. Connected profiles and original files are preserved.');return false;}
  const confirm=await vscode.window.showQuickPick([{label:'Remove '+removable.length+' owned files',remove:true},{label:'Cancel',remove:false}],{title:'Clean managed storage',placeHolder:'Only extension-owned cache, unused profile or snapshot files are removed. SDK environments and original reports, rules and executables are preserved.',ignoreFocusOut:true});
  if(!confirm?.remove)return false;const result=await cleanManagedStorage(storage,roots,removable,protectedPaths,cutoff);await vscode.window.showInformationMessage('Removed '+result.removed+' managed files ('+(result.bytes/1048576).toFixed(1)+' MiB).');return result.removed>0;
}
