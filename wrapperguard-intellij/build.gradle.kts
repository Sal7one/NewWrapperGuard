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
        // Compile against the oldest supported runtime: Compose/Jewel inline functions embed
        // their platform ABI in this plugin. sinceBuild alone cannot make newer inlined APIs
        // compatible with 252. A local override is for development/host acceptance only;
        // release CI builds this default and verifies the same artifact on minimum/current IDEs.
        val localIdePath = providers.gradleProperty("wrapperguard.localIdePath").orNull
        val platformVersion = providers.gradleProperty("wrapperguard.platformVersion").getOrElse("2025.2")
        if (localIdePath != null) local(localIdePath)
        else if (platformVersion.startsWith("2025.2")) intellijIdeaCommunity(platformVersion)
        // IC artifacts were replaced by the unified IDEA distribution starting in 2025.3.
        else intellijIdea(platformVersion)

        bundledPlugin("com.intellij.gradle")

        // Resolve the bundled Compose/Jewel/Skiko compile modules for this platform version.
        // Runtime module/classloader dependencies are declared explicitly in plugin.xml.
        // See https://github.com/JetBrains/intellij-platform-compose-plugin-template
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

val buildIntegrationSdk by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the first-party Python SDK wheel used by the setup GUI"
    workingDir(rootProject.projectDir)
    commandLine("python3", "scripts/build-sdk.py")
    inputs.files(rootProject.fileTree("sdk/python") {
        exclude("build/**", "**/__pycache__/**", "**/*.egg-info/**")
    })
    inputs.file(rootProject.file("scripts/build-sdk.py"))
    outputs.file(rootProject.file("build/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl"))
}

tasks.processResources {
    dependsOn(buildIntegrationSdk)
    from(rootProject.files("LICENSE", "THIRD_PARTY_NOTICES.md")) {
        into("META-INF")
    }
    from(rootProject.file("build/sdk/wrapperguard_sdk-0.4.0-py3-none-any.whl")) {
        into("integrations")
    }
}

tasks.test {
    // IDE platform tests and presentation tests use JUnit 3/4. Explicit discovery avoids
    // picking up an unrelated bundled Jupiter engine from a locally supplied IDE SDK.
    useJUnitPlatform { includeEngines("junit-vintage") }
}

// Run minimum-compiled bridge tests inside a separate target IDE/runtime and sandbox.
// Changing this test runtime does not change the release's minimum compilation target.
val bridgeRuntimeVersion = providers.gradleProperty("wrapperguard.bridgeRuntimeVersion")
val bridgeRuntimePath = providers.gradleProperty("wrapperguard.bridgeRuntimePath")
if (bridgeRuntimeVersion.isPresent || bridgeRuntimePath.isPresent) {
    intellijPlatformTesting.testIde.register("testJewelBridgeRuntime") {
        if (bridgeRuntimePath.isPresent) localPath.set(file(bridgeRuntimePath.get()))
        else {
            type.set(IntelliJPlatformType.IntellijIdeaUltimate)
            version.set(bridgeRuntimeVersion)
        }
        testFramework(TestFrameworkType.Platform)
        plugins {
            bundledPlugin("com.intellij.gradle")
            listOf("intellij.platform.compose", "intellij.libraries.skiko", "intellij.libraries.compose.foundation.desktop",
                "intellij.platform.jewel.foundation", "intellij.platform.jewel.ui", "intellij.platform.jewel.ideLafBridge").forEach { bundledModule(it) }
            if (bridgeRuntimeVersion.orNull?.startsWith("2026.") == true) bundledModule("intellij.libraries.compose.runtime.desktop")
        }
        task {
            useJUnitPlatform { includeEngines("junit-vintage") }
            filter {
                includeTestsMatching("*JewelPanelCompatibilityTest")
                includeTestsMatching("*GuardSmokeTest.testDescriptorLoadsBundledComposeWithoutRequiringLaterAggregateAlias")
                includeTestsMatching("*GuardSmokeTest.testBundledJewelBridgeCreatesAnActualPanelThroughCompatibilityBoundary")
            }
            bridgeRuntimeVersion.orNull?.let { systemProperty("wrapperguard.bridge.expectedRuntimeVersion", it) }
        }
    }
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
            create(IntelliJPlatformType.IntellijIdeaUltimate,
                providers.gradleProperty("wrapperguard.verifierVersion").getOrElse("2025.3"))
        }
    }
}
