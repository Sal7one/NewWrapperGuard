plugins { kotlin("jvm") }

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":wrapperguard-core"))
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
