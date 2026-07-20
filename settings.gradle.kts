pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // IntelliJ Platform Gradle Plugin resolves IDE builds and bundled plugins from here
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://www.jetbrains.com/intellij-repository/snapshots")
        // Jewel/Compose platform artifacts (if ever needed outside the bundled modules)
        maven("https://packages.jetbrains.team/maven/p/kpm/public")
    }
}

rootProject.name = "wrapperguard-next"
include(
    ":wrapperguard-core",
    ":wrapperguard-rules",
    ":wrapperguard-cli",
    ":wrapperguard-intellij"
)
