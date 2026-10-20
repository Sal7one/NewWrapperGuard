const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');const Module=require('node:module');const{execFileSync}=require('node:child_process');
const original=Module._load;let maintenance,bootstrap;
try{Module._load=function(id,...args){return id==='vscode'?{ProgressLocation:{Notification:1},window:{withProgress:async(_options,callback)=>callback()}}:original.call(this,id,...args);};maintenance=require('../dist/methods/maintenance');bootstrap=require('../dist/methods/bootstrap');}finally{Module._load=original;}
async function fixture(t){const temporary=await fs.mkdtemp(path.join(os.tmpdir(),'wg-maintenance-'));const base=await fs.realpath(temporary);t.after(()=>fs.rm(base,{recursive:true,force:true}));const root=path.join(base,'project'),storage=path.join(base,'storage');await fs.mkdir(root);await fs.mkdir(storage);return{base,root,storage,environment:path.join(storage,'sdk-environment')};}
test('SDK removal requires ownership and rejects every saved reference including aliases and missing executable leaves',async t=>{
 const{base,root,storage,environment}=await fixture(t);await fs.mkdir(path.join(environment,'bin'),{recursive:true});await fs.writeFile(path.join(environment,'bin/python'),'generated');
 await assert.rejects(maintenance.removeManagedEnvironment(storage,[root],[]));assert.equal(await fs.readFile(path.join(environment,'bin/python'),'utf8'),'generated');await maintenance.markNewManagedEnvironment(environment);
 for(const reference of [path.join(environment,'bin/python'),path.join(environment,'bin/missing')])await assert.rejects(maintenance.removeManagedEnvironment(storage,[root],[reference]),/Saved connections/);
 const alias=path.join(base,'alias');await fs.symlink(storage,alias);await assert.rejects(maintenance.removeManagedEnvironment(storage,[root],[path.join(alias,'sdk-environment/bin/python')]),/Saved connections/);
 const original=path.join(base,'original-report.json');await fs.writeFile(original,'keep');await fs.symlink(original,path.join(environment,'external-link'));assert.equal(await maintenance.removeManagedEnvironment(storage,[root],[]),true);assert.equal(await fs.readFile(original,'utf8'),'keep');await assert.rejects(fs.lstat(environment),{code:'ENOENT'});
});
test('SDK removal preserves unsafe links, unknown ownership, sibling projects and outside files',async t=>{
 const{base,root,storage,environment}=await fixture(t);const outside=path.join(base,'other-environment');await fs.mkdir(outside);await fs.writeFile(path.join(outside,'original'),'keep');await fs.symlink(outside,environment);await assert.rejects(maintenance.removeManagedEnvironment(storage,[root],[]));assert.equal(await fs.readFile(path.join(outside,'original'),'utf8'),'keep');await fs.unlink(environment);await fs.mkdir(environment);await fs.writeFile(path.join(environment,maintenance.MANAGED_ENVIRONMENT_MARKER),'{"schemaVersion":1,"owner":"other"}');await assert.rejects(maintenance.removeManagedEnvironment(storage,[root],[]),/ownership/);await assert.rejects(maintenance.removeManagedEnvironment(storage,[root,storage],[]),/outside every/);
});
test('bundled SDK install and repair work offline in a real managed interpreter without scanner execution',async t=>{
 const{base,root,storage,environment}=await fixture(t);const run=(file,args)=>execFileSync(file,args,{encoding:'utf8',timeout:20000,stdio:['ignore','pipe','ignore']}).trim();let python;
 for(const candidate of [process.env.WRAPPERGUARD_TEST_PYTHON,process.env.WRAPPERGUARD_BUILD_PYTHON,'python3','python','/opt/homebrew/bin/python3','/usr/local/bin/python3'].filter(Boolean)){try{python=run(candidate,['-I','-c','import sys; assert sys.version_info >= (3,10); import ensurepip; print(sys.executable)']);break;}catch{}}
 assert.ok(python,'SDK offline installation regression requires Python 3.10+ with ensurepip.');
 const extensionPath=path.join(base,'extension');const wheel=path.join(extensionPath,'resources/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl');await fs.mkdir(path.dirname(wheel),{recursive:true});
 const files={'wrapperguard_sdk/__init__.py':'__version__ = "0.4.0"\n','wrapperguard_sdk-0.4.0.dist-info/METADATA':'Metadata-Version: 2.1\nName: wrapperguard-sdk\nVersion: 0.4.0\n','wrapperguard_sdk-0.4.0.dist-info/WHEEL':'Wheel-Version: 1.0\nGenerator: regression\nRoot-Is-Purelib: true\nTag: py3-none-any\n'};
 const code=String.raw`import json,sys,zipfile
files=json.loads(sys.argv[2])
files["wrapperguard_sdk-0.4.0.dist-info/RECORD"]="".join(name+",,\n" for name in files)+"wrapperguard_sdk-0.4.0.dist-info/RECORD,,\n"
with zipfile.ZipFile(sys.argv[1],"w") as archive:
 for name,content in files.items(): archive.writestr(name,content)`;
 run(python,['-I','-c',code,wheel,JSON.stringify(files)]);
 const context={globalStorageUri:{fsPath:storage},extensionPath};const selected=await bootstrap.ensureManagedSdk(context,python);assert.equal(await bootstrap.pythonVersion(selected,storage,true),run(selected,['-I','-c','import sys; print(".".join(map(str,sys.version_info[:3])))']));
 const packageFile=run(selected,['-I','-c','import wrapperguard_sdk; print(wrapperguard_sdk.__file__)']);await fs.writeFile(packageFile,'__version__ = "0.1.0"\n');await fs.rm(path.join(path.dirname(packageFile),'__pycache__'),{recursive:true,force:true});assert.equal(await bootstrap.pythonVersion(selected,storage,true),undefined);
 await bootstrap.ensureManagedSdk(context,python);assert.ok(await bootstrap.pythonVersion(selected,storage,true));const marker=JSON.parse(await fs.readFile(path.join(environment,maintenance.MANAGED_ENVIRONMENT_MARKER),'utf8'));assert.equal(marker.owner,'wrapperguard.sdk-environment');assert.equal(await maintenance.removeManagedEnvironment(storage,[root],[]),true);
});
