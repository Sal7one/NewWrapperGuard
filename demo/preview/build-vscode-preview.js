#!/usr/bin/env node
'use strict';
// Standalone local demo made from the real VS Code webview renderer. No production hooks.
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const {spawnSync} = require('node:child_process');
const repoRoot = path.resolve(__dirname,'../..');
function option(name,fallback) { const at=process.argv.indexOf(name);return at<0?fallback:process.argv[at+1]; }
const reportPath=path.resolve(option('--report',path.join(repoRoot,'demo/report.json')));
const settingsPath=path.resolve(option('--settings',path.join(repoRoot,'demo/settings.json')));
const outputPath=path.resolve(option('--out',path.join(__dirname,'vscode.html')));
const rendererPath=path.join(repoRoot,'vscode-extension/dist/webviewUi.js');
if (!fs.existsSync(rendererPath)) throw new Error('Compile the extension first: cd vscode-extension && npm run compile');
const report=JSON.parse(fs.readFileSync(reportPath,'utf8'));
if (!Array.isArray(report.findings) || !Array.isArray(report.providerRuns)) throw new Error('Expected a ScanReport with findings and providerRuns.');
const settings=JSON.parse(fs.readFileSync(settingsPath,'utf8'));
const methods=JSON.parse(fs.readFileSync(path.join(repoRoot,'catalog/security-methods.json'),'utf8')).methods;
const original=Module._load;
let WrapperGuardWebviewProvider;
try {
  Module._load=function(id,...args) { return id==='vscode'?{}:original.call(this,id,...args); };
  ({WrapperGuardWebviewProvider}=require(rendererPath));
} finally { Module._load=original; }
let html=new WrapperGuardWebviewProvider().buildHtml();
const nonce=html.match(/script-src 'nonce-([^']+)'/)[1];
const safeJson=value=>JSON.stringify(value).replace(/</g,'\\u003c').replace(/\u2028/g,'\\u2028').replace(/\u2029/g,'\\u2029');
const bootstrap=`window.__WRAPPERGUARD_DEMO_DATA__=${safeJson({report,settings,methods})};`;
const bridge=fs.readFileSync(path.join(__dirname,'demo-bridge.js'),'utf8');
const check=spawnSync(process.execPath,['--check'],{input:bootstrap+'\n'+bridge,encoding:'utf8'});
if (check.status!==0) throw new Error(check.stderr);
html=html.replace('<head>','<head>\n<title>Atlas Payments — WrapperGuard demo</title>');
html=html.replace('</head>',`<style>
:root { --vscode-font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; --vscode-editor-font-family: 'SFMono-Regular', Consolas, monospace; }
#demo-banner { flex:0 0 28px;display:flex;align-items:center;gap:12px;padding:0 16px;border-bottom:1px solid var(--border);background:var(--surface-lowest);font-size:10px; }
#demo-banner strong { letter-spacing:.07em;color:var(--primary);font-size:9px;font-weight:600; }
#demo-banner .demo-project { display:none; }
#demo-banner .demo-copy { color:var(--text-dim);font-size:9px;flex:1; }
#demo-banner button { min-height:20px;padding:1px 7px;border-radius:3px;border:1px solid var(--border);background:transparent;color:var(--text-dim);font-size:9px;cursor:pointer; }
#demo-toast { position:fixed;bottom:36px;right:16px;max-width:420px;padding:10px 14px;border:1px solid var(--border-strong);border-radius:5px;background:var(--surface-highest);box-shadow:0 5px 24px #0004;z-index:20;font-size:12px; }
#demo-dialog { max-width:min(560px,calc(100vw - 32px));max-height:85vh;overflow:auto;color:var(--text);background:var(--surface);border:1px solid var(--border-strong);border-radius:8px;padding:20px;font:13px/1.5 var(--vscode-font-family); }
#demo-dialog::backdrop { background:#0008; }
#demo-dialog h2 { margin-top:0;font-size:17px; } #demo-dialog label { display:flex;gap:10px;align-items:center;padding:7px 0; }
#demo-dialog .demo-field { display:block;font-size:11px;color:var(--text-dim);margin-top:9px; } #demo-dialog .demo-field input,#demo-dialog .demo-field select { display:block;width:100%;margin-top:5px;padding:7px 8px;color:var(--text);background:var(--surface-lowest);border:1px solid var(--border-strong);border-radius:3px;font-size:12px; } #demo-dialog .demo-summary { border-left:2px solid var(--primary);padding:9px 12px;background:var(--surface-low);font-size:11px;line-height:1.8;margin-top:14px; }
#demo-dialog pre { white-space:pre-wrap;font:12px/1.6 var(--vscode-editor-font-family);background:var(--mono-bg);padding:12px;max-height:45vh;overflow:auto; }
#demo-dialog .demo-dialog-actions { display:flex;justify-content:flex-end;gap:8px;margin-top:16px; }
@media(max-width:540px) { #demo-banner { gap:8px;padding:0 12px; } #demo-banner .demo-copy { display:none; } #demo-banner strong { margin-right:auto; } }
</style></head>`);
html=html.replace('<body>',`<body class="vscode-dark"><div id="demo-banner" role="note"><strong>SIMULATED DEMO</strong><span class="demo-project">Atlas Payments</span><span class="demo-copy">Fictional findings and providers · no scripts or SDKs run</span><button id="demo-theme" type="button">Light theme</button><button id="demo-reset" type="button">Reset demo</button></div><div id="demo-toast" role="status" hidden></div><dialog id="demo-dialog" aria-label="Demo action"></dialog><script nonce="${nonce}">${bootstrap}\n${bridge}</script>`);
fs.mkdirSync(path.dirname(outputPath),{recursive:true});
fs.writeFileSync(outputPath,html);
console.log('Built real-renderer demo: '+outputPath+' ('+report.findings.length+' fictional findings)');
