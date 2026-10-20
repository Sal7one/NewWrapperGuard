export type AnalyzerKey =
  | 'unix'
  | 'windows'
  | 'powershell'
  | 'wrapper-props'
  | 'settings'
  | 'init'
  | 'groovy'
  | 'kotlin'
  | 'catalog'
  | 'maven'
  | 'generic';

/** Faithful port of FileTypeRouter.rulesFor. */
export function rulesFor(fileName: string): AnalyzerKey[] {
  if (fileName === 'pom.xml') return ['maven'];
  const dot = fileName.lastIndexOf('.');
  const extension = dot >= 0 ? fileName.slice(dot + 1) : '';
  if (fileName === 'gradlew' || extension === 'sh') return ['unix'];
  if (fileName === 'gradlew.bat' || extension === 'bat' || extension === 'cmd') return ['windows'];
  if (extension === 'ps1') return ['powershell'];
  if (fileName === 'gradle-wrapper.properties') return ['wrapper-props'];
  if (fileName === 'settings.gradle' || fileName === 'settings.gradle.kts') return ['settings'];
  if (fileName.startsWith('init.') && (fileName.endsWith('.gradle') || fileName.endsWith('.gradle.kts'))) return ['init'];
  if (fileName === 'build.gradle') return ['groovy'];
  if (fileName === 'build.gradle.kts') return ['kotlin'];
  if (fileName === 'libs.versions.toml') return ['catalog'];
  if (extension === 'gradle' || extension === 'kts') return ['generic'];
  if (extension === 'bash' || extension === 'zsh' || extension === 'bats') return ['unix'];
  return [];
}

/** Faithful port of FileTypeRouter.isScannable. */
export function isScannable(name: string): boolean {
  const dot = name.lastIndexOf('.');
  const extension = dot >= 0 ? name.slice(dot + 1) : '';
  return (
    name === 'gradlew' ||
    name === 'gradlew.bat' ||
    name === 'gradle-wrapper.properties' ||
    name === 'libs.versions.toml' ||
    name === 'pom.xml' ||
    ['sh', 'bash', 'zsh', 'ps1', 'bat', 'cmd', 'gradle', 'kts'].includes(extension) ||
    name.startsWith('settings.gradle') ||
    name.startsWith('build.gradle') ||
    name.startsWith('init.gradle')
  );
}
