package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.swing.*
import kotlinx.serialization.json.*
import javax.swing.table.DefaultTableModel
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Registration occurs only at creation of a new UUID artifact; existing files are never adopted. */
data class ManagedArtifactState(var path: String = "", var kind: String = "", var fingerprint: String = "")
internal data class ManagedArtifact(val path: Path, val kind: String, val bytes: Long, val status: String, val removable: Boolean)
internal data class ArtifactFingerprint(val digest: String, val bytes: Long)

internal object ManagedStorage {
    private val usage = ReentrantReadWriteLock()
    fun <T> withUse(work: () -> T): T = usage.read(work)
    fun <T> withMaintenance(work: () -> T): T {
        require(usage.writeLock().tryLock()) { "An analysis or setup operation is running. Wait for it to finish before maintenance." }
        try { return work() } finally { usage.writeLock().unlock() }
    }
    fun refreshOwned(path: Path) {
        val actual = path.toAbsolutePath().normalize()
        val state = WrapperGuardSettings.getInstance().state
        val record = state.managedArtifacts.firstOrNull { it.path == actual.toString() } ?: error("Unregistered artifacts are preserved.")
        require(actual.parent == OptionalMethodConnections.directory().toRealPath() && !Files.isSymbolicLink(actual))
        record.fingerprint = fingerprint(actual).digest
    }

    fun registerCreated(path: Path, kind: String) {
        val root = OptionalMethodConnections.directory().toRealPath()
        val actual = path.toAbsolutePath().normalize()
        require(actual.parent == root && kind in setOf("SDK environment", "Method profile", "AI input", "Tool cache"))
        val fingerprint = runCatching { fingerprint(actual) }.getOrNull() ?: return // Preserve artifacts exceeding inventory bounds.
        val state = WrapperGuardSettings.getInstance().state
        synchronized(state) {
            if (state.managedArtifacts.size < 1024 && state.managedArtifacts.none { it.path == actual.toString() })
                state.managedArtifacts.add(ManagedArtifactState(actual.toString(), kind, fingerprint.digest))
        }
    }

    /** Original reports, selected executables and active environments are retained regardless of provider enablement. */
    internal fun activePaths(state: WrapperGuardSettings.State): List<Path> {
        val values = mutableListOf(state.managedSdkPython, state.providerConfigurationPath, state.managedProviderConfigurationPath)
        state.methodConnections.forEach { values.addAll(listOf(it.pythonExecutable, it.runnerExecutable, it.modulePath, it.rulesPath, it.reportPath, it.provenanceManifestPath, it.profilePath, it.configurationPath, it.cachePath)) }
        state.customProviders.forEach { values.addAll(listOf(it.executable) + it.arguments + it.externalFiles) }
        // Preserve references in the original user configuration as well as GUI connections.
        if (state.providerConfigurationPath.isNotBlank()) runCatching {
            val root = ProjectManager.getInstance().openProjects.firstNotNullOfOrNull { it.basePath?.let(Path::of) } ?: return@runCatching
            com.sal7one.wrapperguard.core.vetting.ProviderConfiguration.load(Path.of(state.providerConfigurationPath), root).providers.forEach {
                values.addAll(it.command); values.addAll(it.inputScope?.externalFiles.orEmpty())
            }
        }.onFailure { throw IllegalStateException("The original provider configuration must be readable before cleanup.") }
        // Resolve references inside bounded, explicitly selected SDK profiles without interpreting commands.
        val profilePaths = state.methodConnections.map { it.profilePath } + values.filter { it.endsWith(".profile.json") || it.endsWith(".json") }
        var preserveEnvironments = false
        profilePaths.filter(String::isNotBlank).distinct().forEach { value ->
            runCatching {
                val file = Path.of(value)
                if (!file.isAbsolute || !Files.isRegularFile(file) || Files.size(file) > 65_536) return@runCatching
                var visited = 0
                fun strings(element: JsonElement, depth: Int = 0) {
                    require(++visited <= 10_000 && depth <= 32)
                    when (element) {
                        is JsonObject -> element.values.forEach { strings(it, depth + 1) }
                        is JsonArray -> element.forEach { strings(it, depth + 1) }
                        is JsonPrimitive -> if (element.isString) values.add(element.content)
                    }
                }
                strings(Json.parseToJsonElement(Files.readString(file)))
            }.onFailure { preserveEnvironments = true }
        }
        // SDK private configuration cannot be inferred by the host. Preserve every managed environment while an undeclared custom provider is enabled.
        if (state.customProviders.any { custom -> state.providers.any { it.id == custom.id && it.enabled } && (custom.kind == "sdk" || !custom.scopeDeclared) }) preserveEnvironments = true
        runCatching {
            ProcessHandle.allProcesses().use { processes -> processes.limit(10_001).forEach { process -> process.info().command().ifPresent(values::add) } }
        }.onFailure { preserveEnvironments = true }
        if (preserveEnvironments) values.addAll(state.managedArtifacts.filter { it.kind == "SDK environment" }.map { it.path })
        return values.filter(String::isNotBlank).mapNotNull { value -> runCatching { Path.of(value).takeIf(Path::isAbsolute)?.toAbsolutePath()?.normalize() }.getOrNull() }
            .flatMap { listOf(it, runCatching { it.toRealPath() }.getOrDefault(it)) }.distinct()
    }

    fun inventory(): List<ManagedArtifact> = usage.read {
        val root = OptionalMethodConnections.directory()
        if (!Files.isDirectory(root)) return@read emptyList()
        val state = WrapperGuardSettings.getInstance().state
        val active = activePaths(state)
        val records = synchronized(state) { state.managedArtifacts.map { it.copy() } }
        inventory(root.toRealPath(), records, active)
    }

    internal fun inventory(root: Path, records: List<ManagedArtifactState>, active: List<Path>): List<ManagedArtifact> {
        Files.newDirectoryStream(root).use { entries ->
            return entries.take(1025).also { require(it.size <= 1024) { "Managed storage inventory exceeds its item limit." } }.map { path ->
                val record = records.firstOrNull { it.path == path.toAbsolutePath().normalize().toString() }
                val referenced = active.any { it == path || it.startsWith(path) }
                val current = if (record == null) null else runCatching { fingerprint(path) }.getOrNull()
                val owned = record != null && current != null && record.fingerprint == current.digest
                ManagedArtifact(path, record?.kind ?: "Unregistered file", current?.bytes ?: 0, when {
                    referenced -> "In use · preserved"
                    record == null -> "Existing / unregistered · preserved"
                    !owned -> "Changed or exceeds limits · preserved"
                    else -> "Owned orphan · can remove"
                }, !referenced && owned)
            }.sortedWith(compareByDescending<ManagedArtifact> { it.removable }.thenBy { it.path.fileName.toString() })
        }
    }

    fun clearToolCaches(): Int = withMaintenance {
        val state = WrapperGuardSettings.getInstance().state
        require(state.providerConfigurationPath.isBlank() && state.customProviders.none { custom -> state.providers.any { it.id == custom.id && it.enabled } && (custom.kind == "sdk" || !custom.scopeDeclared) }) { "Private or original provider configuration may reference managed caches. Preserve them until those connections are disconnected." }
        val root = OptionalMethodConnections.directory().toRealPath()
        val records = state.managedArtifacts.filter { it.kind == "Tool cache" }
        require(records.isNotEmpty()) { "No registered tool caches exist. Legacy or original cache directories are preserved." }
        val active = activePaths(state)
        records.forEach { record ->
            val path = Path.of(record.path)
            require(path.parent == root && path.fileName.toString().startsWith("cache-") && !Files.isSymbolicLink(path))
            require(active.none { it != path && it.startsWith(path) }) { "A selected report, file, SDK or process references a cache item; it was preserved." }
            require(state.customProviders.none { custom -> (listOf(custom.executable) + custom.arguments + custom.externalFiles).any { runCatching { Path.of(it).toAbsolutePath().normalize() == path }.getOrDefault(false) } }) { "A custom provider references this cache; disconnect it before cleanup." }
            record.fingerprint = clearCache(root, record, active.filterNot { it == path })
        }
        records.size
    }

    internal fun clearCache(root: Path, record: ManagedArtifactState, protected: List<Path>): String {
        val path = Path.of(record.path).toAbsolutePath().normalize()
        require(record.kind == "Tool cache" && path.parent == root && path.fileName.toString().matches(Regex("cache-[0-9a-f-]{36}")) && !Files.isSymbolicLink(path)) { "Only registered UUID tool caches may be cleared." }
        require(protected.none { it == path || it.startsWith(path) }) { "Referenced user files or processes inside this cache are preserved." }
        val snapshot = record.copy(fingerprint = fingerprint(path).digest)
        removeOwned(root, snapshot, protected)
        Files.createDirectory(path)
        return fingerprint(path).digest
    }

    fun removeUnusedDefaultSdk() = withMaintenance {
        val state = WrapperGuardSettings.getInstance().state
        require(state.managedSdkPython.isNotBlank()) { "There is no default managed SDK environment." }
        val root = OptionalMethodConnections.directory().toRealPath()
        val environment = OptionalSdkInstaller.managedEnvironment(Path.of(state.managedSdkPython), root, state.managedArtifacts)
        val record = state.managedArtifacts.first { it.path == environment.toString() }
        val active = activePaths(state.copy(managedSdkPython = ""))
        require(active.none { it == environment || it.startsWith(environment) }) { "This environment is referenced by a saved connection or process. Disconnect those connections and stop its tools before removal." }
        removeOwned(root, record, active)
        state.managedSdkPython = ""; state.managedArtifacts.removeAll { it.path == record.path }
        OptionalMethodConnections.changed()
    }

    fun cleanup(paths: Set<Path>): Int {
        require(usage.writeLock().tryLock()) { "An analysis or setup operation is running. Wait for it to finish before cleanup." }
        try {
            val root = OptionalMethodConnections.directory().toRealPath()
            val state = WrapperGuardSettings.getInstance().state
            val candidates = inventory(root, state.managedArtifacts.map { it.copy() }, activePaths(state)).filter { it.path in paths }
            require(candidates.size == paths.size && candidates.all { it.removable }) { "Some selected artifacts are now active or changed. Refresh the inventory." }
            candidates.forEach { artifact ->
                val record = state.managedArtifacts.first { it.path == artifact.path.toString() }
                removeOwned(root, record, activePaths(state))
                state.managedArtifacts.removeAll { it.path == record.path }
            }
            return candidates.size
        } finally { usage.writeLock().unlock() }
    }

    internal fun removeOwned(root: Path, record: ManagedArtifactState, active: List<Path>) {
        val path = Path.of(record.path).toAbsolutePath().normalize()
        require(path.parent == root && !Files.isSymbolicLink(path) && active.none { it == path || it.startsWith(path) }) { "Active or non-owned artifacts cannot be removed." }
        require(fingerprint(path).digest == record.fingerprint) { "Artifact changed after inventory; preserved." }
        val expected = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        Files.newDirectoryStream(root).use { parent ->
            require(parent is SecureDirectoryStream<Path>) { "This filesystem does not support safe managed cleanup; artifacts were preserved." }
            fun remove(directory: SecureDirectoryStream<Path>, name: Path, expectedKey: Any? = null) {
                val attributes = directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                require(expectedKey == null || expectedKey == attributes.fileKey()) { "Artifact changed after inventory; preserved." }
                if (attributes.isDirectory) {
                    directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { opened ->
                        require(opened.getFileAttributeView(BasicFileAttributeView::class.java).readAttributes().fileKey() == attributes.fileKey())
                        opened.toList().forEach { child -> remove(opened, child.fileName) }
                    }
                    directory.deleteDirectory(name)
                } else directory.deleteFile(name) // A link itself is deleted; its target is never traversed.
            }
            remove(parent, path.fileName, expected)
        }
    }

    internal fun fingerprint(path: Path): ArtifactFingerprint {
        require(!Files.isSymbolicLink(path))
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L; var entries = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun entry(file: Path, attrs: BasicFileAttributes) {
            require(++entries <= 50_000 && System.nanoTime() < deadline && file.nameCount - path.nameCount <= 64)
            require(attrs.fileKey() != null && (attrs.isRegularFile || attrs.isDirectory || attrs.isSymbolicLink))
            digest.update((path.relativize(file).toString() + "|" + attrs.fileKey() + "|" + attrs.size() + "|" + attrs.lastModifiedTime()).toByteArray())
            if (attrs.isSymbolicLink) { digest.update(Files.readSymbolicLink(file).toString().toByteArray()); return }
            if (attrs.isRegularFile) {
                require(attrs.size() <= 536_870_912 && bytes + attrs.size() <= 1_073_741_824)
                Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buffer = ByteArray(8192); var count = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break
                        count += read; bytes += read
                        require(count <= 536_870_912 && bytes <= 1_073_741_824 && System.nanoTime() < deadline)
                        digest.update(buffer, 0, read)
                    }
                }
                val after = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                require(attrs.fileKey() == after.fileKey() && attrs.size() == after.size() && attrs.lastModifiedTime() == after.lastModifiedTime())
            }
        }
        // Bound inventory before sorting; never follows symlinks into user-owned trees.
        val paths = mutableListOf<Path>()
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            fun add(file: Path) { require(paths.size < 50_000 && System.nanoTime() < deadline && file.nameCount - path.nameCount <= 64); paths.add(file) }
            override fun preVisitDirectory(directory: Path, attrs: BasicFileAttributes): FileVisitResult { add(directory); return FileVisitResult.CONTINUE }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { add(file); return FileVisitResult.CONTINUE }
        })
        paths.sorted().forEach { file -> entry(file, Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)) }
        return ArtifactFingerprint(digest.digest().joinToString("") { "%02x".format(it) }, bytes)
    }
}

internal class ManagedStorageDialog(private val project: Project, private val items: List<ManagedArtifact>) : DialogWrapper(project) {
    private val model = object : DefaultTableModel(arrayOf<Any>("Remove", "Artifact", "Type", "Size", "Status"), 0) {
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = column == 0 && items[row].removable
    }
    init {
        items.forEach { model.addRow(arrayOf<Any>(false, it.path.fileName.toString(), it.kind, "${it.bytes / 1024} KiB", it.status)) }
        title = "WrapperGuard managed storage"; setOKButtonText("Remove selected orphans"); init()
        isOKActionEnabled = items.any { it.removable }
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 12)).apply {
        add(JPanel(BorderLayout(0, 8)).apply {
            add(JBLabel("<html>Only registered artifacts created by WrapperGuard can be removed. Original user files and unknown artifacts are preserved.<br>Analysis/setup prevent maintenance. Tool-cache clearing is explicit and may require future database downloads.</html>"), BorderLayout.NORTH)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEADING)).apply {
                add(JButton("Install / repair SDK…").apply { addActionListener { InstallOptionalSdkDialog(project).showAndGet() } })
                add(JButton("Clear owned tool caches…").apply { addActionListener {
                    if (com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Clear only registered WrapperGuard tool caches? Original and legacy cache directories remain untouched. Vulnerability databases may download again on the next explicit scan.", "Clear owned caches", com.intellij.openapi.ui.Messages.getQuestionIcon()) != com.intellij.openapi.ui.Messages.YES) return@addActionListener
                    project.getService(NativeScanAutomation::class.java).pause("Paused · cache maintenance")
                    methodProgress(project, "Clearing owned tool caches") { ManagedStorage.clearToolCaches() }
                        .onSuccess { close(OK_EXIT_CODE) }.onFailure { setErrorText(it.message ?: "Caches were preserved.") }
                } })
                add(JButton("Remove unused default SDK…").apply { addActionListener {
                    if (com.intellij.openapi.ui.Messages.showYesNoDialog(project, "Remove the default managed SDK only if no saved connection or process references it? Disconnect method/custom connections first. Original Python installations are preserved.", "Remove unused managed SDK", com.intellij.openapi.ui.Messages.getQuestionIcon()) != com.intellij.openapi.ui.Messages.YES) return@addActionListener
                    project.getService(NativeScanAutomation::class.java).pause("Paused · SDK maintenance")
                    methodProgress(project, "Removing unused managed SDK") { ManagedStorage.removeUnusedDefaultSdk() }
                        .onSuccess { close(OK_EXIT_CODE) }.onFailure { setErrorText(it.message ?: "The environment was preserved.") }
                } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(JBTable(model).apply { accessibleContext.accessibleName = "Managed storage inventory"; columnModel.getColumn(0).preferredWidth = 52 }), BorderLayout.CENTER)
        preferredSize = Dimension(850, 390)
    }
    override fun doOKAction() {
        val selected = items.indices.filter { model.getValueAt(it, 0) == true }.map { items[it].path }.toSet()
        if (selected.isEmpty()) { setErrorText("Select an owned orphan to remove."); return }
        methodProgress(project, "Removing owned WrapperGuard artifacts") { ManagedStorage.cleanup(selected) }
            .onSuccess { super.doOKAction() }.onFailure { setErrorText(it.message ?: "Cleanup did not complete. Refresh the inventory.") }
    }
}
