import * as vscode from 'vscode';
import { Finding, ScanSettings, ScanState } from './models';
import { realpath } from 'node:fs/promises';
import * as path from 'node:path';
import { MethodView, methodById } from './methods/catalog';
import { ProviderDescriptor, ProviderPermissions } from './providers/sdk';
import { HistoryView, HistoryFinding } from './methods/history';
import { AutomationView } from './methods/automation';
const EXTENSION_VERSION = (require('../package.json') as {version:string}).version;

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
  configureProvider(id: string): void;
  configureMethod(id: string): void;
  toggleMethod(id: string, enabled: boolean): void;
  disconnectMethod(id: string): void;
  openMethodDocs(id: string): void;
  openSettings(): void;
  addProvider():void;
  editProvider(id:string):void;
  removeProvider(id:string):void;
  toggleProvider(id:string,enabled:boolean):void;
  selectProject(root:string):void;
  manageStorage():void;
  sdkMaintenance():void;
  evidenceSettings(id:string):void;
  applyPreset():void;
  importPreset():void;
  exportPreset():void;
  automation(action:'configure'|'start'|'pause'):void;
  historyEnabled(enabled:boolean):void;
  historyRetention():void;
  clearHistory():void;
  compareHistory(id:string):void;
  openHistoryFinding(finding:HistoryFinding):void;
}
export interface ConnectionView {descriptor:ProviderDescriptor;permissions:ProviderPermissions;editable:boolean;status:string;projectRoots?:string[]}

export async function findingFileUri(finding: Finding,projectRoot?:string): Promise<vscode.Uri> {
  const root = projectRoot ? vscode.workspace.workspaceFolders?.find(folder=>path.resolve(folder.uri.fsPath)===path.resolve(projectRoot))?.uri : vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root || !finding.location.path || path.isAbsolute(finding.location.path) || finding.location.path.split(/[\\/]/).includes('..')) throw new Error('Finding has no safe workspace file.');
  const [base,target] = await Promise.all([realpath(root.fsPath),realpath(path.resolve(root.fsPath,finding.location.path))]);
  const relative = path.relative(base,target);
  if (!relative || relative === '..' || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) throw new Error('Finding file is outside the workspace.');
  return vscode.Uri.file(target);
}

export async function openFindingInEditor(finding: Finding,projectRoot?:string): Promise<void> {
  const root = projectRoot ? vscode.workspace.workspaceFolders?.find(folder=>path.resolve(folder.uri.fsPath)===path.resolve(projectRoot))?.uri : vscode.workspace.workspaceFolders?.[0]?.uri;
  if (!root) {
    void vscode.window.showWarningMessage('Open a workspace folder first.');
    return;
  }
  const document = await vscode.workspace.openTextDocument(await findingFileUri(finding,root.fsPath));
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
 * The WrapperGuard workspace: compact navigation, aligned findings table,
 * docked evidence inspector, execution graph and explicit provider permissions.
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
    exportSarif: () => undefined,
    configureProvider: () => undefined,
    configureMethod: () => undefined,
    toggleMethod: () => undefined,
    disconnectMethod: () => undefined,
    openMethodDocs: () => undefined,
    openSettings: () => undefined,
    addProvider:()=>undefined,editProvider:()=>undefined,removeProvider:()=>undefined,toggleProvider:()=>undefined,selectProject:()=>undefined,manageStorage:()=>undefined,sdkMaintenance:()=>undefined,evidenceSettings:()=>undefined,applyPreset:()=>undefined,importPreset:()=>undefined,exportPreset:()=>undefined,automation:()=>undefined,
    historyEnabled:()=>undefined,historyRetention:()=>undefined,clearHistory:()=>undefined,compareHistory:()=>undefined,openHistoryFinding:()=>undefined
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
    webviewView.webview.onDidReceiveMessage((msg: { type: string; finding?: Finding; providerId?: string; methodId?: string; enabled?: boolean;projectRoot?:string;historyId?:string;historyFinding?:HistoryFinding }) => {
      if (!msg || typeof msg.type !== 'string') return;
      // Resolve actions against host state; a webview cannot submit forged paths or fingerprints.
      if (msg.finding) {
        const resolved = this.lastState.kind === 'scanned' ? this.lastState.report.findings.find(f => f.fingerprint === msg.finding?.fingerprint) : undefined;
        if (!resolved) return;
        msg.finding = resolved;
      }
      switch (msg.type) {
        case 'ready':this.setState(this.lastState);break;
        case 'add-provider':this.handlers.addProvider();break;
        case 'edit-provider':if(this.platform.connections.some(item=>item.editable&&item.descriptor.id===msg.providerId))this.handlers.editProvider(msg.providerId!);break;
        case 'remove-provider':if(this.platform.connections.some(item=>item.editable&&item.descriptor.id===msg.providerId))this.handlers.removeProvider(msg.providerId!);break;
        case 'toggle-provider':if(typeof msg.enabled==='boolean'&&this.platform.connections.some(item=>item.descriptor.id===msg.providerId))this.handlers.toggleProvider(msg.providerId!,msg.enabled);break;
        case 'select-project':if(this.platform.projects.some(item=>item.root===msg.projectRoot))this.handlers.selectProject(msg.projectRoot!);break;
        case 'manage-storage':this.handlers.manageStorage();break;
        case 'sdk-maintenance':this.handlers.sdkMaintenance();break;
        case 'evidence-settings':if(typeof msg.methodId==='string'&&methodById(msg.methodId))this.handlers.evidenceSettings(msg.methodId);break;
        case 'apply-preset':this.handlers.applyPreset();break;
        case 'import-preset':this.handlers.importPreset();break;
        case 'export-preset':this.handlers.exportPreset();break;
        case 'automation-configure':this.handlers.automation('configure');break;
        case 'automation-start':this.handlers.automation('start');break;
        case 'automation-pause':this.handlers.automation('pause');break;
        case 'history-enabled':if(typeof msg.enabled==='boolean')this.handlers.historyEnabled(msg.enabled);break;
        case 'history-retention':this.handlers.historyRetention();break;
        case 'history-clear':this.handlers.clearHistory();break;
        case 'history-compare':if(this.lastHistory.entries.some(item=>item.id===msg.historyId))this.handlers.compareHistory(msg.historyId!);break;
        case 'history-open':{const requested=msg.historyFinding;const finding=[...(this.lastHistory.comparison?.newlyReported??[]),...(this.lastHistory.comparison?.noLongerReported??[])].find(item=>item.fingerprint===requested?.fingerprint&&item.providerId===requested?.providerId);if(finding)this.handlers.openHistoryFinding(finding);break;}
        case 'configure-method':
          if (typeof msg.methodId === 'string' && methodById(msg.methodId)) this.handlers.configureMethod(msg.methodId);
          break;
        case 'toggle-method':
          if (typeof msg.methodId === 'string' && methodById(msg.methodId) && typeof msg.enabled === 'boolean') this.handlers.toggleMethod(msg.methodId,msg.enabled);
          break;
        case 'method-docs':
          if (typeof msg.methodId === 'string' && methodById(msg.methodId)) this.handlers.openMethodDocs(msg.methodId);
          break;
        case 'disconnect-method':
          if (typeof msg.methodId === 'string' && methodById(msg.methodId)) this.handlers.disconnectMethod(msg.methodId);
          break;
        case 'configure-provider':
          if (typeof msg.providerId === 'string') this.handlers.configureProvider(msg.providerId);
          break;
        case 'settings':
          this.handlers.openSettings();
          break;
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
      ? { kind: s.kind, report: s.report, settings: this.lastSettings, methods: this.lastMethods,history:this.lastHistory,automation:this.lastAutomation,...this.platform }
      : { kind: s.kind, error: s.kind === 'failed' ? s.error : undefined, settings: this.lastSettings, methods: this.lastMethods,history:this.lastHistory,automation:this.lastAutomation,...this.platform };
    this.view?.webview.postMessage({ type: 'state', state: payload });
  }

  private lastSettings?: ScanSettings;
  private lastMethods: readonly MethodView[] = [];
  private platform:{connections:ConnectionView[];projects:Array<{root:string;name:string}>;selectedProject?:string}={connections:[],projects:[]};
  setPlatform(platform:typeof this.platform):void{this.platform=platform;}
  private lastHistory:HistoryView={enabled:false,limit:20,entries:[]};
  setHistory(history:HistoryView):void{this.lastHistory=history;}
  private lastAutomation?:AutomationView;
  setAutomation(view:AutomationView):void{this.lastAutomation=view;}

  setMethods(methods: readonly MethodView[]): void { this.lastMethods = methods; }

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
/* A quiet, dense developer workspace. Tables and evidence carry the hierarchy. */
:root{--bg:#13121b;--surface:#1f1f28;--surface-low:#1b1b24;--surface-lowest:#101018;--surface-high:#2a2933;--surface-highest:#35343e;--border:#353440;--border-strong:#656473;--text:#e4e1ee;--text-dim:#a8a6b9;--primary:#c3c0ff;--primary-container:#4f46e5;--on-primary-container:#fff;--error:#ffb4ab;--warn:#ffb695;--ok:#8fd7a6;--info:#a9bad3;--mono-bg:#14131b;--mono-bar:#22212b;--tok-kw:#8ab4f8;--tok-str:#e8be95;--tok-com:#8caa88;--tok-op:#d7c8f4;--wg-border:var(--border);--wg-border-strong:#9491ac;--wg-error:var(--error);--wg-text-dim:var(--text-dim);--font:var(--vscode-font-family,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif);--code:var(--vscode-editor-font-family,'SFMono-Regular',Consolas,monospace)}
body.vscode-light{--bg:#f8f9fc;--surface:#fff;--surface-low:#f0f1f6;--surface-lowest:#e9ebf2;--surface-high:#e8eaf2;--surface-highest:#e1e4ee;--border:#d6d9e3;--border-strong:#969baa;--text:#252737;--text-dim:#60677b;--primary:#4d44c7;--primary-container:#4f46e5;--error:#ae302b;--warn:#9a5c14;--ok:#277344;--info:#38628a;--mono-bg:#f5f6fa;--mono-bar:#edf0f5;--tok-kw:#355aa6;--tok-str:#916028;--tok-com:#597248;--tok-op:#763d92;--wg-border-strong:#737b92}
*{box-sizing:border-box}body{margin:0;color:var(--text);background:var(--bg);font:13px/1.5 var(--font);display:flex;flex-direction:column;height:100vh;overflow:hidden}button,input,select{font:inherit}button{color:inherit;cursor:pointer}button:disabled{opacity:.5;cursor:default}button:focus-visible,input:focus-visible,select:focus-visible,[tabindex]:focus-visible{outline:2px solid var(--primary);outline-offset:2px}.mono{font-family:var(--code)}h1,h2,h3,p{margin:0}h1{font-size:20px;line-height:28px;letter-spacing:-.4px;font-weight:600}h2{font-size:16px;line-height:1.4;font-weight:600}h3{font-size:13px;font-weight:600}.muted{color:var(--text-dim)}.err{color:var(--error)}.warn{color:var(--warn)}.ok{color:var(--ok)}.info{color:var(--info)}
.app-bar{height:46px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 16px;background:var(--surface-low);border-bottom:1px solid var(--border)}.app-title{display:flex;align-items:center;gap:9px;font-weight:600}.app-title .logo{display:flex;width:21px;height:23px;color:var(--primary)}.brand-ver{font-size:10px;font-weight:400;color:var(--text-dim)}.workspace-label{font-size:12px;color:var(--text);font-weight:400;border-left:1px solid var(--border-strong);margin-left:5px;padding-left:14px}.app-actions{display:flex;align-items:center;gap:6px}.icon-btn{width:28px;height:28px;border:1px solid transparent;border-radius:3px;background:transparent;color:var(--text-dim);font-size:18px;display:inline-flex;align-items:center;justify-content:center}.icon-btn:hover{background:var(--surface-high);color:var(--text)}
.workspace-shell{display:flex;flex:1;min-height:0}.sidebar{width:176px;flex:none;background:var(--surface-low);border-right:1px solid var(--border);display:flex;flex-direction:column;padding:16px 8px 10px;gap:18px}.sidebar-label,.section-label{font-size:10px;font-weight:600;letter-spacing:.07em;text-transform:uppercase;color:var(--text-dim)}.sidebar-label{padding:0 8px;margin-bottom:8px}.tabs{display:flex;flex-direction:column;gap:3px}.tab{display:flex;align-items:center;gap:9px;min-height:34px;padding:6px 9px;background:transparent;border:0;border-left:2px solid transparent;border-radius:2px;text-align:left;font-size:12px;color:var(--text-dim);white-space:nowrap}.tab .ticon{width:16px;height:17px;display:flex;align-items:center;justify-content:center}.ticon svg{width:16px;height:16px;stroke:currentColor;fill:none;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.tab:hover{background:var(--surface-high);color:var(--text)}.tab.active{background:color-mix(in srgb,var(--primary) 8%,var(--surface-low));color:var(--primary);border-left-color:var(--primary)}.tcount{margin-left:auto;padding:0 5px;min-width:21px;text-align:center;font:10px/18px var(--code);background:var(--surface-highest);border-radius:3px;color:var(--text-dim)}
.status-card{border-top:1px solid var(--border);padding:14px 0 0}.status-card>.sidebar-label{margin-bottom:9px}.pill{display:inline-flex;align-items:center;gap:5px;padding:3px 7px;font-size:10px;font-weight:500;border:1px solid var(--border);border-radius:3px;color:var(--text-dim)}.status-card>.pill{margin:0 7px}.pill.err,.badge.err{color:var(--error);border-color:color-mix(in srgb,var(--error) 34%,transparent);background:color-mix(in srgb,var(--error) 8%,transparent)}.pill.warn,.badge.warn{color:var(--warn);border-color:color-mix(in srgb,var(--warn) 32%,transparent);background:color-mix(in srgb,var(--warn) 7%,transparent)}.pill.ok,.badge.ok{color:var(--ok);border-color:color-mix(in srgb,var(--ok) 32%,transparent);background:color-mix(in srgb,var(--ok) 7%,transparent)}.sidebar-summary{padding:8px 8px 0}.srow{display:flex;align-items:center;gap:6px;padding:3px 0;font-size:11px;color:var(--text-dim)}.srow .val{margin-left:auto;font:11px var(--code);color:var(--text)}.sic{width:11px;text-align:center}.sidebar-bottom{margin-top:auto;padding:12px 8px 0;border-top:1px solid var(--border);font-size:10px;color:var(--text-dim)}.sidebar-health{display:flex;align-items:center;gap:6px;margin-bottom:7px}.health-dot{width:5px;height:5px;border-radius:50%;background:var(--ok)}.health-dot.warn{background:var(--warn)}.sidebar-bottom .sidebar-label{padding:0;margin-bottom:9px}.provider-mini{display:flex;justify-content:space-between;padding:2px 0}.sidebar-version{font:10px var(--code);margin-top:12px;color:var(--text-dim)}
main{flex:1;min-width:0;min-height:0;padding:18px 20px 16px;overflow:hidden}.tab-panel{display:none;height:100%;min-height:0;overflow:auto}.tab-panel.active{display:block}.page-head{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:14px;min-height:32px}.page-head .eyebrow{display:none}.page-head p{font-size:12px;color:var(--text-dim);margin-top:3px}.page-head .btn-row{margin:0}.page-heading{min-width:0}.section-label{margin:14px 0 7px}.panel-card{background:var(--surface-low);border:1px solid var(--border);border-radius:3px;min-width:0;overflow:hidden}.panel-title{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:10px 12px;border-bottom:1px solid var(--border);font-size:12px;font-weight:600}.panel-title .subtle{font-size:11px;font-weight:400;color:var(--text-dim)}.btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:28px;padding:0 10px;border-radius:3px;font-size:11px;line-height:1.4;font-weight:500;white-space:nowrap;text-decoration:none}.btn-primary{background:var(--primary-container);color:var(--on-primary-container);border:1px solid transparent}.btn-primary:hover:not(:disabled){background:#5e56ee}.btn-ghost{background:transparent;color:var(--text);border:1px solid var(--border-strong)}.btn-ghost:hover:not(:disabled){background:var(--surface-high)}.btn-row{display:flex;gap:7px;flex-wrap:wrap}.link-btn{background:none;border:0;color:var(--primary);font-size:12px;padding:0;text-align:left}.hero{display:flex;align-items:center;justify-content:center;flex-direction:column;gap:12px;height:100%;min-height:250px;text-align:center;color:var(--text-dim)}.hero .big{font-size:19px;font-weight:600;color:var(--text)}.hero>div{max-width:480px}.badge{display:inline-flex;align-items:center;gap:4px;font-size:10px;font-weight:500;line-height:1.5;padding:2px 6px;border-radius:3px;border:1px solid var(--border-strong);color:var(--text-dim);white-space:nowrap}.badge.info{color:var(--info)}.badge.action{font-size:10px;padding:2px 6px;background:var(--surface-high);border-color:var(--border);color:var(--text-dim)}.empty-note{padding:28px 20px;color:var(--text-dim);font-size:12px;text-align:center;line-height:1.8}
.overview-status{display:flex;align-items:center;gap:10px;min-height:42px;padding:0 12px;border:1px solid var(--border);border-left:2px solid var(--error);background:var(--surface-low);border-radius:3px}.overview-status.ok{border-left-color:var(--ok)}.overview-status.warn{border-left-color:var(--warn)}.overview-status strong{font-size:13px;font-weight:600}.overview-status .muted{font-size:12px}.overview-status .link-btn{margin-left:auto;font-size:11px}.status-dot{width:6px;height:6px;border-radius:50%;background:currentColor;flex:none}.metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:0;border:1px solid var(--border);border-radius:3px;margin:12px 0 18px;background:var(--surface-low)}.metric{padding:11px 14px;display:flex;align-items:center;gap:12px;border-right:1px solid var(--border);min-width:0}.metric:last-child{border-right:0}.metric .top{order:2}.metric .label{font-size:11px;color:var(--text-dim)}.metric .value{font:600 21px/28px var(--code);white-space:nowrap}.overview-grid{display:grid;grid-template-columns:minmax(0,1.25fr) minmax(350px,1fr);gap:16px;align-items:start}.attention-table,.health-table{width:100%;border-collapse:collapse;font-size:12px;table-layout:fixed}.attention-table th,.health-table th{font-size:10px;font-weight:500;text-align:left;color:var(--text-dim);padding:7px 12px;background:var(--surface)}.attention-table td{padding:10px 12px;border-top:1px solid var(--border);vertical-align:top}.attention-table th:last-child,.attention-table td:last-child{width:78px;text-align:right}.attention-table .link-btn{font-size:12px;color:var(--text);line-height:1.5}.attention-table .link-btn:hover{color:var(--primary)}.review-path{font:11px/1.6 var(--code);color:var(--text-dim);margin-top:4px;overflow-wrap:anywhere}.health-table th:last-child,.health-table td:last-child{text-align:right;width:100px}.health-table td{padding:9px 12px;border-top:1px solid var(--border);font-size:12px}.health-table .run-meta{font:10px var(--code);color:var(--text-dim);margin-top:3px}.scan-info{margin-top:16px}.scan-meta{display:grid;grid-template-columns:1.35fr 1.1fr .7fr .8fr;margin:0;padding:12px;gap:12px}.scan-meta dt{font-size:10px;color:var(--text-dim);margin-bottom:4px}.scan-meta dd{font-size:11px;margin:0;overflow-wrap:anywhere}.notice{font-size:11px;color:var(--text-dim);padding:9px 11px;border:1px solid var(--border);border-left:2px solid var(--warn);border-radius:3px;line-height:1.6;margin-top:12px;background:var(--surface-low)}.notice strong{color:var(--text);font-weight:500}.notice details{margin-top:5px}.notice summary{cursor:pointer;color:var(--text-dim);font-size:10px}.notice ul{padding-left:17px;margin:7px 0 0}.notice li+li{margin-top:5px}.notice .notice-code{font:10px var(--code);color:var(--text)}.scope-note{font-size:10px;color:var(--text-dim);margin-top:10px;line-height:1.6}
#panel-findings.active{display:flex;flex-direction:column}#panel-findings .page-head{margin-bottom:10px}.findings-toolbar{display:flex;align-items:center;gap:8px;margin-bottom:10px;flex:none}.search-wrap{display:flex;align-items:center;gap:8px;flex:1;background:var(--surface-low);border:1px solid var(--border);border-radius:3px;padding-left:10px;max-width:450px;color:var(--text-dim)}.findings-toolbar input{width:100%;min-width:0;height:30px;background:transparent;border:0;color:var(--text);font-size:12px;padding:0 8px 0 0;outline:none}.search-wrap:focus-within{border-color:var(--primary)}.clear-btn{background:none;border:0;color:var(--text-dim);padding:4px 10px;font-size:14px}.filter-hint{margin-left:auto;color:var(--text-dim);font-size:11px}.findings-layout{flex:1;min-height:0;display:grid;grid-template-rows:minmax(200px,1fr) 268px;border:1px solid var(--border);border-radius:3px;overflow:hidden;background:var(--bg)}.findings-layout>.list{overflow:auto;background:var(--bg)}.findings-layout>.detail{min-height:0;overflow:auto;border-top:1px solid var(--border);background:var(--surface-low)}.finding-columns{display:grid;grid-template-columns:minmax(250px,2fr) minmax(230px,1.65fr) minmax(140px,1fr) 114px;align-items:center;gap:12px}.list-head{height:30px;padding:0 12px;position:sticky;top:0;z-index:1;background:var(--surface);border-bottom:1px solid var(--border);font-size:10px;color:var(--text-dim);letter-spacing:.04em}.finding-row{height:32px;padding:0 12px;border:0;border-bottom:1px solid var(--border);border-left:2px solid transparent;width:100%;text-align:left;background:transparent;color:var(--text)}.finding-row:nth-child(odd){background:color-mix(in srgb,var(--surface) 25%,var(--bg))}.finding-row:hover{background:var(--surface-high)}.finding-row.selected{border-left-color:var(--primary);background:color-mix(in srgb,var(--primary-container) 14%,var(--bg))}.finding-issue{display:flex;align-items:center;gap:8px;min-width:0}.finding-row .ficon{width:14px;flex:none;font-size:14px;line-height:18px}.finding-row .title{font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}.finding-row .where{font:11px var(--code);color:var(--text-dim);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row-provider{font-size:11px;color:var(--text-dim);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.row-decision{display:flex;justify-content:flex-start}.row-decision .badge{font-size:10px;padding:1px 6px}.finding-row.suppressed{color:var(--text-dim)}.finding-row.suppressed .where{opacity:.8}
.detail{min-width:0;overflow-wrap:anywhere}.detail-header{display:flex;align-items:center;gap:10px;height:34px;padding:0 12px;border-bottom:1px solid var(--border);background:var(--surface);font-size:11px;font-weight:600}.detail-header .mono{font-size:10px;color:var(--text-dim);font-weight:400}.detail .back{display:none}.detail-content{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.15fr);height:calc(100% - 34px)}.detail-overview{padding:12px 14px;overflow:auto;border-right:1px solid var(--border)}.detail-evidence{padding:10px 14px;overflow:auto;display:flex;flex-direction:column;min-width:0}.detail h2{font-size:15px;line-height:1.45;letter-spacing:-.15px;margin:0 0 7px}.detail-facts{display:flex;align-items:center;gap:7px;flex-wrap:wrap;font-size:11px;color:var(--text-dim);margin-bottom:7px}.detail-description{font-size:13px;color:var(--text-dim);line-height:1.55;margin:8px 0}.provider-line{font-size:11px;color:var(--text-dim);line-height:1.6}.provider-line strong{font-weight:500;color:var(--text)}.provider-id-inline{font:10px var(--code);margin-left:7px}.detail .section-label{font-size:10px;margin:10px 0 5px}.path{display:flex;align-items:center;flex-wrap:wrap;gap:5px}.path-chip{font:11px/1.65 var(--code);color:var(--text-dim)}.path-chip.path-final{color:var(--text)}.path-arrow{color:var(--text-dim)}.action-chips{display:flex;gap:4px;flex-wrap:wrap}.detail .action-chips{margin-top:8px}.snippet{border:1px solid var(--border);border-radius:3px;overflow:hidden;background:var(--mono-bg);flex:none}.snippet-bar{display:flex;align-items:center;justify-content:space-between;background:var(--mono-bar);border-bottom:1px solid var(--border);padding:5px 8px;gap:8px}.snippet-bar .file{font:10px/1.6 var(--code);color:var(--text-dim);overflow-wrap:anywhere}.copy-btn{background:none;border:0;color:var(--text-dim);font-size:10px;padding:1px 3px;white-space:nowrap}.copy-btn:hover{color:var(--primary)}.snippet-line{display:flex;font:12px/1.65 var(--code);padding:6px 0}.snippet-line .ln{flex:none;width:34px;text-align:right;padding-right:9px;color:var(--text-dim);user-select:none}.snippet-line .code{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;padding-right:9px}.snippet-line.hit{background:color-mix(in srgb,var(--error) 6%,transparent);border-left:2px solid var(--error)}.snippet-note{padding:6px 9px;font-size:10px;color:var(--text-dim);border-top:1px solid var(--border)}.tok-kw{color:var(--tok-kw)}.tok-str{color:var(--tok-str)}.tok-com{color:var(--tok-com)}.tok-op{color:var(--tok-op)}.remediation{padding-top:9px;margin-top:8px;border-top:1px solid var(--border)}.remediation .head{font-size:10px;letter-spacing:.05em;text-transform:uppercase;color:var(--primary);font-weight:600;margin-bottom:3px}.remediation p{font-size:12px;line-height:1.6;color:var(--text-dim)}.detail-actions{display:flex;gap:6px;flex-wrap:wrap;padding-top:10px;margin-top:auto}.detail-actions .btn{font-size:10px;padding:0 9px;min-height:26px}
#panel-graph.active{display:flex;flex-direction:column}.graph-workspace{display:grid;grid-template-columns:minmax(0,1fr) 258px;flex:1;min-height:0;border:1px solid var(--border);border-radius:4px;overflow:hidden}.graph-main{min-width:0;min-height:0;display:flex;flex-direction:column;background:var(--surface-low)}.g-toolbar{display:flex;align-items:center;gap:10px;padding:9px 12px;border-bottom:1px solid var(--border);flex:none}.g-tools{display:flex;border:1px solid var(--border-strong);border-radius:4px;overflow:hidden}.g-btn{width:30px;height:28px;border:0;background:var(--surface);color:var(--text-dim);font-size:16px}.g-btn:hover{background:var(--surface-high);color:var(--primary)}.g-btn+.g-btn{border-left:1px solid var(--border)}.g-find{min-width:0;width:190px;max-width:50%;height:30px;border:1px solid var(--border);border-radius:4px;background:var(--surface);color:var(--text);font:11px var(--code);padding:0 10px}.graph-stat{margin-left:auto;font:10px var(--code);color:var(--text-dim)}.graph-wrap{flex:1;min-height:240px;overflow:auto;position:relative;padding:12px;background-color:var(--surface-low);background-image:radial-gradient(var(--border) .6px,transparent .6px);background-size:20px 20px}.graph{position:relative;margin:auto;transform-origin:top left}.g-svg{display:block;overflow:visible}.gnode-card{position:absolute;width:180px;min-height:62px;display:flex;flex-direction:column;justify-content:center;gap:5px;background:var(--surface);border:1px solid var(--border-strong);border-radius:4px;padding:9px 10px;cursor:pointer;text-align:center;transition:opacity .15s}.gnode-card .g-cat{font-size:9px;text-transform:uppercase;color:var(--text-dim);letter-spacing:.06em}.gnode-card.g-entry .g-cat{color:var(--primary)}.gnode-card .g-name{font:11px/1.5 var(--code);color:var(--text);overflow-wrap:anywhere}.gnode-card.g-sus{border:1px solid var(--error);background:color-mix(in srgb,var(--error) 9%,var(--surface))}.gnode-card.g-sus .g-name{color:var(--error)}.gnode-card .g-badge{font-size:9px;color:var(--error);text-transform:uppercase;letter-spacing:.03em}.gnode-card .g-desc{font-size:9px;line-height:1.5;color:var(--text-dim);padding-top:4px;border-top:1px solid var(--border)}.gnode-card:hover,.gnode-card:focus-visible{border-color:var(--primary)}.g-edge path{stroke:var(--wg-border-strong);fill:none;stroke-width:1.8}.g-edge.err path{stroke:var(--error);stroke-dasharray:5 4}.g-edge.active path{stroke-width:2.6}.g-edge.dim{opacity:.14}.g-elabel{fill:var(--text-dim);paint-order:stroke;stroke:var(--surface-low);stroke-width:4px;stroke-linejoin:round}.g-edge.err .g-elabel{fill:var(--error)}.gnode-card.dim-node{opacity:.18}.gnode-card.g-hit,.gnode-card.g-neighbor{outline:1px solid var(--primary);outline-offset:2px}.graph-inspector{overflow:auto;background:var(--surface-low);border-left:1px solid var(--border);padding:0 12px 14px}.graph-inspector>.section-label{padding:13px 0;margin:0 0 12px;border-bottom:1px solid var(--border)}.path-block{border:1px solid var(--border);border-left:2px solid var(--error);border-radius:3px;background:var(--surface);padding:10px 11px;margin-bottom:10px}.path-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;gap:6px}.path-title{font:11px var(--code);color:var(--error)}.path-step{font:10px/1.8 var(--code);overflow-wrap:anywhere;color:var(--text-dim);padding-left:8px;border-left:1px solid var(--border);margin-left:3px}.path-last{color:var(--error);border-color:var(--error)}.path-rule{font-size:10px;color:var(--text-dim);line-height:1.7;margin-top:10px;padding-top:8px;border-top:1px solid var(--border)}.path-link{display:block;margin-top:10px;color:var(--primary);font-size:10px;background:none;border:0;padding:0}.legend{display:flex;gap:10px;flex-wrap:wrap;padding:10px 12px;border-top:1px solid var(--border);flex:none;font-size:10px;color:var(--text-dim)}.legend-key{display:inline-flex;gap:5px;align-items:center}.legend-dot{width:6px;height:6px;border:1px solid var(--border-strong);background:var(--surface)}.legend-dot.err{border-color:var(--error);background:color-mix(in srgb,var(--error) 20%,transparent)}.detached{display:flex;flex-direction:column;gap:5px}.detached-card{font:9px/1.6 var(--code);overflow-wrap:anywhere;padding:7px 8px;border:1px solid var(--border);border-radius:3px;background:var(--surface);cursor:pointer;text-align:left}.detached-card:hover{border-color:var(--primary)}.graph-note{font-size:10px;color:var(--text-dim);margin-top:10px;flex:none}

.policy-intro{max-width:740px}.policy-summary{display:flex;align-items:center;gap:16px;font-size:11px;color:var(--text-dim);margin:0 0 12px}.policy-summary strong{color:var(--text);font-weight:500}.provider-table{width:100%;border-collapse:collapse;font-size:12px}.provider-table th{text-align:left;color:var(--text-dim);font-size:10px;font-weight:500;letter-spacing:.04em;background:var(--surface);padding:8px 12px;border-bottom:1px solid var(--border)}.provider-table td{padding:11px 12px;border-bottom:1px solid var(--border);vertical-align:middle}.provider-table tr:last-child td{border:0}.provider-table .provider-title{font-size:12px;font-weight:500;white-space:nowrap}.provider-id{font:10px/1.6 var(--code);color:var(--text-dim);margin-top:3px}.provider-table .provider-note{font-size:10px;color:var(--text-dim);line-height:1.5;max-width:220px;margin-top:4px}.provider-table .run-meta{font:10px/1.6 var(--code);color:var(--text-dim);margin-top:3px}.grants-column{width:260px}.grants-title{display:block;margin-bottom:5px}.grant-matrix{display:grid;grid-template-columns:repeat(5,minmax(28px,1fr));gap:5px;text-align:center}.grant-heading{font-size:9px;letter-spacing:0}.grant-cell{display:flex;justify-content:center;align-items:center;height:25px;font-size:12px;color:var(--text-dim)}.grant-cell.granted{color:var(--primary)}.provider-table .btn{font-size:10px;min-height:26px;padding:0 9px}.provider-table .muted-run{opacity:.65}.policy-settings{margin-top:16px}.policy-row{display:grid;grid-template-columns:210px 1fr;gap:16px;padding:9px 12px;font-size:11px;border-bottom:1px solid var(--border)}.policy-row:last-child{border:0}.policy-row .k{color:var(--text-dim)}.policy-row .v{font-family:var(--code);font-size:11px;overflow-wrap:anywhere}.app-footer{height:23px;flex:none;display:flex;align-items:center;justify-content:space-between;padding:0 12px;background:var(--surface-lowest);border-top:1px solid var(--border);font-size:9px;color:var(--text-dim)}.app-footer .chip{margin-left:12px;color:var(--error);padding-left:10px;border-left:1px solid var(--border)}.footer-links{display:flex;gap:14px}.spin{display:inline-block;animation:wgspin 1s linear infinite}@keyframes wgspin{to{transform:rotate(360deg)}}
@media(max-width:1199px){.sidebar{width:160px}.tab{font-size:11px;gap:7px;padding-left:7px;padding-right:7px}main{padding:16px}.overview-grid{grid-template-columns:1fr 340px;gap:12px}.metric{padding:10px;gap:9px}.metric .label{font-size:10px}.metric .value{font-size:19px}.finding-columns{grid-template-columns:minmax(220px,2fr) minmax(150px,1.4fr) minmax(120px,1fr) 100px;gap:9px}.finding-row .where{font-size:10px}.finding-row .title{font-size:11px}.row-provider{font-size:10px}.findings-layout{grid-template-rows:minmax(200px,1fr) 288px}.provider-table th,.provider-table td{padding-left:9px;padding-right:9px}.provider-table .provider-id{font-size:9px}.grants-column{width:215px}.provider-table .provider-title{white-space:normal}.scan-meta{grid-template-columns:1fr 1fr}}
@media(max-width:999px){.sidebar{width:150px}.workspace-label{max-width:200px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.overview-grid{grid-template-columns:1fr}.metric{flex-direction:column;gap:0;align-items:flex-start;padding:9px 12px}.metric .top{order:0}.metric .value{font-size:20px}.graph-workspace{grid-template-columns:1fr}.graph-inspector{display:none}.finding-columns{grid-template-columns:minmax(200px,2fr) minmax(140px,1.3fr) 100px;gap:9px}.finding-columns>.row-provider,.list-head>.provider-heading{display:none}.findings-layout{grid-template-rows:minmax(180px,1fr) 310px}.detail-content{grid-template-columns:1fr 1fr}.detail-overview,.detail-evidence{padding:10px}.detail h2{font-size:14px}.provider-id-inline{display:none}.provider-table th:nth-child(3),.provider-table td:nth-child(3){display:none}.grants-column{width:195px}.policy-row{grid-template-columns:180px 1fr}.brand-ver{display:none}.filter-hint{display:none}}
@media(max-width:767px){.app-bar{height:44px;padding:0 12px}.app-title{font-size:12px}.app-title .logo{width:18px;height:20px}.workspace-label{font-size:11px;max-width:130px;margin-left:0;padding-left:10px}.app-actions{gap:2px}.app-actions .btn{font-size:10px;min-height:27px;padding:0 8px}.app-actions .icon-btn{width:26px;font-size:16px}.workspace-shell{flex-direction:column}.sidebar{width:auto;padding:0;border-right:0;border-bottom:1px solid var(--border);gap:0;flex:none}.sidebar>.sidebar-label,.status-card,.sidebar-bottom{display:none}.tabs{flex-direction:row;gap:0}.tab{justify-content:center;flex:1;border-left:0;border-bottom:2px solid transparent;border-radius:0;padding:9px 3px;font-size:10px;gap:5px}.tab.active{border-bottom-color:var(--primary);border-left-color:transparent}.tab .ticon{display:none}.tcount{margin-left:0;min-width:17px;font-size:9px;padding:0 4px}.tab:nth-child(3){font-size:0}.tab:nth-child(3):after{content:'Graph';font-size:10px}main{padding:14px 12px}.page-head{gap:8px;margin-bottom:12px}h1{font-size:19px}.page-head p{font-size:11px}.page-head .btn-row{gap:5px}.page-head .btn-row .btn{font-size:9px;min-height:27px;padding:0 7px}.metrics{grid-template-columns:repeat(2,minmax(0,1fr));margin:10px 0 14px}.metric{flex-direction:row;gap:9px;padding:9px 12px;align-items:center}.metric .top{order:2}.metric:nth-child(2){border-right:0}.metric:nth-child(-n+2){border-bottom:1px solid var(--border)}.metric .value{font-size:19px}.overview-status{padding:8px 10px;align-items:flex-start;flex-wrap:wrap;gap:7px;min-height:38px}.overview-status .status-dot{margin-top:6px}.overview-status .muted{font-size:11px}.overview-status .link-btn{display:none}.overview-grid{gap:12px}.attention-table td{padding:9px 10px}.attention-table th{padding:7px 10px}.attention-table .link-btn{font-size:11px}.review-path{font-size:10px}.health-table td{font-size:11px}.findings-layout{display:block;overflow:auto}.findings-layout>.list{height:100%;overflow:auto}.finding-columns{display:flex;align-items:flex-start;flex-wrap:wrap;gap:3px 8px}.list-head{display:none}.finding-row{height:auto;min-height:67px;padding:9px 10px;position:relative}.finding-issue{width:100%;padding-right:76px;gap:7px}.finding-row .title{font-size:12px;white-space:normal;line-height:1.5}.finding-row .where{font-size:10px;width:100%;padding-left:21px;margin-top:1px}.finding-row>.row-provider{display:block;font-size:10px;padding-left:21px}.finding-row>.row-decision{position:absolute;right:9px;top:10px;max-width:80px}.row-decision .badge{font-size:9px;padding:1px 4px}.findings-layout>.detail{display:none;border-top:0}.findings-layout.narrow>.detail{display:block;height:100%;overflow-y:auto;padding-bottom:16px}.detail-header{display:none}.detail .back{display:flex;width:max-content;margin-bottom:12px}.detail-content{display:block;height:auto}.detail-overview{border-right:0;padding:14px 14px 4px}.detail-evidence{padding:4px 14px 14px}.detail h2{font-size:17px;margin-top:0}.detail-facts{font-size:11px}.detail-description{font-size:13px;line-height:1.65}.provider-line{font-size:12px}.detail .section-label{font-size:10px;margin:13px 0 6px}.detail .action-chips{margin-top:10px}.detail-actions{margin-top:12px}.remediation p{font-size:12px}.detail-actions .btn{font-size:10px}.snippet-bar .file{font-size:10px}.snippet-line{font-size:12px}.provider-table{display:block}.provider-table thead{display:none}.provider-table tbody{display:block}.provider-table tr{display:grid;grid-template-columns:minmax(0,1fr) auto;border-bottom:1px solid var(--border);padding:12px;gap:9px 12px}.provider-table tr:last-child{border:0}.provider-table td{border:0;padding:0}.provider-table td:first-child{grid-column:1}.provider-table td:nth-child(2){grid-column:2;text-align:right}.provider-table td:nth-child(3){display:none}.provider-table td:nth-child(4){grid-column:1;grid-row:2}.provider-table td:last-child{grid-column:2;grid-row:2;align-self:end}.provider-table .provider-title{font-size:12px}.provider-id{font-size:9px}.grants-column{width:auto}.grant-matrix{min-width:165px}.grant-cell{font-size:10px;gap:4px;flex-direction:column;height:auto;min-height:35px;line-height:1.2;padding:3px 1px}.grant-cell:before{content:attr(data-label);font-size:8px;color:var(--text-dim)}.provider-table .provider-note{max-width:170px}.policy-summary{gap:9px;flex-wrap:wrap;font-size:10px}.policy-row{grid-template-columns:1fr;gap:4px;padding:9px 12px}.graph-workspace{display:flex;flex-direction:column;min-height:380px}.g-toolbar{padding:9px;gap:8px}.graph-stat{display:none}.g-find{max-width:none;flex:1;width:auto}.graph-note{font-size:9px}.app-footer{padding:0 10px;font-size:8px}.footer-links{display:none}.app-footer .chip{margin-left:7px;padding-left:7px}.scope-note{font-size:9px}.policy-settings-button{font-size:9px!important;padding:0 8px}.scan-meta{grid-template-columns:1fr 1fr}}
@media(max-width:399px){.workspace-label{display:none}.app-actions .icon-btn{width:24px}.app-actions .btn{padding:0 7px}.tab{font-size:9px}.tab:nth-child(3):after{font-size:9px}.finding-row .title{font-size:11px}}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}

.methods-intro{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:18px;align-items:start;padding:18px 20px;background:var(--surface-low);border:1px solid var(--border);border-top:2px solid var(--primary);border-radius:3px;margin-bottom:20px}.methods-intro p{max-width:720px;color:var(--text-dim);font-size:12px;line-height:1.7;margin-top:7px}.method-summary{font:11px var(--code);color:var(--primary);white-space:nowrap;padding-top:4px}.method-category{margin:22px 0 9px;display:flex;align-items:center;gap:10px}.method-category h2{font-size:13px}.method-category p{color:var(--text-dim);font-size:11px}.method-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.method-card{background:var(--surface-low);border:1px solid var(--border);border-radius:4px;padding:15px 16px;display:flex;flex-direction:column;gap:11px;min-width:0}.method-card.enabled{border-color:color-mix(in srgb,var(--primary) 30%,var(--border))}.method-card-top{display:flex;align-items:flex-start;gap:12px;justify-content:space-between}.method-card h3{font-size:13px;line-height:1.5;margin:0 0 3px}.method-card p{font-size:11px;color:var(--text-dim);line-height:1.75;max-width:680px}.method-tags{display:flex;gap:6px;flex-wrap:wrap}.method-tags .badge{font-size:9px}.method-actions{display:flex;flex-wrap:wrap;align-items:center;gap:9px;margin-top:auto;padding-top:11px;border-top:1px solid var(--border)}.method-actions .method-state{font-size:10px;color:var(--text-dim);margin-right:auto}.method-actions .method-state.ready{color:var(--ok)}.method-switch{display:inline-flex;align-items:center;gap:8px;flex:none;font-size:10px;color:var(--text-dim);cursor:pointer}.method-switch input{appearance:none;width:30px;height:18px;margin:0;background:var(--surface-highest);border:1px solid var(--border-strong);border-radius:20px;position:relative;cursor:pointer}.method-switch input:before{content:'';position:absolute;top:3px;left:3px;width:10px;height:10px;background:var(--text-dim);border-radius:50%;transition:transform .14s}.method-switch input:checked{background:var(--primary-container);border-color:var(--primary-container)}.method-switch input:checked:before{transform:translateX(12px);background:white}.method-switch input:disabled{opacity:.5;cursor:wait}.method-note{font-size:10px;color:var(--text-dim);line-height:1.8;margin:16px 0 8px;padding-left:11px;border-left:2px solid var(--border-strong)}.research-flow{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1px;background:var(--border);border:1px solid var(--border);border-radius:3px;overflow:hidden;margin:16px 0}.research-flow>div{padding:13px 15px;background:var(--surface-low)}.research-flow strong{font-size:11px;font-weight:500}.research-flow p{font-size:10px;color:var(--text-dim);margin-top:4px;line-height:1.7}.research-step{color:var(--primary);font:10px var(--code);margin-right:8px}.methods-bottom{margin:20px 0;display:flex;gap:12px;align-items:center;color:var(--text-dim);font-size:11px}.methods-bottom .link-btn{font-size:11px}
.history-finding-row{grid-template-columns:minmax(0,1fr) auto;align-items:start}.history-finding-row>span{min-width:0}@media(max-width:767px){.history-finding-row{grid-template-columns:1fr}.history-finding-row .link-btn{margin-top:8px}}
@media(max-width:1100px){.method-grid{grid-template-columns:1fr}.methods-intro{grid-template-columns:1fr}.method-summary{padding-top:0}.research-flow{grid-template-columns:1fr}}
@media(max-width:767px){.tabs{overflow-x:auto}.tab{flex:0 0 auto;padding:9px 12px}.methods-intro{padding:14px}.method-card{padding:13px}.method-actions .method-state{flex:0 0 100%;margin-right:0}.method-category{flex-wrap:wrap;gap:3px}.method-category p{width:100%}}

</style>
</head>
<body>
  <header class="app-bar">
    <div class="app-title"><span class="logo"><svg viewBox="0 0 24 26" fill="none" aria-hidden="true"><path d="M12 2 21 6v7c0 5-5 9-9 11-4-2-9-6-9-11V6l9-4Z" stroke="currentColor" stroke-width="1.7"/><path d="m8 12 3 3 5-6" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg></span><span>WrapperGuard</span><span class="brand-ver mono">v${EXTENSION_VERSION}</span><span class="workspace-label" id="workspaceName">Workspace security</span><select id="projectSelector" aria-label="Workspace project" style="max-width:180px;min-width:70px;background:var(--surface-low);color:var(--text);border:1px solid var(--border-strong);border-radius:3px;padding:4px"></select></div>
    <div class="app-actions">
      <button class="icon-btn" id="exportBtn" title="Export report (JSON)" aria-label="Export report">↓</button>
      <button class="icon-btn" id="settingsBtn" title="Show policy" aria-label="Show policy">⚙</button>
      <button class="btn btn-primary" id="rescanBtn" title="Rescan project">↻ Rescan</button>
    </div>
  </header>
  <div class="workspace-shell">
    <aside class="sidebar" aria-label="WrapperGuard navigation and status">
      <div class="sidebar-label">Workspace</div>
      <nav class="tabs" id="tabs" aria-label="Workspace views">
        <button class="tab active" data-tab="overview" aria-current="page"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 3h5v5H3zM12 3h5v5h-5zM3 12h5v5H3zM12 12h5v5h-5z"/></svg></span>Overview</button>
        <button class="tab" data-tab="findings"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m10 2 7 3v5c0 4-4 7-7 8-3-1-7-4-7-8V5zM10 6v5M10 14h.01"/></svg></span>Findings <span class="tcount" id="tabFindingsCount">0</span></button>
        <button class="tab" data-tab="graph"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="7" y="2" width="6" height="4" rx="1"/><rect x="2" y="14" width="6" height="4" rx="1"/><rect x="12" y="14" width="6" height="4" rx="1"/><path d="M10 6v4M5 14v-4h10v4"/></svg></span>Execution graph</button>
        <button class="tab" data-tab="policy"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m10 2 7 3v5c0 4-4 7-7 8-3-1-7-4-7-8V5z"/><path d="m7 10 2 2 4-4"/></svg></span>Providers</button>
        <button class="tab" data-tab="tools"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="m12 3 5 5-3 3-5-5zM8 9l-5 5v3h3l5-5M12 3l2-1 4 4-1 2"/></svg></span>Tools</button>
        <button class="tab" data-tab="research"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 2h6M8 2v6l-5 8c-1 2 1 2 2 2h10c1 0 3 0 2-2l-5-8V2M6 12h8"/></svg></span>Research &amp; AI</button>
        <button class="tab" data-tab="history"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 5v4h4M3 9a7 7 0 1 1 2 6M10 5v5l3 2"/></svg></span>History</button>
        <button class="tab" data-tab="automation"><span class="ticon"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3a7 7 0 1 1-6 3M3 3v4h4M10 6v4l3 2"/></svg></span>Automation</button>
      </nav>
      <section class="status-card" aria-label="Current scan summary">
        <div class="sidebar-label">Report status</div><span class="pill" id="verdictPill">Not scanned</span>
        <div class="sidebar-summary">
          <div class="srow"><span class="sic err">×</span><span>Blocking</span><span class="val" id="sumBlockers">0</span></div>
          <div class="srow"><span class="sic warn">△</span><span>Warnings</span><span class="val" id="sumWarnings">0</span></div>
          <div class="srow"><span class="sic info">✓</span><span>Baselined</span><span class="val" id="sumSuppressed">0</span></div>
        </div>
      </section>
      <div class="sidebar-bottom"><div class="sidebar-label">Provider health</div><div id="sidebarProviderHealth" aria-live="polite">Run a scan to see coverage.</div></div>
    </aside>
    <main>
      <section class="tab-panel active" id="panel-overview"></section>
      <section class="tab-panel" id="panel-findings">
        <div class="page-head"><div class="page-heading"><h1>Findings</h1></div><span class="badge" id="findingsScope">All findings</span></div>
        <div class="findings-toolbar"><div class="search-wrap"><span aria-hidden="true">⌕</span><input id="filterInput" type="search" aria-label="Search findings" placeholder="Search findings, rules, files or providers…" spellcheck="false"/><button class="clear-btn" id="btnClearFilter" title="Clear filter" aria-label="Clear findings search">×</button></div><span class="filter-hint" id="findingsHint"></span></div>
        <div class="findings-layout" id="findingsLayout"></div>
      </section>
      <section class="tab-panel" id="panel-graph"></section>
      <section class="tab-panel" id="panel-policy"></section>
      <section class="tab-panel" id="panel-tools"></section>
      <section class="tab-panel" id="panel-research"></section>
      <section class="tab-panel" id="panel-history"></section>
      <section class="tab-panel" id="panel-automation"></section>
    </main>
  </div>
  <footer class="app-footer"><span id="footerLeft">WrapperGuard: Not scanned</span><span class="footer-links"><span>Local workspace analysis</span><span>Provider API v1</span></span></footer>

<script nonce="${nonce}">
const vscode = acquireVsCodeApi();
let state = { kind: 'not-scanned' };
let selectedFp = null;
let filterText = '';
let wide = window.matchMedia('(min-width: 768px)').matches;
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
const SEV_ICON = { ERROR: ['×', 'err'], WARN: ['△', 'warn'], INFO: ['i', 'info'] };
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

window.matchMedia('(min-width: 768px)').addEventListener('change', function (e) {
  wide = e.matches;
  renderFindings();
  fitGraph();
});

function report() { return state.kind === 'scanned' ? state.report : null; }

function humanAction(action) {
  return ({WARNING:'Warning',MAJOR_WARNING:'Major warning',ALERT:'Alert',BLOCK_BUILD:'Block build',BLOCK_SYNC:'Block sync'})[action] || action;
}
function actionChips(actions) {
  return (actions || []).map(function(action) { return '<span class="badge action" title="' + esc(action) + '">' + esc(humanAction(action)) + '</span>'; }).join('') || '<span class="muted">No actions granted</span>';
}
function grantMatrix(actions, heading) {
  const labels = [['WARNING','Warn'],['MAJOR_WARNING','Major'],['ALERT','Alert'],['BLOCK_BUILD','Build'],['BLOCK_SYNC','Sync']];
  return '<div class="grant-matrix' + (heading ? ' grant-heading' : '') + '">' + labels.map(function(item) {
    if (heading) return '<span title="' + esc(humanAction(item[0])) + '">' + item[1] + '</span>';
    const granted = (actions || []).indexOf(item[0]) >= 0;
    return '<span data-label="' + item[1] + '" class="grant-cell' + (granted ? ' granted' : '') + '" title="' + esc(item[0] + ': ' + (granted ? 'Granted' : 'Not granted')) + '" aria-label="' + esc(humanAction(item[0]) + ': ' + (granted ? 'Granted' : 'Not granted')) + '">' + (granted ? '✓' : '—') + '</span>';
  }).join('') + '</div>';
}
function providerName(id) {
  const r = report();
  const run = r && (r.providerRuns || []).find(function(item) { return item.descriptor.id === id; });
  return run ? run.descriptor.displayName : id;
}
function runStatus(run) {
  const status = run.status;
  const cls = status === 'COMPLETED' ? 'ok' : status === 'DISABLED' ? '' : status === 'FAILED' ? 'err' : 'warn';
  const label = ({COMPLETED:'Completed',TIMED_OUT:'Timed out',FAILED:'Failed',DISABLED:'Disabled',CANCELLED:'Cancelled'})[status] || status;
  return '<span class="badge ' + cls + '" title="' + esc(status) + '">' + esc(label) + '</span>';
}
function renderPolicy() {
  const r = report();
  const panel = byId('panel-policy');
  const runs = r ? (r.providerRuns || []) : [];
  let html = '<div class="page-head"><div class="page-heading"><div class="eyebrow">Permission boundaries</div><h1>Policy &amp; providers</h1><p class="policy-intro">Independent vetting. Shared actions. Explicit permissions.</p></div><div class="btn-row"><button class="btn btn-ghost" id="sdkMaintenance">SDK maintenance</button><button class="btn btn-ghost" id="manageStorage">Storage</button><button class="btn btn-primary" id="addSdk">Add SDK</button><button class="btn btn-ghost policy-settings-button" id="providerSettings">Settings</button></div></div>';
  const enabled = runs.filter(function(run) { return run.permissions.enabled; }).length;
  const completed = runs.filter(function(run) { return run.status === 'COMPLETED'; }).length;
  html += '<div class="policy-summary"><span><strong>' + enabled + '</strong> enabled providers</span><span><strong>' + completed + '</strong> completed</span><span>Grants are owned by your User Settings</span></div>';
  if (!runs.length) html += '<div class="empty-note">Run a scan to see registered providers and their permissions.</div>';
  else {
    html += '<div class="panel-card"><table class="provider-table" aria-label="Vetting providers and action grants"><thead><tr><th>Provider</th><th>Latest run</th><th>Execution</th><th class="grants-column"><span class="grants-title">Granted actions</span>' + grantMatrix([],true) + '</th><th></th></tr></thead><tbody>';
    runs.forEach(function(run,index) {
      html += '<tr' + (!run.permissions.enabled ? ' class="muted-run"' : '') + '><td><div class="provider-title">' + esc(run.descriptor.displayName) + '</div><div class="provider-id">' + esc(run.descriptor.id) + ' · v' + esc(run.descriptor.version) + ' · API ' + run.descriptor.apiVersion + '</div></td>';
      html += '<td>' + runStatus(run) + '<div class="run-meta">' + run.durationMs + ' ms · ' + run.findingCount + ' findings</div>' + (run.message ? '<div class="provider-note">' + esc(run.message) + '</div>' : '') + '</td>';
      html += '<td><span>' + (run.permissions.required ? 'Required' : 'Optional') + '</span><div class="run-meta">' + (run.permissions.enabled ? 'Enabled' : 'Disabled') + ' · ' + run.permissions.timeoutMs / 1000 + 's timeout</div></td>';
      html += '<td class="grants-column">' + grantMatrix(run.permissions.actions,false) + '</td>';
      html += '<td><button class="btn btn-ghost provider-permissions" data-provider-index="' + index + '" title="Configure permissions" aria-label="Configure permissions for ' + esc(run.descriptor.displayName) + '">Configure</button></td></tr>';
    });
    html += '</tbody></table></div>';
  }
  html += '<div class="notice"><strong>How enforcement works</strong> · Only granted actions affect the result. Build and sync gates apply when a cooperating extension calls the API. Arbitrary VS Code tasks are outside this gate.</div>';
  const connections=(state.connections || []).filter(function(item) { return item.descriptor.id!=='wrapperguard' && !(state.methods || []).some(function(method) { return method.providerId===item.descriptor.id; }); });
  html += '<div class="method-category"><h2>Your SDK connections</h2><p>Any Provider API v1 implementation · your executable, inputs and grants</p></div><div class="method-grid">';
  connections.forEach(function(item,index) {
    const d=item.descriptor, scope=d.inputScope;
    html += '<article class="method-card' + (item.permissions.enabled ? ' enabled' : '') + '"><div class="method-card-top"><div><h3>' + esc(d.displayName) + '</h3><div class="method-tags"><span class="badge">' + esc(d.category || 'Uncategorized') + '</span>' + (d.tags || []).map(function(tag) { return '<span class="badge">'+esc(tag)+'</span>'; }).join('') + '</div></div><label class="method-switch"><span>' + (item.permissions.enabled?'On':'Off') + '</span><input class="custom-toggle" data-connection="' + index + '" type="checkbox" aria-label="Enable ' + esc(d.displayName) + '"' + (item.permissions.enabled?' checked':'') + (state.kind==='scanning'?' disabled':'') + '/></label></div><p>' + esc(d.summary || 'Independent SDK connection. Permissions are controlled here.') + '</p><p class="review-path">' + esc(d.id) + ' · v' + esc(d.version) + '</p><p class="review-path">' + esc(item.projectRoots && item.projectRoots.length ? 'Bound to: '+item.projectRoots.join(', ') : 'Applies to all selected projects') + '</p><p>' + (scope ? esc((scope.includes || []).length + ' project include patterns · ' + (scope.excludes || []).length + ' exclusions · ' + (scope.externalFiles || []).length + ' external inputs') : 'Input scope unknown · freshness covers host-known inputs only') + '</p><div class="method-tags">' + actionChips(item.permissions.actions) + '</div><div class="method-actions"><span class="method-state">' + esc(item.status) + '</span><button class="link-btn custom-permissions" data-connection="'+index+'">Permissions</button>' + (item.editable?'<button class="link-btn custom-edit" data-connection="'+index+'">Edit</button><button class="link-btn custom-remove" data-connection="'+index+'">Remove</button>':'<span class="badge">Extension SDK</span>') + '</div></article>';
  });
  html += '</div>' + (!connections.length?'<div class="empty-note">Connect a custom SDK, Python adapter or command provider. Add SDK guides you through literal arguments, category, read set and permissions.</div>':'') + '<p class="method-note">Declared inputs support content freshness checks. A trusted process can access other files; these declarations do not sandbox it. Method presets never grant a custom SDK permission.</p>';
  const settings = state.settings;
  if (settings) {
    html += '<div class="policy-settings"><div class="section-label">Built-in scanner policy</div><div class="panel-card">';
    html += '<div class="policy-row"><span class="k">Allowed distribution hosts</span><span class="v">' + esc(settings.allowedHosts.join(', ') || 'None configured') + '</span></div>';
    html += '<div class="policy-row"><span class="k">Blocked commands</span><span class="v">' + esc(settings.blockedCommands.join(', ') || 'None configured') + '</span></div>';
    html += '<div class="policy-row"><span class="k">Distribution SHA-256</span><span class="v">' + (settings.requireDistributionSha256 ? 'Required' : 'Optional') + '</span></div></div></div>';
  }
  panel.innerHTML = html;
  byId('addSdk').addEventListener('click',function() { post('add-provider'); });
  byId('manageStorage').addEventListener('click',function() { post('manage-storage'); });
  byId('sdkMaintenance').addEventListener('click',function() { post('sdk-maintenance'); });
  ['edit','remove','permissions','toggle'].forEach(function(action) { panel.querySelectorAll('.custom-'+action).forEach(function(button) { button.addEventListener(action==='toggle'?'change':'click',function() {const item=connections[Number(button.getAttribute('data-connection'))];if(item)vscode.postMessage({type:action==='permissions'?'configure-provider':action+'-provider',providerId:item.descriptor.id,enabled:action==='toggle'?button.checked:undefined});});});});
  byId('providerSettings').addEventListener('click',function () { post('settings'); });
  panel.querySelectorAll('.provider-permissions').forEach(function(button) {
    button.addEventListener('click',function () {
      const run = runs[Number(button.getAttribute('data-provider-index'))];
      if (run) vscode.postMessage({type:'configure-provider',providerId:run.descriptor.id});
    });
  });
}


function renderMethods(research) {
  const panel = byId(research ? 'panel-research' : 'panel-tools');
  const active=document.activeElement;
  const focusMethod=active && panel.contains(active) ? active.getAttribute('data-method') : null;
  const focusKind=focusMethod ? ['method-toggle','method-configure','method-permissions','method-disconnect','method-docs'].find(function(kind) { return active.classList.contains(kind); }) : null;
  const all = state.methods || [];
  const methods = all.filter(function(m) { return research ? ['RESEARCH','AI'].indexOf(m.category)>=0 : ['RESEARCH','AI'].indexOf(m.category)<0; });
  const enabled = methods.filter(function(m) { return m.enabled; }).length;
  const disabled=methods.length-enabled;
  let html = '<div class="page-head"><div class="page-heading"><h1>' + (research ? 'Research &amp; AI' : 'Security tools') + '</h1><p>' + (research ? 'Explainable experiments. Your model. Your decision.' : 'Choose the checks that fit your project.') + '</p></div>' + (research?'<span class="badge">User Settings</span>':'<div class="btn-row"><button class="btn btn-ghost apply-preset">Choose preset</button><button class="btn btn-ghost import-preset">Import preset</button><button class="btn btn-ghost export-preset">Export preset</button></div>') + '</div>';
  html += '<div class="methods-intro"><div><h2>' + (research ? 'Better evidence, with clear limits' : 'One workspace. Independent methods.') + '</h2><p>' + (research ? 'Research ideas become separate, removable providers. The semantic method decodes literal script data and highlights risky behavior without executing it. External AI needs a model provider you connect; no trained model is bundled.' : 'Start with local checks for Android, Gradle and Maven projects. Add language scanners, dependency checks or a web security report when you need them. Each connection keeps its own permissions and can be turned off at any time.') + '</p></div><div class="method-summary">' + enabled + ' enabled · ' + disabled + ' disabled</div></div>';
  if (research) html += '<div class="research-flow"><div><strong><span class="research-step">01</span>Read source safely</strong><p>Literal data only. Bound the file, decode and output budgets.</p></div><div><strong><span class="research-step">02</span>Inspect behavior</strong><p>Expose encoding layers and execution patterns as reviewable evidence.</p></div><div><strong><span class="research-step">03</span>Keep authority explicit</strong><p>Research starts with warnings. Model output never grants itself build permissions.</p></div></div>';
  const groups = [['BUILT_IN','Built-in checks','Local deterministic rules · no SDK setup'],['SAST','Source analysis','Scripts, Python and scanner reports'],['SUPPLY_CHAIN','Supply chain','Dependency findings for Gradle, Maven and other ecosystems'],['DAST','Web & API security','Explicit target scope for imported results'],['RESEARCH','Research methods','Experimental, separate from the default scanner'],['AI','AI & models','Bring a provider and validate it against your own data']];
  groups.forEach(function(group) {
    const entries = methods.filter(function(m) { return m.category===group[0]; });
    if (!entries.length) return;
    html += '<div class="method-category"><h2>' + esc(group[1]) + '</h2><p>' + esc(group[2]) + '</p></div><div class="method-grid">';
    entries.forEach(function(m) {
      const builtin=m.category==='BUILT_IN';
      const status=m.status || (builtin ? 'Ready locally' : m.configured ? 'Connected' : 'Setup needed');
      const mode=builtin ? 'Built-in' : m.availability==='REPORT_IMPORT' ? 'Report import' : m.availability==='EXTERNAL_MODEL' ? 'External model' : 'Optional provider';
      html += '<article class="method-card' + (m.enabled ? ' enabled' : '') + '"><div class="method-card-top"><div><h3>' + esc(m.title) + '</h3><div class="method-tags"><span class="badge">' + esc(mode) + '</span>' + (m.maturity==='EXPERIMENTAL' ? '<span class="badge warn">Experimental</span>' : '') + '</div></div><label class="method-switch"><span>' + (m.enabled ? 'On' : 'Off') + '</span><input class="method-toggle" data-method="' + esc(m.id) + '" type="checkbox" aria-label="Enable ' + esc(m.title) + '"' + (m.enabled ? ' checked' : '') + (state.kind==='scanning' ? ' disabled' : '') + '/></label></div><p>' + esc(m.summary) + '</p><div class="method-actions"><span class="method-state' + (m.enabled && m.configured ? ' ready' : '') + '">' + esc(status) + '</span>';
      if (m.docsUrl) html += '<button class="link-btn method-docs" data-method="' + esc(m.id) + '">Reference</button>';
      if (!builtin && m.configured && (m.availability==='REPORT_IMPORT' || m.category==='SUPPLY_CHAIN' || m.format==='sarif')) html += '<button class="link-btn method-evidence" data-method="'+esc(m.id)+'">Evidence settings</button>';
      if (!builtin && m.configured) html += '<button class="link-btn method-permissions" data-method="' + esc(m.id) + '">Permissions</button><button class="link-btn method-disconnect" data-method="' + esc(m.id) + '">Disconnect</button>';
      if (!builtin) html += '<button class="btn btn-ghost method-configure" data-method="' + esc(m.id) + '"' + (state.kind==='scanning' ? ' disabled' : '') + '>' + (m.configured ? 'Configure' : 'Set up') + '</button>';
      html += '</div></article>';
    });
    html += '</div>';
  });
  if (!methods.length) html += '<div class="empty-note">Loading the security method catalog…</div>';
  html += '<p class="method-note">' + (research ? 'Experimental findings are signals to review. This is not the published paper implementation or a validated malware classifier. No project code or model is downloaded automatically.' : 'A clean result applies to the enabled methods and the evidence they checked. Disabled checks reduce coverage. Imported reports may be outdated; connecting a report does not run its scanner.') + '</p><div class="methods-bottom"><button class="link-btn method-settings">Open User Settings</button><span>Tools share findings and actions through Provider API v1.</span></div>';
  panel.innerHTML=html;
  panel.querySelectorAll('.apply-preset').forEach(function(button) { button.addEventListener('click',function() { post('apply-preset'); }); });
  panel.querySelectorAll('.import-preset').forEach(function(button) { button.addEventListener('click',function() { post('import-preset'); }); });
  panel.querySelectorAll('.export-preset').forEach(function(button) { button.addEventListener('click',function() { post('export-preset'); }); });
  panel.querySelectorAll('.method-toggle').forEach(function(input) { input.addEventListener('change',function() { vscode.postMessage({type:'toggle-method',methodId:input.getAttribute('data-method'),enabled:input.checked}); }); });
  panel.querySelectorAll('.method-permissions').forEach(function(button) { button.addEventListener('click',function() { const method=all.find(function(m) { return m.id===button.getAttribute('data-method'); });if (method && method.providerId) vscode.postMessage({type:'configure-provider',providerId:method.providerId}); }); });
  panel.querySelectorAll('.method-disconnect').forEach(function(button) { button.addEventListener('click',function() { vscode.postMessage({type:'disconnect-method',methodId:button.getAttribute('data-method')}); }); });
  panel.querySelectorAll('.method-evidence').forEach(function(button) { button.addEventListener('click',function() { vscode.postMessage({type:'evidence-settings',methodId:button.getAttribute('data-method')}); }); });
  panel.querySelectorAll('.method-configure').forEach(function(button) { button.addEventListener('click',function() { vscode.postMessage({type:'configure-method',methodId:button.getAttribute('data-method')}); }); });
  panel.querySelectorAll('.method-docs').forEach(function(button) { button.addEventListener('click',function() { vscode.postMessage({type:'method-docs',methodId:button.getAttribute('data-method')}); }); });
  panel.querySelectorAll('.method-settings').forEach(function(button) { button.addEventListener('click',function() { post('settings'); }); });
  if (focusMethod && focusKind) { const next=panel.querySelector('.' + focusKind + '[data-method="' + focusMethod + '"]') || panel.querySelector('.method-configure[data-method="' + focusMethod + '"]');if (next) next.focus(); }
}

function renderHistory() {
  const panel=byId('panel-history'),history=state.history || {enabled:false,limit:20,entries:[]},entries=history.entries || [];
  let html='<div class="page-head"><div class="page-heading"><h1>Scan history</h1><p>Local summaries. Comparable scope. Changes to review.</p></div><div class="btn-row"><button class="btn btn-ghost" id="historyRetention">Keep '+history.limit+'</button><button class="btn btn-ghost" id="historyClear">Clear project history</button><button class="btn '+(history.enabled?'btn-ghost':'btn-primary')+'" id="historyToggle">'+(history.enabled?'Pause saving':'Enable history')+'</button></div></div>';
  html+='<div class="methods-intro"><div><h2>'+(history.enabled?'Saving local scan summaries':'History is optional')+'</h2><p>'+(history.enabled?'Each scan saves a small summary for this project: finding identities, titles, file paths and provider versions. Source evidence, finding messages and call chains are omitted.':'Enable history to compare future scans of this project. Summaries stay in extension-owned local storage. Pausing stops new records; saved records remain until you clear them.')+'</p></div><span class="method-summary">'+(history.enabled?'Opted in':'Saving off')+' · '+history.limit+' per project</span></div>';
  if(history.error)html+='<div class="notice"><strong>History unavailable</strong> · '+esc(history.error)+'</div>';
  if(entries.length){
    html+='<div class="panel-card"><table class="provider-table" aria-label="Local scan history"><thead><tr><th>Scan</th><th>Results</th><th>Providers</th><th>Comparison</th></tr></thead><tbody>';
    entries.forEach(function(entry,index) {const limited=!entry.complete||entry.stale||entry.truncated||!entry.scopeKnown;html+='<tr><td><div class="provider-title">'+esc(new Date(entry.createdAt).toLocaleString())+'</div><div class="run-meta">'+esc(entry.operation)+(index===0?' · Latest':'')+'</div></td><td><span class="badge '+(limited?'warn':entry.verdict==='CLEAN'?'ok':'')+'">'+esc((VERDICT[entry.verdict] || ['',entry.verdict])[1])+'</span><div class="run-meta">'+entry.findingCount+' findings'+(entry.truncated?' · Truncated':'')+(entry.stale?' · Stale':'')+'</div></td><td><details><summary>'+entry.providerCount+' providers</summary><div class="run-meta">'+(entry.providers || []).map(function(item) {return esc(item.id)+' · v'+esc(item.version)+' · '+esc(item.status);}).join('<br/>')+'</div></details></td><td>'+(index?'<button class="btn btn-ghost history-compare" data-history="'+esc(entry.id)+'">Compare to latest</button>':'<span class="badge">'+(entry.scopeKnown?'Known scope':'Scope unknown')+'</span>')+'</td></tr>';});
    html+='</tbody></table></div>';
  }else html+='<div class="empty-note">'+(history.enabled?'Run a scan to save the first summary for this project.':'Saving is off. Enable history to view retained records and save future scans.')+'</div>';
  const delta=history.comparison;
  if(delta){
    html+='<div class="method-category"><h2>Reimport &amp; scan comparison</h2><p>'+esc(history.comparisonLabel || '')+'</p></div>';
    if(!delta.available)html+='<div class="notice"><strong>Comparison unavailable</strong> · '+esc(delta.reason || 'The scopes cannot be established as equivalent.')+'</div>';
    else{
      html+='<div class="policy-summary"><span><strong>'+delta.newlyReported.length+'</strong> newly reported</span><span><strong>'+delta.noLongerReported.length+'</strong> no longer reported</span><span><strong>'+delta.unchanged+'</strong> still reported</span></div>';
      [['newlyReported','Newly reported'],['noLongerReported','No longer reported']].forEach(function(group) {const findings=delta[group[0]] || [];if(!findings.length)return;html+='<div class="panel-card" style="margin-top:12px"><div class="panel-title">'+group[1]+'<span class="subtle">'+findings.length+' findings</span></div>';findings.slice(0,200).forEach(function(finding,index) {html+='<div class="policy-row history-finding-row"><span><span class="badge '+(finding.severity==='ERROR'?'err':finding.severity==='WARN'?'warn':'info')+'">'+esc(finding.severity)+'</span> '+esc(finding.title)+'<div class="review-path">'+esc(finding.providerId)+' · '+esc(finding.ruleId)+' · '+esc(finding.path)+'</div></span>'+(finding.path==='.'?'<span>Project-level finding</span>':'<button class="link-btn history-open" data-group="'+group[0]+'" data-index="'+index+'">Open current file</button>')+'</div>';});if(findings.length>200)html+='<p class="method-note">Showing the first 200 findings.</p>';html+='</div>';});
    }
  }
  html+='<p class="method-note">Comparisons require the same project, operation and known configuration, with complete, fresh, untruncated scans. All fingerprints count, including approved findings. “No longer reported” describes the scanner output; it does not prove a vulnerability was fixed. History never changes your baseline approvals or enforcement.</p>';
  panel.innerHTML=html;
  byId('historyToggle').addEventListener('click',function() {vscode.postMessage({type:'history-enabled',enabled:!history.enabled});});
  byId('historyClear').addEventListener('click',function() {post('history-clear');});byId('historyRetention').addEventListener('click',function() {post('history-retention');});
  panel.querySelectorAll('.history-compare').forEach(function(button) {button.addEventListener('click',function() {vscode.postMessage({type:'history-compare',historyId:button.getAttribute('data-history')});});});
  panel.querySelectorAll('.history-open').forEach(function(button) {button.addEventListener('click',function() {const finding=delta && delta[button.getAttribute('data-group')][Number(button.getAttribute('data-index'))];if(finding)vscode.postMessage({type:'history-open',historyFinding:finding});});});
}

function renderAutomation() {
  const view=state.automation || {configured:false,running:false,status:'Paused · configure providers first',providerIds:[],onChanges:true,debounceMs:2000,intervalMinutes:0};
  const panel=byId('panel-automation');
  let html='<div class="page-head"><div class="page-heading"><h1>Automatic scans</h1><p>Reviewed providers, explicit triggers, one project at a time.</p></div><div class="btn-row"><button class="btn btn-ghost" id="automationConfigure">Configure</button><button class="btn '+(view.running?'btn-ghost':'btn-primary')+'" id="automationSession"'+(!view.running&&!view.configured?' disabled':'')+'>'+(view.running?'Pause':'Review &amp; start')+'</button></div></div>';
  html+='<div class="overview-status '+(view.running?'ok':'warn')+'" role="status" aria-live="polite"><strong>'+esc(view.status)+'</strong></div>';
  if(view.error)html+='<div class="empty-note err" role="alert">'+esc(view.error)+'</div>';
  html+='<div class="panel-card" style="margin-top:14px"><div class="panel-title">This editor session<span class="badge">SCAN only</span></div><div class="policy-row"><span>Project</span><span>'+esc(view.project || state.selectedProject || 'Choose an open project')+'</span></div><div class="policy-row"><span>After changes</span><span>'+(view.onChanges?view.debounceMs+' ms debounce':'Off')+'</span></div><div class="policy-row"><span>Interval</span><span>'+(view.intervalMinutes?view.intervalMinutes+' minutes':'Off')+'</span></div><div class="policy-row"><span>Providers</span><span>'+esc(view.providerIds.join(', ') || 'None selected')+'</span></div><div class="policy-row"><span>Last automatic scan</span><span>'+esc(view.lastRun?new Date(view.lastRun).toLocaleString():'Not run')+'</span></div></div>';
  html+='<p class="method-note">Saving preferences, enabling a method or reloading the editor leaves automation paused. Start reviews the selected providers and their network/cost declarations; no immediate or missed scans run. Automatic scans never launch builds, synchronization, downloads or tool installation. Declarations describe your review and do not restrict trusted tool code. Settings, connection and project changes pause the session.</p>';
  panel.innerHTML=html;byId('automationConfigure').addEventListener('click',function(){post('automation-configure');});byId('automationSession').addEventListener('click',function(){post(view.running?'automation-pause':'automation-start');});
}
function render() {
  const selector=byId('projectSelector');
  if(selector){selector.innerHTML=(state.projects || []).map(function(project) { return '<option value="'+esc(project.root)+'"'+(state.selectedProject===project.root?' selected':'')+'>'+esc(project.name)+'</option>'; }).join('');selector.disabled=state.kind==='scanning'||!(state.projects || []).length;}
  renderStatusCard();
  renderOverview();
  renderFindings();
  renderGraph();
  renderPolicy();
  renderMethods(false);
  renderMethods(true);
  renderHistory();
  renderAutomation();
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
  pill.textContent = vm[1];
  const r = report();
  if (r && r.projectPath) byId('workspaceName').textContent = r.policyName ? r.policyName.split(' · ')[0] : r.projectPath.split('/').filter(Boolean).pop();
  const blockers = r ? r.findings.filter(function (f) { return f.policyAction === 'BLOCK' && f.status === 'ACTIVE'; }).length : 0;
  const warnings = r ? r.findings.filter(function (f) { return f.severity === 'WARN' && f.status === 'ACTIVE'; }).length : 0;
  const suppressed = r ? r.findings.filter(function (f) { return f.status === 'SUPPRESSED'; }).length : 0;
  byId('sumBlockers').textContent = String(blockers);
  byId('sumWarnings').textContent = String(warnings);
  byId('sumSuppressed').textContent = String(suppressed);
  byId('tabFindingsCount').textContent = String(r ? r.findings.length : 0);
  const runs = r ? (r.providerRuns || []) : [];
  const enabledRuns = runs.filter(function(run) { return run.permissions.enabled; });
  const healthyRuns = enabledRuns.filter(function(run) { return run.status === 'COMPLETED'; }).length;
  const failedRuns = enabledRuns.length - healthyRuns;
  byId('sidebarProviderHealth').innerHTML = runs.length ? '<div class="sidebar-health"><span class="health-dot' + (failedRuns ? ' warn' : '') + '"></span><span>' + healthyRuns + ' of ' + enabledRuns.length + ' completed</span></div><div class="provider-mini"><span>Need attention</span><span class="mono ' + (failedRuns ? 'warn' : '') + '">' + failedRuns + '</span></div><div class="provider-mini"><span>Disabled</span><span class="mono">' + (runs.length - enabledRuns.length) + '</span></div>' : 'Run a scan to see coverage.';

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
    btn.innerHTML = '<span class="spin">↻</span> Scanning';
  } else {
    btn.disabled = false;
    btn.innerHTML = '↻ Rescan';
  }
}

function renderOverview() {
  const panel = byId('panel-overview');
  if (state.kind === 'not-scanned') {
    panel.innerHTML = '<div class="hero"><div class="big">No scan yet</div><div>Inspect wrappers, build files and bootstrap scripts before they run.</div><button class="btn btn-primary" id="heroScan">↻ Scan workspace</button></div>';
    byId('heroScan').addEventListener('click', function () { post('scan'); });return;
  }
  if (state.kind === 'scanning') {
    panel.innerHTML = '<div class="hero"><div class="big"><span class="spin">↻</span> Scanning workspace</div><div>Reading wrappers, build files and provider results.</div></div>';return;
  }
  if (state.kind === 'failed') {
    panel.innerHTML = '<div class="hero"><div class="big">Scan failed</div><div class="mono">' + esc(state.error || '') + '</div><button class="btn btn-primary" id="retryBtn">↻ Retry scan</button></div>';
    byId('retryBtn').addEventListener('click', function () { post('scan'); });return;
  }
  const r = state.report;
  const active = r.findings.filter(function(f) { return f.status === 'ACTIVE'; });
  const blockers = active.filter(function(f) { return f.policyAction === 'BLOCK'; }).length;
  const warnings = active.filter(function(f) { return f.severity === 'WARN'; }).length;
  const runs = r.providerRuns || [];
  const completed = runs.filter(function(run) { return run.status === 'COMPLETED'; }).length;
  const enabled = runs.filter(function(run) { return run.permissions.enabled; }).length;
  const vm = verdictMeta();
  const cls = blockers > 0 ? 'err' : (!r.complete || r.stale) ? 'warn' : r.verdict === 'CLEAN' ? 'ok' : 'warn';
  let html = '<div class="page-head"><div class="page-heading"><h1>Overview</h1></div><div class="btn-row"><button class="btn btn-ghost" id="ovExport">Export JSON</button><button class="btn btn-ghost" id="ovExportSarif">Export SARIF</button></div></div>';
  html += '<div class="overview-status ' + cls + '"><span class="status-dot"></span><strong>' + esc(vm[1]) + '</strong><span class="muted">' + (blockers ? blockers + ' findings require a decision.' : r.complete ? 'Provider results are ready for review.' : 'Review provider coverage before continuing.') + '</span><button class="link-btn" id="ovReview">Review findings →</button></div>';
  html += '<div class="metrics">';
  html += '<div class="metric"><div class="top"><span class="label">Blocking findings</span></div><span class="value err">' + blockers + '</span></div>';
  html += '<div class="metric"><div class="top"><span class="label">Warnings</span></div><span class="value warn">' + warnings + '</span></div>';
  html += '<div class="metric"><div class="top"><span class="label">Files scanned</span></div><span class="value">' + r.scannedFiles + '</span></div>';
  html += '<div class="metric"><div class="top"><span class="label">Providers completed</span></div><span class="value">' + completed + '<span style="font-size:12px;color:var(--text-dim)"> / ' + enabled + '</span></span></div></div>';
  html += '<div class="overview-grid"><section class="panel-card"><div class="panel-title">Requires attention<span class="subtle">Highest priority first</span></div><table class="attention-table"><thead><tr><th>Finding / location</th><th>Decision</th></tr></thead><tbody>';
  const priority = active.filter(function(f) { return f.policyAction === 'BLOCK' || f.policyAction === 'REVIEW'; }).sort(function(a,b) { return (a.policyAction === 'BLOCK' ? 0 : 1) - (b.policyAction === 'BLOCK' ? 0 : 1); }).slice(0,5);
  if (!priority.length) html += '<tr><td colspan="2"><div class="empty-note">No active findings require a decision.</div></td></tr>';
  priority.forEach(function(f) {
    const blocking = f.policyAction === 'BLOCK';
    html += '<tr><td><button class="link-btn review-finding" data-fp="' + esc(f.fingerprint) + '">' + esc(f.title) + '</button><div class="review-path">' + esc(f.location.path) + ':' + (f.location.line || 1) + '</div></td><td><span class="badge ' + (blocking ? 'err' : 'warn') + '">' + (blocking ? 'Block' : 'Review') + '</span></td></tr>';
  });
  html += '</tbody></table></section>';
  html += '<section class="panel-card"><div class="panel-title">Provider runs<button class="link-btn" id="ovProviders">Configure →</button></div><table class="health-table"><thead><tr><th>Provider / run</th><th>Status</th></tr></thead><tbody>';
  runs.forEach(function(run) { html += '<tr><td>' + esc(run.descriptor.displayName) + '<div class="run-meta">' + run.durationMs + ' ms · ' + run.findingCount + ' findings' + (run.permissions.required ? ' · required' : '') + '</div></td><td>' + runStatus(run) + '</td></tr>'; });
  if (!runs.length) html += '<tr><td colspan="2"><div class="empty-note">No provider run details.</div></td></tr>';
  html += '</tbody></table></section></div>';
  if (!r.complete) {
    const incompleteRuns = runs.filter(function(run) { return run.permissions.enabled && run.status !== 'COMPLETED' && run.status !== 'DISABLED'; });
    const labels = {TIMED_OUT:'timed out',FAILED:'failed',CANCELLED:'cancelled'};
    const summary = incompleteRuns.map(function(run) { return run.descriptor.displayName + ' ' + (labels[run.status] || run.status.toLowerCase()); }).join('; ');
    html += '<div class="notice"><strong>Incomplete coverage</strong> · ' + esc(summary || 'Some providers did not complete') + '.';
    if ((r.notices || []).length) html += '<details><summary>View report notices (' + r.notices.length + ')</summary><ul>' + r.notices.map(function(notice) { return '<li>' + (notice.code ? '<span class="notice-code">' + esc(notice.code) + '</span> · ' : '') + esc(notice.message) + '</li>'; }).join('') + '</ul></details>';
    html += '</div>';
  }
  if (r.stale) html += '<div class="notice"><strong>Report is stale</strong> · A watched file changed. Rescan to refresh the result.</div>';
  html += '<section class="panel-card scan-info"><div class="panel-title">Report details<span class="subtle">Current scan</span></div><dl class="scan-meta">';
  html += '<div><dt>Active policy</dt><dd>' + esc(r.policyName) + '</dd></div><div><dt>Generated</dt><dd class="mono">' + esc(r.generatedAtEpochMs ? new Date(r.generatedAtEpochMs).toLocaleString() : 'Not available') + '</dd></div><div><dt>Scan duration</dt><dd class="mono">' + r.durationMs + ' ms</dd></div><div><dt>Execution graph</dt><dd class="mono">' + (r.edges || []).length + ' discovered edges</dd></div></dl></section>';
  html += '<div class="scope-note">Built-in analysis reads project files without running them. External providers run only with your grants.</div>';
  panel.innerHTML = html;
  byId('ovExport').addEventListener('click',function() { post('export'); });
  byId('ovExportSarif').addEventListener('click',function() { post('sarif'); });
  byId('ovReview').addEventListener('click',function() { switchTab('findings'); });
  byId('ovProviders').addEventListener('click',function() { switchTab('policy'); });
  panel.querySelectorAll('.review-finding').forEach(function(button) { button.addEventListener('click',function() { selectedFp=button.getAttribute('data-fp');renderFindings();switchTab('findings'); }); });
}

function groupFindings() {
  const r = report();
  if (!r) return null;
  const groups = { blockers: [], errors: [], warnings: [], info: [], suppressed: [], accepted: [] };
  r.findings.forEach(function (f) {
    if (f.status === 'SUPPRESSED') {
      groups.suppressed.push(f);
      return;
    }
    if (f.status !== 'ACTIVE') { groups.accepted.push(f);return; }
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
  return (f.ruleId + ' ' + f.title + ' ' + f.location.path + ' ' + f.category + ' ' + f.message + ' ' + (f.providerId || 'wrapperguard') + ' ' + providerName(f.providerId || 'wrapperguard') + ' ' + f.status + ' ' + (f.actions || []).join(' ') + ' ' + (f.actions || []).map(humanAction).join(' ')).toLowerCase().indexOf(q) >= 0;
}

function rowHtml(f) {
  const sev = SEV_ICON[f.severity] || SEV_ICON.INFO;
  const suppressed = f.status === 'SUPPRESSED';
  const decision = suppressed ? ['info','Baselined'] : f.status !== 'ACTIVE' ? ['info','Accepted'] : f.policyAction === 'BLOCK' ? ['err','Block'] : f.policyAction === 'REVIEW' ? ['warn','Review'] : ['info','Allow'];
  const location = f.location.path + (f.location.line != null ? ':' + f.location.line : '');
  return '<button type="button" class="finding-row finding-columns' + (suppressed ? ' suppressed' : '') + (f.fingerprint === selectedFp ? ' selected' : '') + '" data-fp="' + esc(f.fingerprint) + '" aria-pressed="' + (f.fingerprint === selectedFp) + '" title="' + esc(f.ruleId + ' · ' + f.title) + '"><span class="finding-issue"><span class="ficon ' + sev[1] + '" aria-label="' + esc(f.severity) + '">' + sev[0] + '</span><span class="title">' + esc(f.title) + '</span></span><span class="where" title="' + esc(location) + '">' + esc(location) + '</span><span class="row-provider" title="' + esc(f.providerId || 'wrapperguard') + '">' + esc(providerName(f.providerId || 'wrapperguard')) + '</span><span class="row-decision"><span class="badge ' + decision[0] + '">' + decision[1] + '</span></span></button>';
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
  byId('findingsScope').textContent = filterText ? visible + ' matching findings' : total + ' findings';
  byId('findingsHint').textContent = visible + ' shown';
  if (!selectedFp && wide && r.findings.length) selectedFp = r.findings.find(function(f) { return f.status === 'ACTIVE' && f.policyAction === 'BLOCK'; })?.fingerprint || r.findings[0].fingerprint;
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
      ['blockers', 'Execution blockers', 'err'],
      ['errors', 'Errors', 'err'],
      ['warnings', 'Warnings', 'warn'],
      ['info', 'Information', 'info'],
      ['suppressed', 'Approved baseline', 'info'],
      ['accepted', 'Accepted / resolved', 'info']
    ];
    defs.forEach(function (d) {
      const items = groups[d[0]].filter(matchesFilter);
      if (items.length === 0) return;
      items.forEach(function (f) { list += rowHtml(f); });
    });
  }
  const detailHtml = sel ? detailHtmlFor(sel, true) : '<div class="empty-note">Select a finding to inspect evidence, execution path, and remediation.</div>';
  layout.innerHTML = '<div class="list" id="findingsList"><div class="list-head finding-columns"><span>Issue</span><span>Location</span><span class="provider-heading">Provider</span><span>Decision</span></div>' + list + '</div><div class="detail" id="findingDetail" tabindex="0" role="region" aria-label="Finding evidence and actions">' + detailHtml + '</div>';

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
  const policy = f.status === 'SUPPRESSED' ? ['info', 'Approved baseline'] : f.status !== 'ACTIVE' ? ['info', f.status.toLowerCase()] : f.policyAction === 'BLOCK' ? ['err', 'Blocks execution'] : f.policyAction === 'REVIEW' ? ['warn', 'Review required'] : ['info', 'Informational'];
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
    snippet += '<div class="snippet-note">Credential values in this evidence are redacted.</div>';
  }
  let html = '<div class="detail-header"><span>Inspector</span><span class="mono">' + esc(f.ruleId) + '</span><span class="badge ' + policy[0] + '" style="margin-left:auto">' + policy[1] + '</span></div>';
  html += '<div class="detail-content"><div class="detail-overview">';
  if (withBack) html += '<button class="btn btn-ghost back" id="detailBack">← Back to findings</button>';
  html += '<h2>' + esc(f.title) + '</h2>';
  html += '<div class="detail-facts"><span class="' + sev[1] + '">' + esc(f.severity) + '</span><span>·</span><span>' + esc(f.category) + '</span><span>·</span><span>' + esc(f.confidence) + ' confidence</span><span>·</span><span>' + esc(f.status) + '</span></div>';
  html += '<div class="provider-line"><span>Provider </span><strong>' + esc(providerName(f.providerId || 'wrapperguard')) + '</strong><span class="provider-id-inline">' + esc(f.providerId || 'wrapperguard') + '</span></div>';
  html += '<p class="detail-description">' + esc(f.message) + '</p>';
  html += '<div class="section-label">Execution path</div><div class="path">' + pathHtml + '</div>';
  html += '<div class="action-chips">' + actionChips(f.actions) + '</div></div>';
  html += '<div class="detail-evidence"><div class="snippet"><div class="snippet-bar"><span class="file">' + esc(f.location.path) + '</span><button class="copy-btn js-copy" title="Copy evidence">Copy</button></div>' + snippet + '</div>';
  html += '<div class="remediation"><div class="head">Remediation</div><p>' + esc(f.remediation || 'No specific remediation provided.') + '</p></div>';
  html += '<div class="detail-actions">';
  if (f.location.path && f.location.path !== '.') html += '<button class="btn btn-primary" id="detailOpen">Open in editor</button>';
  html += '<button class="btn btn-ghost js-copy">Copy evidence</button>';
  if (f.status === 'SUPPRESSED') {
    html += '<button class="btn btn-ghost" id="detailUnbaseline" title="Remove this finding from your trusted baseline and rescan">Restore finding</button>';
  } else {
    if (!f.providerId || f.providerId === 'wrapperguard') html += '<button class="btn btn-ghost" id="detailSuppress" title="Add a wrapperguard:ignore marker at this line">Suppress</button>';
    html += '<button class="btn btn-ghost" id="detailBaseline" title="Add to your trusted baseline (stored outside the repository)">Add to baseline</button>';
  }
  html += '</div></div></div>';
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
    if (f.policyAction !== 'BLOCK' || f.status !== 'ACTIVE') return;
    if (!blockerByPath[f.location.path]) blockerByPath[f.location.path] = f;
  });
  const suspLines = {};
  r.findings.forEach(function (f) {
    if (f.policyAction === 'BLOCK' && f.status === 'ACTIVE') suspLines[f.location.path + ':' + (f.location.line != null ? f.location.line : '')] = f;
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
  const rowH = 136;
  const W = Math.max(460, maxCol * colW + 70);
  const H = maxDepth * rowH + 55;

  const pos = {};
  layerKeys.forEach(function (d, li) {
    const list = order[d];
    const layerW = list.length * colW;
    const startX = (W - layerW) / 2;
    list.forEach(function (n, i) {
      pos[n] = { x: Math.round(startX + i * colW), y: 25 + li * rowH };
    });
  });

  let svg = '<svg class="g-svg" viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '">';
  svg += '<defs>';
  svg += '<pattern id="wgGrid" width="22" height="22" patternUnits="userSpaceOnUse"><circle cx="1.5" cy="1.5" r="1.5" fill="var(--wg-border)" opacity="0.35"></circle></pattern>';
  svg += '<marker id="wgArrow" markerHeight="7" markerWidth="7" orient="auto-start-reverse" refX="9" refY="5" viewBox="0 0 10 10"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--wg-border-strong)"></path></marker>';
  svg += '<marker id="wgArrowErr" markerHeight="7" markerWidth="7" orient="auto-start-reverse" refX="9" refY="5" viewBox="0 0 10 10"><path d="M 0 0 L 10 5 L 0 10 z" fill="var(--error)"></path></marker>';
  svg += '</defs>';

  edges.forEach(function (e) {
    const a = pos[e.from.path];
    const b = pos[e.to.path];
    if (!a || !b) return;
    const x1 = a.x + 90;
    const y1 = a.y + 62;
    const x2 = b.x + 90;
    const y2 = b.y + 2;
    const key = e.to.path + ':' + (e.to.line != null ? e.to.line : '');
    const err = suspLines[key] || blockerByPath[e.to.path];
    const marker = err ? 'url(#wgArrowErr)' : 'url(#wgArrow)';
    svg += '<g class="g-edge' + (err ? ' err' : '') + '" data-from="' + esc(e.from.path) + '" data-to="' + esc(e.to.path) + '">';
    svg += '<title>' + esc(e.viaCommand) + '</title>';
    svg += '<path d="M ' + x1 + ' ' + y1 + ' C ' + x1 + ' ' + (y1 + 38) + ', ' + x2 + ' ' + (y2 - 38) + ', ' + x2 + ' ' + y2 + '" marker-end="' + marker + '"></path>';
    const label = (e.from.line != null ? 'line:' + e.from.line : '') || (e.viaCommand || '').slice(0, 24);
    if (label) {
      svg += '<text class="g-elabel" x="' + ((x1 + x2) / 2 + 9) + '" y="' + ((y1 + y2) / 2) + '" font-family="var(--vscode-editor-font-family)" font-size="9">' + esc(label) + '</text>';
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
    let card = '<div class="gnode-card' + (blocker ? ' g-sus' : '') + (isEntry ? ' g-entry' : '') + '" data-node="' + esc(n) + '" role="button" tabindex="0" title="Click to inspect this file" style="left:' + p.x + 'px;top:' + p.y + 'px;animation-delay:' + (cardIndex * 45) + 'ms">';
    cardIndex++;
    if (isEntry) {
      card += '<div class="g-cat">&#9654; Entry point</div>';
    } else {
      card += '<div class="g-cat">' + esc(ext) + '</div>';
    }
    if (blocker) {
      card += '<div class="g-badge">Execution blocked</div>';
    }
    card += '<div class="g-name mono">' + esc(n) + '</div>';
    if (blocker) {
      card += '<div class="g-desc mono">' + esc(blocker.title) + '</div>';
    }
    card += '</div>';
    cards += card;
  });

  let explorer = '';
  const blockerFindings = r.findings.filter(function (f) { return f.policyAction === 'BLOCK' && f.status === 'ACTIVE'; });
  if (blockerFindings.length > 0) {
    explorer = '<div class="section-label" style="margin-top:12px">Paths to blockers (' + blockerFindings.length + ')</div>';
    blockerFindings.forEach(function (f, i) {
      const chain = (f.callChain || []).map(function (l) { return l.source.path + ':' + (l.source.line != null ? l.source.line : ''); });
      chain.push(f.location.path + ':' + (f.location.line != null ? f.location.line : ''));
      explorer += '<div class="path-block">';
      explorer += '<div class="path-head"><span class="path-title mono">Path #' + (i + 1) + '</span><span class="badge err">Blocked</span></div>';
      chain.forEach(function (step, si) {
        const last = si === chain.length - 1;
        explorer += '<div class="path-step mono' + (last ? ' path-last' : '') + '">' + (last ? '&#8618; ' : '&#183; ') + esc(step) + '</div>';
      });
      explorer += '<div class="path-rule mono">' + esc(f.ruleId) + ' — ' + esc(f.title) + '</div>';
      explorer += '<button class="path-link" data-fp="' + esc(f.fingerprint) + '">Inspect finding →</button></div>';
    });
  }

  const toolbar =
    '<div class="g-toolbar">' +
    '<div class="g-tools">' +
    '<button class="g-btn" id="gZoomIn" title="Zoom in">+</button>' +
    '<button class="g-btn" id="gZoomOut" title="Zoom out">-</button>' +
    '<button class="g-btn" id="gFit" title="Fit to view">&#10534;</button>' +
    '</div>' +
    '<input id="gFind" class="g-find" type="search" aria-label="Find graph node" placeholder="Find node…" spellcheck="false"/><span class="graph-stat">' + nodes.size + ' files · ' + edges.length + ' edges</span>' +
    '</div>';

  // Detached files: findings whose files are not part of any call chain (e.g. build.gradle).
  const detached = [];
  r.findings.forEach(function (f) {
    if (!nodes.has(f.location.path) && detached.indexOf(f.location.path) < 0) detached.push(f.location.path);
  });
  let detachedHtml = '';
  if (detached.length > 0) {
    detachedHtml = '<div class="section-label" style="margin-top:12px">Detached files (' + detached.length + ')</div><div class="detached">';
    detached.sort().forEach(function (p) {
      detachedHtml += '<button class="detached-card mono" data-node="' + esc(p) + '" title="Click to inspect this file">' + esc(p) + '</button>';
    });
    detachedHtml += '</div>';
  }

  const legend = '<div class="legend"><span class="legend-key"><span class="legend-dot"></span>Script or configuration</span><span class="legend-key"><span class="legend-dot err"></span>Execution blocker</span><span>Hover to trace connections</span></div>';
  panel.innerHTML = '<div class="page-head"><div class="page-heading"><div class="eyebrow">Static execution analysis</div><h1>Execution graph</h1><p>Trace how build files connect to scripts and providers.</p></div><span class="badge">' + nodes.size + ' files · ' + edges.length + ' edges</span></div>' +
    '<div class="graph-workspace"><div class="graph-main">' + toolbar + '<div class="graph-wrap" id="graphWrap"><div class="graph" id="gInner" style="width:' + W + 'px;height:' + H + 'px">' + svg + cards + '</div></div>' + legend + '</div><aside class="graph-inspector" aria-label="Risky path explorer">' + (explorer || '<div class="section-label">Paths to blockers (0)</div><p class="muted">No active blockers in this graph.</p>') + detachedHtml + '</aside></div>' +
    '<div class="graph-note">Execution paths are inferred from source. Select a node to inspect its findings.</div>';
  bindGraphEvents(r);
  fitGraph();
}

function openNodeFromGraph(n, r) {
  const byPath = r.findings.filter(function (f) { return f.location.path === n; });
  const finding =
    byPath.filter(function (f) { return f.policyAction === 'BLOCK' && f.status === 'ACTIVE'; })[0] ||
    byPath.filter(function (f) { return f.severity === 'ERROR'; })[0] ||
    byPath.filter(function (f) { return f.severity === 'WARN'; })[0] ||
    byPath[0];
  if (finding) {
    selectedFp = finding.fingerprint;
    switchTab('findings');
    renderFindings();
  } else {
    post('open', {
      fingerprint: 'node-' + n,
      ruleId: 'WG-NODE',
      title: n,
      message: n,
      severity: 'INFO',
      confidence: 'LOW',
      category: 'AUDIT',
      kind: 'AUDIT_EVIDENCE',
      location: { path: n },
      reachable: true,
      callChain: [],
      policyAction: 'ALLOW',
      status: 'ACTIVE'
    });
  }
}

function applyZoom() {
  const inner = byId('gInner');
  if (!inner) return;
  inner.style.transform = 'scale(' + zoomScale + ')';
  inner.style.transformOrigin = 'top left';
  const svg = inner.querySelector('svg');
  const wrap = byId('graphWrap');
  if (svg && wrap) {
    const w = Number(svg.getAttribute('width'));
    inner.style.marginLeft = Math.max(0,(wrap.clientWidth - 24 - w * zoomScale) / 2) + 'px';
    inner.style.marginRight = '0';
  }
}
function fitGraph() {
  const wrap = byId('graphWrap');
  if (!wrap || !wrap.clientWidth || !wrap.clientHeight) return;
  const svg = wrap.querySelector('svg');
  if (!svg) return;
  zoomScale = Math.min(1.15, Math.max(0.45, Math.min((wrap.clientWidth - 30) / Number(svg.getAttribute('width')), (wrap.clientHeight - 30) / Number(svg.getAttribute('height')))));
  applyZoom();
}

function bindGraphEvents(r) {
  const zIn = byId('gZoomIn');
  const zOut = byId('gZoomOut');
  const zFit = byId('gFit');
  if (zIn) zIn.addEventListener('click', function () { zoomScale = Math.min(2, zoomScale + 0.2); applyZoom(); });
  if (zOut) zOut.addEventListener('click', function () { zoomScale = Math.max(0.4, zoomScale - 0.2); applyZoom(); });
  if (zFit) zFit.addEventListener('click', fitGraph);
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
    node.addEventListener('click', function () { openNodeFromGraph(node.getAttribute('data-node'), r); });
    node.addEventListener('keydown', function(event) { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault();openNodeFromGraph(node.getAttribute('data-node'),r); } });
  });
  document.querySelectorAll('#panel-graph .path-link').forEach(function(button) { button.addEventListener('click',function() { selectedFp=button.getAttribute('data-fp');renderFindings();switchTab('findings'); }); });
  document.querySelectorAll('#panel-graph .detached-card').forEach(function (card) {
    card.addEventListener('click', function () {
      openNodeFromGraph(card.getAttribute('data-node'), r);
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
  document.querySelectorAll('.tab').forEach(function (t) { const active=t.getAttribute('data-tab') === name;t.classList.toggle('active',active);if(active)t.setAttribute('aria-current','page');else t.removeAttribute('aria-current'); });
  document.querySelectorAll('.tab-panel').forEach(function (p) { p.classList.remove('active'); });
  byId('panel-' + name).classList.add('active');
  if (name === 'graph') fitGraph();
}

byId('tabs').addEventListener('click', function (e) {
  const tab = e.target.closest('.tab');
  if (!tab) return;
  switchTab(tab.getAttribute('data-tab'));
});

byId('rescanBtn').addEventListener('click', function () { post('scan'); });
byId('projectSelector').addEventListener('change',function() { vscode.postMessage({type:'select-project',projectRoot:byId('projectSelector').value}); });
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
vscode.postMessage({type:'ready'});
</script>
</body>
</html>`;
  }
}
