package com.sal7one.wrapperguard.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

/**
 * Watches wrapper/build/script files for changes. A relevant change marks the current
 * report STALE immediately; the rescan itself is debounced by the service (3 s) to absorb
 * write bursts, and the service's content-hash comparison skips rescans for VFS-only noise
 * (timestamp refreshes, reverts to identical content).
 */
class WrapperGuardVfsListener(private val project: Project) : BulkFileListener {

    override fun before(events: List<VFileEvent>) = markRelevant(events)
    override fun after(events: List<VFileEvent>) = markRelevant(events)

    private fun markRelevant(events: List<VFileEvent>) {
        if (project.isDisposed) return
        val service = project.getService(WrapperGuardProjectService::class.java)
        if (events.any { service.isWatchedPath(it.path, it.file?.isDirectory == true) })
            service.markStaleAndScheduleRescan()
    }

    companion object {
        /** Subscribes this listener to VFS changes for the project's lifetime. */
        fun register(project: Project) {
            project.messageBus.connect(project).subscribe(
                VirtualFileManager.VFS_CHANGES,
                WrapperGuardVfsListener(project)
            )
        }
    }
}
