export interface CalledScript {
  rawLine: string;
  path: string;
  lineNumber: number;
}

const UNIX_PATTERNS: RegExp[] = [
  /^\s*(source|\.)\s+"(?<path>[^"]+\.sh)"/,
  /^\s*(source|\.)\s+'(?<path>[^']+\.sh)'/,
  /^\s*(source|\.)\s+(["']?)(?<path>[^"'\s;|&]+)\2/,
  /^\s*(bash|sh|env\s+(?:-S\s+)?bash|env\s+sh)\s+"(?<path>[^"]+\.sh)"/,
  /^\s*(bash|sh|env\s+(?:-S\s+)?bash|env\s+sh)\s+'(?<path>[^']+\.sh)'/,
  /^\s*(bash|sh|env\s+(?:-S\s+)?bash|env\s+sh)\s+(["']?)(?<path>[^"'\s;|&]+)\2/,
  /^\s*(?<path>\.\/[^\s;|&]+\.sh|[^\s;|&/]+\.sh)\s*(?:[;&|]|$)/
];

const WINDOWS_PATTERNS: RegExp[] = [
  /^\s*CALL\s+"(?<path>[^"]+\.(?:bat|cmd))"/i,
  /^\s*CALL\s+(?<path>[^"'\s]+\.(?:bat|cmd))/i,
  /^\s*"(?<path>[^"]+\.(?:bat|cmd))"/i,
  /^\s*(?<path>[^"'\s]+\.(?:bat|cmd))(?:\s|$)/i,
  /powershell(?:\.exe)?\s+.*-File\s+"(?<path>[^"]+\.ps1)"/i,
  /powershell(?:\.exe)?\s+.*-File\s+(?<path>[^"'\s]+\.ps1)/i
];

const PS1_PATTERNS: RegExp[] = [
  /^\s*\.\s+(["']?)(?<path>[^"'\s]+\.ps1)\1/i
];

function parse(content: string, patterns: RegExp[]): CalledScript[] {
  const out: CalledScript[] = [];
  const seen = new Set<string>();
  content.split(/\r\n|\r|\n/).forEach((line, idx) => {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#') || trimmed.startsWith('REM') || trimmed.startsWith('::')) return;
    for (const pattern of patterns) {
      const m = pattern.exec(line);
      const p = m?.groups?.path;
      if (p !== undefined && !seen.has(p)) {
        seen.add(p);
        out.push({ rawLine: trimmed, path: p, lineNumber: idx + 1 });
      }
    }
  });
  return out;
}

/** Faithful port of CalledScriptsResolver: discovers script-to-script references. */
export function findCalledScripts(fileName: string, content: string): CalledScript[] {
  if (fileName === 'gradlew' || fileName.endsWith('.sh')) {
    return parse(content, UNIX_PATTERNS);
  }
  if (fileName === 'gradlew.bat' || fileName.endsWith('.bat') || fileName.endsWith('.cmd')) {
    return parse(content, WINDOWS_PATTERNS);
  }
  if (fileName.endsWith('.ps1')) {
    return parse(content, PS1_PATTERNS);
  }
  return [];
}
