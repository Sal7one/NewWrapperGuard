package com.sal7one.wrapperguard.intellij

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.policy.EnforcementMode
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.rules.RepositoryScanner
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class WrapperGuardProjectService(private val project: Project) {
    private val listeners = CopyOnWriteArrayList<(ScanReport?) -> Unit>()

    @Volatile
    var report: ScanReport? = null
        private set

    /**
     * Runs a scan synchronously on the calling thread. Intended for background tasks.
     * UI callers should prefer [scanAsync], which runs on a [Task.Backgroundable].
     */
    fun scan(): ScanReport? {
        val root = project.basePath?.let(Path::of) ?: return null
        return RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.WARN)).also {
            report = it
            listeners.forEach { listener -> listener(it) }
        }
    }

    /**
     * Runs a scan in the background (off the EDT). When it finishes, the latest
     * [report] is published to all listeners — including the Compose UI, which
     * observes it and re-renders.
     */
    fun scanAsync() {
        object : Task.Backgroundable(project, "WrapperGuard: Scanning project", false) {
            override fun run(indicator: ProgressIndicator) {
                scan()
            }
        }.queue()
    }

    fun addListener(listener: (ScanReport?) -> Unit) {
        listeners += listener
        listener(report)
    }

    fun removeListener(listener: (ScanReport?) -> Unit) {
        listeners -= listener
    }
}
