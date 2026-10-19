package com.sal7one.wrapperguard.core.vetting

import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.policy.ScanPolicy
import com.sal7one.wrapperguard.core.policy.ScanSettings
import java.nio.file.Path
import kotlinx.serialization.Serializable

/** Shared host capabilities. Implementations choose their own detection technology. */
@Serializable
enum class VettingAction { WARNING, MAJOR_WARNING, ALERT, BLOCK_BUILD, BLOCK_SYNC }

@Serializable
enum class VettingOperation { SCAN, BUILD, SYNC }

@Serializable
data class ProviderDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val apiVersion: Int = 1
)

/**
 * Grants are supplied by the user/host, never by repository or provider output.
 * Required failures/incomplete results can block only with a matching block grant and
 * host Enforce policy. Timeout (100..300000 ms) includes time in the worker queue.
 */
@Serializable
data class ProviderPermissions(
    val enabled: Boolean = true,
    val actions: Set<VettingAction> = emptySet(),
    val required: Boolean = false,
    val timeoutMs: Long = 30_000
)

data class VettingRequest(
    val projectRoot: Path,
    val policy: ScanPolicy,
    val settings: ScanSettings,
    val baseline: List<BaselineEntry> = emptyList(),
    val operation: VettingOperation = VettingOperation.SCAN
)

/**
 * An explicitly registered local SDK. Implementations must cooperate with interruption.
 * In-process SDKs execute trusted code; the host permissions constrain their report actions,
 * not filesystem/network access. A separate process allows termination but is not a security
 * sandbox; install only trusted providers.
 */
interface VettingProvider {
    val descriptor: ProviderDescriptor
    fun scan(request: VettingRequest): ProviderResult
}

@Serializable
data class ProviderResult(
    val findings: List<Finding> = emptyList(),
    val edges: List<ScriptEdge> = emptyList(),
    val scannedFiles: Int = 0,
    val complete: Boolean = true,
    val notices: List<ScanNotice> = emptyList()
)

data class ProviderRegistration(
    val provider: VettingProvider,
    val permissions: ProviderPermissions
)

@Serializable
enum class ProviderRunStatus { COMPLETED, FAILED, TIMED_OUT, DISABLED }

@Serializable
data class ProviderRun(
    val descriptor: ProviderDescriptor,
    val permissions: ProviderPermissions,
    val status: ProviderRunStatus,
    val durationMs: Long = 0,
    val findingCount: Int = 0,
    val message: String? = null
)
