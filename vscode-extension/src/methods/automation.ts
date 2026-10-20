export interface AutomationProviderPolicy {network:'NONE'|'LOCAL'|'REMOTE'|'UNKNOWN';cost:'LOW'|'METERED'|'UNKNOWN';acknowledged:boolean}
export interface AutomationPreferences {schemaVersion:1;enabled:boolean;onChanges:boolean;debounceMs:number;intervalMinutes:number;providers:Record<string,AutomationProviderPolicy>}
export interface AutomationView {configured:boolean;running:boolean;status:string;project?:string;providerIds:string[];policies?:Record<string,AutomationProviderPolicy>;onChanges:boolean;debounceMs:number;intervalMinutes:number;lastRun?:number;error?:string}
export const DEFAULT_AUTOMATION:AutomationPreferences={schemaVersion:1,enabled:false,onChanges:true,debounceMs:2000,intervalMinutes:0,providers:{}};
export function validateAutomationPreferences(input:unknown):AutomationPreferences {
  if(!input||typeof input!=='object'||Array.isArray(input))throw new Error('Automation requires user-owned settings.');
  const value=input as AutomationPreferences;
  if(Object.keys(value).length!==6||Object.keys(value).some(key=>!['schemaVersion','enabled','onChanges','debounceMs','intervalMinutes','providers'].includes(key))||value.schemaVersion!==1||typeof value.enabled!=='boolean'||typeof value.onChanges!=='boolean'||!Number.isInteger(value.debounceMs)||value.debounceMs<500||value.debounceMs>60000||!Number.isInteger(value.intervalMinutes)||(value.intervalMinutes!==0&&(value.intervalMinutes<5||value.intervalMinutes>1440))||(!value.onChanges&&!value.intervalMinutes)||!value.providers||typeof value.providers!=='object'||Array.isArray(value.providers)||Object.keys(value.providers).length>33)throw new Error('Choose a 500–60000 ms debounce, a 5–1440 minute interval (or zero), and at least one trigger.');
  const providers:Record<string,AutomationProviderPolicy>=Object.create(null);
  for(const[id,policy]of Object.entries(value.providers)){
    if(!/^[a-z][a-z0-9._-]{0,79}$/.test(id)||!policy||typeof policy!=='object'||Object.keys(policy).length!==3||Object.keys(policy).some(key=>!['network','cost','acknowledged'].includes(key))||!['NONE','LOCAL','REMOTE','UNKNOWN'].includes(policy.network)||!['LOW','METERED','UNKNOWN'].includes(policy.cost)||typeof policy.acknowledged!=='boolean')throw new Error('Each automation provider needs a reviewed network and cost policy.');
    providers[id]={...policy};
  }
  return {...value,providers};
}
interface Clock {schedule(callback:()=>void,ms:number):unknown;cancel(handle:unknown):void;now():number}
const realClock:Clock={schedule:(callback,ms)=>setTimeout(callback,ms),cancel:handle=>clearTimeout(handle as ReturnType<typeof setTimeout>),now:()=>Date.now()};
/** Explicit session arming is separate from persisted preferences. Never starts on save/open. */
export class AutomationController {
  private preferences:AutomationPreferences=DEFAULT_AUTOMATION;
  private view:AutomationView={configured:false,running:false,status:'Paused · choose providers and start explicitly',providerIds:[],onChanges:true,debounceMs:2000,intervalMinutes:0};
  private debounce:unknown;private interval:unknown;private abort?:AbortController;private generation=0;
  constructor(private readonly run:(project:string,ids:readonly string[],signal:AbortSignal)=>Promise<void>,private readonly changed:(view:AutomationView)=>void,private readonly clock:Clock=realClock){}
  snapshot():AutomationView{return{...this.view,providerIds:[...this.view.providerIds],policies:Object.fromEntries(Object.entries(this.view.policies??{}).map(([id,policy])=>[id,{...policy}]))};}
  configure(preferences:AutomationPreferences):void {
    this.pause('Paused · saved settings require explicit Start');this.preferences=validateAutomationPreferences(preferences);
    this.view={...this.view,configured:preferences.enabled,providerIds:Object.keys(preferences.providers),policies:this.preferences.providers,onChanges:preferences.onChanges,debounceMs:preferences.debounceMs,intervalMinutes:preferences.intervalMinutes};this.emit();
  }
  start(project:string,readyIds:readonly string[]):void {
    const ids=Object.keys(this.preferences.providers);
    if(!this.preferences.enabled||!ids.length||ids.some(id=>!this.preferences.providers[id].acknowledged||!readyIds.includes(id)))throw new Error('Review and enable every selected provider before starting automation.');
    this.pause('Starting');this.view={...this.view,running:true,project,status:'Watching · no scan runs until the next trigger',error:undefined};this.emit();this.scheduleInterval();
  }
  inputChanged(project:string):void {
    if(!this.view.running||this.view.project!==project||!this.preferences.onChanges)return;
    if(this.debounce!==undefined)this.clock.cancel(this.debounce);
    this.debounce=this.clock.schedule(()=>{this.debounce=undefined;void this.tick();},this.preferences.debounceMs);
  }
  pause(reason='Paused by user'):void {
    this.generation++;if(this.debounce!==undefined)this.clock.cancel(this.debounce);if(this.interval!==undefined)this.clock.cancel(this.interval);this.debounce=undefined;this.interval=undefined;this.abort?.abort();this.view={...this.view,running:false,status:reason};this.emit();
  }
  dispose():void{this.pause('Stopped');}
  private emit():void{this.changed(this.snapshot());}
  private scheduleInterval():void {
    if(this.view.running&&this.preferences.intervalMinutes)this.interval=this.clock.schedule(()=>{this.interval=undefined;void this.tick();this.scheduleInterval();},this.preferences.intervalMinutes*60000);
  }
  private async tick():Promise<void> {
    if(!this.view.running||this.abort)return;const generation=this.generation;const project=this.view.project!;const abort=new AbortController();this.abort=abort;this.view={...this.view,status:'Scanning reviewed providers'};this.emit();
    try{await this.run(project,this.view.providerIds,abort.signal);if(generation===this.generation)this.view={...this.view,lastRun:this.clock.now(),status:'Watching · last automated scan completed',error:undefined};}
    catch{if(generation===this.generation){this.pause('Paused · automatic scan failed; review and start again');this.view={...this.view,error:'Automatic evaluation failed. Review the latest report and connection readiness.'};}}
    finally{if(this.abort===abort)this.abort=undefined;this.emit();}
  }
}
