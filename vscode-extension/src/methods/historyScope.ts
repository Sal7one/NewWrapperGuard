import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { ScanSettings } from '../models';
import { ProviderPermissions, VettingProvider } from '../providers/sdk';
import { validateCommandConfiguration } from '../providers/commandProvider';
import { readProjectFile } from '../scanner/safePath';
import { HistoryScope } from './history';

function canonical(value:unknown):string {
  if(Array.isArray(value))return'['+value.map(canonical).join(',')+']';
  if(value&&typeof value==='object')return'{'+Object.entries(value).filter(([,item])=>item!==undefined).sort(([a],[b])=>a.localeCompare(b)).map(([key,item])=>JSON.stringify(key)+':'+canonical(item)).join(',')+'}';
  return JSON.stringify(value)??'null';
}
const sha=(value:string|Buffer):string=>createHash('sha256').update(value).digest('hex');
/** SARIF declares its producer separately from the importer; results never identify rules. */
export function sarifProducerIdentity(reportPath:string):unknown|undefined {
  try{
    const file=path.join(fs.realpathSync(path.dirname(reportPath)),path.basename(reportPath));
    const report=JSON.parse(readProjectFile(path.parse(file).root,file,16*1024*1024).toString('utf8'));
    if(report.version!=='2.1.0'||!Array.isArray(report.runs)||!report.runs.length||report.runs.length>32)return undefined;
    const component=(value:any):unknown=>{
      if(!value||typeof value!=='object'||typeof value.name!=='string'||!value.name.trim()||value.name.length>200||typeof(value.semanticVersion??value.version)!=='string'||!(value.semanticVersion??value.version).trim()||!Array.isArray(value.rules)||!value.rules.length||value.rules.length>5000||value.isComprehensive===false)throw new Error();
      const ids=new Set<string>();for(const rule of value.rules){if(!rule||typeof rule!=='object'||typeof rule.id!=='string'||!rule.id||rule.id.length>200||ids.has(rule.id))throw new Error();ids.add(rule.id);}
      return{name:value.name,semanticVersion:value.semanticVersion,version:value.version,guid:value.guid,organization:value.organization,informationUri:value.informationUri,rules:[...value.rules].sort((a:any,b:any)=>a.id.localeCompare(b.id)),globalMessageStrings:value.globalMessageStrings,properties:value.properties};
    };
    const identity=report.runs.map((run:any)=>{
      if(!run||typeof run!=='object'||!run.tool?.driver)throw new Error();
      if(run.tool.extensions!==undefined&&!Array.isArray(run.tool.extensions))throw new Error();
      const invocations=run.invocations===undefined?[]:run.invocations;if(!Array.isArray(invocations)||invocations.length>128)throw new Error();
      return{driver:component(run.tool.driver),extensions:(run.tool.extensions??[]).map(component),invocations:invocations.map((invocation:any)=>({arguments:invocation.arguments,commandLine:invocation.commandLine,ruleConfigurationOverrides:invocation.ruleConfigurationOverrides,notificationConfigurationOverrides:invocation.notificationConfigurationOverrides,configuration:invocation.properties?.configuration})),configuration:run.properties?.configuration};
    });
    if(Buffer.byteLength(JSON.stringify(identity))>1024*1024)return undefined;return identity;
  }catch{return undefined;}
}
/** Detection configuration only: source and imported report bytes intentionally stay out. */
export function historyScope(settings:ScanSettings,providers:readonly VettingProvider[],permissions:Record<string,ProviderPermissions>,sources:readonly {file:string;contents:string}[],managedDirectory:string):HistoryScope {
  let known=true;const configuration:unknown[]=[];
  const executableIdentity=(file:string):void=>{
    try{const resolved=fs.realpathSync(file);configuration.push({executable:file,resolved,sha256:sha(readProjectFile(path.parse(resolved).root,resolved,16*1024*1024))});}
    catch{known=false;configuration.push({executable:file,identityUnavailable:true});}
  };
  try{
    const entries=sources.flatMap(source=>validateCommandConfiguration(JSON.parse(source.contents)).providers);
    for(const provider of providers){
      const id=provider.descriptor.id;const enabled=permissions[id]?.enabled??id==='wrapperguard';
      configuration.push({descriptor:provider.descriptor,permissions:permissions[id]});if(!enabled||id==='wrapperguard')continue;
      const entry=entries.find(item=>item.id===id);if(!entry||!provider.descriptor.inputScope){known=false;continue;}
      configuration.push(entry);
      const args=entry.command;const profileFile=args[args.length-1];
      executableIdentity(args[0]);
      const managed=args[1]==='-I'&&args[2]==='-m'&&args[3]==='wrapperguard_sdk'&&args[4]==='--profile'&&args.length===6&&path.resolve(path.dirname(profileFile))===path.resolve(managedDirectory);
      if(!managed){if(entry.inputScope?.externalFiles?.length)known=false;continue;}
      const anchored=path.join(fs.realpathSync(path.dirname(profileFile)),path.basename(profileFile));
      const profile=JSON.parse(readProjectFile(path.parse(anchored).root,anchored,65_536).toString('utf8'));
      configuration.push({profile});
      if(profile.reportPath&&!profile.runner){const producer=profile.format==='sarif'?sarifProducerIdentity(profile.reportPath):undefined;if(producer)configuration.push({producer});else known=false;}
      if(profile.runner?.sandbox?.executable)executableIdentity(profile.runner.sandbox.executable);
      else if(profile.runner?.executable)executableIdentity(profile.runner.executable);
      if(profile.runner?.rulesPath){const file=profile.runner.rulesPath;const canonicalFile=path.join(fs.realpathSync(path.dirname(file)),path.basename(file));configuration.push({rules:sha(readProjectFile(path.parse(canonicalFile).root,canonicalFile,16*1024*1024))});}
      // A module manifest may load other mutable scripts; its complete semantics are not declared.
      if(profile.runner?.modulePath)known=false;
      // Python entrypoints and model names do not identify the mutable package or
      // loaded model behind them. A single script/binary hash cannot prove that scope.
      if(['bandit','semgrep','psscriptanalyzer','local-ai'].includes(profile.runner?.tool))known=false;
      if(['http-audit','container-python'].includes(profile.runner?.tool)||profile.runner?.sandbox)known=false;
    }
    return{scopeId:sha(canonical({settings,configuration})),known};
  }catch{return{scopeId:sha(canonical({settings,configuration,unavailable:true})),known:false};}
}
