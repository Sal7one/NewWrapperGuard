package com.sal7one.wrapperguard.intellij.settings

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

internal fun <T> methodProgress(project: Project, title: String, work: (ProgressIndicator) -> T): Result<T> {
    var result: Result<T>? = null
    ProgressManager.getInstance().run(object : Task.Modal(project, title, true) {
        override fun run(indicator: ProgressIndicator) { result = runCatching { work(indicator) } }
    })
    return result ?: Result.failure(IllegalStateException("Setup was cancelled."))
}

/** Explicit UI bootstrap: trusted Python + bundled wheel, no repository install hooks. */
internal object OptionalSdkInstaller {
    private const val WHEEL = "wrapperguard_sdk-0.4.0-py3-none-any.whl"
    fun install(project: Project, basePython: String, bandit: Boolean, semgrep: Boolean, indicator: ProgressIndicator): Path = ManagedStorage.withUse {
        installEnvironment(project, basePython, bandit, semgrep, indicator)
    }

    private fun installEnvironment(project: Project, basePython: String, bandit: Boolean, semgrep: Boolean, indicator: ProgressIndicator): Path {
        val python = OptionalMethodProfiles.checkedPath(basePython, OptionalMethodConnections.roots(project), executable = true)
        val directory = OptionalMethodConnections.directory().resolve("sdk-${UUID.randomUUID()}")
        Files.createDirectories(directory.parent)
        require(OptionalMethodConnections.roots(project).none { directory.parent.toRealPath().startsWith(it.toRealPath()) })
        indicator.text = "Checking Python 3.10 or newer"
        run(listOf(python.toString(), "-I", "-c", "import sys; raise SystemExit(0 if sys.version_info >= (3,10) else 1)"), indicator, 15)
        indicator.text = "Creating a dedicated SDK environment"
        run(listOf(python.toString(), "-I", "-m", "venv", "--copies", directory.toString()), indicator, 120)
        val managedPython = directory.resolve(if (System.getProperty("os.name").startsWith("Windows")) "Scripts/python.exe" else "bin/python")
        val wheel = directory.resolve(WHEEL)
        val resource = OptionalSdkInstaller::class.java.getResourceAsStream("/integrations/$WHEEL")
            ?: throw IllegalStateException("This plugin package is missing its SDK wheel. Install a complete WrapperGuard build.")
        resource.use { Files.copy(it, wheel) }
        indicator.text = "Installing the bundled WrapperGuard SDK (offline)"
        run(listOf(managedPython.toString(), "-I", "-m", "pip", "--isolated", "install", "--no-index", "--no-deps", "--force-reinstall", "--disable-pip-version-check", wheel.toString()), indicator, 120)
        Files.deleteIfExists(wheel)
        if (bandit) {
            indicator.text = "Downloading Bandit 1.9.4 and its dependencies from PyPI"
            run(listOf(managedPython.toString(), "-I", "-m", "pip", "--isolated", "install", "--disable-pip-version-check", "--index-url", "https://pypi.org/simple", "bandit==1.9.4"), indicator, 180)
        }
        if (semgrep) {
            indicator.text = "Downloading Semgrep 1.181.0 and its dependencies from PyPI"
            run(listOf(managedPython.toString(), "-I", "-m", "pip", "--isolated", "install", "--disable-pip-version-check", "--index-url", "https://pypi.org/simple", "semgrep==1.181.0"), indicator, 300)
        }
        OptionalSdkCapabilities.probe(managedPython.toString()).requireCurrent()
        ManagedStorage.registerCreated(directory, "SDK environment")
        WrapperGuardSettings.getInstance().state.managedSdkPython = managedPython.toString()
        OptionalMethodConnections.changed()
        return managedPython
    }

    fun repair(project: Project, selectedPython: String, bandit: Boolean, semgrep: Boolean, indicator: ProgressIndicator): Path = ManagedStorage.withMaintenance {
        val python = OptionalMethodProfiles.checkedPath(selectedPython, OptionalMethodConnections.roots(project), executable = true)
        val environment = managedEnvironment(python, OptionalMethodConnections.directory().toRealPath(), WrapperGuardSettings.getInstance().state.managedArtifacts)
        val wheel = Files.createTempFile(environment, "wrapperguard-repair-", ".whl")
        // pip validates the distribution filename, so preserve the bundled wheel name in a temporary subdirectory.
        Files.delete(wheel)
        val staging = Files.createTempDirectory(environment, "wrapperguard-repair-")
        val archive = staging.resolve(WHEEL)
        try {
            OptionalSdkInstaller::class.java.getResourceAsStream("/integrations/$WHEEL")?.use { Files.copy(it, archive) }
                ?: error("This plugin package is missing its bundled SDK wheel.")
            indicator.text = "Repairing the bundled SDK offline in the selected managed environment"
            run(listOf(python.toString(), "-I", "-m", "pip", "--isolated", "install", "--no-index", "--no-deps", "--force-reinstall", "--disable-pip-version-check", archive.toString()), indicator, 120)
            if (bandit) {
                indicator.text = "Updating managed Bandit from PyPI"
                run(listOf(python.toString(), "-I", "-m", "pip", "--isolated", "install", "--upgrade", "--disable-pip-version-check", "--index-url", "https://pypi.org/simple", "bandit==1.9.4"), indicator, 180)
            }
            if (semgrep) {
                require(!System.getProperty("os.name").startsWith("Windows")) { "Semgrep direct execution is unavailable on Windows; import its report instead." }
                indicator.text = "Updating managed Semgrep from PyPI"
                run(listOf(python.toString(), "-I", "-m", "pip", "--isolated", "install", "--upgrade", "--disable-pip-version-check", "--index-url", "https://pypi.org/simple", "semgrep==1.181.0"), indicator, 300)
            }
            OptionalSdkCapabilities.probe(python.toString()).requireCurrent()
        } finally {
            Files.deleteIfExists(archive); Files.deleteIfExists(staging)
            runCatching { ManagedStorage.refreshOwned(environment) }
            OptionalMethodConnections.changed()
        }
        python
    }

    internal fun managedEnvironment(python: Path, root: Path, records: List<ManagedArtifactState>): Path {
        val selected = python.toAbsolutePath().normalize()
        val environment = selected.parent?.parent ?: error("Choose a managed SDK Python executable.")
        require(environment.parent == root && !Files.isSymbolicLink(environment) && environment.toRealPath() == environment &&
            records.any { it.kind == "SDK environment" && it.path == environment.toString() }) { "Repair is limited to registered environments created by WrapperGuard. Create a new environment for other Python installations." }
        require(selected.parent.fileName.toString() in setOf("bin", "Scripts") && selected.fileName.toString() in setOf("python", "python.exe") && Files.isRegularFile(selected) && !Files.isSymbolicLink(selected)) { "Choose the Python executable in the registered managed environment." }
        return environment
    }

    private fun run(arguments: List<String>, indicator: ProgressIndicator, seconds: Int) {
        indicator.checkCanceled()
        val process = ProcessBuilder(arguments).directory(OptionalMethodConnections.directory().toFile()).redirectErrorStream(true).start()
        val reader = Thread { runCatching { process.inputStream.use { input ->
            val buffer = ByteArray(4096); while (input.read(buffer) >= 0) { /* discard bounded progress output; never echo command environment */ }
        } } }.apply { isDaemon = true; start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                indicator.checkCanceled()
                check(System.nanoTime() < deadline) { "Installation timed out. Check the selected Python and try again." }
            }
            check(process.exitValue() == 0) { "Installation failed. Check that Python includes venv/ensurepip. Bandit downloads also require access to PyPI." }
        } finally {
            process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); reader.join(1000)
        }
    }
}
