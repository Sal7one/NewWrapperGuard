import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';
import { ScanReport } from '../models';
import { VettingOperation } from '../providers/sdk';
import { checkedProjectDirectory, readProjectFile } from '../scanner/safePath';
import { maskSecretValues } from '../scanner/redaction';

export const HISTORY_BYTE_LIMIT=2*1024*1024;
export const HISTORY_UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const HEX=/^[0-9a-f]{64}$/;
const VERDICTS=['NOT_SCANNED','CLEAN','REVIEW_REQUIRED','EXECUTION_BLOCKED','INCOMPLETE','STALE','FAILED'];
export interface HistoryFinding {fingerprint:string;providerId:string;ruleId:string;title:string;severity:'INFO'|'WARN'|'ERROR';status:string;path:string}
export interface HistoryScope {scopeId:string;known:boolean}
export interface HistoryEntry {schemaVersion:1;id:string;projectId:string;createdAt:number;operation:VettingOperation;scopeId:string;scopeKnown:boolean;complete:boolean;stale:boolean;truncated:boolean;verdict:string;providers:Array<{id:string;version:string;status:string}>;findings:HistoryFinding[]}
export interface HistoryDelta {available:boolean;reason?:string;newlyReported:HistoryFinding[];noLongerReported:HistoryFinding[];unchanged:number}
export type HistoryListItem=Omit<HistoryEntry,'findings'> & {findingCount:number;providerCount:number};
export interface HistoryView {enabled:boolean;limit:number;entries:HistoryListItem[];comparison?:HistoryDelta;comparisonLabel?:string;error?:string}
const object=(value:unknown):value is Record<string,unknown>=>!!value&&typeof value==='object'&&!Array.isArray(value);
const text=(value:unknown,max:number,empty=false):value is string=>typeof value==='string'&&value.length<=max&&(empty||!!value)&&!/[\u0000-\u001f\u007f-\u009f]/.test(value);
const exact=(value:Record<string,unknown>,keys:string[]):boolean=>Object.keys(value).length===keys.length&&Object.keys(value).every(key=>keys.includes(key));
const relative=(value:unknown):value is string=>text(value,4096)&&!path.isAbsolute(value)&&!value.includes('\\')&&!/^[A-Za-z]:/.test(value)&&!value.split('/').includes('..');
const failure=():Error=>new Error('Local history could not be read safely. It may be malformed, oversized or outside its owned storage.');
export function validateHistoryEntry(value:unknown):HistoryEntry {
  if(!object(value)||!exact(value,['schemaVersion','id','projectId','createdAt','operation','scopeId','scopeKnown','complete','stale','truncated','verdict','providers','findings'])||value.schemaVersion!==1||typeof value.id!=='string'||!HISTORY_UUID.test(value.id)||typeof value.projectId!=='string'||!HEX.test(value.projectId)||typeof value.scopeId!=='string'||!HEX.test(value.scopeId)||!Number.isSafeInteger(value.createdAt)||(value.createdAt as number)<0||!['SCAN','BUILD','SYNC'].includes(value.operation as string)||!VERDICTS.includes(value.verdict as string)||['scopeKnown','complete','stale','truncated'].some(key=>typeof value[key]!=='boolean')||!Array.isArray(value.providers)||value.providers.length>33||!Array.isArray(value.findings)||value.findings.length>5000)throw failure();
  for(const provider of value.providers)if(!object(provider)||!exact(provider,['id','version','status'])||typeof provider.id!=='string'||!/^[a-z][a-z0-9._-]{0,79}$/.test(provider.id)||!text(provider.version,80)||!['COMPLETED','FAILED','TIMED_OUT','DISABLED'].includes(provider.status as string))throw failure();
  for(const finding of value.findings)if(!object(finding)||!exact(finding,['fingerprint','providerId','ruleId','title','severity','status','path'])||!text(finding.fingerprint,128)||typeof finding.providerId!=='string'||!/^[a-z][a-z0-9._-]{0,79}$/.test(finding.providerId)||typeof finding.ruleId!=='string'||!/^[A-Za-z0-9][A-Za-z0-9_.:/-]{0,199}$/.test(finding.ruleId)||!text(finding.title,512,true)||!['INFO','WARN','ERROR'].includes(finding.severity as string)||!text(finding.status,64)||!relative(finding.path))throw failure();
  return value as unknown as HistoryEntry;
}
export function historyProjectId(projectRoot:string):string {let root=fs.realpathSync(projectRoot).replace(/\\/g,'/');if(process.platform==='win32')root=root.toLowerCase();return createHash('sha256').update(root).digest('hex');}
export function compareHistory(older:HistoryEntry,newer:HistoryEntry):HistoryDelta {
  const unavailable=(reason:string):HistoryDelta=>({available:false,reason,newlyReported:[],noLongerReported:[],unchanged:0});
  if(older.projectId!==newer.projectId)return unavailable('These scans belong to different projects.');
  if(older.operation!==newer.operation)return unavailable('The scan operations differ.');
  if(!older.scopeKnown||!newer.scopeKnown)return unavailable('A provider configuration or input scope is unknown.');
  if(older.scopeId!==newer.scopeId)return unavailable('The configured methods, providers or rules changed.');
  if(!older.complete||!newer.complete||older.stale||newer.stale||older.truncated||newer.truncated)return unavailable('Both scans must be complete, fresh and untruncated.');
  const key=(finding:HistoryFinding)=>finding.providerId+':'+finding.fingerprint;
  const before=new Map(older.findings.map(finding=>[key(finding),finding])),after=new Map(newer.findings.map(finding=>[key(finding),finding]));
  return{available:true,newlyReported:[...after].filter(([id])=>!before.has(id)).map(([,finding])=>finding),noLongerReported:[...before].filter(([id])=>!after.has(id)).map(([,finding])=>finding),unchanged:[...after.keys()].filter(id=>before.has(id)).length};
}
export function historyListItem(entry:HistoryEntry):HistoryListItem {const{findings,...metadata}=entry;return{...metadata,findingCount:findings.length,providerCount:entry.providers.length};}
export class HistoryStore {
  constructor(private readonly directory:string,private readonly workspaceRoots:readonly string[]=[]){ }
  private location(projectRoot:string):{base:string;directory:string;projectId:string} {
    const root=fs.realpathSync(projectRoot);const inside=(file:string,base:string):boolean=>{const rel=path.relative(base,file);return rel===''||rel!=='..'&&!rel.startsWith('..'+path.sep)&&!path.isAbsolute(rel);};
    if(!path.isAbsolute(this.directory)||[projectRoot,...this.workspaceRoots].some(base=>inside(path.resolve(this.directory),path.resolve(base))))throw failure();
    fs.mkdirSync(this.directory,{recursive:true,mode:0o700});if(fs.lstatSync(this.directory).isSymbolicLink())throw failure();
    const base=fs.realpathSync(this.directory);if([root,...this.workspaceRoots.map(file=>fs.realpathSync(file))].some(project=>inside(base,project)))throw failure();
    const projectId=historyProjectId(root),directory=path.join(base,projectId);fs.mkdirSync(directory,{recursive:true,mode:0o700});if(!checkedProjectDirectory(base,directory))throw failure();return{base,directory,projectId};
  }
  read(projectRoot:string,id:string):HistoryEntry {
    if(!HISTORY_UUID.test(id))throw failure();const location=this.location(projectRoot),file=path.join(location.directory,id+'.json');
    try{const bytes=readProjectFile(location.base,file,HISTORY_BYTE_LIMIT);const value=validateHistoryEntry(JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes)));if(value.projectId!==location.projectId||value.id!==id)throw failure();return value;}catch{throw failure();}
  }
  list(projectRoot:string):HistoryEntry[] {
    const location=this.location(projectRoot),files=fs.readdirSync(location.directory);if(files.length>1000)throw failure();
    const owned=files.filter(file=>file.endsWith('.json')&&HISTORY_UUID.test(file.slice(0,-5)));if(owned.length>100)throw failure();
    return owned.map(file=>this.read(projectRoot,file.slice(0,-5))).sort((a,b)=>b.createdAt-a.createdAt||b.id.localeCompare(a.id));
  }
  record(projectRoot:string,report:ScanReport,operation:VettingOperation,scope:HistoryScope,retention=20):HistoryEntry {
    if(!Number.isInteger(retention)||retention<1||retention>100||!HEX.test(scope.scopeId))throw failure();
    if(fs.realpathSync(report.projectPath)!==fs.realpathSync(projectRoot))throw failure();
    const location=this.location(projectRoot),previous=this.list(projectRoot);const createdAt=Math.max(Date.now(),(previous[0]?.createdAt??0)+1);const entry:HistoryEntry={schemaVersion:1,id:randomUUID(),projectId:location.projectId,createdAt,operation,scopeId:scope.scopeId,scopeKnown:scope.known,complete:report.complete,stale:report.stale,truncated:false,verdict:report.verdict,providers:(report.providerRuns??[]).map(run=>({id:run.descriptor.id,version:run.descriptor.version,status:run.status})),findings:[]};
    let bytes=Buffer.byteLength(JSON.stringify(entry));
    for(const input of report.findings){
      const finding:HistoryFinding={fingerprint:input.fingerprint,providerId:input.providerId??'wrapperguard',ruleId:input.ruleId,title:maskSecretValues(input.title).masked.replace(/[\u0000-\u001f\u007f-\u009f]/g,' ').slice(0,512),severity:input.severity,status:input.status,path:input.location.path.replace(/\\/g,'/')};
      if(entry.findings.length>=5000||!relative(finding.path)){entry.truncated=true;continue;}
      const size=Buffer.byteLength(JSON.stringify(finding))+1;if(bytes+size>HISTORY_BYTE_LIMIT){entry.truncated=true;break;}entry.findings.push(finding);bytes+=size;
    }
    validateHistoryEntry(entry);const file=path.join(location.directory,entry.id+'.json'),temporary=file+'.tmp';fs.writeFileSync(temporary,JSON.stringify(entry),{encoding:'utf8',mode:0o600,flag:'wx'});fs.renameSync(temporary,file);
    for(const old of previous.slice(retention-1))fs.unlinkSync(path.join(location.directory,old.id+'.json'));
    return entry;
  }
  clear(projectRoot:string):void {const location=this.location(projectRoot);const files=fs.readdirSync(location.directory);if(files.length>1000)throw failure();for(const file of files)if(file.endsWith('.json')&&HISTORY_UUID.test(file.slice(0,-5))){const target=path.join(location.directory,file);if(fs.lstatSync(target).isSymbolicLink())throw failure();if(fs.lstatSync(target).isFile())fs.unlinkSync(target);}}
}
