package com.sal7one.wrapperguard.intellij.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.sal7one.wrapperguard.core.methods.SecurityMethod
import com.sal7one.wrapperguard.core.methods.SecurityMethodCatalog
import com.sal7one.wrapperguard.core.model.ScanReport
import com.sal7one.wrapperguard.core.vetting.VettingAction
import com.sal7one.wrapperguard.intellij.vetting.displayName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*

private fun textNote(value: String) = JBLabel("<html><div style='width:510px'>$value</div></html>")
private fun fieldPanel(label: String, field: JBTextField, project: Project, directory: Boolean = false): JPanel = JPanel(BorderLayout(8, 4)).apply {
    add(JBLabel(label).apply { labelFor = field }, BorderLayout.NORTH)
    add(field, BorderLayout.CENTER)
    add(JButton("Browse…").apply { addActionListener {
        val descriptor = FileChooserDescriptor(true, directory, false, false, false, false).withTitle(label)
        FileChooser.chooseFile(descriptor, project, null)?.let { field.text = it.path }
    } }, BorderLayout.EAST)
    field.accessibleContext.accessibleName = label
    alignmentX = 0f
    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
}

internal class InstallOptionalSdkDialog(private val project: Project) : DialogWrapper(project) {
    private val python = JBTextField(42)
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val bandit = JBCheckBox("Download Bandit 1.9.4 and its dependencies from PyPI", false).apply { isEnabled = !windows }
    private val semgrep = JBCheckBox("Download Semgrep 1.181.0 and its dependencies from PyPI", false).apply { isEnabled = !windows }
    var installedPython: String? = null; private set
    init { title = "Install WrapperGuard analysis SDK"; setOKButtonText("Install SDK"); init() }
    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(textNote("Choose an installed Python 3.10+ outside your projects. WrapperGuard creates a dedicated environment in your IDE configuration and installs its bundled SDK offline. Your Python environment is not modified."))
        add(Box.createVerticalStrut(12)); add(fieldPanel("Trusted Python executable", python, project))
        add(Box.createVerticalStrut(12)); add(bandit); add(semgrep)
        if (windows) add(textNote("Direct source analyzers require a POSIX host. Report imports and local model report review remain available on Windows."))
        add(Box.createVerticalStrut(8)); add(textNote("The SDK includes report importers and advisory script behavior analysis. PowerShell, Trivy, OSV and model servers are installed separately. Only explicitly selected analyzer downloads use the network in this dialog."))
    }
    override fun doOKAction() {
        val result = methodProgress(project, "Installing WrapperGuard SDK") { OptionalSdkInstaller.install(project, python.text, bandit.isSelected, semgrep.isSelected, it) }
        result.onSuccess { installedPython = it.toString(); super.doOKAction() }
            .onFailure { setErrorText(it.message ?: "SDK installation failed.") }
    }
}

/** Browse-based setup. The fixed SDK command is generated; no JSON or arbitrary command entry. */
internal class OptionalMethodSetupDialog(
    private val project: Project, private val method: SecurityMethod, private val report: ScanReport? = null
) : DialogWrapper(project) {
    private val state = WrapperGuardSettings.getInstance().state
    private val previous = state.methodConnections.firstOrNull { it.methodId == method.id }
    private val python = JBTextField(previous?.pythonExecutable ?: state.managedSdkPython, 42)
    private val reportFile = JBTextField(previous?.reportPath.orEmpty(), 42)
    private val runner = JBTextField(previous?.runnerExecutable.orEmpty(), 42)
    private val module = JBTextField(previous?.modulePath.orEmpty(), 42)
    private val rules = JBTextField(previous?.rulesPath.orEmpty(), 42)
    private val rulePacks = OptionalMethodProfiles.semgrepPacks.mapValues { (id, label) -> JBCheckBox(label, id in (previous?.rulePacks ?: listOf("legacy-android-ktor"))) }
    private val responseFormat = JComboBox(arrayOf("Structured schema (recommended)", "JSON object (compatibility)")).apply { selectedIndex = if (previous?.responseFormat == "json_object") 1 else 0 }
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val origin = JBTextField(previous?.targetOrigin.orEmpty(), 42)
    private val pathScope = JBTextField(previous?.targetPath ?: "/", 42)
    private val endpoint = JBTextField(previous?.endpoint ?: "http://127.0.0.1:1234/v1", 42)
    private val model = JBTextField(previous?.model.orEmpty(), 42)
    private val preferred = JBCheckBox("Use this connection when another provider has the same ID", previous?.preferManaged ?: false)
    private val mode = JComboBox(arrayOf("Import an existing JSON report", "Run the installed analyzer"))
    private val reportPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val runnerPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val directOnly = method.id in setOf("research.script-semantics", "ai.external-model")
    init {
        title = "Connect ${method.title}"; setOKButtonText("Verify and save connection")
        mode.selectedIndex = if (directOnly || !windows && previous?.mode == "runner") 1 else 0
        if (windows && !directOnly) mode.isEnabled = false
        init(); mode.addActionListener { updateMode() }; updateMode()
    }
    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(textNote(method.summary)); add(Box.createVerticalStrut(12))
        add(fieldPanel("WrapperGuard SDK Python (outside all projects)", python, project))
        add(JPanel(FlowLayout(FlowLayout.LEADING, 0, 8)).apply {
            add(JButton("Install SDK…").apply { addActionListener { val dialog = InstallOptionalSdkDialog(project); if (dialog.showAndGet()) {
                python.text = dialog.installedPython.orEmpty()
                if (method.id in setOf("sast.bandit", "sast.semgrep")) {
                    val name = if (method.id == "sast.bandit") "bandit" else "semgrep"
                    val executable = Path.of(python.text).parent.resolve(if (windows) "$name.exe" else name)
                    if (Files.isExecutable(executable)) runner.text = executable.toString()
                }
            } } })
            add(JButton("Tool setup guide ↗").apply { addActionListener { BrowserUtil.browse(methodGuide(method.id)) } })
        })
        if (method.id in OptionalMethodProfiles.runnerMethods && !directOnly) add(JPanel(BorderLayout(8, 0)).apply {
            add(JBLabel("Analysis source"), BorderLayout.WEST); add(mode, BorderLayout.CENTER)
        })
        reportPanel.add(fieldPanel(if (method.id == "ai.external-model") "Exported WrapperGuard report" else "Existing tool JSON report (outside all projects)", reportFile, project))
        reportPanel.add(Box.createVerticalStrut(7)); reportPanel.add(textNote("An imported report does not prove that its results match the current source. Coverage depends on the producing tool and the inputs it examined."))
        if (method.id == "ai.external-model") {
            reportPanel.add(JButton("Use the current scan report").apply { isEnabled = report != null; addActionListener {
                val current = report ?: return@addActionListener
                methodProgress(project, "Preparing advisory report") { ManagedStorage.withUse {
                    val dir = OptionalMethodConnections.directory(); Files.createDirectories(dir)
                    val destination = dir.resolve("ai-input-${java.util.UUID.randomUUID()}.json")
                    Files.writeString(destination, Json.encodeToString(OptionalMethodProfiles.aiInput(current)))
                    ManagedStorage.registerCreated(destination, "AI input")
                    destination
                } }.onSuccess { reportFile.text = it.toString() }.onFailure { setErrorText(it.message ?: "Report could not be prepared.") }
            } })
        }
        add(Box.createVerticalStrut(12)); add(reportPanel)
        if (!directOnly) runnerPanel.add(fieldPanel(if (method.id == "sast.powershell") "Installed PowerShell executable (pwsh)" else "Installed analyzer executable", runner, project))
        if (windows && !directOnly) add(textNote("Report import is available on Windows. Direct source analyzers require a POSIX host."))
        if (method.id == "sast.semgrep") {
            runnerPanel.add(JBLabel("Bundled review rule packs (used when custom rules is blank)"))
            rulePacks.values.forEach { runnerPanel.add(it) }
            runnerPanel.add(fieldPanel("Optional trusted rules file (replaces bundled packs)", rules, project))
            runnerPanel.add(textNote("The controlled Semgrep runner inspects a Kotlin/Java source snapshot with bundled curated checks by default. It does not run Gradle, Maven or project source."))
        }
        if (method.id == "sast.powershell") runnerPanel.add(fieldPanel("Installed PSScriptAnalyzer module (.psd1 or module directory)", module, project, directory = true))
        if (method.id == "sca.trivy") runnerPanel.add(textNote("Trivy may download vulnerability databases and indexes into an IDE-managed cache outside the project. It scans dependency inputs without building the project."))
        if (method.id == "sca.osv") runnerPanel.add(textNote("OSV-Scanner sends dependency names and versions to OSV. The controlled runner uses --no-resolve; it does not install dependencies or run a build."))
        if (method.id == "research.script-semantics") runnerPanel.add(textNote("Runs the SDK's bounded static analyzer for Python and PowerShell behavior signals. It does not execute source, download a model, reproduce a paper's detector, or grant blocking permissions."))
        if (method.id == "ai.external-model") {
            runnerPanel.add(JBLabel("Local OpenAI-compatible endpoint").apply { labelFor = endpoint }); runnerPanel.add(endpoint)
            runnerPanel.add(JBLabel("Model name loaded in your local server").apply { labelFor = model }); runnerPanel.add(model)
            runnerPanel.add(JBLabel("Model response format").apply { labelFor = responseFormat }); runnerPanel.add(responseFormat)
            runnerPanel.add(Box.createVerticalStrut(8)); runnerPanel.add(textNote("Start your local LM Studio, Ollama or vLLM server and load a model first. Only numeric loopback endpoints are permitted. The selected report is sent to that local service; project source files are not loaded. Model responses are advisory audit evidence and never block a build or sync."))
        }
        add(runnerPanel)
        if (method.id == "dast.zap") {
            add(Box.createVerticalStrut(12)); add(JBLabel("Exact target origin (for example https://staging.example.com:8443)").apply { labelFor = origin }); add(origin)
            add(JBLabel("Allowed target path prefix").apply { labelFor = pathScope }); add(pathScope)
            add(textNote("ZAP is a report import. WrapperGuard sends no DAST requests and never starts an active attack."))
        }
        add(Box.createVerticalStrut(12)); add(preferred)
        add(textNote("Existing SDK registrations and provider files are preserved. Select this option to use the managed connection for this provider ID instead of an existing registration."))
        add(Box.createVerticalStrut(12)); add(textNote("Saving verifies the installed SDK without starting an analyzer or reading the report. New connections remain disabled until you enable them, start with warning-only grants, and run only for an explicit scan. Build/sync permissions are reviewed separately."))
    }.let { panel -> JBScrollPane(panel).apply { preferredSize = Dimension(650, if (method.id == "ai.external-model") 690 else 560); border = BorderFactory.createEmptyBorder() } }
    private fun updateMode() {
        reportPanel.isVisible = method.id == "ai.external-model" || !directOnly && mode.selectedIndex == 0
        runnerPanel.isVisible = directOnly || mode.selectedIndex == 1
        contentPanel.revalidate(); contentPanel.repaint()
    }
    override fun createLeftSideActions(): Array<javax.swing.Action> = if (previous == null) emptyArray() else arrayOf(
        object : javax.swing.AbstractAction("Disconnect") {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) {
                if (Messages.showYesNoDialog(project,
                        "Remove this managed connection and disable its method? Original provider files and reports are preserved.",
                        "Disconnect ${method.title}", Messages.getQuestionIcon()) != Messages.YES) return
                runCatching { OptionalMethodConnections.disconnect(method) }
                    .onSuccess { close(CANCEL_EXIT_CODE) }
                    .onFailure { setErrorText("The connection could not be removed. Review the IDE configuration directory.") }
            }
        }
    )
    override fun doOKAction() {
        val connection = MethodConnectionState(methodId = method.id, pythonExecutable = python.text.trim(),
            mode = if (directOnly || method.id in OptionalMethodProfiles.runnerMethods && mode.selectedIndex == 1) "runner" else "report",
            reportPath = reportFile.text.trim(), runnerExecutable = runner.text.trim(), modulePath = module.text.trim(), rulesPath = rules.text.trim(),
            rulePacks = rulePacks.filter { it.value.isSelected }.keys.toMutableList(), responseFormat = if (responseFormat.selectedIndex == 1) "json_object" else "json_schema",
            targetOrigin = origin.text.trim(), targetPath = pathScope.text.trim(), endpoint = endpoint.text.trim(), model = model.text.trim(), preferManaged = preferred.isSelected,
            projectRoot = if (method.id == "ai.external-model" || !directOnly && mode.selectedIndex == 0) Path.of(requireNotNull(project.basePath)).toRealPath().toString() else "",
            allowedOperations = previous?.allowedOperations?.toMutableList() ?: mutableListOf("SCAN"), requestedActions = previous?.requestedActions?.toMutableList() ?: mutableListOf("WARNING"))
        val result = methodProgress(project, "Verifying ${method.title} connection") { OptionalMethodConnections.save(project, method, connection) }
        result.onSuccess { super.doOKAction() }.onFailure { setErrorText(it.message ?: "Connection setup failed.") }
    }
}

internal class OptionalMethodPermissionsDialog(private val project: Project, private val method: SecurityMethod, report: ScanReport? = null, private val custom: Boolean = false) : DialogWrapper(project) {
    private val settings = WrapperGuardSettings.getInstance()
    private val initial = (report?.providerRuns?.firstOrNull { it.descriptor.id == method.providerId }?.permissions
        ?: settings.permissions(requireNotNull(method.providerId))).let { permissions ->
        if (settings.state.providers.none { it.id == method.providerId } && report?.providerRuns?.none { it.descriptor.id == method.providerId } != false)
            permissions.copy(timeoutMs = if (method.id in setOf("sca.trivy", "sca.osv")) 150_000 else 45_000)
        else permissions
    }
    private val advisory = method.id in setOf("research.script-semantics", "ai.external-model")
    private val actions = VettingAction.entries.associateWith { action -> JBCheckBox(action.displayName(), action in initial.actions).apply {
        isEnabled = !advisory || action !in setOf(VettingAction.BLOCK_BUILD, VettingAction.BLOCK_SYNC)
        if (!isEnabled) isSelected = false
    } }
    private val required = JBCheckBox("Require a successful result before permitted build/sync operations", initial.required && !advisory).apply { isEnabled = !advisory }
    private val timeout = JSpinner(SpinnerNumberModel(initial.timeoutMs, 100L, 300_000L, 100L))
    private val connection = settings.state.methodConnections.firstOrNull { it.methodId == method.id }
    private val buildOperation = JBCheckBox("Allow this connection during IDE build / run", connection?.allowedOperations?.contains("BUILD") == true).apply { isEnabled = !advisory && connection != null }
    private val syncOperation = JBCheckBox("Allow this connection during Gradle sync", connection?.allowedOperations?.contains("SYNC") == true).apply { isEnabled = !advisory && connection != null }
    init { title = "Permissions · ${method.title}"; setOKButtonText("Save permissions"); init() }
    override fun createCenterPanel(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(textNote(if (advisory) "This method is advisory. Its output cannot stop a build or sync." else "Permissions cap provider requests. A granted action alone does not enable a method or sandbox its code."))
        add(Box.createVerticalStrut(10)); actions.values.forEach(::add); add(Box.createVerticalStrut(10)); if (!custom) { add(buildOperation); add(syncOperation) }; add(required)
        add(JPanel(BorderLayout(8, 0)).apply { add(JBLabel("Host timeout (milliseconds)"), BorderLayout.WEST); add(timeout, BorderLayout.CENTER) })
        add(textNote("Saving applies these host grants to this provider ID, including a connection from an existing configuration. Its command definition is preserved."))
        if (custom) add(textNote("Enabled adapters receive the operation in their structured request: scan, build or sync. These host grants cap their requested actions. Input declarations cover only the files explicitly declared; they do not sandbox provider code."))
        else add(textNote("New profiles start with explicit scans and warnings only. These controls also update the generated profile's requested actions and allowed operations. Imported reports do not establish current source freshness, even when used before a build or sync."))
    }
    override fun doOKAction() {
        val state = settings.state
        val previous = state.providers.firstOrNull { it.id == method.providerId }
        val provider = WrapperGuardSettings.ProviderState(requireNotNull(method.providerId), previous?.enabled ?: initial.enabled,
            actions.filterValues { it.isSelected }.keys.map { it.name }.toMutableList(), required.isSelected, (timeout.value as Number).toLong(), overrideConfiguredPermissions = true)
        try {
            if (!custom) OptionalMethodConnections.updateProfilePermissions(method,
                listOf("SCAN") + (if (buildOperation.isSelected) listOf("BUILD") else emptyList()) + (if (syncOperation.isSelected) listOf("SYNC") else emptyList()), provider.actions)
            state.providers = (state.providers.filterNot { it.id == method.providerId } + provider).toMutableList()
            try { OptionalMethodConnections.refreshGeneratedConfiguration() } finally { OptionalMethodConnections.changed() }
            super.doOKAction()
        } catch (e: Exception) { setErrorText(e.message ?: "Permissions could not be saved.") }
    }
}

internal fun methodGuide(id: String): String = when (id) {
    "sast.semgrep" -> "https://semgrep.dev/docs/getting-started/quickstart"
    "sast.bandit" -> "https://bandit.readthedocs.io/en/latest/start.html"
    "sast.powershell" -> "https://learn.microsoft.com/en-us/powershell/utility-modules/psscriptanalyzer/installing-psscriptanalyzer"
    "sca.trivy" -> "https://trivy.dev/latest/getting-started/installation/"
    "sca.osv" -> "https://google.github.io/osv-scanner/installation/"
    "dast.zap" -> "https://www.zaproxy.org/download/"
    "ai.external-model" -> "https://lmstudio.ai/docs/developer/openai-compat"
    else -> "https://github.com/Sal7one/NewWrapperGaurd/blob/master/docs/integrations.md"
}
