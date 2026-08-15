/** Faithful port of core/util/UrlAllowlist. */
function hostOf(url: string): string {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return '';
  }
}

export function isHostAllowed(url: string, allowed: string[]): boolean {
  const host = hostOf(url);
  if (host === '') return false;
  return allowed.some((entry) => {
    const allowedHost = entry.replace(/^\./, '').toLowerCase();
    return host === allowedHost || host.endsWith('.' + allowedHost);
  });
}

export function isAllowed(url: string, allowedHosts: string[], patterns: string[]): boolean {
  if (isHostAllowed(url, allowedHosts)) return true;
  return patterns.some((pattern) => {
    try {
      return new RegExp(pattern).test(url);
    } catch {
      return false;
    }
  });
}
