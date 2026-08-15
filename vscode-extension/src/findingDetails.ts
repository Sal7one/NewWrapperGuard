import * as vscode from 'vscode';
import * as fs from 'node:fs';
import { Finding, ScanState } from './models';

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function getNonce(): string {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let out = '';
  for (let i = 0; i < 32; i++) {
    out += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return out;
}

const SEVERITY_META: Record<string, { label: string; cls: string; icon: string }> = {
  ERROR: { label: 'Error', cls: 'err', icon: '✖' },
  WARN: { label: 'Warning', cls: 'warn', icon: '▲' },
  INFO: { label: 'Info', cls: 'info', icon: 'ℹ' }
};

const POLICY_META: Record<string, { label: string; cls: string }> = {
  BLOCK: { label: 'Blocks execution', cls: 'err' },
  REVIEW: { label: 'Review required', cls: 'warn' },
  ALLOW: { label: 'Informational', cls: 'info' }
};

/** Reads a few real file lines around the finding for the snippet block. */
function readSnippet(finding: Finding, root: vscode.Uri): Array<{ num: number; text: string; hit: boolean }> | null {
  const line = finding.location.line;
  if (line === undefined) {
    return null;
  }
  try {
    const filePath = vscode.Uri.joinPath(root, finding.location.path).fsPath;
    const lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
    const start = Math.max(0, line - 3);
    const end = Math.min(lines.length, line + 2);
    return lines.slice(start, end).map((text, i) => ({ num: start + i + 1, text, hit: start + i + 1 === line }));
  } catch {
    return null;
  }
}

export async function openFindingInEditor(finding: Finding, root: vscode.Uri): Promise<void> {
  const document = await vscode.workspace.openTextDocument(vscode.Uri.joinPath(root, finding.location.path));
  const editor = await vscode.window.showTextDocument(document);
  const line = Math.max(0, (finding.location.line ?? 1) - 1);
  editor.selection = new vscode.Selection(line, 0, line, 0);
  editor.revealRange(new vscode.Range(line, 0, line, 0), vscode.TextEditorRevealType.InCenter);
}

let panel: vscode.WebviewPanel | undefined;

export function showFindingDetails(finding: Finding, state: ScanState): void {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) {
    void vscode.window.showWarningMessage('Open a workspace folder first.');
    return;
  }

  if (panel) {
    panel.dispose();
  }
  panel = vscode.window.createWebviewPanel(
    'wrapperguardFindingDetail',
    `${finding.ruleId} · ${finding.title}`,
    vscode.ViewColumn.Beside,
    { enableScripts: true, retainContextWhenHidden: true }
  );

  panel.webview.html = buildHtml(finding, root);
  panel.webview.onDidReceiveMessage(async (msg: { type: string }) => {
    switch (msg.type) {
      case 'open':
        await openFindingInEditor(finding, root);
        break;
      case 'copy': {
        await vscode.env.clipboard.writeText(finding.evidence?.text ?? finding.message);
        void vscode.window.showInformationMessage('WrapperGuard: evidence copied to the clipboard.');
        break;
      }
      case 'suppress':
      case 'baseline':
        void vscode.window.showInformationMessage(
          'Suppression and baselines land in v0.3 with the trusted-baseline model — a repository must not be able to suppress its own payload.'
        );
        break;
    }
  });
  panel.onDidDispose(() => {
    panel = undefined;
  });
}

function buildHtml(finding: Finding, root: vscode.Uri): string {
  const nonce = getNonce();
  const sev = SEVERITY_META[finding.severity] ?? SEVERITY_META.INFO;
  const policy = POLICY_META[finding.policyAction] ?? { label: 'Review required', cls: 'warn' };
  const location = `${finding.location.path}:${finding.location.line ?? ''}`;

  const chain: string[] = (finding.callChain ?? []).map(
    (link) => `${link.source.path}:${link.source.line ?? ''}`
  );
  if (location !== chain[chain.length - 1]) {
    chain.push(location);
  }
  const pathHtml = chain.length > 0
    ? chain.map((c, i) => `<span class="path-chip${i === chain.length - 1 ? ' path-final' : ''}">${esc(c)}</span>${i < chain.length - 1 ? '<span class="path-arrow">→</span>' : ''}`).join('')
    : `<span class="path-chip">${esc(location)}</span>`;

  const snippet = readSnippet(finding, root);
  let snippetHtml: string;
  if (snippet && snippet.length > 0) {
    snippetHtml = snippet
      .map((l) => {
        const num = String(l.num).padStart(4, ' ');
        const body = esc(l.text);
        return l.hit
          ? `<div class="snippet-line hit"><span class="ln">${num}</span><span class="code">${body || ' '}</span></div>`
          : `<div class="snippet-line"><span class="ln">${num}</span><span class="code">${body || ' '}</span></div>`;
      })
      .join('');
  } else if (finding.evidence) {
    snippetHtml = `<div class="snippet-line hit"><span class="ln">   ·</span><span class="code">${esc(finding.evidence.text)}</span></div>`;
  } else {
    snippetHtml = '<div class="snippet-line"><span class="ln">   ·</span><span class="code muted">(no snippet available)</span></div>';
  }
  if (finding.evidence?.redacted) {
    snippetHtml += '<div class="snippet-note">🔒 Credential values in this evidence are redacted.</div>';
  }

  const remediationHtml = finding.remediation
    ? `<p>${esc(finding.remediation)}</p>`
    : '<p class="muted">No specific remediation provided.</p>';

  return `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8"/>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}';"/>
<style>
:root {
  --wg-bg: #13121b; --wg-surface: #1f1f28; --wg-surface-low: #1b1b24;
  --wg-surface-high: #2a2933; --wg-surface-highest: #35343e;
  --wg-border: #464555; --wg-border-strong: #918fa1;
  --wg-text: #e4e1ee; --wg-text-dim: #c7c4d8;
  --wg-primary: #c3c0ff; --wg-primary-container: #4f46e5; --wg-on-primary-container: #dad7ff;
  --wg-error: #ffb4ab; --wg-warn: #ffb695; --wg-ok: #7ee787;
  --wg-blue: #569cd6; --wg-mono-bg: #1e1e1e; --wg-mono-bar: #2d2d2d;
}
body.vscode-light {
  --wg-bg: #fbfbfd; --wg-surface: #ffffff; --wg-surface-low: #f6f6f8;
  --wg-surface-high: #f3f3f7; --wg-surface-highest: #ececf1;
  --wg-border: #d8d8e0; --wg-border-strong: #a5a5b0;
  --wg-text: #1f1f23; --wg-text-dim: #6b6b76;
  --wg-primary: #3d34c4; --wg-primary-container: #4f46e5; --wg-on-primary-container: #ffffff;
  --wg-error: #b3261e; --wg-warn: #9a6700; --wg-ok: #22863a;
  --wg-blue: #005fb8; --wg-mono-bg: #f3f3f7; --wg-mono-bar: #e4e4ea;
}
* { box-sizing: border-box; }
body {
  margin: 0; padding: 0; color: var(--wg-text);
  background: var(--wg-bg);
  font-family: var(--vscode-font-family);
  font-size: 14px; line-height: 20px;
}
.wrap { max-width: 880px; margin: 0 auto; padding: 16px; display: flex; flex-direction: column; gap: 16px; }
.mono { font-family: var(--vscode-editor-font-family); }
.header { display: flex; flex-direction: column; gap: 8px; padding-bottom: 12px; border-bottom: 1px solid var(--wg-border); }
.rule-chip {
  align-self: flex-start; display: inline-flex; align-items: center; gap: 6px;
  padding: 2px 8px; border: 1px solid color-mix(in srgb, var(--wg-error) 50%, transparent);
  background: color-mix(in srgb, var(--wg-error) 12%, transparent);
  color: var(--wg-error); border-radius: 999px;
  font-family: var(--vscode-editor-font-family); font-size: 11px; line-height: 16px;
  text-transform: uppercase; font-weight: 700; letter-spacing: 0.05em;
}
.rule-chip.ok { border-color: color-mix(in srgb, var(--wg-ok) 50%, transparent); background: color-mix(in srgb, var(--wg-ok) 12%, transparent); color: var(--wg-ok); }
.rule-chip.warn { border-color: color-mix(in srgb, var(--wg-warn) 50%, transparent); background: color-mix(in srgb, var(--wg-warn) 12%, transparent); color: var(--wg-warn); }
h1 { margin: 0; font-size: 18px; line-height: 24px; font-weight: 600; }
.sub { color: var(--wg-text-dim); font-size: 12px; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.cell { background: var(--wg-surface); border: 1px solid var(--wg-border); border-radius: 4px; padding: 8px; display: flex; flex-direction: column; gap: 4px; }
.cell .label { font-size: 11px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--wg-text-dim); }
.cell .value { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.value.err { color: var(--wg-error); }
.value.warn { color: var(--wg-warn); }
.value.info { color: var(--wg-blue); }
.section-label { font-size: 11px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; color: var(--wg-text-dim); margin-bottom: 4px; }
.path { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; background: var(--wg-surface-highest); border: 1px solid var(--wg-border); border-radius: 4px; padding: 8px; }
.path-chip { font-family: var(--vscode-editor-font-family); font-size: 11px; line-height: 16px; color: var(--wg-text-dim); }
.path-chip.path-final { color: var(--wg-error); font-weight: 700; border-bottom: 1px dashed var(--wg-error); }
.path-arrow { color: var(--wg-border-strong); }
.snippet { border: 1px solid var(--wg-border); border-radius: 4px; overflow: hidden; background: var(--wg-mono-bg); }
.snippet-bar { display: flex; align-items: center; justify-content: space-between; background: var(--wg-mono-bar); border-bottom: 1px solid var(--wg-border); padding: 4px 8px; }
.snippet-bar .file { font-family: var(--vscode-editor-font-family); font-size: 11px; color: var(--wg-text-dim); }
.copy-btn { background: transparent; border: none; color: var(--wg-text-dim); cursor: pointer; font-size: 12px; padding: 2px 6px; border-radius: 4px; }
.copy-btn:hover { color: var(--wg-primary); background: color-mix(in srgb, var(--wg-primary) 12%, transparent); }
.snippet-line { display: flex; font-family: var(--vscode-editor-font-family); font-size: 11px; line-height: 16px; }
.snippet-line .ln { flex: 0 0 44px; text-align: right; padding-right: 10px; color: var(--wg-border-strong); user-select: none; }
.snippet-line .code { white-space: pre-wrap; word-break: break-all; color: var(--wg-text); }
.snippet-line.hit { background: color-mix(in srgb, var(--wg-error) 12%, transparent); border-left: 2px solid var(--wg-error); }
.snippet-line.hit .code { font-weight: 700; }
.snippet-note { padding: 4px 10px; font-size: 11px; color: var(--wg-text-dim); border-top: 1px solid var(--wg-border); }
.remediation { background: var(--wg-surface-low); border-left: 2px solid var(--wg-primary); border-radius: 0 4px 4px 0; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
.remediation .head { color: var(--wg-primary); font-size: 11px; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; display: flex; align-items: center; gap: 6px; }
.remediation p { margin: 0; font-size: 12px; line-height: 16px; }
.muted { color: var(--wg-text-dim); }
.actions { display: flex; gap: 16px; padding-top: 16px; border-top: 1px solid var(--wg-border); flex-wrap: wrap; }
button {
  display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 12px;
  border-radius: 4px; font-family: var(--vscode-font-family); font-size: 12px; cursor: pointer;
}
.btn-primary { background: var(--wg-primary-container); color: var(--wg-on-primary-container); border: 1px solid transparent; font-weight: 500; }
.btn-primary:hover { background: color-mix(in srgb, var(--wg-primary-container) 85%, var(--wg-text)); }
.btn-ghost { background: transparent; color: var(--wg-text); border: 1px solid var(--wg-border-strong); }
.btn-ghost:hover { background: var(--wg-surface-high); }
.btn-ghost[disabled] { opacity: 0.45; cursor: not-allowed; }
</style>
</head>
<body>
<div class="wrap">
  <header class="header">
    <span class="rule-chip ${sev.cls}">${sev.icon} ${esc(finding.ruleId)}</span>
    <h1>${esc(finding.title)}</h1>
    <div class="sub mono">${esc(finding.category)} · confidence ${esc(finding.confidence)} · status ${esc(finding.status)}</div>
  </header>

  <div class="grid">
    <div class="cell">
      <span class="label">Severity</span>
      <span class="value ${sev.cls}">${sev.icon} ${sev.label}</span>
    </div>
    <div class="cell">
      <span class="label">Confidence</span>
      <span class="value info">✓ ${esc(finding.confidence)}</span>
    </div>
    <div class="cell">
      <span class="label">Policy Impact</span>
      <span class="value ${policy.cls}">${policy.label}</span>
    </div>
  </div>

  <section>
    <div class="section-label">Execution Path</div>
    <div class="path">${pathHtml}</div>
  </section>

  <section>
    <div class="section-label">Snippet</div>
    <div class="snippet">
      <div class="snippet-bar">
        <span class="file">${esc(finding.location.path)}</span>
        <button class="copy-btn" id="copy" title="Copy evidence">⧉ copy</button>
      </div>
      ${snippetHtml}
    </div>
  </section>

  <section class="remediation">
    <div class="head">⚙ Remediation</div>
    ${remediationHtml}
  </section>

  <footer class="actions">
    <button class="btn-primary" id="open">⌨ Open in Editor</button>
    <button class="btn-ghost" id="copy2">⧉ Copy Evidence</button>
    <button class="btn-ghost" id="suppress" disabled title="Available in v0.3 (trusted-baseline model)">◌ Suppress finding</button>
    <button class="btn-ghost" id="baseline" disabled title="Available in v0.3 (trusted-baseline model)">◈ Add to Baseline</button>
  </footer>
</div>
<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
document.getElementById('open').addEventListener('click', () => vscode.postMessage({ type: 'open' }));
document.getElementById('copy').addEventListener('click', () => vscode.postMessage({ type: 'copy' }));
document.getElementById('copy2').addEventListener('click', () => vscode.postMessage({ type: 'copy' }));
document.getElementById('suppress').addEventListener('click', () => vscode.postMessage({ type: 'suppress' }));
document.getElementById('baseline').addEventListener('click', () => vscode.postMessage({ type: 'baseline' }));
</script>
</body>
</html>`;
}
