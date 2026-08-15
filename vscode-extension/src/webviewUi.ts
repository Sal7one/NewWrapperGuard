import * as vscode from 'vscode';
import { Finding, ScanState } from './models';

/** Actions the webview UI can request; wired to real commands by the extension host. */
export interface UiHandlers {
  scan(): void;
  exportReport(): void;
  openFinding(finding: Finding): void;
  copyEvidence(finding: Finding): void;
}

export async function openFindingInEditor(finding: Finding): Promise<void> {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) {
    void vscode.window.showWarningMessage('Open a workspace folder first.');
    return;
  }
  const document = await vscode.workspace.openTextDocument(vscode.Uri.joinPath(root, finding.location.path));
  const editor = await vscode.window.showTextDocument(document);
  const line = Math.max(0, (finding.location.line ?? 1) - 1);
  editor.selection = new vscode.Selection(line, 0, line, 0);
  editor.revealRange(new vscode.Range(line, 0, line, 0), vscode.TextEditorRevealType.InCenter);
}

function getNonce(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let out = '';
  for (let i = 0; i < 32; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

/**
 * The WrapperGuard sidebar as a real web app: header, editor-style tabs
 * (Overview / Findings / Execution Graph / Policy), verdict status pill, and a
 * design-token-matched UI per design_reference/vs_code_finding_details.
 */
export class WrapperGuardWebviewProvider implements vscode.WebviewViewProvider {
  static readonly viewType = 'wrapperGuard.main';

  handlers: UiHandlers = {
    scan: () => undefined,
    exportReport: () => undefined,
    openFinding: () => undefined,
    copyEvidence: () => undefined
  };

  private view?: vscode.WebviewView;

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView;
    webviewView.webview.options = { enableScripts: true };
    webviewView.webview.html = this.buildHtml();
    webviewView.webview.onDidReceiveMessage((msg: { type: string; finding?: Finding }) => {
      switch (msg.type) {
        case 'scan':
          this.handlers.scan();
          break;
        case 'export':
          this.handlers.exportReport();
          break;
        case 'open':
          if (msg.finding) {
            this.handlers.openFinding(msg.finding);
          }
          break;
        case 'copy':
          if (msg.finding) {
            this.handlers.copyEvidence(msg.finding);
          }
          break;
      }
    });
  }

  /** Pushes the latest scan state; the webview re-renders all panes from it. */
  setState(s: ScanState): void {
    const payload = s.kind === 'scanned' ? { kind: s.kind, report: s.report } : { kind: s.kind, error: s.kind === 'failed' ? s.error : undefined };
    this.view?.webview.postMessage({ type: 'state', state: payload });
  }

  private buildHtml(): string {
    const nonce = getNonce();
    return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';"/>
<style>
:root {
  --bg: #13121b; --surface: #1f1f28; --surface-low: #1b1b24; --surface-lowest: #0e0d16;
  --surface-high: #2a2933; --surface-highest: #35343e;
  --border: #464555; --border-strong: #918fa1;
  --text: #e4e1ee; --text-dim: #c7c4d8;
  --primary: #c3c0ff; --primary-container: #4f46e5; --on-primary-container: #dad7ff;
  --error: #ffb4ab; --warn: #ffb695; --ok: #7ee787; --info: #8ab4f8;
  --mono-bg: #1e1e1e; --mono-bar: #2d2d2d;
}
body.vscode-light {
  --bg: #fbfbfd; --surface: #ffffff; --surface-low: #f6f6f8; --surface-lowest: #f1f1f4;
  --surface-high: #f3f3f7; --surface-highest: #ececf1;
  --border: #d8d8e0; --border-strong: #a5a5b0;
  --text: #1f1f23; --text-dim: #6b6b76;
  --primary: #3d34c4; --primary-container: #4f46e5; --on-primary-container: #ffffff;
  --error: #b3261e; --warn: #9a6700; --ok: #22863a; --info: #005fb8;
  --mono-bg: #f3f3f7; --mono-bar: #e4e4ea;
}
* { box-sizing: border-box; }
body {
  margin: 0; padding: 0; color: var(--text); background: var(--bg);
  font-family: var(--vscode-font-family); font-size: 13px; line-height: 18px;
}
button { font-family: var(--vscode-font-family); }
.mono { font-family: var(--vscode-editor-font-family); }
/* ── app header ─────────────────────────── */
.app-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; background: var(--surface-low);
  border-bottom: 1px solid var(--border);
}
.brand { display: flex; align-items: center; gap: 8px; }
.logo {
  width: 26px; height: 26px; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--primary-container) 30%, transparent);
  border: 1px solid color-mix(in srgb, var(--primary) 35%, transparent);
  color: var(--primary); font-size: 14px;
}
.brand-name { font-size: 14px; font-weight: 700; color: var(--primary); line-height: 16px; }
.brand-ver { font-size: 10px; color: var(--text-dim); }
.header-actions { display: flex; gap: 6px; }
.btn {
  display: inline-flex; align-items: center; gap: 5px; height: 26px; padding: 0 10px;
  border-radius: 4px; font-size: 11px; font-weight: 500; cursor: pointer; white-space: nowrap;
}
.btn:disabled { opacity: 0.5; cursor: default; }
.btn-primary { background: var(--primary-container); color: var(--on-primary-container); border: 1px solid transparent; }
.btn-primary:hover:not(:disabled) { background: color-mix(in srgb, var(--primary-container) 85%, var(--text)); }
.btn-ghost { background: transparent; color: var(--text); border: 1px solid var(--border-strong); }
.btn-ghost:hover:not(:disabled) { background: var(--surface-high); }
/* ── tabs ───────────────────────────────── */
.tabs { display: flex; border-bottom: 1px solid var(--border); background: var(--surface-low); }
.tab {
  flex: 1; background: transparent; border: none; color: var(--text-dim);
  padding: 7px 4px 6px; font-size: 11px; font-weight: 700; letter-spacing: 0.05em;
  text-transform: uppercase; cursor: pointer; border-bottom: 2px solid transparent;
}
.tab:hover { color: var(--primary); }
.tab.active { color: var(--primary); border-bottom-color: var(--primary); }
/* ── status pill strip ──────────────────── */
.status-strip { padding: 8px 10px 4px; }
.pill {
  display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px;
  border-radius: 999px; font-size: 11px; font-weight: 700; letter-spacing: 0.05em;
  text-transform: uppercase; border: 1px solid var(--border-strong); color: var(--text-dim);
}
.pill.err { color: var(--error); border-color: color-mix(in srgb, var(--error) 50%, transparent); background: color-mix(in srgb, var(--error) 12%, transparent); }
.pill.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 50%, transparent); background: color-mix(in srgb, var(--warn) 12%, transparent); }
.pill.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 50%, transparent); background: color-mix(in srgb, var(--ok) 12%, transparent); }
.counts { display: inline-flex; gap: 10px; margin-left: 8px; font-size: 11px; color: var(--text-dim); }
.counts .err { color: var(--error); }
.counts .warn { color: var(--warn); }
/* ── panes ──────────────────────────────── */
main { padding: 4px 10px 12px; overflow-y: auto; }
.tab-panel { display: none; }
.tab-panel.active { display: block; }
.section-label {
  font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase;
  color: var(--text-dim); margin: 10px 0 5px;
}
/* overview */
.hero { text-align: center; padding: 28px 10px; color: var(--text-dim); }
.hero .big { font-size: 16px; font-weight: 600; color: var(--text); margin-bottom: 6px; }
.hero .btn { margin-top: 12px; height: 30px; font-size: 12px; padding: 0 16px; }
.banner {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 4px;
  border: 1px solid var(--border); background: var(--surface); font-size: 12px; font-weight: 600;
}
.banner.err { color: var(--error); border-color: color-mix(in srgb, var(--error) 45%, transparent); background: color-mix(in srgb, var(--error) 10%, var(--surface)); }
.banner.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 45%, transparent); background: color-mix(in srgb, var(--warn) 10%, var(--surface)); }
.banner.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 45%, transparent); background: color-mix(in srgb, var(--ok) 10%, var(--surface)); }
.tiles { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; margin-top: 8px; }
.tile {
  background: var(--surface); border: 1px solid var(--border); border-radius: 4px;
  padding: 8px 10px; display: flex; flex-direction: column; gap: 2px;
}
.tile .label { font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--text-dim); }
.tile .value { font-size: 18px; font-weight: 700; line-height: 22px; }
.tile .value.err { color: var(--error); }
.tile .value.warn { color: var(--warn); }
.tile .value.ok { color: var(--ok); }
.meta { margin-top: 10px; font-size: 11px; color: var(--text-dim); display: flex; flex-direction: column; gap: 2px; }
.meta .mono { font-size: 10px; }
.notice { font-size: 11px; color: var(--text-dim); border-left: 2px solid var(--border-strong); padding-left: 8px; margin-top: 8px; }
/* findings list + detail split */
.findings-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr); align-items: start; gap: 0 12px; }
.findings-layout .detail { border-left: 1px solid var(--border); padding-left: 10px; position: sticky; top: 0; max-height: calc(100vh - 120px); overflow-y: auto; }
.findings-layout.narrow { display: block; }
.findings-layout.narrow .detail { border-left: none; padding-left: 0; position: static; max-height: none; }
.group-head {
  display: flex; align-items: center; gap: 6px; margin-top: 10px; padding: 3px 6px;
  font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase;
  color: var(--text-dim); border-bottom: 1px solid var(--border);
}
.group-head .err { color: var(--error); }
.group-head .warn { color: var(--warn); }
.group-head .info { color: var(--info); }
.finding-row {
  display: flex; align-items: flex-start; gap: 7px; padding: 5px 6px; cursor: pointer;
  border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent);
  border-radius: 2px;
}
.finding-row:hover { background: var(--surface-high); }
.finding-row.selected { background: color-mix(in srgb, var(--primary-container) 22%, transparent); border-left: 2px solid var(--primary); }
.finding-row .ficon { font-size: 12px; line-height: 16px; padding-top: 1px; }
.finding-row .ficon.err { color: var(--error); }
.finding-row .ficon.warn { color: var(--warn); }
.finding-row .ficon.info { color: var(--info); }
.finding-row .body { flex: 1; min-width: 0; }
.finding-row .title { font-size: 12px; font-weight: 500; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.finding-row .where { font-size: 10px; color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.finding-row .badges { display: flex; gap: 4px; margin-top: 3px; flex-wrap: wrap; }
.badge {
  font-size: 9px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase;
  padding: 1px 6px; border-radius: 999px; border: 1px solid var(--border-strong); color: var(--text-dim);
}
.badge.err { color: var(--error); border-color: color-mix(in srgb, var(--error) 50%, transparent); background: color-mix(in srgb, var(--error) 12%, transparent); }
.badge.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 50%, transparent); background: color-mix(in srgb, var(--warn) 12%, transparent); }
.badge.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 50%, transparent); background: color-mix(in srgb, var(--ok) 12%, transparent); }
.empty-note { padding: 14px 6px; color: var(--text-dim); font-size: 12px; text-align: center; }
/* detail pane */
.detail { padding: 8px 2px 2px; }
.detail .back { display: none; }
.narrow .detail .back { display: inline-flex; }
.detail h2 { margin: 6px 0 2px; font-size: 15px; line-height: 20px; font-weight: 600; }
.detail .sub { color: var(--text-dim); font-size: 11px; margin-bottom: 8px; }
.grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; }
.cell { background: var(--surface); border: 1px solid var(--border); border-radius: 4px; padding: 6px 8px; display: flex; flex-direction: column; gap: 2px; }
.cell .label { font-size: 9px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--text-dim); }
.cell .value { display: flex; align-items: center; gap: 5px; font-size: 11px; }
.value.err { color: var(--error); }
.value.warn { color: var(--warn); }
.value.info { color: var(--info); }
.path { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; background: var(--surface-highest); border: 1px solid var(--border); border-radius: 4px; padding: 6px 8px; }
.path-chip { font-family: var(--vscode-editor-font-family); font-size: 10px; color: var(--text-dim); }
.path-chip.path-final { color: var(--error); font-weight: 700; border-bottom: 1px dashed var(--error); }
.path-arrow { color: var(--border-strong); }
.snippet { border: 1px solid var(--border); border-radius: 4px; overflow: hidden; background: var(--mono-bg); }
.snippet-bar { display: flex; align-items: center; justify-content: space-between; background: var(--mono-bar); border-bottom: 1px solid var(--border); padding: 3px 8px; }
.snippet-bar .file { font-family: var(--vscode-editor-font-family); font-size: 10px; color: var(--text-dim); }
.copy-btn { background: transparent; border: none; color: var(--text-dim); cursor: pointer; font-size: 11px; padding: 1px 6px; border-radius: 4px; }
.copy-btn:hover { color: var(--primary); background: color-mix(in srgb, var(--primary) 12%, transparent); }
.snippet-line { display: flex; font-family: var(--vscode-editor-font-family); font-size: 10px; line-height: 15px; }
.snippet-line .ln { flex: 0 0 40px; text-align: right; padding-right: 8px; color: var(--border-strong); user-select: none; }
.snippet-line .code { white-space: pre-wrap; word-break: break-all; color: var(--text); padding: 1px 4px; }
.snippet-line.hit { background: color-mix(in srgb, var(--error) 12%, transparent); border-left: 2px solid var(--error); }
.snippet-line.hit .code { font-weight: 700; }
.snippet-note { padding: 3px 8px; font-size: 10px; color: var(--text-dim); border-top: 1px solid var(--border); }
.remediation { background: var(--surface-low); border-left: 2px solid var(--primary); border-radius: 0 4px 4px 0; padding: 8px 10px; display: flex; flex-direction: column; gap: 5px; }
.remediation .head { color: var(--primary); font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; }
.remediation p { margin: 0; font-size: 11px; line-height: 15px; }
.detail-actions { display: flex; gap: 6px; padding-top: 10px; flex-wrap: wrap; }
/* execution graph */
.edge-row { display: flex; align-items: center; gap: 6px; padding: 5px 6px; border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); }
.edge-row .via { font-size: 10px; color: var(--text-dim); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.edge-row .arrow { color: var(--border-strong); }
/* policy */
.policy-row { display: flex; justify-content: space-between; gap: 8px; padding: 6px 4px; border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); font-size: 11px; }
.policy-row .k { color: var(--text-dim); }
.policy-row .v { text-align: right; font-weight: 500; }
.spin { animation: wgspin 1s linear infinite; display: inline-block; }
@keyframes wgspin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
</head>
<body>
  <header class="app-header">
    <div class="brand">
      <span class="logo">🛡</span>
      <div>
        <div class="brand-name">WrapperGuard</div>
        <div class="brand-ver mono">v0.2.0</div>
      </div>
    </div>
    <div class="header-actions">
      <button class="btn btn-ghost" id="exportBtn" title="Export the scan report as JSON">⤓ Export</button>
      <button class="btn btn-primary" id="rescanBtn">⟳ Rescan</button>
    </div>
  </header>

  <nav class="tabs" id="tabs">
    <button class="tab active" data-tab="overview">Overview</button>
    <button class="tab" data-tab="findings">Findings</button>
    <button class="tab" data-tab="graph">Execution Graph</button>
    <button class="tab" data-tab="policy">Policy</button>
  </nav>

  <div class="status-strip">
    <span class="pill" id="verdictPill">Not scanned</span>
    <span class="counts" id="counts"></span>
  </div>

  <main>
    <section class="tab-panel active" id="panel-overview"></section>
    <section class="tab-panel" id="panel-findings"><div class="findings-layout" id="findingsLayout"></div></section>
    <section class="tab-panel" id="panel-graph"></section>
    <section class="tab-panel" id="panel-policy"></section>
  </main>

<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
let state = { kind: 'not-scanned' };
let selectedFp = null;
let wide = window.matchMedia('(min-width: 540px)').matches;

const VERDICT = {
  NOT_SCANNED: ['dim', 'Not scanned'],
  CLEAN: ['ok', 'Clean'],
  REVIEW_REQUIRED: ['warn', 'Review Required'],
  EXECUTION_BLOCKED: ['err', 'Execution Blocked'],
  INCOMPLETE: ['warn', 'Scan Incomplete'],
  STALE: ['warn', 'Scan Stale'],
  FAILED: ['err', 'Scan Failed']
};
const SEV_ICON = { ERROR: ['✖', 'err'], WARN: ['▲', 'warn'], INFO: ['ℹ', 'info'] };

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function byId(id) { return document.getElementById(id); }
function post(type, finding) { vscode.postMessage({ type: type, finding: finding }); }

window.addEventListener('message', function (e) {
  const m = e.data;
  if (m && m.type === 'state') {
    state = m.state;
    render();
  }
});

window.matchMedia('(min-width: 540px)').addEventListener('change', function (e) {
  wide = e.matches;
  renderFindings();
});

function report() { return state.kind === 'scanned' ? state.report : null; }

function render() {
  renderPill();
  renderOverview();
  renderFindings();
  renderGraph();
  renderPolicy();
  renderBusy();
}

function verdictMeta() {
  if (state.kind === 'scanning') return ['warn', 'Scanning…'];
  if (state.kind === 'failed') return ['err', 'Scan Failed'];
  if (state.kind === 'scanned') return VERDICT[state.report.verdict] || ['dim', state.report.verdict];
  return ['dim', 'Not scanned'];
}

function renderPill() {
  const vm = verdictMeta();
  const pill = byId('verdictPill');
  pill.className = 'pill ' + vm[0];
  pill.textContent = '◉ Verdict: ' + vm[1];
  const r = report();
  const counts = byId('counts');
  if (!r) { counts.innerHTML = ''; return; }
  const blockers = r.findings.filter(function (f) { return f.policyAction === 'BLOCK'; }).length;
  const errors = r.findings.filter(function (f) { return f.severity === 'ERROR'; }).length;
  const warnings = r.findings.filter(function (f) { return f.severity === 'WARN'; }).length;
  counts.innerHTML = '<span class="err">✖ ' + blockers + ' blockers</span><span class="err">✖ ' + errors + ' errors</span><span class="warn">▲ ' + warnings + ' warnings</span>';
}

function renderBusy() {
  const btn = byId('rescanBtn');
  if (state.kind === 'scanning') {
    btn.disabled = true;
    btn.innerHTML = '<span class="spin">⟳</span> Scanning…';
  } else {
    btn.disabled = false;
    btn.innerHTML = '⟳ Rescan';
  }
}

function renderOverview() {
  const panel = byId('panel-overview');
  if (state.kind === 'not-scanned') {
    panel.innerHTML = '<div class="hero"><div class="big">No scan yet</div><div>Wrappers, build files, and bootstrap scripts are unprotected until scanned.</div><button class="btn btn-primary" id="heroScan">⟳ Scan Workspace</button></div>';
    byId('heroScan').addEventListener('click', function () { post('scan'); });
    return;
  }
  if (state.kind === 'scanning') {
    panel.innerHTML = '<div class="hero"><div class="big"><span class="spin">⟳</span> Scanning project…</div><div>Reading wrappers, build files, and bootstrap scripts.</div></div>';
    return;
  }
  if (state.kind === 'failed') {
    panel.innerHTML = '<div class="hero"><div class="big">Scan failed</div><div class="mono" style="font-size:11px">' + esc(state.error || '') + '</div><button class="btn btn-primary" id="retryBtn">⟳ Retry</button></div>';
    byId('retryBtn').addEventListener('click', function () { post('scan'); });
    return;
  }
  const r = state.report;
  const blockers = r.findings.filter(function (f) { return f.policyAction === 'BLOCK'; }).length;
  const errors = r.findings.filter(function (f) { return f.severity === 'ERROR'; }).length;
  const warnings = r.findings.filter(function (f) { return f.severity === 'WARN'; }).length;
  const vm = verdictMeta();
  const bannerCls = blockers > 0 ? 'err' : (!r.complete || r.stale ? 'warn' : r.verdict === 'CLEAN' ? 'ok' : 'warn');
  const bannerIcon = bannerCls === 'err' ? '⛔' : bannerCls === 'warn' ? '▲' : '✓';
  let html = '<div class="banner ' + bannerCls + '">' + bannerIcon + ' ' + esc(vm[1]) + (blockers > 0 ? ' — ' + blockers + ' blocker' + (blockers === 1 ? '' : 's') : '') + '</div>';
  html += '<div class="tiles">';
  html += '<div class="tile"><span class="label">Blockers</span><span class="value err">' + blockers + '</span></div>';
  html += '<div class="tile"><span class="label">Errors</span><span class="value err">' + errors + '</span></div>';
  html += '<div class="tile"><span class="label">Warnings</span><span class="value warn">' + warnings + '</span></div>';
  html += '<div class="tile"><span class="label">Files scanned</span><span class="value">' + r.scannedFiles + '</span></div>';
  html += '</div>';
  html += '<div class="meta">';
  html += '<span>Policy: ' + esc(r.policyName) + ' · ' + r.durationMs + ' ms</span>';
  const ts = r.generatedAtEpochMs ? new Date(r.generatedAtEpochMs).toLocaleTimeString() : '';
  html += '<span>Last scan: ' + esc(ts) + '</span>';
  html += '<span class="mono">' + esc(r.projectPath) + '</span>';
  html += '</div>';
  if (!r.complete) {
    html += '<div class="notice">⚠ Scan incomplete: ' + esc((r.notices || []).map(function (n) { return n.message; }).join('; ') || 'some files were skipped.') + '</div>';
  }
  if (r.stale) {
    html += '<div class="notice">⚠ Report is stale — a watched file changed after the scan. Rescan to refresh.</div>';
  }
  html += '<div style="margin-top:12px;display:flex;gap:6px">';
  html += '<button class="btn btn-primary" id="ovRescan">⟳ Rescan Project</button>';
  html += '<button class="btn btn-ghost" id="ovExport">⤓ Export JSON</button>';
  html += '</div>';
  panel.innerHTML = html;
  byId('ovRescan').addEventListener('click', function () { post('scan'); });
  byId('ovExport').addEventListener('click', function () { post('export'); });
}

function groupFindings() {
  const r = report();
  if (!r) return null;
  const groups = { blockers: [], errors: [], warnings: [], info: [] };
  r.findings.forEach(function (f) {
    if (f.policyAction === 'BLOCK') groups.blockers.push(f);
    else if (f.severity === 'ERROR') groups.errors.push(f);
    else if (f.severity === 'WARN') groups.warnings.push(f);
    else groups.info.push(f);
  });
  return groups;
}

function rowHtml(f) {
  const sev = SEV_ICON[f.severity] || SEV_ICON.INFO;
  const badges = '<span class="badge ' + sev[1] + '">' + esc(f.ruleId) + '</span>' +
    '<span class="badge">' + esc(f.category) + '</span>' +
    (f.policyAction === 'BLOCK' ? '<span class="badge err">BLOCK</span>' : '');
  return '<div class="finding-row' + (f.fingerprint === selectedFp ? ' selected' : '') + '" data-fp="' + esc(f.fingerprint) + '">' +
    '<span class="ficon ' + sev[1] + '">' + sev[0] + '</span>' +
    '<div class="body">' +
    '<div class="title">' + esc(f.title) + '</div>' +
    '<div class="where mono">' + esc(f.location.path) + ':' + (f.location.line != null ? f.location.line : '') + '</div>' +
    '<div class="badges">' + badges + '</div>' +
    '</div></div>';
}

function renderFindings() {
  const layout = byId('findingsLayout');
  const groups = groupFindings();
  if (!groups) {
    layout.className = 'findings-layout';
    layout.innerHTML = '<div class="empty-note">Run a scan to populate findings.</div>';
    return;
  }
  const r = report();
  const total = r.findings.length;
  const showingDetail = selectedFp && !wide;
  layout.className = 'findings-layout' + (showingDetail ? ' narrow' : '');

  const sel = r.findings.filter(function (f) { return f.fingerprint === selectedFp; })[0] || null;

  let list = '';
  if (total === 0 && r.complete && !r.stale) {
    list = '<div class="empty-note">✓ Clean — no findings in ' + r.scannedFiles + ' scanned files.</div>';
  } else {
    const defs = [
      ['blockers', '⛔ Execution Blockers', 'err'],
      ['errors', '✖ Errors', 'err'],
      ['warnings', '▲ Warnings', 'warn'],
      ['info', 'ℹ Info', 'info']
    ];
    defs.forEach(function (d) {
      const items = groups[d[0]];
      if (items.length === 0) return;
      list += '<div class="group-head"><span class="' + d[2] + '">' + d[1] + '</span><span>(' + items.length + ')</span></div>';
      items.forEach(function (f) { list += rowHtml(f); });
    });
  }
  const detailHtml = sel ? detailHtmlFor(sel, true) : '<div class="empty-note">Select a finding to see its details.</div>';
  layout.innerHTML = '<div class="list" id="findingsList">' + list + '</div><div class="detail" id="findingDetail">' + detailHtml + '</div>';

  if (showingDetail && sel) {
    byId('findingsList').style.display = 'none';
  }
  layout.querySelectorAll('.finding-row').forEach(function (row) {
    row.addEventListener('click', function () {
      selectedFp = row.getAttribute('data-fp');
      renderFindings();
      if (wide) { byId('findingDetail').scrollIntoView({ block: 'nearest' }); }
    });
  });
  const back = byId('detailBack');
  if (back) back.addEventListener('click', function () { selectedFp = null; renderFindings(); });
  const openBtn = byId('detailOpen');
  if (openBtn) openBtn.addEventListener('click', function () { if (sel) post('open', sel); });
  const copyBtns = document.querySelectorAll('.js-copy');
  copyBtns.forEach(function (b) { b.addEventListener('click', function () { if (sel) post('copy', sel); }); });
}

function detailHtmlFor(f, withBack) {
  const sev = SEV_ICON[f.severity] || SEV_ICON.INFO;
  const policy = f.policyAction === 'BLOCK' ? ['err', 'Blocks execution'] : f.policyAction === 'REVIEW' ? ['warn', 'Review required'] : ['info', 'Informational'];
  const location = f.location.path + ':' + (f.location.line != null ? f.location.line : '');
  const chain = (f.callChain || []).map(function (l) { return l.source.path + ':' + (l.source.line != null ? l.source.line : ''); });
  if (chain[chain.length - 1] !== location) chain.push(location);
  let pathHtml = '';
  if (chain.length > 0) {
    chain.forEach(function (c, i) {
      pathHtml += '<span class="path-chip' + (i === chain.length - 1 ? ' path-final' : '') + '">' + esc(c) + '</span>';
      if (i < chain.length - 1) pathHtml += '<span class="path-arrow">→</span>';
    });
  } else {
    pathHtml = '<span class="path-chip">' + esc(location) + '</span>';
  }
  const evidence = f.evidence ? f.evidence.text : '';
  let snippet = '';
  if (evidence) {
    const ln = f.location.line != null ? String(f.location.line).padStart(4, ' ') : '   ·';
    snippet = '<div class="snippet-line hit"><span class="ln">' + ln + '</span><span class="code">' + esc(evidence) + '</span></div>';
  } else {
    snippet = '<div class="snippet-line"><span class="ln">   ·</span><span class="code" style="color:var(--text-dim)">(no snippet available)</span></div>';
  }
  if (f.evidence && f.evidence.redacted) {
    snippet += '<div class="snippet-note">🔒 Credential values in this evidence are redacted.</div>';
  }
  let html = '';
  if (withBack) html += '<button class="btn btn-ghost back" id="detailBack" style="height:24px;font-size:10px;padding:0 8px">← Back to list</button>';
  html += '<span class="badge ' + sev[1] + '" style="display:inline-block">' + esc(f.ruleId) + '</span>';
  html += '<h2>' + esc(f.title) + '</h2>';
  html += '<div class="sub mono">' + esc(f.category) + ' · confidence ' + esc(f.confidence) + ' · status ' + esc(f.status) + '</div>';
  html += '<div class="grid3">';
  html += '<div class="cell"><span class="label">Severity</span><span class="value ' + sev[1] + '">' + sev[0] + ' ' + esc(f.severity) + '</span></div>';
  html += '<div class="cell"><span class="label">Confidence</span><span class="value info">✓ ' + esc(f.confidence) + '</span></div>';
  html += '<div class="cell"><span class="label">Policy Impact</span><span class="value ' + policy[0] + '">' + policy[1] + '</span></div>';
  html += '</div>';
  html += '<div class="section-label">Execution Path</div><div class="path">' + pathHtml + '</div>';
  html += '<div class="section-label">Snippet</div>';
  html += '<div class="snippet"><div class="snippet-bar"><span class="file">' + esc(f.location.path) + '</span><button class="copy-btn js-copy" title="Copy evidence">⧉ copy</button></div>' + snippet + '</div>';
  html += '<div class="section-label">Remediation</div>';
  html += '<div class="remediation"><div class="head">⚙ Remediation</div><p>' + esc(f.remediation || 'No specific remediation provided.') + '</p></div>';
  html += '<div class="detail-actions">';
  html += '<button class="btn btn-primary" id="detailOpen" style="height:26px;font-size:11px">⌨ Open in Editor</button>';
  html += '<button class="btn btn-ghost js-copy" style="height:26px;font-size:11px">⧉ Copy Evidence</button>';
  html += '<button class="btn btn-ghost" disabled style="height:26px;font-size:11px;opacity:.45" title="Available in v0.3 (trusted-baseline model)">◌ Suppress</button>';
  html += '<button class="btn btn-ghost" disabled style="height:26px;font-size:11px;opacity:.45" title="Available in v0.3 (trusted-baseline model)">◈ Baseline</button>';
  html += '</div>';
  return html;
}

function renderGraph() {
  const panel = byId('panel-graph');
  const r = report();
  if (!r) { panel.innerHTML = '<div class="empty-note">Run a scan to see script call chains.</div>'; return; }
  const edges = r.edges || [];
  if (edges.length === 0) {
    panel.innerHTML = '<div class="empty-note">No script call chains detected in this project.</div>';
    return;
  }
  let html = '<div class="section-label">Script call chains (' + edges.length + ')</div>';
  edges.forEach(function (e) {
    html += '<div class="edge-row mono">';
    html += '<span class="path-chip">' + esc(e.from.path) + ':' + (e.from.line != null ? e.from.line : '') + '</span>';
    html += '<span class="arrow">→</span>';
    html += '<span class="path-chip path-final">' + esc(e.to.path) + ':' + (e.to.line != null ? e.to.line : '') + '</span>';
    html += '<span class="via">via ' + esc(e.viaCommand) + '</span>';
    html += '</div>';
  });
  panel.innerHTML = html;
}

function renderPolicy() {
  const panel = byId('panel-policy');
  const r = report();
  let rows = '';
  if (r) {
    rows += '<div class="policy-row"><span class="k">Policy</span><span class="v">' + esc(r.policyName) + '</span></div>';
    rows += '<div class="policy-row"><span class="k">Mode</span><span class="v">ENFORCE</span></div>';
    rows += '<div class="policy-row"><span class="k">Block when</span><span class="v">actionable finding, severity ≥ ERROR</span></div>';
    rows += '<div class="policy-row"><span class="k">Requires reachable</span><span class="v">yes</span></div>';
    rows += '<div class="policy-row"><span class="k">Audit/INFO evidence</span><span class="v">never blocks</span></div>';
  } else {
    rows = '<div class="empty-note">Run a scan to load the active policy.</div>';
  }
  panel.innerHTML = rows +
    '<div class="notice" style="margin-top:10px">Scope: this view reports on the scanned workspace. Enforcement at run time is provided by the IntelliJ/Android Studio plugin (before-run guard); terminal and CI protection comes from the CLI. A never-scanned project is never treated as protected.</div>';
}

/* tabs */
document.getElementById('tabs').addEventListener('click', function (e) {
  const tab = e.target.closest('.tab');
  if (!tab) return;
  document.querySelectorAll('.tab').forEach(function (t) { t.classList.toggle('active', t === tab); });
  document.querySelectorAll('.tab-panel').forEach(function (p) { p.classList.remove('active'); });
  byId('panel-' + tab.getAttribute('data-tab')).classList.add('active');
});

byId('rescanBtn').addEventListener('click', function () { post('scan'); });
byId('exportBtn').addEventListener('click', function () { post('export'); });

render();
</script>
</body>
</html>`;
  }
}
