import * as vscode from 'vscode';
import { AutomationPreferences, AutomationProviderPolicy, validateAutomationPreferences } from './automation';
export interface AutomationCandidate {id:string;title:string;enabled:boolean}
export async function configureAutomation(previous:AutomationPreferences,candidates:AutomationCandidate[]):Promise<AutomationPreferences|undefined> {
  const selected=await vscode.window.showQuickPick(candidates.map(candidate=>({label:candidate.title,description:candidate.id+' · '+(candidate.enabled?'Enabled':'Disabled · enable separately before Start'),id:candidate.id,picked:!!previous.providers[candidate.id]})),{title:'Automation — choose providers',canPickMany:true,ignoreFocusOut:true,placeHolder:'Only these providers may run automatically. This does not enable them or grant actions.'});
  if(!selected)return undefined;if(!selected.length)throw new Error('Select at least one provider, or use Pause to stop automation.');
  const providers:Record<string,AutomationProviderPolicy>={};
  for(const selectedProvider of selected){
    const network=await vscode.window.showQuickPick([{label:'No network expected',value:'NONE' as const},{label:'Local service only',value:'LOCAL' as const},{label:'Remote network access',value:'REMOTE' as const},{label:'Network behavior is unknown',value:'UNKNOWN' as const}],{title:selectedProvider.label+' — network declaration',placeHolder:'User review only; this declaration does not restrict a trusted process.',ignoreFocusOut:true});if(!network)return undefined;
    const cost=await vscode.window.showQuickPick([{label:'Low local resource cost',value:'LOW' as const},{label:'Metered or potentially expensive',value:'METERED' as const},{label:'Cost is unknown',value:'UNKNOWN' as const}],{title:selectedProvider.label+' — resource/cost declaration',ignoreFocusOut:true});if(!cost)return undefined;
    providers[selectedProvider.id]={network:network.value,cost:cost.value,acknowledged:true};
  }
  const triggers=await vscode.window.showQuickPick([{label:'After source changes',value:'changes',picked:previous.onChanges},{label:'On an interval while this editor is open',value:'interval',picked:!!previous.intervalMinutes}],{title:'Automation — triggers',canPickMany:true,ignoreFocusOut:true});if(!triggers)return undefined;
  const onChanges=triggers.some(item=>item.value==='changes'),hasInterval=triggers.some(item=>item.value==='interval');
  if(!onChanges&&!hasInterval)throw new Error('Choose at least one automatic scan trigger.');
  let debounceMs=previous.debounceMs,intervalMinutes=hasInterval?(previous.intervalMinutes||15):0;
  if(onChanges){const input=await vscode.window.showInputBox({title:'Wait after changes (milliseconds)',value:String(debounceMs),prompt:'500–60000 ms. Consecutive changes combine into one scan.',ignoreFocusOut:true,validateInput:text=>/^\d+$/.test(text)&&Number(text)>=500&&Number(text)<=60000?undefined:'Choose 500–60000 milliseconds.'});if(input===undefined)return undefined;debounceMs=Number(input);}
  if(hasInterval){const input=await vscode.window.showInputBox({title:'Automatic scan interval (minutes)',value:String(intervalMinutes),prompt:'5–1440 minutes, while this editor session is open. Missed runs are not replayed.',ignoreFocusOut:true,validateInput:text=>/^\d+$/.test(text)&&Number(text)>=5&&Number(text)<=1440?undefined:'Choose 5–1440 minutes.'});if(input===undefined)return undefined;intervalMinutes=Number(input);}
  const review=await vscode.window.showQuickPick([{label:'Save reviewed preferences',save:true,description:selected.map(item=>item.id+': '+providers[item.id].network+'/'+providers[item.id].cost).join(' · ')},{label:'Cancel',save:false}],{title:'Automation stays paused until Start',placeHolder:'Only SCAN runs. No build/sync launches, downloads, permission changes or catch-up runs. Network/cost declarations are not a sandbox.',ignoreFocusOut:true});
  return review?.save?validateAutomationPreferences({schemaVersion:1,enabled:true,onChanges,debounceMs,intervalMinutes,providers}):undefined;
}
