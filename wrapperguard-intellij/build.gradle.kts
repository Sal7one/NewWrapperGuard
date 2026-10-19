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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    intellijPlatform {
        // Target 2025.3 for development; sinceBuild=252 keeps the plugin installable on 2025.2+,
        // which is the minimum platform version that exposes the com.intellij.modules.compose module.
        // NB: from 2025.3 the IntelliJ Platform Gradle Plugin requires intellijIdea() (IC/ideaIC
        // is no longer published as a Maven artifact).
        val localIdePath = providers.gradleProperty("wrapperguard.localIdePath").orNull
        if (localIdePath != null) local(localIdePath) else intellijIdea("2025.3")

        bundledPlugin("com.intellij.gradle")

        // Officially-supported way to depend on the bundled Compose UI framework. Adds the
        // com.intellij.modules.compose dependency and wires Compose/Jewel/Skiko into the plugin
        // classloader. See https://github.com/JetBrains/intellij-platform-compose-plugin-template
        @Suppress("UnstableApiUsage")
        composeUI()

        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.sal7one.wrapperguard.next"
        name = "WrapperGuard"
        version = project.version.toString()
        vendor {
            name = "sal7one"
            url = "https://github.com/Sal7one/NewWrapperGaurd"
        }
        description = "Local vetting workspace with built-in static analysis, configurable SDK and command providers, and separate build/run and Gradle sync permissions."
        ideaVersion {
            sinceBuild = "252"
        }
    }
}

kotlin { jvmToolchain(21) }

tasks.test {
    // IDE platform tests and presentation tests use JUnit 3/4. Explicit discovery avoids
    // picking up an unrelated bundled Jupiter engine from a locally supplied IDE SDK.
    useJUnitPlatform { includeEngines("junit-vintage") }
}

tasks.runIde {
    // Sandbox-only: auto-open the WrapperGuard tool window so visual checks and
    // screenshots of the dev IDE do not need a manual click.
    jvmArgs("-Dwrapperguard.autoshow=true")
    if (providers.gradleProperty("wrapperguard.demo").orNull == "true") {
        jvmArgs("-Dwrapperguard.demo=true")
        listOf("demoReport", "demoTab", "demoControl", "demoWindow").forEach { option ->
            providers.gradleProperty("wrapperguard.$option").orNull?.let { value ->
                jvmArgs("-Dwrapperguard.$option=$value")
            }
        }
        providers.gradleProperty("wrapperguard.demoProject").orNull?.let { args(it) }
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            // Binary-compat check against the development IDE. IC is no longer published
            // since 2025.3, so verify against the full IDE (IU).
            // NB: Android Studio is not verified here — IPGP 2.11 mangles the AS installer
            // URL for 2025.3.x; the real Android Studio install test is a manual step
            // (install the release zip into AS from the GitHub release).
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.3")
        }
    }
}
