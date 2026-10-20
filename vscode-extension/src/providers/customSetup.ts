import * as vscode from 'vscode';
import { mkdir, readFile, rename, writeFile, realpath, lstat } from 'node:fs/promises';
import * as path from 'node:path';
import { randomBytes } from 'node:crypto';
import { CommandProviderConfig, CommandProviderEntry, validateCommandConfiguration } from './commandProvider';
import { EXTERNAL_PERMISSIONS, validateDescriptor, validatePermissions, VETTING_ACTIONS } from './sdk';
import { validateExecutable } from '../methods/setup';
import { validateGlob, validateInputScope, GENERATED_EXCLUDES } from './inputScope';
import { SECURITY_METHODS } from '../methods/catalog';

export { GENERATED_EXCLUDES } from './inputScope';
export function isWithin(file:string,root:string):boolean {const relative=path.relative(root,file);return relative === '' || relative !== '..' && !relative.startsWith('..'+path.sep) && !path.isAbsolute(relative);}
export async function outsideRoots(file:string,roots:string[]):Promise<string> {
  if (!path.isAbsolute(file)) throw new Error('Choose an absolute file outside every workspace folder.');
  const resolved=await realpath(file);
  const anchored=path.join(await realpath(path.dirname(path.resolve(file))),path.basename(file));
  for (const root of roots) {const canonical=await realpath(root);if(isWithin(resolved,canonical) || isWithin(anchored,canonical))throw new Error('Choose a file outside every workspace folder.');}
  if (!(await lstat(resolved)).isFile()) throw new Error('Choose a regular file.');
  return resolved;
}
/** Save only structured configuration. Arguments never pass through a shell. */
export async function writeProviderConfiguration(file:string,config:CommandProviderConfig,roots:string[]):Promise<void> {
  const directory=path.dirname(file);await mkdir(directory,{recursive:true,mode:0o700});
  const canonical=await realpath(directory);
  for (const root of roots) if(isWithin(canonical,await realpath(root)))throw new Error('Provider configuration must be outside every workspace folder.');
  const value=validateCommandConfiguration(config);const text=JSON.stringify(value,null,2)+'\n';
  if(Buffer.byteLength(text)>65_536)throw new Error('Provider configuration exceeds its file limit.');
  const temporary=path.join(canonical,'.providers-'+randomBytes(8).toString('hex')+'.tmp');
  await writeFile(temporary,text,{encoding:'utf8',mode:0o600,flag:'wx'});await rename(temporary,file);
}
export async function readProviderConfiguration(file:string):Promise<CommandProviderConfig> {
  try {return validateCommandConfiguration(JSON.parse(await readFile(file,'utf8')));}catch(error){if((error as NodeJS.ErrnoException).code==='ENOENT')return{apiVersion:1,providers:[]};throw error;}
}
async function text(title:string,value:string,validate?:(value:string)=>string|undefined):Promise<string|undefined> {return vscode.window.showInputBox({title,value,ignoreFocusOut:true,validateInput:validate});}
/** A row editor keeps whitespace, quotes and metacharacters as literal argv data. */
export async function editLiteralList(title:string,initial:string[],validate:(value:string)=>string|undefined=()=>undefined,maximum=128):Promise<string[]|undefined> {
  const values=[...initial];
  while(true){
    const choice=await vscode.window.showQuickPick([{label:'Done',description:values.length+' entries',action:'done',index:-1},...(values.length<maximum?[{label:'Add entry',description:'One literal value; no command-line parsing',action:'add',index:-1}]:[]),...values.map((value,index)=>({label:String(index+1)+'. '+(value || '(empty argument)'),description:'Edit or remove this entry',action:'edit',index}))],{title,ignoreFocusOut:true});
    if(!choice)return undefined;if(choice.action==='done')return values;
    if(choice.action==='edit'){
      const action=await vscode.window.showQuickPick([{label:'Edit value',action:'edit'},{label:'Remove entry',action:'remove'}],{title:title+' — entry '+(choice.index+1),ignoreFocusOut:true});
      if(!action)continue;if(action.action==='remove'){values.splice(choice.index,1);continue;}
    }
    const value=await text(title+' — '+(choice.action==='add'?'new entry':'edit entry'),choice.action==='add'?'':values[choice.index],validate);
    if(value===undefined)continue;const problem=validate(value);if(problem)throw new Error(problem);
    if(choice.action==='add')values.push(value);else values[choice.index]=value;
  }
}
const literal=(value:string):string|undefined=>value.length>32768 || value.includes('\0')?'A literal argument must be at most 32768 characters, without NUL.':undefined;
const glob=(value:string):string|undefined=>{try{validateGlob(value);return undefined;}catch(error){return(error as Error).message;}};
export async function configureCustomProvider(roots:string[],existing?:CommandProviderEntry,reservedIds:string[]=[],selectedRoot=roots[0]):Promise<CommandProviderEntry|undefined> {
  if(!vscode.workspace.isTrusted)throw new Error('Trust this workspace before connecting a command provider.');
  if(!roots.length)throw new Error('Open a local workspace folder first.');
  let draft:CommandProviderEntry=existing ? {...existing,command:[...existing.command],permissions:{...existing.permissions,actions:[...existing.permissions.actions]}} : {id:'',displayName:'',version:'1.0.0',category:'OTHER',summary:'',tags:[],command:[],permissions:{...EXTERNAL_PERMISSIONS,actions:['WARNING']},projectRoots:[await realpath(selectedRoot)]};
  if(!existing){
    const id=await text('Add SDK — provider ID','',value=>!/^[a-z][a-z0-9._-]{0,79}$/.test(value) || value==='wrapperguard' || reservedIds.includes(value) || SECURITY_METHODS.some(method=>method.providerId===value)?'Use a unique lowercase ID, e.g. company.android-review.':undefined);
    if(id===undefined)return undefined;draft.id=id;
    const name=await text('Add SDK — display name','',value=>!value.trim() || value.length>160?'Enter a display name (1–160 characters).':undefined);if(name===undefined)return undefined;draft.displayName=name;
  }
  while(true){
    const choice=await vscode.window.showQuickPick([
      {label:'Identity & version',description:draft.displayName+' · '+draft.id+' · '+draft.version,action:'identity'},
      {label:'Executable',description:draft.command[0] || 'Choose an installed executable outside every project',action:'executable'},
      {label:'Literal arguments',description:Math.max(0,draft.command.length-1)+' separate argv entries; no shell expansion',action:'arguments'},
      {label:'Category & labels',description:(draft.category??'OTHER')+' · '+(draft.tags??[]).join(', '),action:'metadata'},
      {label:'Project inputs',description:draft.inputScope===undefined?'Unknown scope — configure a read set':(draft.inputScope.includes??[]).length+' include patterns; '+(draft.inputScope.excludes??[]).length+' exclusions',action:'inputs'},
      {label:'Project binding',description:draft.projectRoots?.length?draft.projectRoots.join(', '):'All selected projects',action:'projects'},
      {label:'External input files',description:(draft.inputScope?.externalFiles??[]).length+' selected reports, scripts or rule files',action:'external'},
      {label:'Permissions',description:(draft.permissions.enabled?'Enabled':'Disabled')+' · '+draft.permissions.actions.join(', ')+' · '+draft.permissions.timeoutMs/1000+'s',action:'permissions'},
      {label:'Save connection',description:'Keep the selected enabled state. Nothing runs until a scan.',action:'save'},
      {label:'Cancel',description:'Discard changes',action:'cancel'}
    ],{title:(existing?'Edit SDK — ':'Add SDK — ')+draft.displayName,ignoreFocusOut:true});
    if(!choice || choice.action==='cancel')return undefined;
    if(choice.action==='identity'){
      const name=await text('SDK display name',draft.displayName);if(name!==undefined)draft.displayName=name;
      const version=await text('SDK version',draft.version);if(version!==undefined)draft.version=version;
    }else if(choice.action==='executable'){
      const selected=await vscode.window.showOpenDialog({title:'Choose provider executable outside every workspace folder',canSelectFiles:true,canSelectFolders:false,canSelectMany:false,openLabel:'Use executable'});
      if(selected?.[0])draft.command=[await validateExecutable(selected[0].fsPath,roots),...draft.command.slice(1)];
    }else if(choice.action==='arguments'){
      const args=await editLiteralList('SDK literal arguments',draft.command.slice(1),literal,127);if(args)draft.command=[draft.command[0]??'',...args];
    }else if(choice.action==='metadata'){
      const category=await vscode.window.showQuickPick(['SAST','SUPPLY_CHAIN','DAST','RESEARCH','AI','OTHER','Custom category…'],{title:'SDK category',ignoreFocusOut:true});
      if(category){const value=category==='Custom category…'?await text('Custom category',draft.category??'OTHER',value=>/^[A-Z][A-Z0-9_]{0,31}$/.test(value)&&value!=='BUILT_IN'?undefined:'Use an uppercase category, up to 32 characters.'):category;if(value!==undefined)draft.category=value;}
      const summary=await text('SDK summary',draft.summary??'',value=>value.length>512?'Use at most 512 characters.':undefined);if(summary!==undefined)draft.summary=summary;
      const tags=await editLiteralList('SDK tags',draft.tags??[],value=>/^[a-z0-9][a-z0-9._-]{0,63}$/.test(value)?undefined:'Use a lowercase tag, up to 64 characters.',16);if(tags)draft.tags=tags;
    }else if(choice.action==='inputs'){
      const includes=await editLiteralList('Project include globs',draft.inputScope?.includes??[],glob);if(!includes)continue;
      const excludes=await editLiteralList('Project exclusion globs',draft.inputScope?.excludes??[],glob);if(excludes)draft.inputScope={...draft.inputScope,includes,excludes};
    }else if(choice.action==='projects'){
      const selected=await vscode.window.showQuickPick([{label:'Selected project only',description:selectedRoot,all:false},{label:'All selected projects',description:'The provider receives each evaluated root through Provider API v1',all:true}],{title:'Choose the SDK project binding',ignoreFocusOut:true});if(selected)draft.projectRoots=selected.all?[]:[await realpath(selectedRoot)];
    }else if(choice.action==='external'){
      const action=await vscode.window.showQuickPick([{label:'Select external input files',action:'select'},{label:'Clear external inputs',action:'clear'}],{title:'Declare reports, scripts and rule files read by this SDK',ignoreFocusOut:true});
      if(action?.action==='clear')draft.inputScope={...draft.inputScope,externalFiles:[]};
      if(action?.action==='select'){const files=await vscode.window.showOpenDialog({title:'Select external inputs outside every workspace folder',canSelectFiles:true,canSelectFolders:false,canSelectMany:true,openLabel:'Declare input files'});if(files)draft.inputScope={...draft.inputScope,externalFiles:await Promise.all(files.map(file=>outsideRoots(file.fsPath,roots)))};}
    }else if(choice.action==='permissions'){
      const permissions=await vscode.window.showQuickPick([{label:'Enabled',value:'enabled',picked:draft.permissions.enabled},{label:'Required when a granted gate action fails',value:'required',picked:draft.permissions.required},...VETTING_ACTIONS.map(action=>({label:action,value:action,picked:draft.permissions.actions.includes(action)}))],{title:'SDK action grants — independent of method presets',canPickMany:true,ignoreFocusOut:true});
      if(permissions){const values=permissions.map(item=>item.value);draft.permissions=validatePermissions({...draft.permissions,enabled:values.includes('enabled'),required:values.includes('required'),actions:VETTING_ACTIONS.filter(action=>values.includes(action))});}
      const timeout=await text('Provider timeout in milliseconds',String(draft.permissions.timeoutMs),value=>/^\d+$/.test(value)&&Number(value)>=100&&Number(value)<=300000?undefined:'Choose 100–300000 milliseconds.');if(timeout!==undefined)draft.permissions=validatePermissions({...draft.permissions,timeoutMs:Number(timeout)});
    }else if(choice.action==='save'){
      if(!draft.command.length || !draft.command[0])throw new Error('Choose the provider executable before saving.');
      draft.command[0]=await validateExecutable(draft.command[0],roots);
      const {command,permissions,projectRoots,...metadata}=draft;validateDescriptor({...metadata,apiVersion:1});if(draft.inputScope)validateInputScope(draft.inputScope);
      const confirm=await vscode.window.showQuickPick([{label:'Save this SDK connection',save:true},{label:'Keep editing',save:false}],{title:draft.displayName+' · '+(draft.permissions.enabled?'enabled':'disabled')+' · '+draft.permissions.actions.join(', '),placeHolder:(draft.inputScope===undefined?'Input scope is UNKNOWN; provider-specific freshness is unverified. Declare its actual read set in Project inputs. ':'')+'This trusted process can access your files. Input declarations do not restrict process access. Outputs must follow Provider API v1.',ignoreFocusOut:true});
      if(confirm?.save)return validateCommandConfiguration({apiVersion:1,providers:[draft]}).providers[0];
    }
  }
}
