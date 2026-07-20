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
        // Target 2025.3 for development; sinceBuild=252 keeps the plugin installable on 2025.2+,
        // which is the minimum platform version that exposes the com.intellij.modules.compose module.
        // NB: from 2025.3 the IntelliJ Platform Gradle Plugin requires intellijIdea() (IC/ideaIC
        // is no longer published as a Maven artifact).
        intellijIdea("2025.3")

        bundledPlugin("com.intellij.gradle")

        // Officially-supported way to depend on the bundled Compose UI framework. Adds the
        // com.intellij.modules.compose dependency and wires Compose/Jewel/Skiko into the plugin
        // classloader. See https://github.com/JetBrains/intellij-platform-compose-plugin-template
        @Suppress("UnstableApiUsage")
        composeUI()

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
            sinceBuild = "252"
        }
    }
}

kotlin { jvmToolchain(21) }
