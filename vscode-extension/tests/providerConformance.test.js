const test=require('node:test');const assert=require('node:assert/strict');const{execFileSync}=require('node:child_process');
const{runConformance}=require('../scripts/provider-conformance');
test('reusable author conformance kit exercises real isolated subprocess attribution, partial/failure/timeout and cancellation paths',async()=>{
 let python;for(const candidate of [process.env.WRAPPERGUARD_TEST_PYTHON,process.env.WRAPPERGUARD_BUILD_PYTHON,'python3','python','/opt/homebrew/bin/python3','/usr/local/bin/python3'].filter(Boolean)){try{python=execFileSync(candidate,['-I','-c','import sys; assert sys.version_info >= (3,10); print(sys.executable)'],{encoding:'utf8',timeout:2000,stdio:['ignore','pipe','ignore']}).trim();break;}catch{}}
 assert.ok(python,'Provider conformance requires a trusted Python 3.10+ interpreter.');const result=await runConformance(python);assert.equal(result.fixtureOnly,true);assert.equal(result.cases.length,6);assert.ok(result.cases.every(item=>item.passed));assert.equal(new Set(result.cases.map(item=>item.id)).size,6);
});
