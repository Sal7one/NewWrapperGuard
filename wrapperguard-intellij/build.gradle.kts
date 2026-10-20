import org.gradle.api.artifacts.component.ModuleComponentIdentifier
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
val bridgeRuntimeBuild = providers.gradleProperty("wrapperguard.bridgeRuntimeBuild")
if (bridgeRuntimeVersion.isPresent || bridgeRuntimePath.isPresent) {
    intellijPlatformTesting.testIde.register("testJewelBridgeRuntime") {
        if (bridgeRuntimePath.isPresent) localPath.set(file(bridgeRuntimePath.get()))
        else {
            type.set(IntelliJPlatformType.IntellijIdeaUltimate)
            version.set(bridgeRuntimeVersion)
        }
        // IPGP 2.11 matched the default "closest" framework to the base compilation
        // platform inside a custom runtime, causing a 252/262 Java11Shim mismatch.
        // Keep target-build pinning and resolved-artifact checks as an invariant under
        // 2.18.1 too; release compilation and its own tests use the minimum platform.
        require(!bridgeRuntimeVersion.isPresent || bridgeRuntimeBuild.isPresent) {
            "A remote bridge runtime requires wrapperguard.bridgeRuntimeBuild for its matching test framework"
        }
        if (bridgeRuntimeBuild.isPresent) {
            testFramework(TestFrameworkType.Platform, bridgeRuntimeBuild)
        } else {
            testFramework(TestFrameworkType.Platform)
        }
        plugins {
            bundledPlugin("com.intellij.gradle")
            listOf("intellij.platform.compose", "intellij.libraries.skiko", "intellij.libraries.compose.foundation.desktop",
                "intellij.platform.jewel.foundation", "intellij.platform.jewel.ui", "intellij.platform.jewel.ideLafBridge").forEach { bundledModule(it) }
            if (bridgeRuntimeVersion.orNull?.startsWith("2026.") == true) {
                bundledModule("intellij.libraries.compose.runtime.desktop")
                // IPGP 2.18.1 assembles the target product-info plugin/module classpath.
                // Individual bundledModule jars alone do not register 262 product modules.
            }
        }
        task {
            useJUnitPlatform { includeEngines("junit-vintage") }
            filter {
                includeTestsMatching("*JewelPanelCompatibilityTest")
                includeTestsMatching("*JewelPlatformRuntimeTest.testDescriptorLoadsBundledComposeWithoutRequiringLaterAggregateAlias")
                includeTestsMatching("*JewelPlatformRuntimeTest.testBundledJewelBridgeCreatesAnActualPanelThroughCompatibilityBoundary")
            }
            bridgeRuntimeVersion.orNull?.let { systemProperty("wrapperguard.bridge.expectedRuntimeVersion", it) }
            val frameworkEvidence = listOf(
                "intellijPlatformTestDependencies_testJewelBridgeRuntime",
                "intellijPlatformTestRuntimeClasspath_testJewelBridgeRuntime",
            ).map { name ->
                configurations.getByName(name).incoming.artifacts.resolvedArtifacts.map { artifacts ->
                    artifacts.mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                        .filter { it.group == "com.jetbrains.intellij.platform" && it.module.startsWith("test-framework") }
                        .distinctBy { "${it.group}:${it.module}:${it.version}" }.sortedBy { it.module }
                        .map { "$name: ${it.group}:${it.module}:${it.version}" }
                }
            }.reduce { dependencies, runtime -> dependencies.zip(runtime) { first, second -> first + second } }
            val frameworkEvidenceFile = layout.buildDirectory.file("reports/jewel-bridge-runtime/test-framework.txt").get().asFile
            val expectedFrameworkBuild = bridgeRuntimeBuild.orNull
            inputs.property("wrapperguard.bridge.testFramework", frameworkEvidence)
            doFirst {
                @Suppress("UNCHECKED_CAST")
                val resolvedFramework = inputs.properties.getValue("wrapperguard.bridge.testFramework") as List<String>
                frameworkEvidenceFile.parentFile.mkdirs()
                frameworkEvidenceFile.writeText(resolvedFramework.joinToString("\n", postfix = "\n"))
                logger.lifecycle("Jewel bridge target test framework: {}", resolvedFramework)
                if (expectedFrameworkBuild != null) {
                    check(resolvedFramework.any { it.contains(":test-framework:") }) { "Target test framework was not resolved" }
                    check(resolvedFramework.all { it.substringAfterLast(':') == expectedFrameworkBuild }) {
                        "Target test framework must match $expectedFrameworkBuild; resolved: $resolvedFramework"
                    }
                }
            }
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
            // Android Studio is covered separately by actual isolated installation,
            // startup and guard acceptance with the minimum-built CI ZIP. These binary
            // verifier targets are IntelliJ IDEA distributions.
            create(IntelliJPlatformType.IntellijIdeaUltimate,
                providers.gradleProperty("wrapperguard.verifierVersion").getOrElse("2025.3"))
        }
    }
}
