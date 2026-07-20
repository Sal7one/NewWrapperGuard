package com.sal7one.wrapperguard.intellij

import com.intellij.openapi.components.Service
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

    fun scan(): ScanReport? {
        val root = project.basePath?.let(Path::of) ?: return null
        return RepositoryScanner().scan(root, ScanPolicy(mode = EnforcementMode.WARN)).also {
            report = it
            listeners.forEach { listener -> listener(it) }
        }
    }

    fun addListener(listener: (ScanReport?) -> Unit) {
        listeners += listener
        listener(report)
    }

    fun removeListener(listener: (ScanReport?) -> Unit) {
        listeners -= listener
    }
}
