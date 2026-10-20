import * as vscode from 'vscode';
import * as path from 'node:path';
import { access, mkdir } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execute=promisify(execFile);

export async function pythonVersion(executable:string,cwd:string,requireSdk=false):Promise<string|undefined> {
  try {
    const code=(requireSdk ? 'import wrapperguard_sdk; assert tuple(map(int,wrapperguard_sdk.__version__.split("."))) >= (0,2,0); ' : '')+'import sys; print(str(sys.version_info.major)+"."+str(sys.version_info.minor)+"."+str(sys.version_info.micro))';
    const {stdout}=await execute(executable,['-I','-c',code],{cwd,timeout:2000,maxBuffer:1024,windowsHide:true});
    const version=stdout.trim();const [major,minor]=version.split('.').map(Number);
    return /^\d+\.\d+\.\d+$/.test(version) && (major>3 || major===3 && minor>=10) ? version : undefined;
  } catch {return undefined;}
}

/** Installs only the bundled first-party wheel; tools are a separate explicit choice. */
export async function ensureManagedSdk(context: vscode.ExtensionContext, systemPython: string): Promise<string> {
  const directory=path.join(context.globalStorageUri.fsPath,'sdk-environment');
  const python=path.join(directory,process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python');
  const wheel=path.join(context.extensionPath,'resources','sdk','wrapperguard_sdk-0.3.0-py3-none-any.whl');
  await access(wheel);
  await mkdir(context.globalStorageUri.fsPath,{recursive:true,mode:0o700});
  await vscode.window.withProgress({location:vscode.ProgressLocation.Notification,title:'WrapperGuard: Preparing the managed SDK'},async()=>{
    try {
      if (!await pythonVersion(python,context.globalStorageUri.fsPath)) {
        await execute(systemPython,['-I','-m','venv',directory],{cwd:context.globalStorageUri.fsPath,timeout:120_000,maxBuffer:65_536,windowsHide:true});
      }
      await execute(python,['-I','-m','pip','--isolated','install','--force-reinstall','--no-index','--no-deps','--disable-pip-version-check',wheel],{cwd:context.globalStorageUri.fsPath,timeout:120_000,maxBuffer:65_536,windowsHide:true});
    } catch { throw new Error('Could not prepare the managed SDK. Choose a Python 3.10+ installation with venv support, or select an existing SDK environment.'); }
  });
  return python;
}
export const PYTHON_TOOLS={bandit:{name:'Bandit',version:'1.9.4'},semgrep:{name:'Semgrep',version:'1.181.0'}} as const;
export async function installManagedPythonTool(context: vscode.ExtensionContext, python: string, tool:keyof typeof PYTHON_TOOLS): Promise<string> {
  const {name,version}=PYTHON_TOOLS[tool];
  await vscode.window.withProgress({location:vscode.ProgressLocation.Notification,title:'WrapperGuard: Installing '+name+' '+version+' from PyPI'},async()=>{
    try {
      await execute(python,['-I','-m','pip','--isolated','install','--index-url','https://pypi.org/simple','--disable-pip-version-check',tool+'=='+version],{cwd:context.globalStorageUri.fsPath,timeout:180_000,maxBuffer:65_536,windowsHide:true});
    } catch { throw new Error(name+' installation failed. Check connectivity or choose an existing '+name+' executable.'); }
  });
  return path.join(path.dirname(python),process.platform === 'win32' ? tool+'.exe' : tool);
}
