/** Bounded lexical advisory check, never a shell interpreter or proof of dynamic code safety. */
const commands = new Set(['curl', 'wget', 'fetch', 'aria2c', 'nc', 'netcat', 'socat', 'ftp', 'scp', 'rsync']);
const prefixes = new Set(['if', 'then', 'else', 'elif', 'while', 'until', 'do', '!', 'command', 'builtin', 'exec', 'env', 'sudo', 'nohup', 'time']);
const fallback = /(?<![\w./-])(curl|wget|fetch|aria2c|nc|netcat|socat|ftp|scp|rsync)(?![\w-])/i;
const assignmentPattern = /[A-Za-z_][A-Za-z0-9_]*=/y;

export function containsUnixNetworkCommand(line: string): boolean {
  return inspect(line, 0, undefined, 0)[0];
}

function inspect(line: string, start: number, end: string | undefined, depth: number): [boolean, number] {
  if (depth >= 32) return [fallback.test(line.slice(start)), line.length];
  let i = start, quote: string | undefined, expectCommand = true, prefixOptions = false, redirectTarget = false;
  let wordStart = -1, word = '';
  function flush(): boolean {
    if (wordStart < 0) return false;
    const token = word;
    const tokenStart = wordStart;
    word = ''; wordStart = -1;
    if (redirectTarget) { redirectTarget = false; return false; }
    if (!expectCommand) return false;
    assignmentPattern.lastIndex = tokenStart;
    const assignment = assignmentPattern.test(line);
    if (assignment || (prefixOptions && token.startsWith('-'))) return false;
    if (prefixes.has(token)) { prefixOptions = true; return false; }
    expectCommand = false;
    return commands.has(token.toLowerCase());
  }
  while (i < line.length) {
    const c = line[i];
    if (quote === undefined && c === end) return [flush(), i + 1];
    if (quote === "'") {
      if (c === "'") quote = undefined; else word += c;
      i++; continue;
    }
    if (c === '\\' && i + 1 < line.length) {
      if (wordStart < 0) wordStart = i;
      word += line[i + 1]; i += 2; continue;
    }
    if ((c === '$' && line[i + 1] === '(') || c === '`' || (quote === undefined && c === '(')) {
      const nested = inspect(line, i + (c === '$' ? 2 : 1), c === '`' ? '`' : ')', depth + 1);
      if (nested[0]) return nested;
      if (wordStart < 0) wordStart = i;
      word += '\u0000'; i = nested[1]; continue;
    }
    if (quote === '"') {
      if (c === '"') quote = undefined; else word += c === '$' ? '\u0000' : c;
      i++; continue;
    }
    if (c === "'" || c === '"') { if (wordStart < 0) wordStart = i; quote = c; }
    else if (c === '#' && wordStart < 0) return [false, line.length];
    else if (/\s/.test(c)) { if (flush()) return [true, i + 1]; }
    else if (c === '<' || c === '>') {
      if (word.length > 0 && /^\d+$/.test(word)) { word = ''; wordStart = -1; }
      else if (flush()) return [true, i + 1];
      redirectTarget = true;
    } else if (';|&){}'.includes(c)) {
      if (flush()) return [true, i + 1];
      expectCommand = true; prefixOptions = false; redirectTarget = false;
    } else { if (wordStart < 0) wordStart = i; word += c === '$' ? '\u0000' : c; }
    i++;
  }
  return [flush(), i];
}
