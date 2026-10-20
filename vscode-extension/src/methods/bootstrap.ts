import * as vscode from 'vscode';
import * as path from 'node:path';
import { access, mkdir, lstat, writeFile } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execute=promisify(execFile);

export async function pythonVersion(executable:string,cwd:string,requireSdk=false):Promise<string|undefined> {
  try {
    const code=(requireSdk ? 'import wrapperguard_sdk; assert tuple(map(int,wrapperguard_sdk.__version__.split("."))) >= (0,4,0); ' : '')+'import sys; print(str(sys.version_info.major)+"."+str(sys.version_info.minor)+"."+str(sys.version_info.micro))';
    const {stdout}=await execute(executable,['-I','-c',code],{cwd,timeout:2000,maxBuffer:1024,windowsHide:true});
    const version=stdout.trim();const [major,minor]=version.split('.').map(Number);
    return /^\d+\.\d+\.\d+$/.test(version) && (major>3 || major===3 && minor>=10) ? version : undefined;
  } catch {return undefined;}
}
async function sdkCapabilities(executable:string,cwd:string):Promise<{runners?:unknown;sandboxBackends?:unknown}> {
  try{
    const {stdout}=await execute(executable,['-I','-m','wrapperguard_sdk','--capabilities'],{cwd,timeout:3000,maxBuffer:65536,windowsHide:true});
    const value=JSON.parse(stdout);return value&&typeof value==='object'&&!Array.isArray(value)?value:{};
  }catch{return{};}
}
export async function sdkSupportedRunners(executable:string,cwd:string):Promise<readonly string[]> {
  const value=await sdkCapabilities(executable,cwd);if(!Array.isArray(value.runners)||value.runners.length>32)return[];
  return value.runners.filter((runner:unknown):runner is {tool:string;supported:true}=>!!runner&&typeof runner==='object'&&typeof (runner as {tool?:unknown}).tool==='string'&&/^[a-z][a-z0-9-]{0,63}$/.test((runner as {tool:string}).tool)&&(runner as {supported?:unknown}).supported===true).map((runner:{tool:string})=>runner.tool);
}
export async function sdkSandboxTools(executable:string,cwd:string):Promise<readonly string[]> {
  const value=await sdkCapabilities(executable,cwd);if(!Array.isArray(value.sandboxBackends)||value.sandboxBackends.length>16)return[];
  const docker=value.sandboxBackends.find(backend=>backend&&backend.engine==='docker'&&backend.supported===true);
  return Array.isArray(docker?.tools)&&docker.tools.length<=32?docker.tools.filter((tool:unknown):tool is string=>typeof tool==='string'&&/^[a-z][a-z0-9-]{0,63}$/.test(tool)):[];
}

/** Installs only the bundled first-party wheel; tools are a separate explicit choice. */
export async function ensureManagedSdk(context: vscode.ExtensionContext, systemPython: string): Promise<string> {
  const directory=path.join(context.globalStorageUri.fsPath,'sdk-environment');
  const python=path.join(directory,process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python');
  const wheel=path.join(context.extensionPath,'resources','sdk','wrapperguard_sdk-0.4.0-py3-none-any.whl');
  await access(wheel);
  await mkdir(context.globalStorageUri.fsPath,{recursive:true,mode:0o700});
  let newEnvironment=false;
  try{const info=await lstat(directory);if(!info.isDirectory()||info.isSymbolicLink())throw new Error('The managed SDK path is not a regular directory.');}catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')newEnvironment=true;else throw error;}
  await vscode.window.withProgress({location:vscode.ProgressLocation.Notification,title:'WrapperGuard: Preparing the managed SDK'},async()=>{
    try {
      if (!await pythonVersion(python,context.globalStorageUri.fsPath)) {
        await execute(systemPython,['-I','-m','venv',directory],{cwd:context.globalStorageUri.fsPath,timeout:120_000,maxBuffer:65_536,windowsHide:true});
      }
      await execute(python,['-I','-m','pip','--isolated','install','--force-reinstall','--no-index','--no-deps','--disable-pip-version-check',wheel],{cwd:context.globalStorageUri.fsPath,timeout:120_000,maxBuffer:65_536,windowsHide:true});
      if(newEnvironment)await writeFile(path.join(directory,'.wrapperguard-owned.json'),JSON.stringify({schemaVersion:1,owner:'wrapperguard.sdk-environment'})+'\n',{flag:'wx',mode:0o600});
    } catch { throw new Error('Could not prepare the managed SDK. Choose a Python 3.10+ installation with venv support, or select an existing SDK environment.'); }
  });
  return python;
}
export const PYTHON_TOOLS={bandit:{name:'Bandit',version:'1.9.4'},semgrep:{name:'Semgrep',version:'1.181.0'}} as const;
export async function installManagedPythonTool(context: vscode.ExtensionContext, python: string, tool:keyof typeof PYTHON_TOOLS,repair=false): Promise<string> {
  const {name,version}=PYTHON_TOOLS[tool];
  await vscode.window.withProgress({location:vscode.ProgressLocation.Notification,title:'WrapperGuard: Installing '+name+' '+version+' from PyPI'},async()=>{
    try {
      await execute(python,['-I','-m','pip','--isolated','install',...(repair?['--force-reinstall']:[]),'--index-url','https://pypi.org/simple','--disable-pip-version-check',tool+'=='+version],{cwd:context.globalStorageUri.fsPath,timeout:180_000,maxBuffer:65_536,windowsHide:true});
    } catch { throw new Error(name+' installation failed. Check connectivity or choose an existing '+name+' executable.'); }
  });
  return path.join(path.dirname(python),process.platform === 'win32' ? tool+'.exe' : tool);
}
