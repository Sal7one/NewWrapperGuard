import * as fs from 'node:fs';
import * as path from 'node:path';

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^$|(){}\[\]\\]/g, '\\$&');
}

function replaceIgnoreCase(text: string, key: string, value: string): string {
  return text.split(new RegExp(escapeRegExp(key), 'i')).join(value);
}

function expandVariables(raw: string, referrer: string): string {
  let expanded = raw.trim().replace(/^["'\u0060]+|["'\u0060]+$/g, '');
  const refDir = path.dirname(referrer);
  const unix: Array<[string, string]> = [
    ['$0', referrer],
    ['$' + '{BASH_SOURCE[0]}', referrer],
    ['$' + '{BASH_SOURCE}', referrer],
    ['$WRAPPER_DIR', refDir],
    ['$BASEDIR', refDir]
  ];
  for (const [k, v] of unix) {
    expanded = expanded.split(k).join(v);
  }
  expanded = replaceIgnoreCase(expanded, '%~dp0', refDir + '\\');
  expanded = replaceIgnoreCase(expanded, '%CD%', refDir);
  expanded = expanded.replace(/\$\((?:cd\s+)?["']?dirname["']?\s+["']?\$0["']?\)/g, refDir);
  return expanded.trim().replace(/^["']+|["']+$/g, '');
}

function isUnderProjectRoot(projectRoot: string, file: string): boolean {
  const root = path.normalize(projectRoot);
  const f = path.normalize(file);
  return f === root || f.startsWith(root.endsWith(path.sep) ? root : root + path.sep);
}

function normalizePath(projectRoot: string, expanded: string, referrer: string): string | null {
  try {
    const cleaned = expanded.replace(/\\/g, '/');
    const p = path.posix.isAbsolute(cleaned) ? cleaned : path.join(path.dirname(referrer), cleaned);
    const resolved = path.normalize(p);
    if (!isUnderProjectRoot(projectRoot, resolved)) return null;
    return resolved;
  } catch {
    return null;
  }
}

function isTextFile(file: string): boolean {
  try {
    const bytes = fs.readFileSync(file);
    return !bytes.includes(0);
  } catch {
    return false;
  }
}

/**
 * Faithful port of SafePathResolver: expand variables, normalize, require existence,
 * in-root containment, size limit, and text content. Returns absolute path or null.
 */
export function resolveScriptPath(projectRoot: string, referrer: string, rawPath: string, maxFileBytes: number): string | null {
  try {
    const expanded = expandVariables(rawPath, referrer);
    const resolved = normalizePath(projectRoot, expanded, referrer);
    if (!resolved) return null;
    if (!fs.existsSync(resolved) || !fs.statSync(resolved).isFile()) return null;
    if (!isUnderProjectRoot(projectRoot, resolved)) return null;
    if (fs.statSync(resolved).size > maxFileBytes) return null;
    if (!isTextFile(resolved)) return null;
    return resolved;
  } catch {
    return null;
  }
}
