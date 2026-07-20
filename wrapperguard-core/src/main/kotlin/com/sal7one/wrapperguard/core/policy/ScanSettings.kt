package com.sal7one.wrapperguard.core.policy

/**
 * Pure-Kotlin scanner configuration. Decouples the rules/scanner from any IDE settings
 * service. The IntelliJ adapter constructs this from its own settings; the CLI builds a
 * default; tests build whatever they need.
 *
 * [ScanPolicy] (verdict/block decisions) is separate from this (what to scan and how).
 */
data class ScanSettings(
    val allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS,
    val allowedUrlPatterns: List<String> = DEFAULT_ALLOWED_URL_PATTERNS,
    val blockedCommands: Set<String> = DEFAULT_BLOCKED_COMMANDS,
    val maxScriptDepth: Int = 10,
    val maxFileBytes: Long = 1_000_000,
    val requireDistributionSha256: Boolean = false,
    val enableRecursiveScanning: Boolean = true,
    val knownSafeRepositoryHosts: Set<String> = DEFAULT_KNOWN_SAFE_REPOSITORIES
) {
    companion object {
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf(
            "services.gradle.org",
            "downloads.gradle.org",
            "downloads.gradle-dn.com",
            "plugins.gradle.org",
            "repo.maven.apache.org",
            "repo1.maven.org",
            "dl.google.com",
            "maven.google.com"
        )

        val DEFAULT_ALLOWED_URL_PATTERNS: List<String> = listOf(
            """^https://services\.gradle\.org/distributions/gradle-[0-9.]+-(bin|all)\.zip$""",
            """^https://downloads\.gradle\.org/distributions/gradle-[0-9.]+-(bin|all)\.zip$"""
        )

        val DEFAULT_BLOCKED_COMMANDS: Set<String> = setOf("certutil", "bitsadmin")

        val DEFAULT_KNOWN_SAFE_REPOSITORIES: Set<String> = setOf(
            "repo.maven.apache.org",
            "repo1.maven.org",
            "plugins.gradle.org",
            "dl.google.com",
            "maven.google.com"
        )
    }
}
