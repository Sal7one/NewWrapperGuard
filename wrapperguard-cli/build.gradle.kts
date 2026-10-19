plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":wrapperguard-core"))
    implementation(project(":wrapperguard-rules"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    testImplementation(kotlin("test"))
}

application { mainClass.set("com.sal7one.wrapperguard.cli.MainKt") }
kotlin { jvmToolchain(21) }

tasks.test { useJUnitPlatform() }
