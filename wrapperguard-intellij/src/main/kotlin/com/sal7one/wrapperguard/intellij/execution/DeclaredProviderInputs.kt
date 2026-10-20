package com.sal7one.wrapperguard.intellij.execution

import com.intellij.openapi.project.Project
import com.sal7one.wrapperguard.core.inputs.*
import com.sal7one.wrapperguard.core.methods.SecurityMethod
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.settings.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/** Host-known first-party input declarations; third-party declarations remain provider/user owned. */
internal object ManagedMethodInputScopes {
    private val excluded = listOf(".git", ".hg", ".svn", ".venv", "venv", "env", "node_modules", "__pycache__", "build", "dist", ".tox", ".nox")
        .map { "**/$it/**" }
    private val dependencyFiles = listOf("pom.xml", "gradle.lockfile", "verification-metadata.xml", "libs.versions.toml",
        "requirements.txt", "requirements-dev.txt", "poetry.lock", "Pipfile.lock", "pdm.lock", "uv.lock", "pyproject.toml",
        "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "go.mod", "go.sum",
        "Cargo.lock", "composer.lock", "Gemfile.lock", "packages.lock.json")

    fun canonical(value: String): String = runCatching { Path.of(value).toRealPath().toString() }
        .getOrElse { Path.of(value).toAbsolutePath().normalize().toString() }
    fun scope(method: SecurityMethod, connection: MethodConnectionState): ProviderInputScope {
        val includes = if (connection.mode != "runner") emptyList() else when (method.id) {
            "sast.bandit" -> listOf("**/*.py", "**/*.PY")
            "sast.semgrep" -> listOf("**/*.kt", "**/*.kts", "**/*.java", "**/*.KT", "**/*.KTS", "**/*.JAVA", "**/AndroidManifest.xml")
            "sast.powershell" -> listOf("**/*.ps1", "**/*.psm1", "**/*.psd1", "**/*.PS1", "**/*.PSM1", "**/*.PSD1")
            "research.container-python" -> listOf("**/*.py", "**/*.PY")
            "research.script-semantics" -> listOf("**/*.py", "**/*.ps1", "**/*.psm1", "**/*.PY", "**/*.PS1", "**/*.PSM1")
            "sca.trivy", "sca.osv" -> dependencyFiles.map { "**/$it" }
            else -> emptyList()
        }
        val files = listOf(connection.profilePath, connection.reportPath, connection.provenanceManifestPath,
            connection.rulesPath.takeIf { connection.mode == "runner" }.orEmpty(),
            connection.modulePath.takeIf { it.isNotBlank() && !Files.isDirectory(Path.of(it)) }.orEmpty())
            .filter(String::isNotBlank).map(::canonical).distinct()
        return ProviderInputScope(includes, if (includes.isEmpty()) emptyList() else excluded, files)
    }
}

internal data class DeclaredInputSnapshot(
    val scopes: List<ProviderInputScope>,
    val externalFiles: List<Path>,
    val toolFiles: List<Path>
)

/** Scope capture and trusted tool fingerprints are separate resource budgets. No tool is run here. */
internal object NativeProviderInputs {
    fun snapshot(registrations: List<ProviderRegistration>, state: WrapperGuardSettings.State, root: Path): DeclaredInputSnapshot {
        val enabled = registrations.filter { registration -> registration.permissions.enabled &&
            (registration.provider.descriptor.id == "wrapperguard" || SecurityMethodCatalog.methods.none { it.providerId == registration.provider.descriptor.id && state.methodFlags[it.id] == false }) }
        val ids = enabled.map { it.provider.descriptor.id }.toSet()
        val scopes = enabled.mapNotNull { it.provider.descriptor.inputScope?.snapshot() }
        val external = mutableSetOf<Path>()
        val tools = mutableSetOf<Path>()
        state.methodConnections.filter { connection -> enabled.any { it.provider.descriptor.id == SecurityMethodCatalog.find(connection.methodId)?.providerId && connectionIsSelected(it.provider.descriptor, connection) } &&
            (connection.projectRoot.isBlank() || runCatching { Path.of(connection.projectRoot).toRealPath() == root.toRealPath() }.getOrDefault(false)) }.forEach { connection ->
            listOf(connection.pythonExecutable, (if (connection.sandboxEnabled) connection.sandboxExecutable else connection.runnerExecutable).takeIf { connection.mode == "runner" }.orEmpty())
                .filter(String::isNotBlank).forEach { value ->
                    val selected = Path.of(value)
                    tools.add(selected.toRealPath())
                    packageMetadata(selected).forEach { external.add(it) }
                }
            if (connection.modulePath.isNotBlank() && connection.mode == "runner") external.addAll(regularFiles(Path.of(connection.modulePath)))
        }
        state.customProviders.filter { it.id in ids && it.kind == "command" && CustomProviders.appliesTo(it, root) }.forEach { custom ->
            tools.add(Path.of(custom.executable).toRealPath())
            // Absolute file argv values are explicitly supplied by the user, never repository discovery.
            custom.arguments.mapNotNull { runCatching { Path.of(it) }.getOrNull() }.filter { it.isAbsolute && Files.isRegularFile(it) && !it.toRealPath().startsWith(root.toRealPath()) }.forEach { tools.add(it.toRealPath()) }
        }
        return DeclaredInputSnapshot(scopes, external.toList(), tools.toList())
    }

    fun capture(root: Path, snapshot: DeclaredInputSnapshot, projectRoots: List<Path>, checkCancelled: () -> Unit = {}): Map<String, String> {
        val inputs = ProviderInputSealer.capture(root, snapshot.scopes, snapshot.externalFiles, projectRoots = projectRoots, checkCancelled = checkCancelled)
        val tools = ProviderInputSealer.capture(root, emptyList(), snapshot.toolFiles, projectRoots = projectRoots,
            limits = InputSealLimits(maxFiles = 256, maxExternalFileBytes = 536_870_912, maxTotalBytes = 1_073_741_824), checkCancelled = checkCancelled)
        return inputs.hashes + tools.hashes.mapKeys { (key, _) -> "tool:$key" }
    }

    /** Expand only an explicitly selected module directory; do not follow descendant links. */
    internal fun regularFiles(selected: Path): List<Path> {
        val root = selected.toRealPath()
        if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) return listOf(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "A selected module directory is unavailable." }
        val files = mutableListOf<Path>()
        var entries = 0
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            fun budget() { require(++entries <= 5_000 && System.nanoTime() < deadline) { "Selected module input inventory exceeds its bounds." } }
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult { budget(); return FileVisitResult.CONTINUE }
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                budget(); require(attributes.isRegularFile && !attributes.isSymbolicLink) { "Selected module inputs cannot contain symlinks or special files." }
                require(files.size < 256) { "Selected module input inventory exceeds its file limit." }; files.add(file)
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    /** Package metadata captures selected Python package versions, not all installed code. */
    private fun packageMetadata(executable: Path): List<Path> {
        val results = linkedSetOf<Path>()
        val roots = listOf(executable, executable.toRealPath()).mapNotNull { it.parent?.parent }.distinct()
        roots.forEach { environment ->
            val sites = mutableListOf(environment.resolve("Lib/site-packages"))
            val lib = environment.resolve("lib")
            if (Files.isDirectory(lib)) Files.newDirectoryStream(lib, "python*").use { versions ->
                versions.take(8).forEach { sites.add(it.resolve("site-packages")) }
            }
            sites.filter { Files.isDirectory(it) }.forEach { site ->
                listOf("wrapperguard_sdk-*.dist-info", "bandit-*.dist-info", "semgrep-*.dist-info").forEach { pattern ->
                    Files.newDirectoryStream(site, pattern).use { entries -> entries.take(8).forEach { entry ->
                        val metadata = entry.resolve("METADATA")
                        if (Files.isRegularFile(metadata)) results.add(metadata.toRealPath())
                    } }
                }
                val rules = site.resolve("wrapperguard_sdk/rules")
                if (Files.isDirectory(rules)) results.addAll(regularFiles(rules))
            }
        }
        return results.toList()
    }
}

/** Exact recursive script names are not globs, and provider output cannot opt into external files. */
internal fun observedProjectPaths(root: Path, values: List<String>): Set<Path> {
    if (values.isEmpty()) return emptySet()
    val canonical = root.toRealPath()
    require(values.size <= 10_000) { "Observed project input limit reached." }
    return values.mapNotNull { value ->
        val supplied = runCatching { Path.of(value) }.getOrNull() ?: return@mapNotNull null
        val candidate = (if (supplied.isAbsolute) supplied else canonical.resolve(supplied)).toAbsolutePath().normalize()
        candidate.takeIf { it != canonical && it.startsWith(canonical) }
    }.toSet()
}

/** Compiled declarations for cheap VFS batches; hashing remains off the EDT. */
internal class DeclaredInputWatcher(snapshot: DeclaredInputSnapshot = DeclaredInputSnapshot(emptyList(), emptyList(), emptyList())) {
    private val matchers = snapshot.scopes.map(ProviderInputSealer::projectPathMatcher)
    private val externals = (snapshot.externalFiles + snapshot.toolFiles + snapshot.scopes.flatMap { it.externalFiles }.map(Path::of))
        .map { it.toAbsolutePath().normalize() }.toSet()

    fun matches(root: Path, value: String, directory: Boolean, observed: Set<Path>): Boolean {
        val path = runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() ?: return false
        // Resolve a surviving parent for deleted files and /tmp aliases without reading source content.
        val realish = runCatching { path.toRealPath() }.getOrElse {
            runCatching { path.parent.toRealPath().resolve(path.fileName) }.getOrDefault(path)
        }
        if (path in externals || realish in externals || directory && externals.any { it.startsWith(path) || it.startsWith(realish) }) return true
        val roots = listOf(root.toAbsolutePath().normalize(), runCatching { root.toRealPath() }.getOrDefault(root))
        val anchor = roots.firstOrNull { path.startsWith(it) } ?: roots.firstOrNull { realish.startsWith(it) } ?: return false
        val local = if (path.startsWith(anchor)) path else realish
        if (directory) return true // A renamed/deleted directory may contain watched files.
        if (local in observed || realish in observed) return true
        val relative = anchor.relativize(local).toString().replace('\\', '/')
        return com.sal7one.wrapperguard.intellij.WrapperGuardProjectService.isWatchedFileName(local.fileName.toString()) || matchers.any { it(relative) }
    }
}
