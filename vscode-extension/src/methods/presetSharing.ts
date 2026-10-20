import * as vscode from 'vscode';
import { SECURITY_METHODS } from './catalog';
import { createOrganizationPreset, OrganizationPreset, readOrganizationPreset, writeOrganizationPreset } from './organizationPresets';

/** Imported files are passive data. Applying flags is always a separate review. */
export async function importPreset(currentFlags:Record<string,boolean>):Promise<OrganizationPreset|undefined> {
  const files=await vscode.window.showOpenDialog({title:'Import an organization method preset',canSelectFiles:true,canSelectFolders:false,canSelectMany:false,filters:{'WrapperGuard preset':['json']},openLabel:'Review preset'});
  if(!files?.[0])return undefined;const preset=await readOrganizationPreset(files[0].fsPath);
  const changes=Object.keys(preset.methodFlags).filter(id=>preset.methodFlags[id]!==currentFlags[id]);
  const review=await vscode.window.showQuickPick([{label:'Apply reviewed method flags',apply:true,description:changes.length+' selection changes',detail:changes.map(id=>(preset.methodFlags[id]?'Enable ':'Disable ')+(SECURITY_METHODS.find(method=>method.id===id)?.title??id)).join(' · ')},{label:'Cancel',apply:false}],{title:preset.title+' · '+preset.version+' · '+preset.issuer,placeHolder:'SHA-256 '+preset.digest+' verifies file integrity only; publisher identity is not authenticated. No grants, commands, downloads or execution.',ignoreFocusOut:true});
  return review?.apply?preset:undefined;
}
export async function exportPreset(flags:Record<string,boolean>):Promise<OrganizationPreset|undefined> {
  const values:string[]=[];
  for(const[field,value]of [['ID','organization.default'],['Title','Organization method selection'],['Version','1.0.0'],['Issuer','My organization']]){
    const input=await vscode.window.showInputBox({title:'Export preset — '+field,value,ignoreFocusOut:true,prompt:'Data-only method selection. No provider permissions, commands, paths or credentials are exported.'});if(input===undefined)return undefined;values.push(input);
  }
  const preset=createOrganizationPreset({id:values[0],title:values[1],version:values[2],issuer:values[3]},flags);
  const destination=await vscode.window.showSaveDialog({title:'Export reviewed method selection',defaultUri:vscode.Uri.file(preset.id+'.json'),filters:{'WrapperGuard preset':['json']}});if(!destination)return undefined;
  await writeOrganizationPreset(destination.fsPath,preset);void vscode.window.showInformationMessage('Preset exported with SHA-256 '+preset.digest+'. The digest checks integrity; it is not a publisher signature.');return preset;
}
