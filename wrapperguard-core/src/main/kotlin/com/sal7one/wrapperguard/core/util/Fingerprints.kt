package com.sal7one.wrapperguard.core.util

import java.security.MessageDigest

object Fingerprints {
    fun finding(ruleId: String, path: String, evidence: String): String {
        val normalized = "$ruleId\u0000${path.replace('\\', '/')}\u0000${evidence.trim()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
