import * as vscode from 'vscode';
import * as path from 'node:path';
import { lstat, mkdir, realpath, rename, rm, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { checkedProjectDirectory, readProjectFile } from '../scanner/safePath';
import { isWithin } from '../providers/customSetup';
import { ensureManagedSdk, installManagedPythonTool, pythonVersion, PYTHON_TOOLS } from './bootstrap';
import { choosePython } from './setup';
export const MANAGED_ENVIRONMENT_MARKER='.wrapperguard-owned.json';
export async function markNewManagedEnvironment(directory:string):Promise<void> {
  await writeFile(path.join(directory,MANAGED_ENVIRONMENT_MARKER),JSON.stringify({schemaVersion:1,owner:'wrapperguard.sdk-environment'})+'\n',{flag:'wx',mode:0o600});
}
export async function removeManagedEnvironment(storage:string,roots:string[],references:readonly string[]):Promise<boolean> {
  const base=await realpath(storage);for(const root of roots)if(isWithin(base,await realpath(root)))throw new Error('SDK storage must be outside every project.');
  const directory=path.join(base,'sdk-environment');let info;
  try{info=await lstat(directory);}catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return false;throw error;}
  if(!info.isDirectory()||info.isSymbolicLink()||!checkedProjectDirectory(base,directory))throw new Error('The managed SDK directory is not a safe owned environment.');
  const marker=JSON.parse(readProjectFile(base,path.join(directory,MANAGED_ENVIRONMENT_MARKER),1024).toString('utf8'));
  if(Object.keys(marker).length!==2||marker.schemaVersion!==1||marker.owner!=='wrapperguard.sdk-environment')throw new Error('This environment has no recognized ownership record. It is preserved.');
  for(const reference of references){
    if(!path.isAbsolute(reference))continue;
    for(let current=path.resolve(reference);;current=path.dirname(current)){
      let canonical:string|undefined;try{canonical=await realpath(current);}catch{/* Missing leaf paths can still reference this environment. */}
      if(isWithin(current,directory)||canonical&&isWithin(canonical,directory))throw new Error('Saved connections still reference this SDK environment. Disconnect or reconfigure them before removal; disabled connections also count.');
      if(path.dirname(current)===current)break;
    }
  }
  const removed=path.join(base,'.sdk-removal-'+randomUUID());await rename(directory,removed);await rm(removed,{recursive:true,force:false});return true;
}
export async function maintainSdk(context:vscode.ExtensionContext,roots:string[],references:()=>Promise<string[]>):Promise<void> {
  await mkdir(context.globalStorageUri.fsPath,{recursive:true,mode:0o700});
  const storage=await realpath(context.globalStorageUri.fsPath);for(const root of roots)if(isWithin(storage,await realpath(root)))throw new Error('SDK storage must be outside every project.');
  const python=path.join(storage,'sdk-environment',process.platform==='win32'?'Scripts/python.exe':'bin/python');
  const ready=await pythonVersion(python,storage,true);
  const choice=await vscode.window.showQuickPick([
    {label:'Install / repair bundled SDK offline',description:ready?'Installed Python '+ready+' · reinstall exact packaged SDK':'Prepare or repair the managed environment',action:'sdk'},
    ...Object.entries(PYTHON_TOOLS).map(([tool,spec])=>({label:'Repair / update '+spec.name+' to '+spec.version,description:'Explicit PyPI download, only in the managed environment',action:tool})),
    {label:'Remove unused managed SDK environment',description:'Requires an ownership record and no saved references; original files are preserved',action:'remove'}
  ],{title:'SDK maintenance',placeHolder:'Repair does not scan projects. Use Storage separately for cache cleanup/offline-cache recovery.',ignoreFocusOut:true});
  if(!choice)return;
  if(choice.action==='sdk'){
    const selected=await choosePython(context,roots,true);if(!selected)return;
    const confirm=await vscode.window.showQuickPick([{label:'Reinstall the bundled SDK offline',confirm:true},{label:'Cancel',confirm:false}],{title:'Review SDK repair',placeHolder:'Creates a managed environment if needed. Reinstalls the exact bundled wheel without network access or project analysis.',ignoreFocusOut:true});if(!confirm?.confirm)return;
    await ensureManagedSdk(context,selected);await vscode.window.showInformationMessage('Managed SDK installed from the bundled wheel. No project scan ran.');return;
  }
  if(choice.action==='remove'){
    const confirm=await vscode.window.showQuickPick([{label:'Remove only the unused owned SDK environment',confirm:true},{label:'Cancel',confirm:false}],{title:'Review managed SDK removal',placeHolder:'Removes the whole generated SDK environment after checking references. Files outside this environment, other Python installations and baseline approvals remain.',ignoreFocusOut:true});if(!confirm?.confirm)return;
    const removed=await removeManagedEnvironment(storage,roots,await references());await vscode.window.showInformationMessage(removed?'Unused managed SDK environment removed.':'No managed SDK environment exists.');return;
  }
  if(!ready)throw new Error('Install or repair the bundled SDK before updating a managed Python tool.');
  const tool=choice.action as keyof typeof PYTHON_TOOLS;const spec=PYTHON_TOOLS[tool];if(!spec)throw new Error('Choose a supported managed tool.');
  const confirm=await vscode.window.showQuickPick([{label:'Download and reinstall '+spec.name+' '+spec.version,confirm:true},{label:'Cancel',confirm:false}],{title:'Review tool repair/download',placeHolder:'Downloads the pinned tool and its dependencies from PyPI. It runs no project scanner and changes no grants.',ignoreFocusOut:true});if(!confirm?.confirm)return;
  await installManagedPythonTool(context,python,tool,true);await vscode.window.showInformationMessage(spec.name+' '+spec.version+' installed. Run a scan explicitly when ready.');
}
