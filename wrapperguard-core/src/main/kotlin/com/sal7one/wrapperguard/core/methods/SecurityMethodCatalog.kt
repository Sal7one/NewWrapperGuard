package com.sal7one.wrapperguard.core.methods

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Descriptive groups, independent from provider permissions and enforcement policy. */
@Serializable
enum class MethodCategory { BUILT_IN, SAST, SUPPLY_CHAIN, DAST, RESEARCH, AI }
@Serializable
enum class MethodAvailability { BUILT_IN, OPTIONAL_TOOL, REPORT_IMPORT, EXTERNAL_MODEL }
@Serializable
enum class MethodMaturity { STABLE, EXPERIMENTAL, EXTERNAL }

@Serializable
data class SecurityMethod(
    val id: String,
    val title: String,
    val summary: String,
    val category: MethodCategory,
    val defaultEnabled: Boolean,
    val providerId: String? = null,
    val availability: MethodAvailability,
    val maturity: MethodMaturity = MethodMaturity.STABLE
)

object SecurityMethodIds {
    const val WRAPPER_INTEGRITY = "builtin.wrapper-integrity"
    const val SCRIPT_RULES = "builtin.script-rules"
    const val BUILD_RULES = "builtin.build-rules"
    const val CATALOG_RULES = "builtin.catalog-rules"
    const val SCRIPT_GRAPH = "builtin.script-graph"
    const val MAVEN_RULES = "builtin.maven-rules"
    const val SCRIPT_SEMANTICS = "research.script-semantics"
    const val EXTERNAL_MODEL = "ai.external-model"
}

/**
 * Portable method identities, mirrored in catalog/security-methods.json.
 * Enabling an optional catalog entry never installs or runs a tool, grants capabilities,
 * or claims that a research model is available. External providers remain user registered.
 */
object SecurityMethodCatalog {
    @Serializable
    private data class CatalogDocument(val schemaVersion: Int, val methods: List<SecurityMethod>)

    private val JSON = Json { ignoreUnknownKeys = true }

    val methods: List<SecurityMethod> by lazy {
        val text = checkNotNull(SecurityMethodCatalog::class.java.getResourceAsStream("/security-methods.json")) {
            "Security method catalog is missing from the application."
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        val document = JSON.decodeFromString<CatalogDocument>(text)
        require(document.schemaVersion == 1 && document.methods.map { it.id }.distinct().size == document.methods.size)
        document.methods
    }

    val builtInMethods: List<SecurityMethod> get() = methods.filter { it.category == MethodCategory.BUILT_IN }
    fun find(id: String): SecurityMethod? = methods.firstOrNull { it.id == id }
    fun isEnabled(id: String, flags: Map<String, Boolean>): Boolean = flags[id] ?: find(id)?.defaultEnabled ?: false
}
