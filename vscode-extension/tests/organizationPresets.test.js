const test=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs/promises');const path=require('node:path');const os=require('node:os');
const {canonicalPresetJson,presetDigest,createOrganizationPreset,validateOrganizationPreset,readOrganizationPreset,writeOrganizationPreset}=require('../dist/methods/organizationPresets');
const identity={id:'acme.android',title:'Acme Android',version:'1.0.0',issuer:'Acme Security'};
const make=()=>createOrganizationPreset(identity,{'builtin.script-rules':true,'sast.semgrep':false},1700000000000);
test('organization preset identity/digest is deterministic and export contains method data only',()=>{
 const preset=make();assert.deepEqual(Object.keys(preset).sort(),['createdAt','digest','id','issuer','methodFlags','schemaVersion','title','version']);assert.equal(canonicalPresetJson({z:1,a:{z:false,a:true}}),'{"a":{"a":true,"z":false},"z":1}');assert.equal(validateOrganizationPreset({...preset,methodFlags:{'sast.semgrep':false,'builtin.script-rules':true}}).digest,preset.digest);assert.equal(make().digest,preset.digest);assert.ok(Object.isFrozen(preset.methodFlags));
});
test('tampering, executable/grant fields, unknown methods and invalid identities are rejected even with a recomputed digest',()=>{
 const preset=make();assert.throws(()=>validateOrganizationPreset({...preset,title:'Changed'}),/digest/);
 for(const change of [{command:['evil']},{permissions:{enabled:true}},{issuer:'bad\nissuer'},{version:'latest'},{methodFlags:{'unknown.method':true}},{createdAt:-1},{methodFlags:{}}]){const {digest,...payload}={...preset,...change};assert.throws(()=>validateOrganizationPreset({...payload,digest:presetDigest(payload)}));}
});
test('preset files are bounded passive JSON; symlinks and oversized input cannot be imported or overwritten',async t=>{
 const base=await fs.mkdtemp(path.join(os.tmpdir(),'wg-presets-'));t.after(()=>fs.rm(base,{recursive:true,force:true}));const file=path.join(base,'selection.json');await writeOrganizationPreset(file,make());assert.deepEqual(await readOrganizationPreset(file),make());const linked=path.join(base,'linked.json');await fs.symlink(file,linked);await assert.rejects(readOrganizationPreset(linked));await assert.rejects(writeOrganizationPreset(linked,make()));assert.deepEqual(await readOrganizationPreset(file),make());await fs.writeFile(file,' '.repeat(65537));await assert.rejects(readOrganizationPreset(file));
});
