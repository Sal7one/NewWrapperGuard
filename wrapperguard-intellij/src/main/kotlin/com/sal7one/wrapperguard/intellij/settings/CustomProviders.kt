package com.sal7one.wrapperguard.intellij.settings

import com.sal7one.wrapperguard.core.inputs.ProviderInputScope
import com.sal7one.wrapperguard.core.methods.*
import com.sal7one.wrapperguard.core.vetting.*
import java.nio.file.Path

/** User-owned adapter registration, independent of the first-party method catalog. */
data class CustomProviderState(
    var id: String = "",
    var displayName: String = "",
    var version: String = "1.0.0",
    var kind: String = "command",
    var executable: String = "",
    var arguments: MutableList<String> = mutableListOf(),
    var category: String = "OTHER",
    var summary: String = "",
    var tags: MutableList<String> = mutableListOf(),
    var scopeDeclared: Boolean = false,
    var includes: MutableList<String> = mutableListOf(),
    var excludes: MutableList<String> = mutableListOf(),
    var externalFiles: MutableList<String> = mutableListOf(),
    var projectRoots: MutableList<String> = mutableListOf()
) {
    fun inputScope(): ProviderInputScope? = if (!scopeDeclared) null else ProviderInputScope(includes.toList(), excludes.toList(), externalFiles.toList())
    fun descriptor() = ProviderDescriptor(id, displayName, version, category = category, summary = summary.ifBlank { null },
        tags = tags.toList(), inputScope = inputScope())
    fun command(permissions: ProviderPermissions) = CommandProviderConfiguration(id, displayName, version,
        listOf(executable) + arguments, permissions, category = category, summary = summary.ifBlank { null },
        tags = tags.toList(), inputScope = inputScope(), projectRoots = projectRoots.toList())
}

internal object CustomProviders {
    fun validate(input: CustomProviderState, roots: List<Path>, requireReady: Boolean = true) {
        require(input.kind in setOf("command", "sdk")) { "Choose a command adapter or an installed IDE SDK." }
        require(input.id != "wrapperguard") { "The built-in provider identity is reserved." }
        require(input.id.matches(Regex("[a-z][a-z0-9._-]{0,79}"))) { "Use a stable lowercase provider ID, for example company.security." }
        require(input.displayName.isNotBlank() && input.displayName.length <= 160 && input.displayName.none { it.isISOControl() }) { "Enter a provider name of at most 160 characters." }
        require(input.version.isNotBlank() && input.version.length <= 80 && input.version.none { it.isISOControl() }) { "Enter a version of at most 80 characters." }
        require(input.category.matches(Regex("[A-Z][A-Z0-9_]{0,31}")) && input.category != "BUILT_IN") { "Choose an uppercase category. BUILT_IN is reserved." }
        require(input.summary.length <= 512 && input.summary.none { it.isISOControl() }) { "Use a description of at most 512 characters." }
        require(input.tags.size <= 16 && input.tags.all { it.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}")) }) { "Use up to 16 lowercase tags, without spaces." }
        if (input.kind == "command") {
            if (requireReady) OptionalMethodProfiles.checkedPath(input.executable, roots, executable = true)
            else storedPath(input.executable, roots)
            input.command(ProviderPermissions(enabled = false)).validate(requireExecutable = requireReady)
        }
        require(input.projectRoots.size <= 32 && input.projectRoots.distinct().size == input.projectRoots.size) { "Choose up to 32 distinct project roots." }
        input.projectRoots.forEach { require(Path.of(it).isAbsolute && (!requireReady || java.nio.file.Files.isDirectory(Path.of(it)))) { "Choose an existing absolute project directory." } }
        input.descriptor().validateMetadata()
        if (input.scopeDeclared) input.externalFiles.forEach { if (requireReady) OptionalMethodProfiles.checkedPath(it, roots) else storedPath(it, roots) }
    }

    /** Disabled registrations can be repaired after a tool/report was removed; no software is run. */
    private fun storedPath(value: String, roots: List<Path>) {
        val selected = Path.of(value)
        require(selected.isAbsolute) { "Keep an absolute installed path, or choose a replacement." }
        val normalized = selected.toAbsolutePath().normalize()
        var existing = normalized
        while (!java.nio.file.Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: error("Invalid installed path.")
        val resolved = existing.toRealPath()
        require(roots.none { root -> normalized.startsWith(root.toAbsolutePath().normalize()) || resolved.startsWith(root.toRealPath()) }) { "Installed paths must remain outside all open projects." }
    }

    fun save(input: CustomProviderState, roots: List<Path>, previousId: String? = null) {
        val settings = WrapperGuardSettings.getInstance()
        val requireReady = previousId == null || settings.permissions(input.id).enabled
        validate(input, roots, requireReady = requireReady)
        fun canonicalOrStored(value: String) = runCatching { Path.of(value).toRealPath().toString() }.getOrDefault(Path.of(value).toAbsolutePath().normalize().toString())
        val normalized = input.copy(projectRoots = input.projectRoots.map(::canonicalOrStored).distinct().toMutableList(), externalFiles = (if (input.scopeDeclared) input.externalFiles.map(::canonicalOrStored) else input.externalFiles).toMutableList())
        require(previousId == null || previousId == input.id) { "Provider IDs are stable. Add a separate provider to use a new ID." }
        val state = settings.state
        require(state.customProviders.none { it.id == input.id && it.id != previousId }) { "This custom provider ID already exists." }
        require(state.methodConnections.none { SecurityMethodCatalog.find(it.methodId)?.providerId == input.id }) { "This ID already belongs to a managed method connection. Configure it from that method card." }
        require(state.customProviders.size - (if (previousId != null) 1 else 0) + state.methodConnections.size < 32) { "At most 32 managed provider connections may be configured." }
        state.customProviders = (state.customProviders.filterNot { it.id == previousId } + normalized).toMutableList()
        if (state.providers.none { it.id == input.id }) state.providers.add(WrapperGuardSettings.ProviderState(
            id = input.id, enabled = false, actions = mutableListOf(VettingAction.WARNING.name), timeoutMs = 45_000))
        try { OptionalMethodConnections.refreshGeneratedConfiguration() } finally { OptionalMethodConnections.changed() }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val state = WrapperGuardSettings.getInstance().state
        require(state.customProviders.any { it.id == id }) { "The provider connection was removed." }
        state.providers.firstOrNull { it.id == id }?.enabled = enabled
        try { OptionalMethodConnections.refreshGeneratedConfiguration() } finally { OptionalMethodConnections.changed() }
    }

    fun appliesTo(provider: CustomProviderState, root: Path): Boolean = provider.projectRoots.isEmpty() || provider.projectRoots.any {
        runCatching { Path.of(it).toRealPath() == root.toRealPath() }.getOrDefault(false)
    }

    fun method(provider: CustomProviderState) = SecurityMethod("custom.${provider.id}", provider.displayName, provider.summary,
        runCatching { MethodCategory.valueOf(provider.category) }.getOrDefault(MethodCategory.SAST), false, provider.id,
        MethodAvailability.OPTIONAL_TOOL)

    fun unavailable(provider: CustomProviderState): VettingProvider = object : VettingProvider {
        override val descriptor = provider.descriptor()
        override fun scan(request: VettingRequest): ProviderResult = error("The selected SDK or adapter is unavailable.")
    }

    fun overlay(provider: VettingProvider, metadata: CustomProviderState): VettingProvider = object : VettingProvider {
        override val descriptor = provider.descriptor.copyWithMetadata(category = metadata.category,
            summary = metadata.summary.ifBlank { provider.descriptor.summary },
            tags = metadata.tags.ifEmpty { provider.descriptor.tags }.toList(),
            inputScope = metadata.inputScope() ?: provider.descriptor.inputScope?.snapshot())
        override fun scan(request: VettingRequest): ProviderResult = provider.scan(request)
    }

    fun remove(id: String) {
        val state = WrapperGuardSettings.getInstance().state
        state.customProviders = state.customProviders.filterNot { it.id == id }.toMutableList()
        state.providers.firstOrNull { it.id == id }?.enabled = false
        // Keep reviewed grants for reinstalling a provider; removal never deletes user executables or reports.
        try { OptionalMethodConnections.refreshGeneratedConfiguration() } finally { OptionalMethodConnections.changed() }
    }
}
