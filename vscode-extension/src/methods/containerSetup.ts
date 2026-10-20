import * as vscode from 'vscode';
import * as path from 'node:path';
import { realpath, stat } from 'node:fs/promises';
import { isWithin } from '../providers/customSetup';
import { validateExecutable } from './setup';
export interface ContainerSandbox {engine:'docker';executable:string;endpoint:string;image:string;allowRootfulDaemon:boolean;memoryMiB:number;cpus:number;pidsLimit:number}
export function validatePinnedImage(image:string):void {
  if(typeof image!=='string'||!/^(?:[a-z0-9][a-z0-9._:/-]{0,199}@)?sha256:[0-9a-f]{64}$/.test(image))throw new Error('Choose an already installed image by exact sha256 digest. Tags and automatic pulls are not allowed.');
}
export function validateImageToolPath(file:string):void {
  if(!file.startsWith('/')||file.length>1024||/[\u0000-\u0020\u007f\\,]/.test(file)||file.split('/').slice(1).some(part=>!part||part==='.'||part==='..'))throw new Error('Use an absolute executable path inside the selected Linux image.');
}
export async function configureContainerSandbox(roots:string[]):Promise<ContainerSandbox|undefined> {
  if(process.platform==='win32')throw new Error('This optional Docker adapter requires a POSIX host with an explicit Unix socket. Use a supported Linux/WSL host for container analysis.');
  const selected=await vscode.window.showOpenDialog({title:'Choose a trusted Docker CLI outside every project',canSelectFiles:true,canSelectFolders:false,canSelectMany:false,openLabel:'Use Docker CLI'});if(!selected?.[0])return undefined;
  const executable=await validateExecutable(selected[0].fsPath,roots);
  const socket=await vscode.window.showInputBox({title:'Docker Unix socket path',prompt:'Absolute local Unix socket. No context discovery, remote endpoint or TCP daemon is used.',ignoreFocusOut:true});if(socket===undefined)return undefined;
  if(!path.isAbsolute(socket))throw new Error('Choose an absolute local Docker Unix socket.');const endpoint=await realpath(socket);if(!(await stat(endpoint)).isSocket())throw new Error('Choose a local Unix socket.');
  for(const root of roots)if(isWithin(endpoint,await realpath(root))||isWithin(path.join(await realpath(path.dirname(socket)),path.basename(socket)),await realpath(root)))throw new Error('Docker sockets must be outside every open project.');
  const image=await vscode.window.showInputBox({title:'Installed container image digest',prompt:'repository@sha256:64-hex or sha256:64-hex. Image must already exist; nothing is pulled.',ignoreFocusOut:true,validateInput:value=>{try{validatePinnedImage(value);return undefined;}catch(error){return(error as Error).message;}}});if(image===undefined)return undefined;validatePinnedImage(image);
  const daemon=await vscode.window.showQuickPick([{label:'Require a rootless Docker daemon',allow:false},{label:'Allow this explicitly selected rootful daemon',description:'Trusted daemon has host-level authority; container restrictions do not reduce daemon permissions',allow:true}],{title:'Review container daemon authority',ignoreFocusOut:true});if(!daemon)return undefined;
  const limits={memoryMiB:256,cpus:1,pidsLimit:32};
  for(const[field,title,min,max]of [['memoryMiB','Memory limit (MiB)',64,2048],['cpus','CPU limit',1,2],['pidsLimit','Process limit',8,128]] as const){const value=await vscode.window.showInputBox({title:'Container — '+title,value:String(limits[field]),prompt:min+'–'+max+'. No network; read-only root. Analysis runs as non-root; a trusted timer can stop it.',ignoreFocusOut:true,validateInput:value=>/^\d+$/.test(value)&&Number(value)>=min&&Number(value)<=max?undefined:'Choose '+min+'–'+max+'.'});if(value===undefined)return undefined;if(!/^\d+$/.test(value)||Number(value)<min||Number(value)>max)throw new Error('Container resource limit is invalid.');limits[field]=Number(value);}
  const review=await vscode.window.showQuickPick([{label:'Use this pinned local container',approved:true,description:image,detail:'Read-only bounded mounts · no network · non-root worker · '+limits.memoryMiB+' MiB · '+limits.cpus+' CPU · '+limits.pidsLimit+' processes'},{label:'Cancel',approved:false}],{title:'Review optional container isolation',placeHolder:'No image pull, build, install or target startup. Trust the CLI/daemon/image; these controls are not a proof against runtime vulnerabilities.',ignoreFocusOut:true});
  return review?.approved?{engine:'docker',executable,endpoint,image,allowRootfulDaemon:daemon.allow,...limits}:undefined;
}
