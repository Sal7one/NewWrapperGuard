package com.sal7one.wrapperguard.rules

import com.sal7one.wrapperguard.core.methods.SecurityMethodIds
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.policy.ScanSettings
import com.sal7one.wrapperguard.core.spi.Rule
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Maps a scanned file to the list of [Rule]s that should analyze it, based on the file's
 * name and extension. Ports the routing logic from old_project's ScriptGraphScanner.analyzeFile.
 *
 * Routes:
 *  - gradlew / *.sh                 → Unix script rules
 *  - gradlew.bat / *.bat / *.cmd    → Windows script rules
 *  - *.ps1 / *.psm1                 → PowerShell script/module rules
 *  - gradle-wrapper.properties      → wrapper-properties rules
 *  - settings.gradle(.kts)          → build settings rules
 *  - init.gradle / init.gradle.kts  → build init rules
 *  - build.gradle                   → Groovy build rules
 *  - build.gradle.kts               → Kotlin build rules
 *  - libs.versions.toml             → version catalog rules
 *  - *.gradle / *.gradle.kts        → generic Gradle rules
 *  - otherwise                      → none (caller decides whether to still scan as text)
 */
object FileTypeRouter {

    fun rulesFor(path: Path): List<Rule> {
        val name = path.name
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return when {
            name == "gradlew" || extension == "sh" -> listOf(ScriptRules.unix())
            name == "gradlew.bat" || extension == "bat" || extension == "cmd" -> listOf(ScriptRules.windows())
            extension in setOf("ps1", "psm1") -> listOf(ScriptRules.powershell())
            name == "gradle-wrapper.properties" -> listOf(WrapperPropertiesRules)
            name == "settings.gradle" || name == "settings.gradle.kts" -> listOf(BuildScriptRules.settings())
            name.startsWith("init.") && (name.endsWith(".gradle") || name.endsWith(".gradle.kts")) -> listOf(BuildScriptRules.init())
            name == "build.gradle" -> listOf(BuildScriptRules.groovy())
            name == "build.gradle.kts" -> listOf(BuildScriptRules.kotlin())
            name == "libs.versions.toml" -> listOf(VersionCatalogRules)
            name == "pom.xml" -> listOf(MavenPomRules)
            extension == "gradle" || extension == "kts" -> listOf(BuildScriptRules.generic())
            // Non-routed script-like files (e.g. .bash/.zsh/.bashrc) fall back to Unix rules.
            extension in setOf("bash", "zsh", "bats") -> listOf(ScriptRules.unix())
            else -> emptyList()
        }
    }

    /** Gate before a rule runs; flags never filter findings after policy has been applied. */
    fun rulesFor(path: Path, settings: ScanSettings): List<Rule> =
        if (SecurityMethodCatalog.isEnabled(methodIdFor(path), settings.methodFlags)) rulesFor(path) else emptyList()

    fun methodIdFor(path: Path): String = when (path.name) {
        "gradle-wrapper.properties" -> SecurityMethodIds.WRAPPER_INTEGRITY
        "libs.versions.toml" -> SecurityMethodIds.CATALOG_RULES
        "pom.xml" -> SecurityMethodIds.MAVEN_RULES
        else -> if (path.name.endsWith(".gradle") || path.name.endsWith(".kts")) SecurityMethodIds.BUILD_RULES else SecurityMethodIds.SCRIPT_RULES
    }

    /** True if a path is interesting enough to scan at all. */
    fun isScannable(name: String): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return name in setOf("gradlew", "gradlew.bat", "gradle-wrapper.properties", "libs.versions.toml", "pom.xml") ||
            extension in setOf("sh", "bash", "zsh", "bats", "ps1", "psm1", "bat", "cmd", "gradle", "kts") ||
            name.startsWith("settings.gradle") || name.startsWith("build.gradle") || name.startsWith("init.gradle")
    }
}
