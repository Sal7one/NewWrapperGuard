plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    id("org.jetbrains.intellij.platform") version "2.11.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

allprojects {
    group = "com.sal7one.wrapperguard"
    version = "0.6.0"
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
