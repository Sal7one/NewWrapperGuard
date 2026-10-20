plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }

// Ship the same catalog used by VS Code, the demo and the SDK setup UI.
tasks.processResources {
    val applicationVersion = project.version.toString()
    inputs.property("appVersion", applicationVersion)
    filesMatching("META-INF/wrapperguard/version.txt") {
        expand("appVersion" to applicationVersion)
    }
    from(rootProject.file("catalog")) { include("security-methods.json", "security-presets.json") }
}
