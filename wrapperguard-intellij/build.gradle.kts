import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.kotlin.plugin.compose")
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    implementation(project(":wrapperguard-core"))
    implementation(project(":wrapperguard-rules"))

    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1")

        bundledPlugin("com.intellij.gradle")

        // Compose + Jewel are bundled with the IntelliJ Platform since 2025.1 (build 251).
        // Consume them as platform-provided modules rather than standalone Maven artifacts.
        // See https://github.com/rock3r/jewel-ijp-template for the canonical setup.
        bundledModule("intellij.platform.compose")
        bundledModule("intellij.platform.jewel.foundation")
        bundledModule("intellij.platform.jewel.ui")
        bundledModule("intellij.platform.jewel.ideLafBridge")

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.sal7one.wrapperguard.next"
        name = "WrapperGuard"
        version = project.version.toString()
        vendor {
            name = "sal7one"
            url = "https://github.com/sal7one/wrapperguard"
        }
        description = "Static analysis for wrappers, build files, and bootstrap scripts. Project code is never executed."
        ideaVersion {
            sinceBuild = "251"
        }
    }
}

kotlin { jvmToolchain(21) }
