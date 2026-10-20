package com.sal7one.wrapperguard.intellij.settings

import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class OptionalMethodProfilesTest {
    @Test fun `disconnect revokes managed connection without altering other tools or original configuration`() {
        val state = WrapperGuardSettings.State(providerConfigurationPath = "/user/original/providers.json")
        state.methodConnections.add(MethodConnectionState(methodId = "sast.bandit"))
        state.methodConnections.add(MethodConnectionState(methodId = "sast.semgrep"))
        state.providers.add(WrapperGuardSettings.ProviderState(id = "python.bandit", enabled = true))
        state.providers.add(WrapperGuardSettings.ProviderState(id = "sast.semgrep", enabled = true))
        val seal = OptionalMethodConnections.inputSeal(state)
        OptionalMethodConnections.forgetConnection(state, requireNotNull(SecurityMethodCatalog.find("sast.bandit")))
        assertEquals(false, state.methodFlags["sast.bandit"])
        assertEquals(listOf("sast.semgrep"), state.methodConnections.map { it.methodId })
        assertFalse(state.providers.first { it.id == "python.bandit" }.enabled)
        assertTrue(state.providers.first { it.id == "sast.semgrep" }.enabled)
        assertEquals("/user/original/providers.json", state.providerConfigurationPath)
        assertNotEquals(seal, OptionalMethodConnections.inputSeal(state))
    }
    @Test fun `generated report profile is warning only and scan only`() {
        val method = requireNotNull(SecurityMethodCatalog.find("sast.bandit"))
        val profile = OptionalMethodProfiles.profile(method, MethodConnectionState(methodId = method.id, reportPath = "/user/reports/bandit.json"))
        assertEquals("python.bandit", profile["id"]?.jsonPrimitive?.content)
        assertEquals(listOf("WARNING"), profile.getValue("requestedActions").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("SCAN"), profile.getValue("allowedOperations").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(profile.containsKey("runner"))
    }

    @Test fun `semantic analyzer and local model never get executable arguments`() {
        val semantic = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find("research.script-semantics")),
            MethodConnectionState(methodId = "research.script-semantics", mode = "runner"))["runner"]!!.jsonObject
        assertEquals("script-semantics", semantic["tool"]?.jsonPrimitive?.content)
        assertFalse(semantic.containsKey("executable"))
        val ai = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find("ai.external-model")),
            MethodConnectionState(methodId = "ai.external-model", mode = "runner", endpoint = "http://127.0.0.1:1234/v1", model = "selected-model", reportPath = "/user/report.json"))
        assertEquals("AI", ai["analysisKind"]?.jsonPrimitive?.content)
        assertEquals("local-ai", ai["runner"]?.jsonObject?.get("tool")?.jsonPrimitive?.content)
        assertFalse(ai["runner"]!!.jsonObject.containsKey("executable"))
    }

    @Test fun `powershell runner records selected module and scopes operations`() {
        val profile = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find("sast.powershell")),
            MethodConnectionState(methodId = "sast.powershell", mode = "runner", runnerExecutable = "/user/pwsh", modulePath = "/user/PSScriptAnalyzer.psd1", allowedOperations = mutableListOf("SCAN", "BUILD")))
        assertEquals("/user/PSScriptAnalyzer.psd1", profile["runner"]!!.jsonObject["modulePath"]?.jsonPrimitive?.content)
        assertEquals(2, profile["allowedOperations"]!!.jsonArray.size)
    }

    @Test fun `semgrep uses fixed runner with curated rules default and SARIF fallback`() {
        val method = requireNotNull(SecurityMethodCatalog.find("sast.semgrep"))
        val profile = OptionalMethodProfiles.profile(method, MethodConnectionState(methodId = method.id, mode = "runner", runnerExecutable = "/user/semgrep"))
        assertEquals("sarif", profile["format"]?.jsonPrimitive?.content)
        assertEquals("semgrep", profile["runner"]!!.jsonObject["tool"]?.jsonPrimitive?.content)
        assertFalse(profile["runner"]!!.jsonObject.containsKey("rulesPath"))
        val report = OptionalMethodProfiles.profile(method, MethodConnectionState(methodId = method.id, reportPath = "/user/semgrep.sarif"))
        assertFalse(report.containsKey("runner"))
    }

    @Test fun `rule packs model response and open report formats are explicit profile choices`() {
        val semgrep = requireNotNull(SecurityMethodCatalog.find("sast.semgrep"))
        val connection = MethodConnectionState(methodId = semgrep.id, mode = "runner", rulePacks = mutableListOf("android-webview", "jvm-crypto"))
        val runner = OptionalMethodProfiles.profile(semgrep, connection)["runner"]!!.jsonObject
        assertEquals(listOf("android-webview", "jvm-crypto"), runner["rulePacks"]!!.jsonArray.map { it.jsonPrimitive.content })
        val custom = OptionalMethodProfiles.profile(semgrep, connection.copy(rulesPath = "/trusted/rules.yml"))["runner"]!!.jsonObject
        assertFalse(custom.containsKey("rulePacks")); assertEquals("/trusted/rules.yml", custom["rulesPath"]!!.jsonPrimitive.content)
        val ai = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find("ai.external-model")), MethodConnectionState(methodId = "ai.external-model", mode = "runner", responseFormat = "json_object", projectRoot = "/project"))
        assertEquals("json_object", ai["runner"]!!.jsonObject["responseFormat"]!!.jsonPrimitive.content)
        assertEquals("/project", ai["projectRoot"]!!.jsonPrimitive.content)
        listOf("sast.sonar" to "sonar", "sca.cyclonedx" to "cyclonedx").forEach { (id, format) ->
            assertEquals(format, OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find(id)), MethodConnectionState(methodId = id))["format"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `setup rejects paths and symlinks inside project`() {
        val temporary = Files.createTempDirectory("wg-method-paths")
        try {
            val project = Files.createDirectory(temporary.resolve("project"))
            val executable = Files.writeString(project.resolve("python"), "fixture")
            val link = temporary.resolve("python-link")
            Files.createSymbolicLink(link, executable)
            assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.checkedPath(link.toString(), listOf(project)) }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun `SDK executable retains venv selection and rejects project symlink to external interpreter`() {
        val temporary = Files.createTempDirectory("wg-method-interpreter")
        try {
            val project = Files.createDirectory(temporary.resolve("project"))
            val base = Files.writeString(temporary.resolve("base-python"), "fixture")
            base.toFile().setExecutable(true)
            val venv = Files.createDirectories(temporary.resolve("venv/bin"))
            val interpreter = Files.createSymbolicLink(venv.resolve("python"), base)
            assertEquals(interpreter, OptionalMethodProfiles.checkedPath(interpreter.toString(), listOf(project), executable = true))
            val repositoryInterpreter = Files.createSymbolicLink(project.resolve("python"), base)
            assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.checkedPath(repositoryInterpreter.toString(), listOf(project), executable = true) }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun `local AI validation rejects remote endpoints`() {
        val temporary = Files.createTempDirectory("wg-method-ai")
        try {
            val root = Files.createDirectory(temporary.resolve("project"))
            val report = Files.writeString(temporary.resolve("report.json"), "{}")
            val connection = MethodConnectionState(methodId = "ai.external-model", mode = "runner", reportPath = report.toString(),
                endpoint = "https://remote.example/v1", model = "selected-model")
            assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.validate(requireNotNull(SecurityMethodCatalog.find(connection.methodId)), connection, listOf(root)) }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun `AI report export minimizes metadata and masks message credentials`() {
        val report = ScanReport(projectPath = "/private/project", verdict = ScanVerdict.REVIEW_REQUIRED, policyName = "warn", scannedFiles = 1, durationMs = 1,
            findings = listOf(Finding(ruleId = "RULE", title = "token=privatevalue", message = "password=privatevalue", severity = Severity.WARN,
                confidence = Confidence.HIGH, category = FindingCategory.CREDENTIALS, location = SourceLocation("build.gradle.kts", 3),
                evidence = Evidence("never export source evidence"))))
        val output = OptionalMethodProfiles.aiInput(report).toString()
        assertFalse(output.contains("privatevalue")); assertFalse(output.contains("never export source")); assertFalse(output.contains("projectPath"))
        assertFalse(output.contains("callChain")); assertTrue(output.contains("[redacted]"))
    }

    @Test fun `execution seal changes when method preferences or report bytes change`() {
        val report = Files.createTempFile("wg-method-seal", ".json")
        try {
            Files.writeString(report, "first")
            val state = WrapperGuardSettings.State(methodConnections = mutableListOf(MethodConnectionState(methodId = "sast.sarif", reportPath = report.toString())))
            val first = OptionalMethodConnections.inputSeal(state)
            Files.writeString(report, "second")
            assertNotEquals(first, OptionalMethodConnections.inputSeal(state))
            val second = OptionalMethodConnections.inputSeal(state)
            state.methodFlags["builtin.maven-rules"] = false
            assertNotEquals(second, OptionalMethodConnections.inputSeal(state))
        } finally { Files.deleteIfExists(report) }
    }
    @Test fun `producer freshness and strict dependency coverage are explicit and sealed`() {
        val manifest = Files.createTempFile("wg-producer", ".json")
        try {
            Files.writeString(manifest, "first producer manifest")
            val connection = MethodConnectionState(methodId = "sca.cyclonedx", reportPath = "/user/report.json", provenanceManifestPath = manifest.toString(), provenanceMaxAgeSeconds = 3600, requireResolvedVersions = true)
            val profile = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find(connection.methodId)), connection)
            assertEquals(3600, profile["reportProvenance"]!!.jsonObject["maxAgeSeconds"]!!.jsonPrimitive.int)
            assertEquals(manifest.toString(), profile["reportProvenance"]!!.jsonObject["manifestPath"]!!.jsonPrimitive.content)
            assertTrue(profile["dependencyCoverage"]!!.jsonObject["requireResolvedVersions"]!!.jsonPrimitive.boolean)
            val scope = com.sal7one.wrapperguard.intellij.execution.ManagedMethodInputScopes.scope(requireNotNull(SecurityMethodCatalog.find(connection.methodId)), connection)
            assertTrue(scope.externalFiles.contains(manifest.toRealPath().toString()))
            val state = WrapperGuardSettings.State(methodConnections = mutableListOf(connection))
            val before = OptionalMethodConnections.inputSeal(state)
            Files.writeString(manifest, "changed producer manifest")
            assertNotEquals(before, OptionalMethodConnections.inputSeal(state))
        } finally { Files.deleteIfExists(manifest) }
    }

    @Test fun `owned HTTP audit has no executable and rejects unowned remote names secrets or scope escape`() {
        val connection = MethodConnectionState(methodId = "dast.http-audit", mode = "runner", targetOrigin = "http://127.0.0.1:8123", targetPath = "/acceptance",
            ownershipConfirmed = true, httpTargetUrls = mutableListOf("http://127.0.0.1:8123/acceptance/secure"), authorizationEnv = "WG_TEST_AUTH")
        OptionalMethodProfiles.validateHttp(connection)
        val profile = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find(connection.methodId)), connection)
        val runner = profile["runner"]!!.jsonObject
        assertEquals("http-audit", runner["tool"]!!.jsonPrimitive.content)
        assertFalse(runner.containsKey("executable"))
        assertEquals("WG_TEST_AUTH", runner["authHeadersEnv"]!!.jsonObject["Authorization"]!!.jsonPrimitive.content)
        assertEquals(listOf("SCAN"), profile["allowedOperations"]!!.jsonArray.map { it.jsonPrimitive.content })
        listOf(connection.copy(ownershipConfirmed = false), connection.copy(httpTargetUrls = mutableListOf("http://localhost:8123/acceptance")),
            connection.copy(httpTargetUrls = mutableListOf("http://127.0.0.1:8123/outside")), connection.copy(authorizationEnv = "Bearer secret"),
            connection.copy(httpTargetUrls = mutableListOf("http://127.0.0.1:8123/acceptance?token=secret")), connection.copy(httpMaxRequests = 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.validateHttp(it) }
        }
    }

    @Test fun `passive capabilities exclude unsupported runners and require current SDK for new connections`() {
        val capabilities = OptionalSdkCapabilities.decode("""{"sdkVersion":"0.4.0","providerApiVersion":1,"runners":[{"tool":"bandit","supported":true},{"tool":"semgrep","supported":false},{"tool":"http-audit","supported":true}]}""")
        capabilities.requireCurrent()
        assertEquals(setOf("bandit", "http-audit"), capabilities.runners)
        assertThrows(IllegalArgumentException::class.java) { capabilities.copy(version = "0.3.0").requireCurrent() }
        assertThrows(IllegalArgumentException::class.java) { OptionalSdkCapabilities.decode("""{"sdkVersion":"0.4.0","providerApiVersion":2,"runners":[]}""") }
    }

    @Test fun `repair only accepts the original interpreter in registered managed environment`() {
        val temporary = Files.createTempDirectory("wg-sdk-repair").toRealPath()
        try {
            val environment = Files.createDirectory(temporary.resolve("sdk-owned"))
            val python = Files.writeString(Files.createDirectory(environment.resolve("bin")).resolve("python"), "fixture")
            val record = ManagedArtifactState(environment.toString(), "SDK environment", "fingerprint")
            assertEquals(environment, OptionalSdkInstaller.managedEnvironment(python, temporary, listOf(record)))
            assertThrows(IllegalArgumentException::class.java) { OptionalSdkInstaller.managedEnvironment(python, temporary, emptyList()) }
            val link = Files.createSymbolicLink(python.parent.resolve("other"), python)
            assertThrows(IllegalArgumentException::class.java) { OptionalSdkInstaller.managedEnvironment(link, temporary, listOf(record)) }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun `missing managed SDK is a failed provider while other enabled providers still complete`() {
        val root = Files.createTempDirectory("wg-missing-sdk").toRealPath()
        try {
            val method = requireNotNull(SecurityMethodCatalog.find("sast.bandit"))
            val permissions = com.sal7one.wrapperguard.core.vetting.ProviderPermissions(enabled = true)
            val missing = OptionalMethodConnections.providerFor(method, MethodConnectionState(methodId = method.id,
                pythonExecutable = root.parent.resolve("does-not-exist-python").toString(), reportPath = root.parent.resolve("does-not-exist-report.json").toString()), permissions, listOf(root))
            val other = object : com.sal7one.wrapperguard.core.vetting.VettingProvider {
                override val descriptor = com.sal7one.wrapperguard.core.vetting.ProviderDescriptor("other.ready", "Ready fixture", "1.0.0")
                override fun scan(request: com.sal7one.wrapperguard.core.vetting.VettingRequest) = com.sal7one.wrapperguard.core.vetting.ProviderResult(scannedFiles = 1)
            }
            val report = com.sal7one.wrapperguard.core.vetting.VettingCoordinator(listOf(
                com.sal7one.wrapperguard.core.vetting.ProviderRegistration(missing, permissions),
                com.sal7one.wrapperguard.core.vetting.ProviderRegistration(other, permissions))).scan(root,
                com.sal7one.wrapperguard.core.policy.ScanPolicy(), com.sal7one.wrapperguard.core.policy.ScanSettings())
            assertEquals(com.sal7one.wrapperguard.core.vetting.ProviderRunStatus.FAILED, report.providerRuns.first { it.descriptor.id == method.providerId }.status)
            assertEquals(com.sal7one.wrapperguard.core.vetting.ProviderRunStatus.COMPLETED, report.providerRuns.first { it.descriptor.id == "other.ready" }.status)
            assertFalse(report.complete)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `dynamic entry cannot escape or run without explicit container execution confirmation`() {
        val connection = MethodConnectionState(methodId = "research.container-python", mode = "runner", sandboxEnabled = true,
            entryPath = "scripts/probe.py", executionConfirmed = true, runnerExecutable = "/usr/local/bin/python", allowedOperations = mutableListOf("SCAN", "BUILD"), requestedActions = mutableListOf("BLOCK_BUILD"))
        OptionalMethodProfiles.validateDynamicEntry(connection)
        val profile = OptionalMethodProfiles.profile(requireNotNull(SecurityMethodCatalog.find(connection.methodId)), connection)
        assertEquals("container-python", profile["runner"]!!.jsonObject["tool"]!!.jsonPrimitive.content)
        assertEquals(listOf("SCAN"), profile["allowedOperations"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("WARNING"), profile["requestedActions"]!!.jsonArray.map { it.jsonPrimitive.content })
        listOf(connection.copy(executionConfirmed = false), connection.copy(sandboxEnabled = false), connection.copy(entryPath = "../probe.py"),
            connection.copy(entryPath = "/tmp/probe.py"), connection.copy(executionTimeoutMs = 10001)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.validateDynamicEntry(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.validateImageExecutable("/bin/../python") }
        assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.validateImageExecutable("/bin/python --flag") }
        assertTrue(OptionalSdkCapabilities.decode("""{"sdkVersion":"0.4.0","providerApiVersion":1,"runners":[]}""").sandboxTools.isEmpty())
    }

    @Test fun `container profile seals installed Docker CLI and never treats in-image tool or socket as a host file`() {
        org.junit.Assume.assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("unix"))
        val temporary = Files.createTempDirectory(java.nio.file.Path.of("/tmp").toRealPath(), "wg-ctr-").toRealPath()
        try {
            val root = Files.createDirectory(temporary.resolve("project"))
            val python = Files.writeString(temporary.resolve("python"), "trusted fixture").also { it.toFile().setExecutable(true) }
            val docker = Files.writeString(temporary.resolve("docker"), "trusted fixture").also { it.toFile().setExecutable(true) }
            val socket = temporary.resolve("docker.sock")
            java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { server ->
                server.bind(java.net.UnixDomainSocketAddress.of(socket))
                val method = requireNotNull(SecurityMethodCatalog.find("sast.bandit"))
                val profilePath = Files.writeString(temporary.resolve("owned.profile.json"), "{}")
                val connection = MethodConnectionState(methodId = method.id, mode = "runner", pythonExecutable = python.toString(), profilePath = profilePath.toString(),
                    runnerExecutable = "/image-only/bin/bandit", sandboxEnabled = true, sandboxExecutable = docker.toString(), sandboxEndpoint = socket.toString(),
                    sandboxImage = "example/scanner@sha256:" + "a".repeat(64), supportedSandboxTools = mutableListOf("bandit"))
                OptionalMethodProfiles.validateSandbox(method.id, connection, listOf(root))
                val permissions = com.sal7one.wrapperguard.core.vetting.ProviderPermissions()
                val provider = com.sal7one.wrapperguard.core.vetting.CommandVettingProvider(OptionalMethodProfiles.command(method, connection, permissions))
                val snapshot = com.sal7one.wrapperguard.intellij.execution.NativeProviderInputs.snapshot(listOf(com.sal7one.wrapperguard.core.vetting.ProviderRegistration(provider, permissions)),
                    WrapperGuardSettings.State(methodConnections = mutableListOf(connection)), root)
                assertTrue(snapshot.toolFiles.contains(docker)); assertTrue(snapshot.toolFiles.contains(python))
                assertFalse(snapshot.toolFiles.contains(java.nio.file.Path.of(connection.runnerExecutable)))
                assertFalse(snapshot.externalFiles.contains(socket)); assertFalse(snapshot.toolFiles.contains(socket))
                val profile = OptionalMethodProfiles.profile(method, connection)["runner"]!!.jsonObject
                assertEquals("/image-only/bin/bandit", profile["executable"]!!.jsonPrimitive.content)
                assertEquals(docker.toString(), profile["sandbox"]!!.jsonObject["executable"]!!.jsonPrimitive.content)
                assertFalse(profile["sandbox"]!!.jsonObject["allowRootfulDaemon"]!!.jsonPrimitive.boolean)
                assertThrows(IllegalArgumentException::class.java) { OptionalMethodProfiles.validateSandbox(method.id, connection.copy(supportedSandboxTools = mutableListOf()), listOf(root)) }
            }
        } finally { temporary.toFile().deleteRecursively() }
    }

    @Test fun `managed SCAN only profiles are skipped before BUILD and SYNC while opaque SDKs retain their contract`() {
        val method = requireNotNull(SecurityMethodCatalog.find("dast.http-audit"))
        val connection = MethodConnectionState(methodId = method.id, profilePath = "/user/owned-http.profile.json", allowedOperations = mutableListOf("SCAN"))
        val descriptor = OptionalMethodProfiles.command(method, connection, com.sal7one.wrapperguard.core.vetting.ProviderPermissions()).descriptor()
        assertTrue(managedOperationAllowed(descriptor, listOf(connection), com.sal7one.wrapperguard.core.vetting.VettingOperation.SCAN))
        assertFalse(managedOperationAllowed(descriptor, listOf(connection), com.sal7one.wrapperguard.core.vetting.VettingOperation.BUILD))
        assertFalse(managedOperationAllowed(descriptor, listOf(connection), com.sal7one.wrapperguard.core.vetting.VettingOperation.SYNC))
        assertTrue(managedOperationAllowed(descriptor, listOf(connection.copy(allowedOperations = mutableListOf("SCAN", "BUILD"))), com.sal7one.wrapperguard.core.vetting.VettingOperation.BUILD))
        val existingSdk = descriptor.copyWithMetadata(inputScope = null)
        assertTrue(managedOperationAllowed(existingSdk, listOf(connection), com.sal7one.wrapperguard.core.vetting.VettingOperation.BUILD))
    }

}
