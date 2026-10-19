/** Security settings must come from the user's settings, never repository-controlled configuration. */
export function userSetting<T>(entry: {
  globalValue?: T; globalLanguageValue?: T; defaultValue?: T;
  workspaceValue?: T; workspaceFolderValue?: T; workspaceLanguageValue?: T; workspaceFolderLanguageValue?: T;
} | undefined, fallback: T): T {
  if (entry && [entry.workspaceValue,entry.workspaceFolderValue,entry.workspaceLanguageValue,entry.workspaceFolderLanguageValue].some(v => v !== undefined)) {
    throw new Error('WrapperGuard security settings must be configured in User Settings; workspace overrides are rejected.');
  }
  return entry?.globalValue ?? entry?.defaultValue ?? fallback;
}
