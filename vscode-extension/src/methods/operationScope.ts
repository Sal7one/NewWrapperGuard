import * as path from 'node:path';
import { realpathSync } from 'node:fs';
import { SECURITY_METHODS } from './catalog';
import { validateCommandConfiguration } from '../providers/commandProvider';
import { ProviderPermissions } from '../providers/sdk';
import { readProjectFile } from '../scanner/safePath';

/** Only generated, explicitly selected profiles declare first-party operation support. */
export function managedOperationScope(operation:string,sources:readonly {file:string;contents:string}[],managedConfiguration:string,permissions:Record<string,ProviderPermissions>):{excluded:string[];files:string[];verify:()=>boolean} {
  const excluded:string[]=[];const reads=new Map<string,Buffer>();
  if(operation!=='SCAN')for(const source of sources){
    if(path.resolve(source.file)!==path.resolve(managedConfiguration))continue;
    for(const entry of validateCommandConfiguration(JSON.parse(source.contents)).providers){
      if(!permissions[entry.id]?.enabled)continue;
      const method=SECURITY_METHODS.find(method=>method.providerId===entry.id);const args=entry.command;
      if(!method||args.length!==6||args[1]!=='-I'||args[2]!=='-m'||args[3]!=='wrapperguard_sdk'||args[4]!=='--profile')continue;
      const expected=path.join(path.dirname(managedConfiguration),method.id+'.profile.json');
      try{
        const directory=realpathSync(path.dirname(expected));const file=path.join(directory,path.basename(expected));
        if(path.join(realpathSync(path.dirname(args[5])),path.basename(args[5]))!==file)continue;
        if(!entry.inputScope?.externalFiles?.some(value=>path.resolve(value)===file))continue;
        const bytes=readProjectFile(directory,file,65_536);const profile=JSON.parse(bytes.toString('utf8'));const operations=profile.allowedOperations;
        if(profile.id!==entry.id||!Array.isArray(operations)||operations.length<1||operations.length>3||operations.some(value=>!['SCAN','BUILD','SYNC'].includes(value))||new Set(operations).size!==operations.length)continue;
        reads.set(file,bytes);if(!operations.includes(operation))excluded.push(entry.id);
      }catch{/* Malformed or unreadable profiles retain normal isolated provider failure. */}
    }
  }
  return{excluded,files:[...reads.keys()],verify:()=>{try{return[...reads].every(([file,bytes])=>bytes.equals(readProjectFile(path.dirname(file),file,65_536)));}catch{return false;}}};
}
