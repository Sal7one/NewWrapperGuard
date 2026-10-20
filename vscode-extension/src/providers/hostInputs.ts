import * as fs from 'node:fs';
import * as path from 'node:path';
import { createHash } from 'node:crypto';
import { ProviderInputScope, GENERATED_EXCLUDES } from './inputScope';
import { VettingProvider, ProviderPermissions } from './sdk';
import { readProjectFile } from '../scanner/safePath';
import { DEFAULT_SEAL_LIMITS } from './inputSeal';

// Candidate names catch additions/deletions; observed reads below cover arbitrary recursive paths.
export const HOST_SOURCE_GLOBS=['**/gradlew','**/gradlew.*','**/gradle-wrapper.properties','**/gradle-wrapper.jar','**/*.gradle','**/*.kts','**/*.kt','**/*.java','**/*.py','**/*.sh','**/*.bash','**/*.zsh','**/*.bats','**/*.ps1','**/*.psm1','**/*.psd1','**/*.bat','**/*.cmd','**/*.toml','**/pom.xml','**/maven-wrapper.properties','**/maven-wrapper.jar','**/mvnw','**/mvnw.cmd','**/gradle.lockfile','**/package-lock.json','**/yarn.lock','**/pnpm-lock.yaml','**/requirements*.txt','**/poetry.lock','**/Pipfile.lock','**/uv.lock','**/AndroidManifest.xml'];
/** Host-known first-party profile paths remain covered when an older connection has no scope. */
export function hostInputScopes(providers:readonly VettingProvider[],permissions:Record<string,ProviderPermissions>,configFiles:string[],managedDirectory:string,baseline:string):ProviderInputScope[] {
  const output:ProviderInputScope[]=[];const files=new Set(configFiles);
  if(fs.existsSync(baseline))files.add(baseline);
  if(permissions.wrapperguard?.enabled!==false)output.push({includes:HOST_SOURCE_GLOBS,excludes:GENERATED_EXCLUDES});
  for(const provider of providers){
    if(!(permissions[provider.descriptor.id]?.enabled??provider.descriptor.id==='wrapperguard'))continue;
    if(provider.descriptor.inputScope)output.push(provider.descriptor.inputScope);
    const profile=path.join(managedDirectory,provider.descriptor.id+'.profile.json');
    // Catalog method IDs can differ from provider IDs (Bandit / PowerShell).
    const candidates=[profile,...(provider.descriptor.id==='python.bandit'?[path.join(managedDirectory,'sast.bandit.profile.json')]:[]),...(provider.descriptor.id==='powershell.psscriptanalyzer'?[path.join(managedDirectory,'sast.powershell.profile.json')]:[])];
    for(const file of candidates){
      if(!fs.existsSync(file))continue;files.add(file);
      const anchored=path.join(fs.realpathSync(path.dirname(file)),path.basename(file));
      const raw=JSON.parse(readProjectFile(path.parse(anchored).root,anchored,65_536).toString('utf8'));
      for(const value of [raw.reportPath,raw.runner?.rulesPath,raw.runner?.modulePath,raw.runner?.inputReportPath])if(typeof value==='string'&&path.isAbsolute(value))files.add(value);
    }
  }
  if(files.size)output.push({externalFiles:[...files].map(file=>path.join(fs.realpathSync(path.dirname(file)),path.basename(file)))});return output;
}
export class ObservedReadSet {
  private readonly hashes=new Map<string,string>();private bytes=0;private invalid=false;
  private readonly projectRoot:string;
  constructor(projectRoot:string){this.projectRoot=fs.realpathSync(projectRoot);}
  observe=(root:string,file:string,bytes:Buffer):void=>{
    if(root!==this.projectRoot)return;
    const hash=createHash('sha256').update(bytes).digest('hex');const previous=this.hashes.get(file);
    if(previous&&previous!==hash)this.invalid=true;
    if(!previous){this.bytes+=bytes.length;this.hashes.set(file,hash);}
    if(this.hashes.size>DEFAULT_SEAL_LIMITS.maxFiles||this.bytes>DEFAULT_SEAL_LIMITS.maxTotalBytes)this.invalid=true;
  };
  verify():boolean {
    if(this.invalid)return false;
    try{return[...this.hashes].every(([file,hash])=>createHash('sha256').update(readProjectFile(this.projectRoot,file,DEFAULT_SEAL_LIMITS.maxFileBytes)).digest('hex')===hash);}catch{return false;}
  }
}
