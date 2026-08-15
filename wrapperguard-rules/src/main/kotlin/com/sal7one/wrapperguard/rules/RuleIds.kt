package com.sal7one.wrapperguard.rules

/**
 * Stable rule identifiers. The old project identified rules only by human-readable message
 * strings (which broke baselines when wording changed). These IDs are the canonical keys for
 * policy, baselines, and suppression.
 *
 * Grouping: WG-SCRIPT-* (shell/bat/ps1/wrapper), WG-BUILD-* (Gradle DSL),
 * WG-CATALOG-* (version catalog), WG-STRUCT-* (project structure), WG-SCAN-* (scanner notices).
 */
object RuleIds {
    // Script / wrapper rules
    const val SUPPLY_PIPE_TO_SHELL = "WG-SCRIPT-001"
    const val SUPPLY_BLOCKED_COMMAND = "WG-SCRIPT-002"
    const val NETWORK_COMMAND = "WG-SCRIPT-003"
    const val NETWORK_INSECURE_TLS = "WG-SCRIPT-004"
    const val OBFUSCATION_ENCODED_EXEC = "WG-SCRIPT-005"
    const val WRAPPER_MUTATION = "WG-SCRIPT-006"
    const val FILE_SYSTEM_DESTRUCTIVE = "WG-SCRIPT-007"
    const val EXECUTION_PRIVILEGE = "WG-SCRIPT-008"
    const val CREDENTIALS_ENV_LEAK = "WG-SCRIPT-009"
    const val NETWORK_URL_NOT_ALLOWLISTED = "WG-SCRIPT-010"
    const val NETWORK_URL_APPROVED = "WG-SCRIPT-011"
    const val EXECUTION_GENERATED_THEN_RUN = "WG-SCRIPT-012"
    const val CONFIG_SUPPRESS_NO_REASON = "WG-SCRIPT-013"

    // Wrapper properties
    const val WRAPPER_MISSING_DISTRIBUTION_URL = "WG-WRAPPER-001"
    const val WRAPPER_NOT_HTTPS = "WG-WRAPPER-002"
    const val WRAPPER_HOST_NOT_ALLOWLISTED = "WG-WRAPPER-003"
    const val WRAPPER_PRE_RELEASE = "WG-WRAPPER-004"
    const val WRAPPER_CHECKSUM_MISSING = "WG-WRAPPER-005"
    const val WRAPPER_JAR_SHA256 = "WG-WRAPPER-006"
    const val WRAPPER_JAR_UNKNOWN = "WG-WRAPPER-007"

    // Build script rules
    const val REPO_NOT_HTTPS = "WG-BUILD-001"
    const val REPO_MALFORMED = "WG-BUILD-002"
    const val REPO_NOT_ALLOWLISTED = "WG-BUILD-003"
    const val REPO_KNOWN_PUBLIC = "WG-BUILD-004"
    const val REPO_ALLOWLISTED = "WG-BUILD-005"
    const val SUPPLY_REMOTE_SCRIPT = "WG-BUILD-006"
    const val EXECUTION_PROCESS = "WG-BUILD-007"
    const val NETWORK_CLIENT = "WG-BUILD-008"
    const val OBFUSCATION_DYNAMIC = "WG-BUILD-009"
    const val CREDENTIALS_HARDCODED = "WG-BUILD-010"
    const val SUPPLY_UNSTABLE_DEP = "WG-BUILD-011"
    const val REPO_MAVEN_LOCAL = "WG-BUILD-012"
    const val REPO_FLAT_DIR = "WG-BUILD-013"
    const val SUPPLY_INIT_SCRIPT = "WG-BUILD-014"
    const val SUPPLY_INIT_LIFECYCLE = "WG-BUILD-015"

    // Version catalog
    const val CATALOG_DYNAMIC_VERSION = "WG-CATALOG-001"

    // Project structure
    const val STRUCT_WRAPPER_JAR_MISSING = "WG-STRUCT-001"
    const val STRUCT_WRAPPER_SCRIPTS_MISSING = "WG-STRUCT-002"
    const val STRUCT_NO_SETTINGS = "WG-STRUCT-003"

    // Recursion notices
    const val SCAN_CIRCULAR = "WG-SCAN-CIRCULAR"
    const val SCAN_DEPTH_LIMIT = "WG-SCAN-DEPTH"
    const val SCAN_UNRESOLVED = "WG-SCAN-UNRESOLVED"
    const val SCAN_ROOT = "WG-SCAN-ROOT"
    const val SCAN_FILE_SKIPPED = "WG-FILE-SKIPPED"
    const val SCAN_FILE_READ = "WG-FILE-READ"
}
