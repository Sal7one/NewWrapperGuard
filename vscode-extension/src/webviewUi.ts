import * as vscode from 'vscode';
import { Finding, ScanSettings, ScanState } from './models';

/** Actions the webview UI can request; wired to real commands by the extension host. */
export interface UiHandlers {
  scan(): void;
  exportReport(): void;
  openFinding(finding: Finding): void;
  copyEvidence(finding: Finding): void;
  suppress(finding: Finding): void;
  baseline(finding: Finding): void;
  unbaseline(finding: Finding): void;
  exportSarif(): void;
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
 * The WrapperGuard sidebar as a real web app, following design_reference/
 * vs_code_finding_details and intellij_plugin_overview: app bar, editor-style tabs,
 * verdict pill + summary card, searchable grouped findings with a syntax-highlighted
 * evidence snippet and full finding detail, execution graph, policy pane, and a
 * status footer with blocker chip.
 */
export class WrapperGuardWebviewProvider implements vscode.WebviewViewProvider {
  static readonly viewType = 'wrapperGuard.main';

  handlers: UiHandlers = {
    scan: () => undefined,
    exportReport: () => undefined,
    openFinding: () => undefined,
    copyEvidence: () => undefined,
    suppress: () => undefined,
    baseline: () => undefined,
    unbaseline: () => undefined,
    exportSarif: () => undefined
  };

  private view?: vscode.WebviewView;
  private lastState: ScanState = { kind: 'not-scanned' };

  /** Optional hook so the extension host can log webview script errors. */
  onWebviewError?: (message: string) => void;

  resolveWebviewView(webviewView: vscode.WebviewView): void {
    this.view = webviewView;
    webviewView.webview.options = { enableScripts: true };
    webviewView.webview.html = this.buildHtml();
    // Re-sync: the webview starts from its own default state, so push the latest one.
    this.setState(this.lastState);
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
        case 'suppress':
          if (msg.finding) {
            this.handlers.suppress(msg.finding);
          }
          break;
        case 'baseline':
          if (msg.finding) {
            this.handlers.baseline(msg.finding);
          }
          break;
        case 'unbaseline':
          if (msg.finding) {
            this.handlers.unbaseline(msg.finding);
          }
          break;
        case 'sarif':
          this.handlers.exportSarif();
          break;
        case 'webview-error':
          this.onWebviewError?.((msg as { message?: string }).message ?? 'unknown error');
          break;
      }
    });
  }

  /** Pushes the latest scan state; the webview re-renders all panes from it. */
  setState(s: ScanState): void {
    this.lastState = s;
    const payload = s.kind === 'scanned'
      ? { kind: s.kind, report: s.report, settings: this.lastSettings }
      : { kind: s.kind, error: s.kind === 'failed' ? s.error : undefined, settings: this.lastSettings };
    this.view?.webview.postMessage({ type: 'state', state: payload });
  }

  private lastSettings?: ScanSettings;

  /** Active scanner settings (shown in the Policy pane). */
  setSettings(settings: ScanSettings): void {
    this.lastSettings = settings;
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
  --tok-kw: #569cd6; --tok-str: #ce9178; --tok-com: #6a9955; --tok-op: #d4d4d4;
}
body.vscode-light {
  --bg: #fbfbfd; --surface: #ffffff; --surface-low: #f6f6f8; --surface-lowest: #f1f1f4;
  --surface-high: #f3f3f7; --surface-highest: #ececf1;
  --border: #d8d8e0; --border-strong: #a5a5b0;
  --text: #1f1f23; --text-dim: #6b6b76;
  --primary: #3d34c4; --primary-container: #4f46e5; --on-primary-container: #ffffff;
  --error: #b3261e; --warn: #9a6700; --ok: #22863a; --info: #005fb8;
  --mono-bg: #f3f3f7; --mono-bar: #e4e4ea;
  --tok-kw: #0000ff; --tok-str: #a31515; --tok-com: #008000; --tok-op: #333333;
}
* { box-sizing: border-box; }
body {
  margin: 0; padding: 0; color: var(--text); background: var(--bg);
  font-family: var(--vscode-font-family); font-size: 13px; line-height: 18px;
  display: flex; flex-direction: column; height: 100vh;
}
button { font-family: var(--vscode-font-family); }
.mono { font-family: var(--vscode-editor-font-family); }
/* ── app bar ───────────────────────────── */
.app-bar {
  display: flex; align-items: center; justify-content: space-between; flex: 0 0 auto;
  padding: 5px 10px; background: var(--surface-low); border-bottom: 1px solid var(--border);
}
.brand { display: flex; align-items: center; gap: 8px; }
.logo {
  width: 24px; height: 24px; border-radius: 6px; display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--primary-container) 30%, transparent);
  border: 1px solid color-mix(in srgb, var(--primary) 35%, transparent);
  color: var(--primary); font-size: 13px;
}
.brand-name { font-size: 13px; font-weight: 700; color: var(--primary); line-height: 15px; }
.brand-ver { font-size: 10px; color: var(--text-dim); }
.app-actions { display: flex; gap: 2px; }
.icon-btn {
  background: transparent; border: none; color: var(--text-dim); cursor: pointer;
  width: 24px; height: 24px; border-radius: 4px; font-size: 13px; display: flex; align-items: center; justify-content: center;
}
.icon-btn:hover:not(:disabled) { color: var(--primary); background: var(--surface-high); }
.icon-btn:disabled { opacity: 0.5; cursor: default; }
.spin { animation: wgspin 1s linear infinite; display: inline-block; }
@keyframes wgspin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
/* ── tabs ───────────────────────────────── */
.tabs { display: flex; flex: 0 0 auto; border-bottom: 1px solid var(--border); background: var(--surface-low); }
.tab {
  flex: 1; background: transparent; border: none; color: var(--text-dim);
  padding: 6px 2px 5px; font-size: 10px; font-weight: 700; letter-spacing: 0.05em;
  text-transform: uppercase; cursor: pointer; border-bottom: 2px solid transparent;
  display: flex; align-items: center; justify-content: center; gap: 4px; white-space: nowrap;
}
.tab .ticon { font-size: 12px; }
.tab:hover { color: var(--primary); }
.tab.active { color: var(--primary); border-bottom-color: var(--primary); }
.tcount {
  font-size: 9px; padding: 0 5px; border-radius: 999px; background: var(--surface-highest);
  color: var(--text-dim); font-weight: 700;
}
/* ── status + summary card ─────────────── */
.status-card {
  flex: 0 0 auto; margin: 8px 10px 0; padding: 8px 10px; border: 1px solid var(--border);
  border-radius: 4px; background: var(--surface); display: flex; flex-direction: column; gap: 6px;
}
.pill {
  align-self: flex-start; display: inline-flex; align-items: center; gap: 6px; padding: 2px 10px;
  border-radius: 999px; font-size: 10px; font-weight: 700; letter-spacing: 0.05em;
  text-transform: uppercase; border: 1px solid var(--border-strong); color: var(--text-dim);
}
.pill.err { color: var(--error); border-color: color-mix(in srgb, var(--error) 50%, transparent); background: color-mix(in srgb, var(--error) 12%, transparent); }
.pill.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 50%, transparent); background: color-mix(in srgb, var(--warn) 12%, transparent); }
.pill.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 50%, transparent); background: color-mix(in srgb, var(--ok) 12%, transparent); }
.srow { display: flex; align-items: center; gap: 6px; font-size: 11px; }
.srow .sic { font-size: 11px; }
.srow .sic.err { color: var(--error); }
.srow .sic.warn { color: var(--warn); }
.srow .sic.info { color: var(--info); }
.srow .val { margin-left: auto; font-weight: 700; font-family: var(--vscode-editor-font-family); font-size: 11px; }
/* ── panes ─────────────────────────────── */
main { flex: 1 1 auto; padding: 4px 10px 12px; overflow-y: auto; }
.tab-panel { display: none; }
.tab-panel.active { display: block; }
.section-label {
  font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase;
  color: var(--text-dim); margin: 12px 0 5px;
}
/* overview */
.hero { text-align: center; padding: 26px 10px; color: var(--text-dim); }
.hero .big { font-size: 16px; font-weight: 600; color: var(--text); margin-bottom: 6px; }
.hero .btn { margin-top: 12px; }
.banner {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px; border-radius: 4px;
  border: 1px solid var(--border); background: var(--surface); font-size: 12px; font-weight: 600;
}
.banner.err { color: var(--error); border-color: color-mix(in srgb, var(--error) 45%, transparent); background: color-mix(in srgb, var(--error) 10%, var(--surface)); }
.banner.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 45%, transparent); background: color-mix(in srgb, var(--warn) 10%, var(--surface)); }
.banner.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 45%, transparent); background: color-mix(in srgb, var(--ok) 10%, var(--surface)); }
.alert-card {
  margin-top: 8px; border: 1px solid var(--border); border-radius: 4px; background: var(--surface-highest);
  padding: 6px 10px; display: flex; flex-direction: column; gap: 3px; font-size: 11px;
}
.alert-card .row { display: flex; justify-content: space-between; gap: 8px; }
.alert-card .k { color: var(--text-dim); }
.alert-card .v { font-weight: 600; text-align: right; }
.metrics { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; margin-top: 8px; }
.metric {
  background: var(--surface); border: 1px solid var(--border); border-radius: 4px; padding: 8px 10px;
  display: flex; flex-direction: column; gap: 2px;
}
.metric .top { display: flex; align-items: center; gap: 6px; }
.metric .mic { font-size: 12px; }
.metric .mic.err { color: var(--error); }
.metric .mic.warn { color: var(--warn); }
.metric .mic.ok { color: var(--ok); }
.metric .mic.info { color: var(--info); }
.metric .label { font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--text-dim); }
.metric .value { font-size: 18px; font-weight: 700; line-height: 22px; }
.metric .value.err { color: var(--error); }
.metric .value.warn { color: var(--warn); }
.metric .value.ok { color: var(--ok); }
.btn-row { display: flex; gap: 6px; margin-top: 12px; flex-wrap: wrap; }
.btn {
  display: inline-flex; align-items: center; gap: 5px; height: 28px; padding: 0 12px;
  border-radius: 4px; font-size: 11px; font-weight: 500; cursor: pointer; white-space: nowrap;
}
.btn:disabled { opacity: 0.5; cursor: default; }
.btn-primary { background: var(--primary-container); color: var(--on-primary-container); border: 1px solid transparent; }
.btn-primary:hover:not(:disabled) { background: color-mix(in srgb, var(--primary-container) 85%, var(--text)); }
.btn-ghost { background: transparent; color: var(--text); border: 1px solid var(--border-strong); }
.btn-ghost:hover:not(:disabled) { background: var(--surface-high); }
.notice { font-size: 11px; color: var(--text-dim); border-left: 2px solid var(--border-strong); padding-left: 8px; margin-top: 10px; }
/* findings */
.findings-toolbar { display: flex; gap: 4px; margin: 8px 0 4px; }
.findings-toolbar input {
  flex: 1; min-width: 0; height: 24px; background: var(--surface-high); border: 1px solid var(--border);
  border-radius: 4px; color: var(--text); font-size: 11px; padding: 0 8px; outline: none;
}
.findings-toolbar input:focus { border-color: var(--primary); }
.findings-toolbar .clear-btn { background: transparent; border: none; color: var(--text-dim); cursor: pointer; padding: 0 6px; border-radius: 4px; }
.findings-toolbar .clear-btn:hover { color: var(--primary); background: var(--surface-high); }
.findings-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.35fr); align-items: start; gap: 0 12px; }
.findings-layout .detail { border-left: 1px solid var(--border); padding-left: 10px; position: sticky; top: 0; max-height: calc(100vh - 190px); overflow-y: auto; }
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
  border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); border-radius: 2px;
}
.finding-row:nth-child(even) { background: color-mix(in srgb, var(--surface-high) 45%, transparent); }
.finding-row:hover { background: var(--surface-high); }
.finding-row.selected { background: color-mix(in srgb, var(--primary-container) 22%, transparent); border-left: 2px solid var(--primary); }
.finding-row .ficon { font-size: 12px; line-height: 16px; padding-top: 1px; }
.finding-row .ficon.err { color: var(--error); }
.finding-row .ficon.warn { color: var(--warn); }
.finding-row .ficon.info { color: var(--info); }
.finding-row .body { flex: 1; min-width: 0; }
.finding-row .title { font-size: 12px; font-weight: 500; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.finding-row .where { font-size: 10px; color: var(--text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.finding-row.suppressed { opacity: 0.55; }
.finding-row.suppressed:hover { opacity: 0.9; }
.finding-row .badges { display: flex; gap: 4px; margin-top: 3px; flex-wrap: wrap; }
.badge {
  font-size: 9px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase;
  padding: 1px 6px; border-radius: 999px; border: 1px solid var(--border-strong); color: var(--text-dim);
}
.badge.err { color: var(--error); border-color: color-mix(in srgb, var(--error) 50%, transparent); background: color-mix(in srgb, var(--error) 12%, transparent); }
.badge.warn { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 50%, transparent); background: color-mix(in srgb, var(--warn) 12%, transparent); }
.badge.ok { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 50%, transparent); background: color-mix(in srgb, var(--ok) 12%, transparent); }
.empty-note { padding: 14px 6px; color: var(--text-dim); font-size: 12px; text-align: center; }
/* detail */
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
.snippet-note { padding: 3px 8px; font-size: 10px; color: var(--text-dim); border-top: 1px solid var(--border); }
.tok-kw { color: var(--tok-kw); }
.tok-str { color: var(--tok-str); }
.tok-com { color: var(--tok-com); font-style: italic; }
.tok-op { color: var(--tok-op); font-weight: 700; }
.remediation { background: var(--surface-low); border-left: 2px solid var(--primary); border-radius: 0 4px 4px 0; padding: 8px 10px; display: flex; flex-direction: column; gap: 5px; }
.remediation .head { color: var(--primary); font-size: 10px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; }
.remediation p { margin: 0; font-size: 11px; line-height: 15px; }
.detail-actions { display: flex; gap: 6px; padding-top: 10px; flex-wrap: wrap; }
/* execution graph */
.graph-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 4px; background: #1a1924; padding: 6px; }
body.vscode-light .graph-wrap { background: var(--surface); }
.graph { position: relative; }
.g-svg { display: block; }
.gnode-card { position: absolute; width: 180px; background: var(--surface); border: 1px solid var(--border); border-radius: 4px; padding: 6px 8px; display: flex; flex-direction: column; gap: 3px; box-shadow: 0 2px 8px rgba(0,0,0,0.35); }
.gnode-card .g-cat { font-size: 8px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--text-dim); display: flex; align-items: center; gap: 4px; }
.gnode-card.g-entry { background: var(--surface-highest); }
.gnode-card.g-entry .g-cat { color: var(--primary); }
.gnode-card .g-name { font-family: var(--vscode-editor-font-family); font-size: 10px; color: var(--text); text-align: center; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.gnode-card .g-badge { align-self: flex-start; font-size: 8px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; padding: 1px 6px; border-radius: 2px; background: color-mix(in srgb, var(--error) 22%, transparent); border: 1px solid color-mix(in srgb, var(--error) 55%, transparent); color: var(--error); }
.gnode-card.g-sus { border: 2px solid var(--error); background: color-mix(in srgb, var(--error) 12%, var(--surface)); box-shadow: 0 0 14px color-mix(in srgb, var(--error) 18%, transparent); animation: wgpulse 3s ease-in-out infinite; }
.gnode-card.g-sus .g-name { color: var(--error); font-weight: 700; }
.gnode-card .g-desc { font-size: 9px; color: var(--text-dim); border-top: 1px solid color-mix(in srgb, var(--error) 35%, transparent); padding-top: 3px; text-align: center; }
@keyframes wgpulse { 0%, 100% { box-shadow: 0 0 6px color-mix(in srgb, var(--error) 12%, transparent); } 50% { box-shadow: 0 0 16px color-mix(in srgb, var(--error) 30%, transparent); } }
.path-block { background: var(--surface-high); border: 1px solid var(--border); border-left: 2px solid var(--error); border-radius: 4px; padding: 6px 8px; margin-bottom: 6px; }
.path-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 3px; }
.path-title { font-family: var(--vscode-editor-font-family); font-size: 10px; font-weight: 700; color: var(--error); }
.path-step { font-family: var(--vscode-editor-font-family); font-size: 9px; color: var(--text-dim); padding-left: 10px; border-left: 1px solid var(--border); margin-left: 4px; }
.path-step.path-last { color: var(--error); font-weight: 700; border-left-color: var(--error); }
.path-rule { font-family: var(--vscode-editor-font-family); font-size: 9px; color: var(--text-dim); padding-left: 14px; margin-top: 3px; }
.g-toolbar { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.g-tools { display: flex; background: var(--surface); border: 1px solid var(--border); border-radius: 4px; box-shadow: 0 3px 10px rgba(0,0,0,0.35); }
.g-btn { background: transparent; border: none; color: var(--text-dim); width: 26px; height: 24px; cursor: pointer; font-size: 13px; }
.g-btn:hover { color: var(--primary); background: var(--surface-high); }
.g-btn + .g-btn { border-left: 1px solid var(--border); }
.g-find { flex: 1; min-width: 0; height: 24px; background: var(--surface); border: 1px solid var(--border); border-radius: 4px; color: var(--text); font-size: 11px; padding: 0 8px; outline: none; }
.g-find:focus { border-color: var(--primary); }
.g-edge { transition: opacity 0.15s ease; }
.g-edge.dim { opacity: 0.1; }
.g-edge.active path { stroke-width: 2.6; }
.g-edge.err path { stroke-dasharray: 5 4; animation: wgdash 1.1s linear infinite; }
@keyframes wgdash { to { stroke-dashoffset: -18; } }
.gnode-card { transition: opacity 0.15s ease; animation: wgenter 0.28s ease both; cursor: pointer; }
.gnode-card:hover { border-color: var(--primary); }
.gnode-card.dim-node { opacity: 0.15; }
.gnode-card.g-neighbor { outline: 1px solid var(--primary); outline-offset: 1px; }
.gnode-card.g-hit { outline: 1px solid var(--primary); outline-offset: 1px; }
@keyframes wgenter { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
#gInner { transition: transform 0.18s ease; }
.edge-row { display: flex; align-items: center; gap: 6px; padding: 5px 6px; border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); }
.edge-row:nth-child(even) { background: color-mix(in srgb, var(--surface-high) 45%, transparent); }
.edge-row .via { font-size: 10px; color: var(--text-dim); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.edge-row .arrow { color: var(--border-strong); }
/* policy */
.policy-row { display: flex; justify-content: space-between; gap: 8px; padding: 6px 4px; border-bottom: 1px solid color-mix(in srgb, var(--border) 55%, transparent); font-size: 11px; }
.policy-row .k { color: var(--text-dim); }
.policy-row .v { text-align: right; font-weight: 500; }
.legend { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; }
/* footer */
.app-footer {
  flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between;
  padding: 3px 10px; border-top: 1px solid var(--border); background: var(--surface-lowest);
  font-size: 10px; color: var(--text-dim);
}
.app-footer .chip {
  display: inline-flex; align-items: center; gap: 4px; margin-left: 8px; padding: 1px 8px;
  border-radius: 999px; font-weight: 700;
}
.app-footer .chip.err { color: var(--error); background: color-mix(in srgb, var(--error) 15%, transparent); }
.footer-links { display: flex; gap: 12px; }
.flink { cursor: pointer; }
.flink:hover { color: var(--primary); }
</style>
</head>
<body>
  <header class="app-bar">
    <div class="brand">
      <span class="logo">🛡</span>
      <div>
        <div class="brand-name">WrapperGuard</div>
        <div class="brand-ver mono">v0.2.0</div>
      </div>
    </div>
    <div class="app-actions">
      <button class="icon-btn" id="exportBtn" title="Export report (JSON)">⤓</button>
      <button class="icon-btn" id="settingsBtn" title="Show policy">⚙</button>
      <button class="icon-btn" id="rescanBtn" title="Rescan project">⟳</button>
    </div>
  </header>

  <nav class="tabs" id="tabs">
    <button class="tab active" data-tab="overview"><span class="ticon">◧</span>Overview</button>
    <button class="tab" data-tab="findings"><span class="ticon">⚠</span>Findings <span class="tcount" id="tabFindingsCount">0</span></button>
    <button class="tab" data-tab="graph"><span class="ticon">⎇</span>Graph</button>
    <button class="tab" data-tab="policy"><span class="ticon">⚙</span>Policy</button>
  </nav>

  <section class="status-card">
    <span class="pill" id="verdictPill">Not scanned</span>
    <div class="srow"><span class="sic err">✖</span><span>Blockers</span><span class="val" id="sumBlockers">0</span></div>
    <div class="srow"><span class="sic err">✖</span><span>Errors</span><span class="val" id="sumErrors">0</span></div>
    <div class="srow"><span class="sic warn">▲</span><span>Warnings</span><span class="val" id="sumWarnings">0</span></div>
    <div class="srow"><span class="sic info">◌</span><span>Suppressed</span><span class="val" id="sumSuppressed">0</span></div>
  </section>

  <main>
    <section class="tab-panel active" id="panel-overview"></section>
    <section class="tab-panel" id="panel-findings">
      <div class="findings-toolbar">
        <input id="filterInput" type="text" placeholder="Search rules, files…" spellcheck="false"/>
        <button class="clear-btn" id="btnClearFilter" title="Clear filter">✕</button>
      </div>
      <div class="findings-layout" id="findingsLayout"></div>
    </section>
    <section class="tab-panel" id="panel-graph"></section>
    <section class="tab-panel" id="panel-policy"></section>
  </main>

  <footer class="app-footer">
    <span id="footerLeft">WrapperGuard: Not scanned</span>
    <span class="footer-links">
      <span class="flink" title="Documentation — coming in v0.3">Docs</span>
      <span class="flink" title="Logs — coming in v0.3">Logs</span>
      <span class="flink" title="Support — coming in v0.3">Support</span>
    </span>
  </footer>

<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
let state = { kind: 'not-scanned' };
let selectedFp = null;
let filterText = '';
let wide = window.matchMedia('(min-width: 540px)').matches;
let zoomScale = 1;
let nodeSearch = '';

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
const KW_LIST = 'curl|wget|fetch|bash|sh|zsh|powershell|pwsh|echo|printf|set|unset|exec|eval|java|export|cd|rm|cp|mv|chmod|sudo|cat|install|build|git|node|npm|pip|python|if|then|fi|else|elif|for|do|done|while|case|esac|function|return|start|call|bitsadmin|certutil';
const KEYWORDS = new RegExp('(^|[^A-Za-z0-9_])(' + KW_LIST + ')(?=[^A-Za-z0-9_]|$)', 'g');

function esc(s) {
  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
function byId(id) { return document.getElementById(id); }
function post(type, finding) { vscode.postMessage({ type: type, finding: finding }); }

function hlLine(raw) {
  let s = esc(raw);
  s = s.replace(/(&quot;[^&]*?&quot;|'[^']*')/g, '<span class="tok-str">$1</span>');
  if (/^#/.test(s)) {
    s = '<span class="tok-com">' + s + '</span>';
  } else {
    s = s.replace(/ #.*$/, function (m) { return '<span class="tok-com">' + m + '</span>'; });
  }
  s = s.replace(/(&gt;{1,2}|&lt;|[|]{1,2}|&&)/g, '<span class="tok-op">$1</span>');
  s = s.replace(KEYWORDS, '$1<span class="tok-kw">$2</span>');
  return s;
}

window.addEventListener('message', function (e) {
  const m = e.data;
  if (m && m.type === 'state') {
    state = m.state;
    render();
  }
});

window.onerror = function (msg, src, line) {
  vscode.postMessage({ type: 'webview-error', message: String(msg) + ' (line ' + line + ')' });
};

window.matchMedia('(min-width: 540px)').addEventListener('change', function (e) {
  wide = e.matches;
  renderFindings();
});

function report() { return state.kind === 'scanned' ? state.report : null; }

function render() {
  renderStatusCard();
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

function renderStatusCard() {
  const vm = verdictMeta();
  const pill = byId('verdictPill');
  pill.className = 'pill ' + vm[0];
  pill.textContent = '◉ ' + vm[1];
  const r = report();
  const blockers = r ? r.findings.filter(function (f) { return f.policyAction === 'BLOCK' && f.status === 'ACTIVE'; }).length : 0;
  const errors = r ? r.findings.filter(function (f) { return f.severity === 'ERROR' && f.status === 'ACTIVE'; }).length : 0;
  const warnings = r ? r.findings.filter(function (f) { return f.severity === 'WARN' && f.status === 'ACTIVE'; }).length : 0;
  const suppressed = r ? r.findings.filter(function (f) { return f.status === 'SUPPRESSED'; }).length : 0;
  byId('sumBlockers').textContent = String(blockers);
  byId('sumErrors').textContent = String(errors);
  byId('sumWarnings').textContent = String(warnings);
  byId('sumSuppressed').textContent = String(suppressed);
  byId('tabFindingsCount').textContent = String(r ? r.findings.length : 0);
  const footer = byId('footerLeft');
  footer.innerHTML = '<span class="mono">WrapperGuard: ' + esc(vm[1]) + '</span>';
  if (state.kind === 'scanned' && blockers > 0) {
    footer.innerHTML += '<span class="chip err">✖ ' + blockers + ' blocker' + (blockers === 1 ? '' : 's') + '</span>';
  }
}

function renderBusy() {
  const btn = byId('rescanBtn');
  if (state.kind === 'scanning') {
    btn.disabled = true;
    btn.innerHTML = '<span class="spin">⟳</span>';
  } else {
    btn.disabled = false;
    btn.innerHTML = '⟳';
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
  const warnings = r.findings.filter(function (f) { return f.severity === 'WARN'; }).length;
  const edges = (r.edges || []).length;
  const vm = verdictMeta();
  const cls = blockers > 0 ? 'err' : (!r.complete || r.stale) ? 'warn' : r.verdict === 'CLEAN' ? 'ok' : 'warn';
  const icon = cls === 'err' ? '⛔' : cls === 'warn' ? '▲' : '✓';
  let html = '<div class="banner ' + cls + '">' + icon + ' ' + esc(vm[1]);
  if (blockers > 0) html += ' — ' + blockers + ' blocker' + (blockers === 1 ? '' : 's');
  html += '</div>';

  const ts = r.generatedAtEpochMs ? new Date(r.generatedAtEpochMs).toLocaleString() : '—';
  html += '<div class="alert-card">';
  html += '<div class="row"><span class="k">Last scan</span><span class="v mono">' + esc(ts) + '</span></div>';
  html += '<div class="row"><span class="k">Active policy</span><span class="v">' + esc(r.policyName) + '</span></div>';
  html += '<div class="row"><span class="k">Scan duration</span><span class="v mono">' + r.durationMs + ' ms</span></div>';
  html += '</div>';

  html += '<div class="metrics">';
  html += '<div class="metric"><div class="top"><span class="mic err">⛔</span><span class="label">Blocking findings</span></div><span class="value err">' + blockers + '</span></div>';
  html += '<div class="metric"><div class="top"><span class="mic warn">▲</span><span class="label">Warnings</span></div><span class="value warn">' + warnings + '</span></div>';
  html += '<div class="metric"><div class="top"><span class="mic info">▤</span><span class="label">Files scanned</span></div><span class="value">' + r.scannedFiles + '</span></div>';
  html += '<div class="metric"><div class="top"><span class="mic ok">⎇</span><span class="label">Call-chain edges</span></div><span class="value">' + edges + '</span></div>';
  html += '</div>';

  html += '<div class="btn-row">';
  html += '<button class="btn btn-primary" id="ovRescan">⟳ Rescan Project</button>';
  html += '<button class="btn btn-ghost" id="ovExport">⤓ Export JSON</button>';
  html += '<button class="btn btn-ghost" id="ovExportSarif">⤓ Export SARIF</button>';
  html += '</div>';

  if (!r.complete) {
    html += '<div class="notice">⚠ Scan incomplete: ' + esc((r.notices || []).map(function (n) { return n.message; }).join('; ') || 'some files were skipped.') + '</div>';
  }
  if (r.stale) {
    html += '<div class="notice">⚠ Report is stale — a watched file changed after the scan. Rescan to refresh.</div>';
  }
  html += '<div class="notice">Scope: this report covers the scanned workspace. Run-time enforcement lives in the IntelliJ/Android Studio plugin; terminal and CI protection comes from the CLI.</div>';

  panel.innerHTML = html;
  byId('ovRescan').addEventListener('click', function () { post('scan'); });
  byId('ovExport').addEventListener('click', function () { post('export'); });
  byId('ovExportSarif').addEventListener('click', function () { post('sarif'); });
}

function groupFindings() {
  const r = report();
  if (!r) return null;
  const groups = { blockers: [], errors: [], warnings: [], info: [], suppressed: [] };
  r.findings.forEach(function (f) {
    if (f.status === 'SUPPRESSED') {
      groups.suppressed.push(f);
      return;
    }
    if (f.status !== 'ACTIVE') return; // fixed findings are not actionable
    if (f.policyAction === 'BLOCK') groups.blockers.push(f);
    else if (f.severity === 'ERROR') groups.errors.push(f);
    else if (f.severity === 'WARN') groups.warnings.push(f);
    else groups.info.push(f);
  });
  return groups;
}

function matchesFilter(f) {
  if (!filterText) return true;
  const q = filterText.toLowerCase();
  return (f.ruleId + ' ' + f.title + ' ' + f.location.path + ' ' + f.category + ' ' + f.message).toLowerCase().indexOf(q) >= 0;
}

function rowHtml(f) {
  const sev = SEV_ICON[f.severity] || SEV_ICON.INFO;
  const suppressed = f.status === 'SUPPRESSED';
  const badges = '<span class="badge ' + sev[1] + '">' + esc(f.ruleId) + '</span>' +
    '<span class="badge">' + esc(f.category) + '</span>' +
    (suppressed ? '<span class="badge">BASELINED</span>' : (f.policyAction === 'BLOCK' ? '<span class="badge err">BLOCK</span>' : ''));
  return '<div class="finding-row' + (suppressed ? ' suppressed' : '') + (f.fingerprint === selectedFp ? ' selected' : '') + '" data-fp="' + esc(f.fingerprint) + '">' +
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
  const visible = r.findings.filter(matchesFilter).length;
  const showingDetail = selectedFp && !wide;
  layout.className = 'findings-layout' + (showingDetail ? ' narrow' : '');

  const sel = r.findings.filter(function (f) { return f.fingerprint === selectedFp; })[0] || null;

  let list = '';
  if (total === 0 && r.complete && !r.stale) {
    list = '<div class="empty-note">✓ Clean — no findings in ' + r.scannedFiles + ' scanned files.</div>';
  } else if (visible === 0) {
    list = '<div class="empty-note">No findings match "' + esc(filterText) + '".</div>';
  } else {
    const defs = [
      ['blockers', '⛔ Execution Blockers', 'err'],
      ['errors', '✖ Errors', 'err'],
      ['warnings', '▲ Warnings', 'warn'],
      ['info', 'ℹ Info', 'info'],
      ['suppressed', '◌ Suppressed (baseline)', 'info']
    ];
    defs.forEach(function (d) {
      const items = groups[d[0]].filter(matchesFilter);
      if (items.length === 0) return;
      list += '<div class="group-head"><span class="' + d[2] + '">' + d[1] + '</span><span>(' + items.length + ')</span></div>';
      items.forEach(function (f) { list += rowHtml(f); });
    });
  }
  const detailHtml = sel ? detailHtmlFor(sel, true) : '<div class="empty-note">Select a finding to inspect evidence, execution path, and remediation.</div>';
  layout.innerHTML = '<div class="list" id="findingsList">' + list + '</div><div class="detail" id="findingDetail">' + detailHtml + '</div>';

  if (showingDetail && sel) {
    byId('findingsList').style.display = 'none';
  }
  layout.querySelectorAll('.finding-row').forEach(function (row) {
    row.addEventListener('click', function () {
      selectedFp = row.getAttribute('data-fp');
      renderFindings();
    });
  });
  const back = byId('detailBack');
  if (back) back.addEventListener('click', function () { selectedFp = null; renderFindings(); });
  const openBtn = byId('detailOpen');
  if (openBtn) openBtn.addEventListener('click', function () { if (sel) post('open', sel); });
  const suppressBtn = byId('detailSuppress');
  if (suppressBtn) suppressBtn.addEventListener('click', function () { if (sel) post('suppress', sel); });
  const baselineBtn = byId('detailBaseline');
  if (baselineBtn) baselineBtn.addEventListener('click', function () { if (sel) post('baseline', sel); });
  const unbaselineBtn = byId('detailUnbaseline');
  if (unbaselineBtn) unbaselineBtn.addEventListener('click', function () { if (sel) post('unbaseline', sel); });
  document.querySelectorAll('.js-copy').forEach(function (b) {
    b.addEventListener('click', function () { if (sel) post('copy', sel); });
  });
}

function detailHtmlFor(f, withBack) {
  const sev = SEV_ICON[f.severity] || SEV_ICON.INFO;
  const policy = f.policyAction === 'BLOCK' ? ['err', '⛔ Blocks execution'] : f.policyAction === 'REVIEW' ? ['warn', '▲ Review required'] : ['info', 'ℹ Informational'];
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
    const lines = evidence.split(String.fromCharCode(10));
    const hitIndex = lines.length - 1;
    lines.forEach(function (line, i) {
      const ln = i === hitIndex && f.location.line != null ? String(f.location.line).padStart(4, ' ') : '   ·';
      snippet += '<div class="snippet-line' + (i === hitIndex ? ' hit' : '') + '"><span class="ln">' + ln + '</span><span class="code">' + hlLine(line) + '</span></div>';
    });
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
  html += '<div class="cell"><span class="label">Policy impact</span><span class="value ' + policy[0] + '">' + policy[1] + '</span></div>';
  html += '</div>';
  html += '<div class="section-label">Execution Path</div><div class="path">' + pathHtml + '</div>';
  html += '<div class="section-label">Snippet</div>';
  html += '<div class="snippet"><div class="snippet-bar"><span class="file">' + esc(f.location.path) + '</span><button class="copy-btn js-copy" title="Copy evidence">⧉ copy</button></div>' + snippet + '</div>';
  html += '<div class="section-label">Remediation</div>';
  html += '<div class="remediation"><div class="head">⚙ Remediation</div><p>' + esc(f.remediation || 'No specific remediation provided.') + '</p></div>';
  html += '<div class="detail-actions">';
  html += '<button class="btn btn-primary" id="detailOpen" style="height:26px;font-size:11px">⌨ Open in Editor</button>';
  html += '<button class="btn btn-ghost js-copy" style="height:26px;font-size:11px">⧉ Copy Evidence</button>';
  if (f.status === 'SUPPRESSED') {
    html += '<button class="btn btn-ghost" id="detailUnbaseline" style="height:26px;font-size:11px" title="Remove this finding from your trusted baseline and rescan">↩ Restore (remove from baseline)</button>';
  } else {
    html += '<button class="btn btn-ghost" id="detailSuppress" style="height:26px;font-size:11px" title="Add a wrapperguard:ignore marker at this line">◌ Suppress finding</button>';
    html += '<button class="btn btn-ghost" id="detailBaseline" style="height:26px;font-size:11px" title="Add to your trusted baseline (stored outside the repository)">◈ Add to Baseline</button>';
  }
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

  const blockerByPath = {};
  r.findings.forEach(function (f) {
    if (f.policyAction !== 'BLOCK') return;
    if (!blockerByPath[f.location.path]) blockerByPath[f.location.path] = f;
  });
  const suspLines = {};
  r.findings.forEach(function (f) {
    if (f.policyAction === 'BLOCK') suspLines[f.location.path + ':' + (f.location.line != null ? f.location.line : '')] = f;
  });

  const children = {};
  const parents = {};
  const nodes = new Set();
  edges.forEach(function (e) {
    (children[e.from.path] = children[e.from.path] || []).push(e.to.path);
    (parents[e.to.path] = parents[e.to.path] || []).push(e.from.path);
    nodes.add(e.from.path);
    nodes.add(e.to.path);
  });
  const depth = {};
  const queue = [];
  Array.from(nodes).sort().forEach(function (n) {
    if (!parents[n]) {
      depth[n] = 0;
      queue.push(n);
    }
  });
  while (queue.length > 0) {
    const n = queue.shift();
    (children[n] || []).forEach(function (c) {
      if (depth[c] === undefined) {
        depth[c] = (depth[n] || 0) + 1;
        queue.push(c);
      }
    });
  }

  // Barycenter ordering (two passes) to reduce edge crossings.
  const layers = {};
  Array.from(nodes).sort().forEach(function (n) {
    const d = depth[n] === undefined ? 0 : depth[n];
    (layers[d] = layers[d] || []).push(n);
  });
  const layerKeys = Object.keys(layers).map(Number).sort(function (a, b) { return a - b; });
  const order = {};
  layerKeys.forEach(function (d) { order[d] = layers[d].slice().sort(); });
  function avgParentPos(n) {
    const ps = parents[n] || [];
    if (ps.length === 0) return 0;
    let s = 0;
    let c = 0;
    ps.forEach(function (p) {
      const dd = depth[p];
      const idx = (order[dd] || []).indexOf(p);
      if (idx >= 0) {
        s += idx;
        c++;
      }
    });
    return c > 0 ? s / c : 0;
  }
  function avgChildPos(n) {
    const cs = children[n] || [];
    if (cs.length === 0) return 0;
    let s = 0;
    let c = 0;
    cs.forEach(function (ch) {
      const dd = depth[ch];
      const idx = (order[dd] || []).indexOf(ch);
      if (idx >= 0) {
        s += idx;
        c++;
      }
    });
    return c > 0 ? s / c : 0;
  }
  for (let i = 1; i < layerKeys.length; i++) {
    const d = layerKeys[i];
    order[d].sort(function (a, b) { return avgParentPos(a) - avgParentPos(b); });
  }
  for (let i = layerKeys.length - 2; i >= 0; i--) {
    const d = layerKeys[i];
    order[d].sort(function (a, b) { return avgChildPos(a) - avgChildPos(b); });
  }

  const maxCol = layerKeys.reduce(function (m, d) { return Math.max(m, order[d].length); }, 1);
  const maxDepth = layerKeys.length;
  const colW = 224;
  const rowH = 152;
  const W = Math.max(460, maxCol * colW + 70);
  const H = maxDepth * rowH + 90;

  const pos = {};
  layerKeys.forEach(function (d, li) {
    const list = order[d];
    const layerW = list.length * colW;
    const startX = (W - layerW) / 2;
    list.forEach(function (n, i) {
      pos[n] = { x: Math.round(startX + i * colW), y: 46 + li * rowH };
    });
  });

  let svg = '<svg class="g-svg" viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '">';
  svg += '<defs>';
  svg += '<pattern id="wgGrid" width="22" height="22" patternUnits="userSpaceOnUse"><circle cx="1.5" cy="1.5" r="1.5" fill="var(--wg-border)" opacity="0.35"></circle></pattern>';
  svg += '<marker id="wgArrow" markerHeight="7" markerWidth="7" orient="auto-start-reverse" refX="9" refY="5" viewBox="0 0 10 10"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--wg-border-strong)"></path></marker>';
  svg += '<marker id="wgArrowErr" markerHeight="7" markerWidth="7" orient="auto-start-reverse" refX="9" refY="5" viewBox="0 0 10 10"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--wg-error)"></path></marker>';
  svg += '</defs>';
  svg += '<rect x="0" y="0" width="' + W + '" height="' + H + '" fill="url(#wgGrid)"></rect>';
  edges.forEach(function (e) {
    const a = pos[e.from.path];
    const b = pos[e.to.path];
    if (!a || !b) return;
    const x1 = a.x + 90;
    const y1 = a.y + 62;
    const x2 = b.x + 90;
    const y2 = b.y + 2;
    const key = e.to.path + ':' + (e.to.line != null ? e.to.line : '');
    const err = suspLines[key];
    const stroke = err ? 'var(--wg-error)' : 'var(--wg-border-strong)';
    const marker = err ? 'url(#wgArrowErr)' : 'url(#wgArrow)';
    svg += '<g class="g-edge' + (err ? ' err' : '') + '" data-from="' + esc(e.from.path) + '" data-to="' + esc(e.to.path) + '">';
    svg += '<path d="M ' + x1 + ' ' + y1 + ' C ' + x1 + ' ' + (y1 + 38) + ', ' + x2 + ' ' + (y2 - 38) + ', ' + x2 + ' ' + y2 + '" fill="none" marker-end="' + marker + '" stroke="' + stroke + '" stroke-width="1.6"></path>';
    const label = (e.from.line != null ? 'line:' + e.from.line : '') || (e.viaCommand || '').slice(0, 24);
    if (label) {
      svg += '<text x="' + ((x1 + x2) / 2 + 9) + '" y="' + ((y1 + y2) / 2) + '" fill="' + (err ? 'var(--wg-error)' : 'var(--wg-text-dim)') + '" font-family="var(--vscode-editor-font-family)" font-size="9">' + esc(label) + '</text>';
    }
    svg += '</g>';
  });
  svg += '</svg>';

  let cards = '';
  let cardIndex = 0;
  Array.from(nodes).sort().forEach(function (n) {
    const p = pos[n];
    if (!p) return;
    const base = n.split('/').pop() || n;
    const ext = base.indexOf('.') >= 0 ? base.slice(base.lastIndexOf('.') + 1).toUpperCase() : 'SCRIPT';
    const blocker = blockerByPath[n];
    const isEntry = !parents[n];
    let card = '<div class="gnode-card' + (blocker ? ' g-sus' : '') + (isEntry ? ' g-entry' : '') + '" data-node="' + esc(n) + '" style="left:' + p.x + 'px;top:' + p.y + 'px;animation-delay:' + (cardIndex * 45) + 'ms">';
    cardIndex++;
    if (isEntry) {
      card += '<div class="g-cat">&#9654; Entry point</div>';
    } else {
      card += '<div class="g-cat">' + esc(ext) + '</div>';
    }
    if (blocker) {
      card += '<div class="g-badge">&#9940; Suspicious execution</div>';
    }
    card += '<div class="g-name mono">' + esc(n) + '</div>';
    if (blocker) {
      card += '<div class="g-desc mono">' + esc(blocker.title) + '</div>';
    }
    card += '</div>';
    cards += card;
  });

  let explorer = '';
  const blockerFindings = r.findings.filter(function (f) { return f.policyAction === 'BLOCK'; });
  if (blockerFindings.length > 0) {
    explorer = '<div class="section-label" style="margin-top:12px">Paths to blockers (' + blockerFindings.length + ')</div>';
    blockerFindings.forEach(function (f, i) {
      const chain = (f.callChain || []).map(function (l) { return l.source.path + ':' + (l.source.line != null ? l.source.line : ''); });
      chain.push(f.location.path + ':' + (f.location.line != null ? f.location.line : ''));
      explorer += '<div class="path-block">';
      explorer += '<div class="path-head"><span class="path-title mono">Path #' + (i + 1) + '</span><span class="badge err">Critical</span></div>';
      chain.forEach(function (step, si) {
        const last = si === chain.length - 1;
        explorer += '<div class="path-step mono' + (last ? ' path-last' : '') + '">' + (last ? '&#8618; ' : '&#183; ') + esc(step) + '</div>';
      });
      explorer += '<div class="path-rule mono">' + esc(f.ruleId) + ' — ' + esc(f.title) + '</div>';
      explorer += '</div>';
    });
  }

  const toolbar =
    '<div class="g-toolbar">' +
    '<div class="g-tools">' +
    '<button class="g-btn" id="gZoomIn" title="Zoom in">+</button>' +
    '<button class="g-btn" id="gZoomOut" title="Zoom out">-</button>' +
    '<button class="g-btn" id="gFit" title="Fit to view">&#10534;</button>' +
    '</div>' +
    '<input id="gFind" class="g-find" type="text" placeholder="Find node..." spellcheck="false"/>' +
    '</div>';

  const legend = '<div class="legend" style="margin-top:8px">' +
    '<span class="badge">entry</span>' +
    '<span class="badge warn">script hop</span>' +
    '<span class="badge err">blocker</span>' +
    '<span class="badge">hover to trace</span>' +
    '</div>';

  panel.innerHTML =
    '<div class="section-label">Call-chain graph (' + nodes.size + ' nodes · ' + edges.length + ' edges)</div>' +
    toolbar +
    '<div class="graph-wrap" id="graphWrap"><div class="graph" id="gInner">' + svg + cards + '</div></div>' +
    explorer + legend +
    '<div class="notice" style="margin-top:10px">Call chains are discovered statically — scripts are never executed during analysis.</div>';
  bindGraphEvents(r);
}

function applyZoom() {
  const inner = byId('gInner');
  if (inner) {
    inner.style.transform = 'scale(' + zoomScale + ')';
    inner.style.transformOrigin = 'top left';
  }
}

function bindGraphEvents(r) {
  const zIn = byId('gZoomIn');
  const zOut = byId('gZoomOut');
  const zFit = byId('gFit');
  if (zIn) zIn.addEventListener('click', function () { zoomScale = Math.min(2, zoomScale + 0.2); applyZoom(); });
  if (zOut) zOut.addEventListener('click', function () { zoomScale = Math.max(0.4, zoomScale - 0.2); applyZoom(); });
  if (zFit) zFit.addEventListener('click', function () {
    const wrap = byId('graphWrap');
    if (!wrap) return;
    const avail = wrap.clientWidth - 20;
    const svg = wrap.querySelector('svg');
    const w = svg ? svg.getAttribute('width') : 460;
    zoomScale = Math.min(2, Math.max(0.4, avail / Number(w)));
    applyZoom();
  });
  const find = byId('gFind');
  if (find) find.addEventListener('input', function (e) {
    nodeSearch = e.target.value.toLowerCase();
    const cardsAll = document.querySelectorAll('#panel-graph .gnode-card');
    cardsAll.forEach(function (c) {
      const n = c.getAttribute('data-node') || '';
      const hit = !nodeSearch || n.toLowerCase().indexOf(nodeSearch) >= 0;
      c.classList.toggle('g-hit', !!nodeSearch && hit);
      c.classList.toggle('dim-node', !!nodeSearch && !hit);
    });
  });
  document.querySelectorAll('#panel-graph .gnode-card').forEach(function (node) {
    node.addEventListener('mouseenter', function () { setEdgeActive(node.getAttribute('data-node'), true); });
    node.addEventListener('mouseleave', function () { setEdgeActive(null, false); });
    node.addEventListener('click', function () {
      const n = node.getAttribute('data-node');
      const blocker = r.findings.filter(function (f) { return f.policyAction === 'BLOCK' && f.location.path === n; })[0];
      if (blocker) {
        selectedFp = blocker.fingerprint;
        switchTab('findings');
        renderFindings();
      }
    });
  });
}

function setEdgeActive(nodeName, on) {
  const edgeEls = document.querySelectorAll('#panel-graph .g-edge');
  const nodeEls = document.querySelectorAll('#panel-graph .gnode-card');
  const neighbors = new Set();
  if (on && nodeName) {
    edgeEls.forEach(function (g) {
      const f = g.getAttribute('data-from');
      const t = g.getAttribute('data-to');
      if (f === nodeName) neighbors.add(t);
      if (t === nodeName) neighbors.add(f);
    });
  }
  edgeEls.forEach(function (g) {
    const f = g.getAttribute('data-from');
    const t = g.getAttribute('data-to');
    const active = on && nodeName && (f === nodeName || t === nodeName);
    g.classList.toggle('dim', on && !active);
    g.classList.toggle('active', !!active);
  });
  nodeEls.forEach(function (c) {
    const n = c.getAttribute('data-node');
    const isSelf = n === nodeName;
    const isNeighbor = on && neighbors.has(n);
    c.classList.toggle('dim-node', on && !isSelf && !isNeighbor);
    c.classList.toggle('g-neighbor', isNeighbor);
  });
}


/* tabs */
function switchTab(name) {
  document.querySelectorAll('.tab').forEach(function (t) { t.classList.toggle('active', t.getAttribute('data-tab') === name); });
  document.querySelectorAll('.tab-panel').forEach(function (p) { p.classList.remove('active'); });
  byId('panel-' + name).classList.add('active');
}

byId('tabs').addEventListener('click', function (e) {
  const tab = e.target.closest('.tab');
  if (!tab) return;
  switchTab(tab.getAttribute('data-tab'));
});

byId('rescanBtn').addEventListener('click', function () { post('scan'); });
byId('exportBtn').addEventListener('click', function () { post('export'); });
byId('settingsBtn').addEventListener('click', function () {
  switchTab('policy');
});
byId('filterInput').addEventListener('input', function (e) {
  filterText = e.target.value;
  renderFindings();
});
byId('btnClearFilter').addEventListener('click', function () {
  filterText = '';
  byId('filterInput').value = '';
  renderFindings();
});

render();
</script>
</body>
</html>`;
  }
}
