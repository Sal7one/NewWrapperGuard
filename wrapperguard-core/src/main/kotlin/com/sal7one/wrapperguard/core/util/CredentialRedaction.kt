package com.sal7one.wrapperguard.core.util

/**
 * Deterministic credential-value redaction, applied everywhere evidence is created, shown,
 * logged, or exported so a finding's evidence text can never carry a literal secret out of
 * the project (UI, logs, JSON, SARIF, Markdown).
 *
 * Conservative and order-dependent:
 *  1. Secret-key assignments (password|token|apiKey|secret|... = "value" / : value).
 *  2. Authorization header values (Bearer/Basic/Token).
 *  3. URL userinfo passwords (https://user:pass@host).
 *  4. Every quoted value inside a credentials { ... } block.
 *
 * Returns the masked text and whether anything changed. Masking is idempotent on the text.
 */
object CredentialRedaction {

    private const val MASK = "[redacted]"

    private val SECRET_KEYS =
        """password|passwd|pwd|secret|token|api[_\-\s]?key|access[_\-\s]?key|secret[_\-\s]?key|""" +
            """client[_\-\s]?secret|private[_\-\s]?key|auth[_\-\s]?token|credential"""

    /** password = "hunter2" / token: 'abc' (quoted values, incl. spaces). */
    private val SECRET_ASSIGN_QUOTED =
        Regex("""(?i)(\b(?:$SECRET_KEYS)\b\s*[=:]\s*)(["'])([^"']{2,})\2""")

    /** password=hunter2 / token: abc (bare values, no quotes/space/separators). */
    private val SECRET_ASSIGN_BARE =
        Regex("""(?i)(\b(?:$SECRET_KEYS)\b\s*[=:]\s*)([^"'\s,;}&|]{2,})""")

    /** Authorization: Bearer eyJ... / authorization = "Basic dXNlcjpwYXNz". */
    private val AUTH_HEADER =
        Regex("""(?i)(\bauthorization\b\s*[=:]\s*["']?\s*(?:bearer|basic|token)?\s*)([^"'\s,;]+)""")

    /** https://user:pass@host -> mask the password part only. */
    private val URL_USERINFO = Regex("""(://[^/\s:@]+):([^@\s/]+)@""")

    /** Any key = "value" inside a credentials { ... } block. */
    private val CREDENTIALS_BLOCK_ASSIGNMENT =
        Regex("""(?i)(\b[a-zA-Z_][\w.]*\b\s*[=:]\s*)(["'])([^"']{2,})\2""")

    private val CREDENTIALS_BLOCK_START = Regex("""(?i)\bcredentials\b\s*\{""")

    fun mask(value: String): Pair<String, Boolean> {
        var changed = false
        var out = value

        out = SECRET_ASSIGN_QUOTED.replace(out) { m ->
            changed = true
            m.groupValues[1] + m.groupValues[2] + MASK + m.groupValues[2]
        }
        out = SECRET_ASSIGN_BARE.replace(out) { m ->
            changed = true
            m.groupValues[1] + MASK
        }
        out = AUTH_HEADER.replace(out) { m ->
            changed = true
            m.groupValues[1] + MASK
        }
        out = URL_USERINFO.replace(out) { m ->
            changed = true
            m.groupValues[1] + ":" + MASK + "@"
        }
        out = maskCredentialsBlocks(out) { blockChanged -> if (blockChanged) changed = true }
        return out to changed
    }

    /** Masks every quoted assignment inside balanced credentials { } spans. */
    private fun maskCredentialsBlocks(text: String, onChanged: (Boolean) -> Unit): String {
        var changed = false
        var out = text
        var searchFrom = 0
        while (true) {
            val start = CREDENTIALS_BLOCK_START.find(out, searchFrom) ?: break
            val open = out.indexOf('{', start.range.first)
            if (open < 0) break
            var depth = 0
            var end = -1
            for (i in open until out.length) {
                when (out[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
            }
            if (end < 0) break
            val block = out.substring(open + 1, end)
            val masked = CREDENTIALS_BLOCK_ASSIGNMENT.replace(block) { m ->
                changed = true
                m.groupValues[1] + m.groupValues[2] + MASK + m.groupValues[2]
            }
            out = out.replaceRange(open + 1, end, masked)
            // Replacement may shrink or expand the span; continue after its new closing brace.
            searchFrom = open + 1 + masked.length + 1
        }
        onChanged(changed)
        return out
    }
}
