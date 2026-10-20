import * as vscode from 'vscode';
import { isIP } from 'node:net';
import { editLiteralList } from '../providers/customSetup';
export function numericAuditUrl(value:string):string {
  const url=new URL(value);const authority=value.match(/^https?:\/\/(\[[0-9a-fA-F:.]+\]|[0-9.]+)(?::[0-9]+)?(?:\/|$)/);
  if(!authority||!isIP(authority[1].replace(/^\[|\]$/g,''))||!['http:','https:'].includes(url.protocol)||url.username||url.password||url.search||url.hash||value.length>8192||/[^\x21-\x7e]|[\\%]|(?:^|\/)\.{1,2}(?:\/|$)/.test(value)||url.pathname.includes('//'))throw new Error('Use an exact HTTP(S) numeric IP URL without credentials, queries, fragments or encoded/dot paths. DNS names are not used by this bounded audit.');
  return url.href;
}
export interface HttpAuditOptions {targetScope:{origins:string[];pathPrefixes:string[]};runner:{tool:'http-audit';ownershipConfirmed:true;targetUrls:string[];maxRequests:number;requestTimeoutMs:number;maxResponseBytes:number;timeoutMs:number;authHeadersEnv?:Record<string,string>}}
export async function configureHttpAudit():Promise<HttpAuditOptions|undefined> {
  const ownership=await vscode.window.showQuickPick([{label:'I own or am authorized to test these exact targets',owned:true,description:'Sends GET requests to explicitly selected URLs; no crawl or redirects'},{label:'Cancel active audit setup',owned:false}],{title:'Owned-target HTTP audit — authorization',placeHolder:'This is active network access. No application starts and no exploit payload is sent. Choose only targets you are authorized to test.',ignoreFocusOut:true});if(!ownership?.owned)return undefined;
  const selected=await editLiteralList('Owned HTTP target URLs',[],value=>{try{numericAuditUrl(value);return undefined;}catch(error){return(error as Error).message;}},32);if(!selected)return undefined;
  const targetUrls=selected.map(numericAuditUrl);if(!targetUrls.length||new Set(targetUrls).size!==targetUrls.length)throw new Error('Choose 1–32 unique, exact numeric-IP target URLs.');
  const runner:HttpAuditOptions['runner']={tool:'http-audit',ownershipConfirmed:true,targetUrls,maxRequests:Math.max(16,targetUrls.length),requestTimeoutMs:5000,maxResponseBytes:262144,timeoutMs:30000};
  for(const[field,title,min,max]of [['maxRequests','Maximum requests',targetUrls.length,32],['requestTimeoutMs','Per-request timeout (ms)',100,10000],['maxResponseBytes','Maximum response bytes',1024,1048576],['timeoutMs','Total audit timeout (ms)',100,300000]] as const){
    const input=await vscode.window.showInputBox({title:'HTTP audit — '+title,value:String(runner[field]),prompt:min+'–'+max+'. Requests remain limited to the exact URLs; redirects are not followed.',ignoreFocusOut:true,validateInput:value=>/^\d+$/.test(value)&&Number(value)>=min&&Number(value)<=max?undefined:'Choose an integer from '+min+' to '+max+'.'});if(input===undefined)return undefined;
    if(!/^\d+$/.test(input)||Number(input)<min||Number(input)>max)throw new Error('Invalid HTTP audit budget.');runner[field]=Number(input);
  }
  const auth=await vscode.window.showQuickPick([{label:'No authentication headers',auth:false},{label:'Reference existing environment variables',description:'Store variable names only; never type tokens or cookies into this dialog',auth:true}],{title:'HTTP audit — authentication',ignoreFocusOut:true});if(!auth)return undefined;
  if(auth.auth){
    const headers=await vscode.window.showQuickPick([{label:'Authorization',value:'Authorization'},{label:'Cookie',value:'Cookie'}],{title:'Choose authentication headers',canPickMany:true,ignoreFocusOut:true});if(!headers)return undefined;
    const references:Record<string,string>={};
    for(const header of headers){const name=await vscode.window.showInputBox({title:header.value+' — environment variable name',prompt:'Only the variable name is stored. Set its secret value before launching VS Code. The value is sent only to the exact reviewed targets.',ignoreFocusOut:true,validateInput:value=>/^[A-Z_][A-Z0-9_]{0,127}$/.test(value)?undefined:'Enter an uppercase environment variable name, not the secret.'});if(name===undefined)return undefined;if(!/^[A-Z_][A-Z0-9_]{0,127}$/.test(name))throw new Error('Enter an environment variable name, not an authentication value.');references[header.value]=name;}
    if(Object.keys(references).length)runner.authHeadersEnv=references;
  }
  const scope={origins:[...new Set(targetUrls.map(value=>new URL(value).origin))],pathPrefixes:[...new Set(targetUrls.map(value=>new URL(value).pathname.replace(/\/$/,'')||'/'))]};
  const review=await vscode.window.showQuickPick([{label:'Use these exact targets and budgets',approved:true,description:targetUrls.join(' · '),detail:runner.maxRequests+' requests maximum · '+runner.requestTimeoutMs+' ms/request · '+runner.maxResponseBytes+' bytes/response · '+runner.timeoutMs+' ms total · '+Object.keys(runner.authHeadersEnv??{}).join(', ')},{label:'Cancel',approved:false}],{title:'Review active HTTP audit',placeHolder:'GET response headers/cookies only. No redirects, DNS lookup, crawling, exploitation, builds or target startup. Coverage is limited to these responses.',ignoreFocusOut:true});
  return review?.approved?{targetScope:scope,runner}:undefined;
}
