package com.sal7one.wrapperguard.core.util

import java.net.URI

/**
 * Checks whether a URL's host is in an allowlist.
 *
 * An entry "gradle.org" matches both "gradle.org" and any subdomain ("services.gradle.org").
 * Pure Kotlin — no IDE dependencies — so the CLI and rules can share it.
 */
object UrlAllowlist {
    fun isHostAllowed(url: String, allowed: Set<String>): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: ""
        if (host.isBlank()) return false
        return allowed.any { entry ->
            val allowedHost = entry.removePrefix(".").lowercase()
            host == allowedHost || host.endsWith(".$allowedHost")
        }
    }

    /**
     * True if the host is allowlisted OR any of the [patterns] (Java regex) matches the
     * full URL. Bad patterns are ignored rather than thrown.
     */
    fun isAllowed(url: String, allowedHosts: Set<String>, patterns: List<String>): Boolean {
        if (isHostAllowed(url, allowedHosts)) return true
        return patterns.any { pattern -> runCatching { Regex(pattern).matches(url) }.getOrDefault(false) }
    }
}
