package com.sal7one.wrapperguard.rules

/** Small lexical check, not a shell interpreter: ordinary arguments, assignments and comments
 * are data. Literal command words (including quoted words) and command substitutions are code.
 * Keep the other script rules independent; this does not prove that dynamic shell code is safe.
 */
internal object UnixNetworkCommands {
    private val commands = setOf("curl", "wget", "fetch", "aria2c", "nc", "netcat", "socat", "ftp", "scp", "rsync")
    private val prefixes = setOf("if", "then", "else", "elif", "while", "until", "do", "!", "command", "builtin", "exec", "env", "sudo", "nohup", "time")
    private val assignment = Regex("[A-Za-z_][A-Za-z0-9_]*=")
    private val fallback = Regex("""(?<![\w./-])(curl|wget|fetch|aria2c|nc|netcat|socat|ftp|scp|rsync)(?![\w-])""", RegexOption.IGNORE_CASE)

    fun contains(line: String): Boolean = inspect(line, 0, null, 0).first

    private fun inspect(line: String, start: Int, end: Char?, depth: Int): Pair<Boolean, Int> {
        // Malformed/deep syntax must not turn this bounded advisory check into a blind spot.
        if (depth >= 32) return fallback.containsMatchIn(line.substring(start)) to line.length
        var i = start
        var quote: Char? = null
        var expectCommand = true
        var prefixOptions = false
        var redirectTarget = false
        var wordStart = -1
        val word = StringBuilder()
        fun flush(): Boolean {
            if (wordStart < 0) return false
            val token = word.toString()
            val tokenStart = wordStart
            word.clear()
            wordStart = -1
            if (redirectTarget) { redirectTarget = false; return false }
            if (!expectCommand) return false
            val isAssignment = assignment.matchAt(line, tokenStart) != null
            if (isAssignment || (prefixOptions && token.startsWith("-"))) return false
            if (token in prefixes) { prefixOptions = true; return false }
            expectCommand = false
            return token.lowercase() in commands
        }
        while (i < line.length) {
            val c = line[i]
            if (quote == null && c == end) return flush() to (i + 1)
            if (quote == '\'') {
                if (c == '\'') quote = null else word.append(c)
                i++; continue
            }
            if (c == '\\' && i + 1 < line.length) {
                if (wordStart < 0) wordStart = i
                word.append(line[i + 1]); i += 2; continue
            }
            if ((c == '$' && line.getOrNull(i + 1) == '(') || c == '`' || (quote == null && c == '(')) {
                val nested = inspect(line, i + if (c == '$') 2 else 1, if (c == '`') '`' else ')', depth + 1)
                if (nested.first) return nested
                if (wordStart < 0) wordStart = i
                word.append('\u0000'); i = nested.second; continue
            }
            if (quote == '"') {
                if (c == '"') quote = null else word.append(if (c == '$') '\u0000' else c)
                i++; continue
            }
            when {
                c == '\'' || c == '"' -> { if (wordStart < 0) wordStart = i; quote = c }
                c == '#' && wordStart < 0 -> return false to line.length
                c.isWhitespace() -> if (flush()) return true to (i + 1)
                c == '<' || c == '>' -> {
                    // A leading fd is part of the redirection, not the command word.
                    if (word.isNotEmpty() && word.all { it.isDigit() }) { word.clear(); wordStart = -1 }
                    else if (flush()) return true to (i + 1)
                    redirectTarget = true
                }
                c in ";|&){}" -> {
                    if (flush()) return true to (i + 1)
                    expectCommand = true; prefixOptions = false; redirectTarget = false
                }
                else -> { if (wordStart < 0) wordStart = i; word.append(if (c == '$') '\u0000' else c) }
            }
            i++
        }
        return flush() to i
    }
}
