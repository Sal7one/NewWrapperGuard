// Apply only after reviewing this file. Invocation is explicit: gradle wrapperGuardCheck.
// This task never becomes a build/sync dependency merely by applying the recipe.
tasks.register<Exec>("wrapperGuardCheck") {
    group = "verification"
    description = "Run the installed WrapperGuard CLI with user-owned provider settings"
    doFirst {
        val cli = providers.environmentVariable("WRAPPERGUARD_CLI").orNull
            ?: error("Set WRAPPERGUARD_CLI to an absolute installed executable")
        require(java.io.File(cli).isAbsolute) { "WRAPPERGUARD_CLI must be absolute" }
        val arguments = mutableListOf(cli, rootProject.projectDir.absolutePath,
            "--operation", "build", "--enforce", "--json")
        providers.environmentVariable("WRAPPERGUARD_PROVIDERS").orNull?.let { settings ->
            require(java.io.File(settings).isAbsolute) { "Provider settings must be absolute and outside this project" }
            arguments += listOf("--providers", settings)
        }
        commandLine(arguments)
    }
    isIgnoreExitValue = false
}
