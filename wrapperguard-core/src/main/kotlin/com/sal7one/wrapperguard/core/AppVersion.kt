package com.sal7one.wrapperguard.core

/** One build-owned identity shared by providers, CLI output and exported reports. */
object AppVersion {
    val current: String by lazy {
        requireNotNull(AppVersion::class.java.getResourceAsStream("/META-INF/wrapperguard/version.txt")) {
            "Packaged WrapperGuard version is missing"
        }.bufferedReader().use { it.readText().trim() }
    }
}
