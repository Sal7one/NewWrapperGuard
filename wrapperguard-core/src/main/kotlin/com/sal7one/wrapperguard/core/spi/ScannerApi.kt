package com.sal7one.wrapperguard.core.spi

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import java.nio.file.Path


data class TextSource(
    val path: Path,
    val relativePath: String,
    val content: String
)

interface Rule {
    val id: String
    fun analyze(source: TextSource): List<Finding>
}

interface ScannerEngine {
    fun scan(projectRoot: Path, policy: ScanPolicy): ScanReport
}

fun interface MlScorer {
    fun scorePowerShell(source: TextSource): MlSignal?
}

object NoopMlScorer : MlScorer {
    override fun scorePowerShell(source: TextSource): MlSignal? = null
}
