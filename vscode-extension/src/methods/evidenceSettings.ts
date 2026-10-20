import * as vscode from 'vscode';
import * as path from 'node:path';
import { realpath, writeFile, rename } from 'node:fs/promises';
import { readProjectFile } from '../scanner/safePath';
import { SecurityMethod } from './catalog';
import { isWithin } from '../providers/customSetup';
/** Optional evidence constraints belong to the connection, not to a vendor implementation. */
export async function editEvidenceSettings(storage:string,method:SecurityMethod,roots:string[]):Promise<boolean> {
  const base=await realpath(storage);for(const root of roots)if(isWithin(base,await realpath(root)))throw new Error('Connection storage must be outside every project.');
  const file=path.join(base,'integrations',method.id+'.profile.json');
  const profile=JSON.parse(readProjectFile(base,file,65536).toString('utf8'));
  if(profile.id!==method.providerId)throw new Error('This managed connection has a different identity.');
  const original=JSON.stringify(profile);
  while(true){
    const choice=await vscode.window.showQuickPick([
      ...(!profile.runner&&profile.reportPath?[{label:'Report provenance manifest',description:profile.reportProvenance?.manifestPath??'Optional · no freshness verification configured',action:'provenance'},{label:'Maximum report age',description:String(profile.reportProvenance?.maxAgeSeconds??86400)+' seconds',action:'age'},{label:'Remove provenance requirement',description:'Imported reports will no longer be verified against manifest inputs/age',action:'clear'}]:[]),
      ...(method.category==='SUPPLY_CHAIN'?[{label:'Require resolved dependency versions',description:profile.dependencyCoverage?.requireResolvedVersions?'On · unresolved versions make coverage incomplete':'Off · unresolved coverage remains visible',action:'versions'}]:[]),
      {label:'Save evidence settings',description:'No scanner runs. Review the resulting coverage after an explicit scan.',action:'save'},{label:'Cancel',description:'Discard changes',action:'cancel'}
    ],{title:method.title+' — evidence & coverage',ignoreFocusOut:true});
    if(!choice||choice.action==='cancel')return false;
    if(choice.action==='provenance'){
      const selected=await vscode.window.showOpenDialog({title:'Choose a provenance manifest outside every project',canSelectFiles:true,canSelectFolders:false,canSelectMany:false,filters:{'JSON manifest':['json']},openLabel:'Use manifest'});if(!selected?.[0])continue;
      const manifest=path.join(await realpath(path.dirname(selected[0].fsPath)),path.basename(selected[0].fsPath));
      for(const root of roots)if(isWithin(manifest,await realpath(root)))throw new Error('Provenance manifests must be outside every open project.');
      readProjectFile(path.parse(manifest).root,manifest,1024*1024);
      profile.reportProvenance={manifestPath:manifest,maxAgeSeconds:profile.reportProvenance?.maxAgeSeconds??86400};
    }else if(choice.action==='age'){
      if(!profile.reportProvenance){await vscode.window.showInformationMessage('Choose a provenance manifest first.');continue;}
      const input=await vscode.window.showInputBox({title:'Maximum report age in seconds',value:String(profile.reportProvenance.maxAgeSeconds??86400),prompt:'1–604800 seconds. This checks listed-input hashes and age; it does not authenticate the report producer or prove full project coverage.',ignoreFocusOut:true,validateInput:value=>/^\d+$/.test(value)&&Number(value)>=1&&Number(value)<=604800?undefined:'Choose 1–604800 seconds.'});if(input===undefined)continue;
      if(!/^\d+$/.test(input)||Number(input)<1||Number(input)>604800)throw new Error('Choose a report age from 1 to 604800 seconds.');profile.reportProvenance.maxAgeSeconds=Number(input);
    }else if(choice.action==='clear')delete profile.reportProvenance;
    else if(choice.action==='versions')profile.dependencyCoverage={requireResolvedVersions:!profile.dependencyCoverage?.requireResolvedVersions};
    else if(choice.action==='save'){
      if(JSON.stringify(profile)===original)return false;
      const current=JSON.parse(readProjectFile(base,file,65536).toString('utf8'));if(JSON.stringify(current)!==original)throw new Error('Connection settings changed during review. Open evidence settings again.');
      const temporary=file+'.evidence.tmp';await writeFile(temporary,JSON.stringify(profile,null,2)+'\n',{flag:'wx',mode:0o600});await rename(temporary,file);return true;
    }
  }
}
