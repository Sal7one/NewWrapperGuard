import * as vscode from 'vscode';
import { ScanState } from './models';

/** Single source of truth for the scan lifecycle; every view re-renders from it. */
export class WrapperGuardState {
  private readonly changed = new vscode.EventEmitter<ScanState>();
  readonly onDidChange = this.changed.event;
  private current: ScanState = { kind: 'not-scanned' };

  get(): ScanState {
    return this.current;
  }

  set(next: ScanState): void {
    this.current = next;
    this.changed.fire(next);
  }
}
