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
}
