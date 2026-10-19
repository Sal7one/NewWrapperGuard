#!/usr/bin/env node
'use strict';
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const assert=require('node:assert/strict');
const {spawnSync}=require('node:child_process');
const {servePreview}=require('./serve');
const repoRoot=path.resolve(__dirname,'../..');
function option(name,fallback){const at=process.argv.indexOf(name);return at<0?fallback:process.argv[at+1];}
function playwright(){
  const paths=[process.env.PLAYWRIGHT_MODULE_PATH,'playwright',path.join(os.homedir(),'.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright')].filter(Boolean);
  for(const candidate of paths){try{return require(candidate);}catch{}}
  throw new Error('An existing Playwright installation is required. Set PLAYWRIGHT_MODULE_PATH; this script never downloads a browser.');
}
function findExecutable(root,depth=0){
  if(!fs.existsSync(root)||depth>7)return undefined;
  for(const entry of fs.readdirSync(root,{withFileTypes:true})){
    const file=path.join(root,entry.name);
    if(entry.isFile()&&['Google Chrome for Testing','Chromium','chrome','chrome-headless-shell'].includes(entry.name))return file;
    if(entry.isDirectory()){const found=findExecutable(file,depth+1);if(found)return found;}
  }
}
async function main(){
  const reportPath=path.resolve(option('--report',path.join(repoRoot,'demo/report.json')));
  const settingsPath=path.resolve(option('--settings',path.join(repoRoot,'demo/settings.json')));
  const out=path.resolve(option('--out',path.join(repoRoot,'demo/screenshots/vscode')));
  const built=spawnSync(process.execPath,[path.join(__dirname,'build-vscode-preview.js'),'--report',reportPath,'--settings',settingsPath],{stdio:'inherit'});
  if(built.status!==0)throw new Error('Demo generation failed.');
  const engine=option('--engine',process.platform==='darwin'?'webkit':'chromium');
  if(engine==='webkit'){
    if(process.platform!=='darwin')throw new Error('The isolated WKWebView capture requires macOS. Use --engine chromium with an existing browser elsewhere.');
    const temporary=fs.mkdtempSync(path.join(os.tmpdir(),'wrapperguard-demo-webkit-'));
    try{
      const binary=path.join(temporary,'capture');
      const compile=spawnSync('/usr/bin/swiftc',['-framework','Cocoa','-framework','WebKit',path.join(__dirname,'capture-webkit.swift'),'-o',binary],{stdio:'inherit'});
      if(compile.status!==0)throw new Error('Could not compile the built-in WebKit capture helper.');
      const capture=spawnSync(binary,[path.join(__dirname,'vscode.html'),out],{stdio:'inherit'});
      if(capture.status!==0)throw new Error('WebKit demo capture or interaction validation failed.');
    }finally{fs.rmSync(temporary,{recursive:true,force:true});}
    return;
  }
  if(engine!=='chromium')throw new Error('Capture engine must be webkit or chromium.');
  const executablePath=process.env.DEMO_BROWSER_EXECUTABLE||findExecutable(path.join(os.homedir(),'.cache/puppeteer/chrome'))||findExecutable(path.join(os.homedir(),'.cache/puppeteer/chrome-headless-shell'));
  if(!executablePath)throw new Error('No cached browser found. Set DEMO_BROWSER_EXECUTABLE to an existing Chrome/Chromium executable.');
  fs.mkdirSync(out,{recursive:true});
  const server=await servePreview(0);
  let browser;
  const entries=[];
  const errors=[];
  try{
    browser=await playwright().chromium.launch({executablePath,headless:true,args:['--no-first-run','--disable-background-networking','--disable-component-update','--disable-sync']});
    const context=await browser.newContext({viewport:{width:1440,height:960},deviceScaleFactor:1.5,locale:'en-GB',timezoneId:'Asia/Riyadh',reducedMotion:'reduce'});
    await context.route('**/*',route=>new URL(route.request().url()).hostname==='127.0.0.1'?route.continue():route.abort());
    const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
    const url='http://127.0.0.1:'+server.address().port;
    await page.goto(url,{waitUntil:'load'});await page.waitForSelector('html[data-demo-ready="true"]');
    await page.evaluate(()=>document.fonts.ready);
    const seed=JSON.parse(fs.readFileSync(reportPath,'utf8'));
    const expectedBlockers=seed.findings.filter(f=>f.status==='ACTIVE'&&f.policyAction==='BLOCK').length;
    assert.equal(await page.locator('#sumBlockers').innerText(),String(expectedBlockers));
    assert.equal(await page.evaluate(()=>window.wrapperGuardDemo.getReport().findings.length),seed.findings.length);
    const focus=seed.findings.find(f=>f.providerId==='acme.supply-chain'&&f.status==='ACTIVE'&&f.policyAction==='BLOCK')||seed.findings[0];
    const focusedRow='.finding-row[data-fp=\"'+focus.fingerprint+'\"]';
    async function tab(name){await page.locator('[data-tab="'+name+'"]').click();await page.locator('main').evaluate(el=>el.scrollTop=0);}
    async function capture(name,description){
      await page.locator('#demo-toast').evaluate(el=>el.hidden=true);
      await page.screenshot({path:path.join(out,name),animations:'disabled'});
      entries.push({file:name,description,viewport:page.viewportSize(),theme:await page.evaluate(()=>document.body.classList.contains('vscode-light')?'light':'dark')});
    }
    await capture('01-overview-dark.png','Blocked project overview with active counts and incomplete provider coverage.');
    await tab('findings');await page.locator(focusedRow).click();
    assert.equal(await page.locator('.finding-row').count(),seed.findings.length);
    await capture('02-findings-dark.png','Real findings list and evidence inspector with provider provenance and granted actions.');
    await tab('graph');
    assert.ok(await page.evaluate(()=>Array.from(document.querySelectorAll('.detached-card')).every(el=>el.closest('.graph-inspector'))));
    await capture('03-execution-dark.png','Statically discovered script graph and active risky paths.');
    await page.setViewportSize({width:1600,height:1000});await capture('09-execution-expanded.png','Expanded workspace showing graph, detached files and risky-path inspector.');await page.setViewportSize({width:1440,height:960});
    await tab('policy');
    const policyHeight=await page.locator('main').evaluate(el=>el.scrollHeight+160);
    await page.setViewportSize({width:1440,height:Math.min(1200,Math.max(960,policyHeight))});
    await capture('04-policy-dark.png','Provider permissions, required/optional coverage, and timeout/failure/disabled states.');
    await page.setViewportSize({width:1440,height:960});await page.evaluate(()=>window.wrapperGuardDemo.setTheme(true));await tab('findings');
    await page.locator(focusedRow).click();await capture('05-findings-light.png','Light theme using the same renderer and report.');
    await page.evaluate(()=>window.wrapperGuardDemo.setTheme(false));await page.setViewportSize({width:430,height:1000});
    await page.locator('#detailBack').waitFor({state:'visible'});await page.locator('#detailBack').click();await page.locator(focusedRow).click();assert.ok(await page.locator('#findingDetail').evaluate(el=>el.scrollWidth<=el.clientWidth+1));
    await page.locator('#findingDetail').evaluate(el=>el.scrollTop=el.scrollHeight);
    assert.ok(await page.locator('#detailBaseline').evaluate(el=>el.getBoundingClientRect().height>0&&el.getBoundingClientRect().bottom<=document.getElementById('findingDetail').getBoundingClientRect().bottom+1));
    await page.locator('#findingDetail').evaluate(el=>el.scrollTop=0);await capture('06-findings-narrow.png','Narrow findings detail and Back navigation.');
    await page.setViewportSize({width:1440,height:960});await tab('findings');
    const baseline=seed.findings.find(f=>f.status==='SUPPRESSED');
    if(baseline){
      await page.locator('.finding-row[data-fp="'+baseline.fingerprint+'"]').click();
      await capture('07-approved-baseline.png','Approved baseline finding remains visible and does not count as an active blocker.');
      assert.match(await page.locator('#findingDetail').innerText(),/Approved baseline/);
      await page.locator('#detailUnbaseline').click();
      assert.equal(await page.evaluate(fp=>window.wrapperGuardDemo.getReport().findings.find(f=>f.fingerprint===fp).status,baseline.fingerprint),'ACTIVE');
      await page.locator('#detailBaseline').click();
      assert.equal(await page.evaluate(fp=>window.wrapperGuardDemo.getReport().findings.find(f=>f.fingerprint===fp).status,baseline.fingerprint),'SUPPRESSED');
    }
    await page.locator('#demo-reset').click();await tab('policy');await page.locator('.provider-permissions').first().click();
    assert.equal(await page.locator('#demo-dialog').evaluate(el=>el.open),true);
    await capture('08-demo-permission-controls.png','Demo-only permission controls; no real provider configuration is changed.');
    await page.getByRole('button',{name:'Cancel',exact:true}).click();
    await page.locator('#demo-reset').click();await tab('findings');
    await page.locator('#filterInput').fill('Sentinel');assert.ok(await page.locator('.finding-row').count()>0);await page.locator('#btnClearFilter').click();
    assert.deepEqual(await page.evaluate(()=>window.__WRAPPERGUARD_DEMO_ERRORS__),[]);
    assert.deepEqual(errors,[]);
    fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify({demo:true,project:'Atlas Payments',source:'vscode-extension/src/webviewUi.ts',report:path.relative(repoRoot,reportPath),browser:path.basename(executablePath),checks:['active blocker counts agree with report','tabs and finding selection','baseline restore and approval stay in browser','permission dialog opens','search and reset','no page or webview script errors'],screenshots:entries},null,2)+'\n');
    console.log('Captured '+entries.length+' verified demo screenshots in '+out);
  }finally{if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
}
main().catch(error=>{console.error(error.stack||error.message);process.exitCode=1;});
