/**
 * Faithful port of core/util/CredentialRedaction. Masks literal secret values in evidence
 * so credentials never leave the project in UI, logs, or exported JSON.
 */
const MASK = '[redacted]';

const SECRET_KEYS =
  'password|passwd|pwd|secret|token|api[_\\-\\s]?key|access[_\\-\\s]?key|secret[_\\-\\s]?key|' +
  'client[_\\-\\s]?secret|private[_\\-\\s]?key|auth[_\\-\\s]?token|credential';

const SECRET_ASSIGN_QUOTED = new RegExp('(\\b(?:' + SECRET_KEYS + ')\\b\\s*[=:]\\s*)(["\'])([^"\']{2,})\\2', 'ig');
const SECRET_ASSIGN_BARE = new RegExp('(\\b(?:' + SECRET_KEYS + ')\\b\\s*[=:]\\s*)([^"\'\\s,;}&|]{2,})', 'ig');
const AUTH_HEADER = new RegExp('(\\bauthorization\\b\\s*[=:]\\s*["\']?\\s*(?:bearer|basic|token)?\\s*)([^"\'\\s,;]+)', 'ig');
const URL_USERINFO = /(:\/\/[^/\s:@]+):([^@\s/]+)@/g;
const CREDENTIALS_BLOCK_ASSIGNMENT = new RegExp('(\\b[a-zA-Z_][\\w.]*\\b\\s*[=:]\\s*)(["\'])([^"\']{2,})\\2', 'ig');
const CREDENTIALS_BLOCK_START = /\\bcredentials\\b\\s*\\{/i;

export function maskSecretValues(value: string): { masked: string; changed: boolean } {
  let changed = false;
  let out = value;

  out = out.replace(SECRET_ASSIGN_QUOTED, (_m, g1: string, g2: string) => {
    changed = true;
    return g1 + g2 + MASK + g2;
  });
  out = out.replace(SECRET_ASSIGN_BARE, (_m, g1: string) => {
    changed = true;
    return g1 + MASK;
  });
  out = out.replace(AUTH_HEADER, (_m, g1: string) => {
    changed = true;
    return g1 + MASK;
  });
  out = out.replace(URL_USERINFO, (_m, g1: string) => {
    changed = true;
    return g1 + ':' + MASK + '@';
  });
  out = maskCredentialsBlocks(out, (blockChanged) => {
    if (blockChanged) changed = true;
  });
  return { masked: out, changed };
}

function maskCredentialsBlocks(text: string, onChanged: (b: boolean) => void): string {
  let changed = false;
  let out = text;
  let searchFrom = 0;
  for (;;) {
    const slice = out.slice(searchFrom);
    CREDENTIALS_BLOCK_START.lastIndex = 0;
    const start = CREDENTIALS_BLOCK_START.exec(slice);
    if (!start) break;
    const openAbs = searchFrom + start.index;
    const open = out.indexOf('{', openAbs);
    if (open < 0) break;
    let depth = 0;
    let end = -1;
    for (let i = open; i < out.length; i++) {
      if (out[i] === '{') depth++;
      else if (out[i] === '}') {
        depth--;
        if (depth === 0) {
          end = i;
          break;
        }
      }
    }
    if (end < 0) break;
    const block = out.substring(open + 1, end);
    const masked = block.replace(CREDENTIALS_BLOCK_ASSIGNMENT, (_m, g1: string, g2: string) => {
      changed = true;
      return g1 + g2 + MASK + g2;
    });
    out = out.substring(0, open + 1) + masked + out.substring(end);
    searchFrom = end + 1;
  }
  onChanged(changed);
  return out;
}
