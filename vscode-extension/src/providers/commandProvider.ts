import { spawn } from 'node:child_process';
import { open, realpath, stat } from 'node:fs/promises';
import { accessSync, constants, statSync, realpathSync } from 'node:fs';
import * as path from 'node:path';
import { DEFAULT_PERMISSIONS, EXTERNAL_PERMISSIONS, ProviderDescriptor, ProviderPermissions, ProviderResult, VettingProvider, validateDescriptor, validatePermissions } from './sdk';

export interface CommandProviderConfig {
  apiVersion: 1; builtIn?: ProviderPermissions;
  providers: CommandProviderEntry[];
}
export type CommandProviderEntry = Omit<ProviderDescriptor,'apiVersion'> & {command:string[];permissions:ProviderPermissions;projectRoots?:string[]};
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value); }
function keys(value: Record<string, unknown>, allowed: string[]): void {
  if (Object.keys(value).some(k => !allowed.includes(k))) throw new Error('Unknown provider configuration property.');
}
function configPermissions(value: unknown, fallback:ProviderPermissions):ProviderPermissions {
  if (value === undefined) return validatePermissions(fallback);
  if (!object(value)) throw new Error('Invalid provider permissions.');
  keys(value,['enabled','actions','required','timeoutMs']);
  return validatePermissions({...EXTERNAL_PERMISSIONS,enabled:true,...value} as ProviderPermissions);
}
export function validateCommandConfiguration(value: unknown): CommandProviderConfig {
  if (!object(value)) throw new Error('Invalid provider configuration.');
  keys(value, ['apiVersion', 'builtIn', 'providers']);
  const apiVersion = value.apiVersion === undefined ? 1 : value.apiVersion;
  const configured = value.providers === undefined ? [] : value.providers;
  if (apiVersion !== 1 || !Array.isArray(configured) || configured.length > 32) throw new Error('Provider configuration requires API version 1 and at most 32 providers.');
  const seen = new Set<string>(['wrapperguard']);
  const providers = configured.map(item => {
    if (!object(item)) throw new Error('Invalid command provider.');
    keys(item, ['id','displayName','version','command','permissions','category','summary','tags','inputScope','projectRoots']);
    const {command,permissions,projectRoots,...metadata}=item;
    const descriptor = validateDescriptor({...metadata,apiVersion:1} as ProviderDescriptor);
    if (descriptor.displayName.length > 160) throw new Error('Command provider name exceeds 160 characters.');
    if (seen.has(descriptor.id)) throw new Error('Duplicate or reserved provider ID.');
    seen.add(descriptor.id);
    if (!Array.isArray(item.command) || item.command.length < 1 || item.command.length > 128 || item.command.some(arg => typeof arg !== 'string' || arg.length > 32768 || arg.includes('\0')) || !path.isAbsolute(item.command[0])) throw new Error('Provider command requires an absolute executable and literal arguments.');
    if(projectRoots!==undefined&&(!Array.isArray(projectRoots)||projectRoots.length>32||new Set(projectRoots).size!==projectRoots.length||projectRoots.some(root=>typeof root!=='string'||!path.isAbsolute(root)||root.length>4096||/[\u0000-\u001f\u007f]/.test(root))))throw new Error('Project bindings require at most 32 distinct absolute root paths.');
    const {apiVersion:_,...fields}=descriptor;
    return {...fields,command:[...item.command] as string[],permissions:configPermissions(item.permissions,{...EXTERNAL_PERMISSIONS,enabled:false}),...(projectRoots!==undefined?{projectRoots:[...projectRoots] as string[]}:{} )};
  });
  return {apiVersion:1,builtIn:configPermissions(value.builtIn,DEFAULT_PERMISSIONS),providers};
}
function contained(file: string, root: string): boolean {
  const relative = path.relative(root,file);
  return relative === '' || !relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative);
}
export async function loadCommandProviders(configPath: string, projectRoot: string, trusted: boolean, workspaceRoots:string[]=[projectRoot]): Promise<{providers: VettingProvider[];permissions:Record<string,ProviderPermissions>;configContents:string;projectBindings:Record<string,string[]>;notApplicableIds:string[]}> {
  if (!trusted) throw new Error('Command providers require a trusted workspace.');
  if (!path.isAbsolute(configPath) || contained(path.resolve(configPath),path.resolve(projectRoot))) throw new Error('Provider configuration must be an absolute file outside the workspace.');
  const [actualConfig, actualRoot] = await Promise.all([realpath(configPath), realpath(projectRoot)]);
  if (contained(actualConfig,actualRoot)) throw new Error('Provider configuration resolves inside the workspace.');
  const anchoredConfig=path.join(await realpath(path.dirname(configPath)),path.basename(configPath));
  for (const root of workspaceRoots) {const canonical=await realpath(root);if(contained(actualConfig,canonical) || contained(anchoredConfig,canonical))throw new Error('Provider configuration must be outside every workspace folder.');}
  const info = await stat(actualConfig);
  if (!info.isFile() || info.size > 65_536) throw new Error('Provider configuration exceeds its file limit.');
  let parsed: unknown;
  const handle = await open(actualConfig,'r');
  const buffer = Buffer.alloc(65_537); let count = 0;
  try {
    while (count < buffer.length) {
      const {bytesRead} = await handle.read(buffer,count,buffer.length-count,null);
      if (!bytesRead) break;
      count += bytesRead;
    }
  } finally { await handle.close(); }
  if (count > 65_536) throw new Error('Provider configuration exceeds its file limit.');
  const bytes = buffer.subarray(0,count);
  let configContents:string;
  try { configContents = new TextDecoder('utf-8',{fatal:true}).decode(bytes); }
  catch { throw new Error('Provider configuration must be valid UTF-8.'); }
  try { parsed = JSON.parse(configContents); }
  catch { throw new Error('Provider configuration is not valid JSON.'); }
  const config = validateCommandConfiguration(parsed);
  const permissions: Record<string,ProviderPermissions> = Object.create(null);
  if (config.builtIn) permissions.wrapperguard = config.builtIn;
  const applicableIds=new Set<string>();
  for(const item of config.providers){if(!item.projectRoots?.length)applicableIds.add(item.id);for(const root of item.projectRoots??[]){try{if(await realpath(root)===actualRoot)applicableIds.add(item.id);}catch{/* Unopened or removed bound roots are not applicable. */}}}
  const canonicalRoots=await Promise.all(workspaceRoots.map(root=>realpath(root)));
  const checkExecutable=(file:string):void=>{
    try{if(!statSync(file).isFile())throw new Error();accessSync(file,constants.X_OK);}catch{throw new Error('Provider executable must be an absolute executable file.');}
    const executable=realpathSync(file),anchored=path.join(realpathSync(path.dirname(file)),path.basename(file));
    if(canonicalRoots.some(root=>contained(executable,root)||contained(anchored,root)))throw new Error('Provider executables must be outside every workspace folder.');
  };
  const providers = config.providers.map(item => ({
    descriptor: (({command,permissions,projectRoots,...metadata})=>({...metadata,apiVersion:1 as const}))(item),
    scan: (request: import('./sdk').VettingRequest): Promise<ProviderResult> => new Promise((resolve,reject) => {
      if(!applicableIds.has(item.id)){reject(new Error('Provider is not applicable to this project.'));return;}
      try{if(realpathSync(request.projectRoot)!==actualRoot)throw new Error();}catch{reject(new Error('Provider request does not match the selected project.'));return;}
      try{checkExecutable(item.command[0]);}catch(error){reject(error);return;}
      if (request.signal.aborted) { reject(new Error('Provider cancelled.')); return; }
      const effectivePermissions = validatePermissions(item.permissions);
      // Only the explicitly configured executable runs; project content is never interpreted by a shell.
      const child = spawn(item.command[0],item.command.slice(1),{shell:false,cwd:actualRoot,detached:process.platform !== 'win32',stdio:['pipe','pipe','ignore'],windowsHide:true});
      const chunks: Buffer[] = []; let size = 0; let settled = false;
      const kill = (): void => {
        if (!child.pid) return;
        if (process.platform === 'win32') {
          const executable = path.join(process.env.SystemRoot ?? 'C:\\Windows','System32','taskkill.exe');
          const killer = spawn(executable,['/PID',String(child.pid),'/T','/F'],{shell:false,stdio:'ignore',windowsHide:true});
          killer.on('error',() => { child.kill('SIGKILL'); });
        } else { try { process.kill(-child.pid,'SIGKILL'); } catch { child.kill('SIGKILL'); } }
      };
      const done = (error?: Error, result?: ProviderResult): void => {
        if (settled) return; settled = true;
        clearTimeout(timer); request.signal.removeEventListener('abort',abort);
        kill(); if (error) reject(error); else resolve(result!);
      };
      const abort = (): void => done(new Error('Provider cancelled.'));
      const timer = setTimeout(() => done(Object.assign(new Error('Provider timed out.'),{name:'TimeoutError'})),effectivePermissions.timeoutMs);
      request.signal.addEventListener('abort',abort,{once:true});
      child.on('error',() => done(new Error('Could not start command provider.')));
      child.stdin.on('error',() => done(new Error('Provider input failed.')));
      child.stdout.on('data',(chunk:Buffer) => {
        size += chunk.length;
        if (size > 4 * 1024 * 1024) { done(new Error('Provider output limit exceeded.')); return; }
        chunks.push(chunk);
      });
      child.on('close',code => {
        if (code !== 0) { done(new Error('Command provider exited unsuccessfully.')); return; }
        try {
          const parsed:unknown=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(Buffer.concat(chunks)));
          if (!object(parsed)) throw new Error();
          keys(parsed,['findings','edges','scannedFiles','complete','notices']);
          const findings = parsed.findings === undefined ? [] : parsed.findings;
          if (!Array.isArray(findings)) throw new Error();
          const normalized = findings.map(finding => {
            if (!object(finding)) throw new Error();
            keys(finding,['fingerprint','ruleId','title','message','severity','confidence','category','kind','location','evidence','remediation','reachable','callChain','policyAction','status','mlSignal','providerId','actions']);
            return {kind:'ACTIONABLE',reachable:true,callChain:[],policyAction:'REVIEW',status:'ACTIVE',actions:[],...finding};
          });
          done(undefined,{...parsed,findings:normalized} as unknown as ProviderResult);
        }
        catch { done(new Error('Command provider returned invalid JSON.')); }
      });
      child.stdin.end(JSON.stringify({apiVersion:1,projectPath:actualRoot,operation:request.operation}) + '\n');
    })
  }));
  const projectBindings:Record<string,string[]>=Object.create(null);
  for (const item of config.providers){
    projectBindings[item.id]=[...(item.projectRoots??[])];permissions[item.id]={...item.permissions,enabled:item.permissions.enabled&&applicableIds.has(item.id)};
  }
  return {providers,permissions,configContents,projectBindings,notApplicableIds:config.providers.filter(item=>!applicableIds.has(item.id)).map(item=>item.id)};
}
