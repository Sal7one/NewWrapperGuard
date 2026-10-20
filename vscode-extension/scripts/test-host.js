#!/usr/bin/env node
'use strict';
const fs=require('node:fs/promises');const os=require('node:os');const path=require('node:path');
const{runTests}=require('@vscode/test-electron');
async function main(){
 const version=process.env.VSCODE_VERSION||'1.142.0';if(!/^\d+\.\d+\.\d+$/.test(version))throw new Error('VSCODE_VERSION must name an exact release.');
 const executable=process.env.VSCODE_EXECUTABLE_PATH;if(executable&&!path.isAbsolute(executable))throw new Error('Choose an absolute installed VS Code executable.');
 const base=await fs.mkdtemp(path.join(os.tmpdir(),'wg-real-host-'));const user=path.join(base,'user'),extensions=path.join(base,'extensions'),project=path.join(base,'project'),second=path.join(base,'second');
 try{
  await fs.mkdir(path.join(user,'User'),{recursive:true});await fs.mkdir(extensions);await fs.mkdir(project);await fs.mkdir(second);await fs.writeFile(path.join(project,'fixture.txt'),'Inert host fixture one.\n');await fs.writeFile(path.join(second,'fixture.txt'),'Inert host fixture two.\n');
  await fs.writeFile(path.join(user,'User/settings.json'),JSON.stringify({'workbench.startupEditor':'none','wrapperGuard.scanOnOpen':false,'security.workspace.trust.enabled':false,'update.mode':'none','extensions.autoCheckUpdates':false,'extensions.autoUpdate':false}));
  const workspace=path.join(base,'test.code-workspace');await fs.writeFile(workspace,JSON.stringify({folders:[{path:project},{path:second}]}));const result=path.join(base,'host-result.json');
  await runTests({version,...(executable?{vscodeExecutablePath:executable}:{}),extensionDevelopmentPath:path.resolve(__dirname,'..'),extensionTestsPath:path.resolve(__dirname,'../host-tests/index.js'),extensionTestsEnv:{WRAPPERGUARD_HOST_PROJECT:project,WRAPPERGUARD_HOST_SECOND:second,WRAPPERGUARD_HOST_RESULT:result},launchArgs:[workspace,'--user-data-dir='+user,'--extensions-dir='+extensions,'--disable-extensions','--disable-telemetry','--skip-welcome','--skip-release-notes','--disable-workspace-trust']});
  const evidence=JSON.parse(await fs.readFile(result,'utf8'));console.log(JSON.stringify(evidence,null,2));
  if(process.env.WRAPPERGUARD_HOST_EVIDENCE){const target=path.resolve(process.env.WRAPPERGUARD_HOST_EVIDENCE);await fs.mkdir(path.dirname(target),{recursive:true});await fs.writeFile(target,JSON.stringify(evidence,null,2)+'\n');}
 }finally{await fs.rm(base,{recursive:true,force:true});}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
