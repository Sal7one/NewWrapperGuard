package com.sal7one.wrapperguard.intellij.execution

import com.sal7one.wrapperguard.core.inputs.*
import com.sal7one.wrapperguard.core.model.*
import com.sal7one.wrapperguard.core.vetting.*
import com.sal7one.wrapperguard.intellij.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DeclaredProviderInputsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun customGlobsAndExternalFilesChangeSealAndWatcherWithoutWatchingSiblingProject() {
        val root = temporary.newFolder("project").toPath().toRealPath()
        val sibling = temporary.newFolder("project-other").toPath().toRealPath()
        val file = Files.writeString(root.resolve("check.custom"), "first")
        val external = temporary.newFile("rules.custom").toPath().toRealPath()
        val scope = ProviderInputScope(includes = listOf("**/*.custom"), externalFiles = listOf(external.toString()))
        val snapshot = DeclaredInputSnapshot(listOf(scope), emptyList(), emptyList())
        val watcher = DeclaredInputWatcher(snapshot)
        val before = NativeProviderInputs.capture(root, snapshot, listOf(root, sibling))
        Files.writeString(file, "changed")
        assertNotEquals(before, NativeProviderInputs.capture(root, snapshot, listOf(root, sibling)))
        assertTrue(watcher.matches(root, file.toString(), false, emptySet()))
        assertTrue(watcher.matches(root, external.toString(), false, emptySet()))
        assertFalse(watcher.matches(root, sibling.resolve("check.custom").toString(), false, emptySet()))
        val sourceChanged = NativeProviderInputs.capture(root, snapshot, listOf(root, sibling))
        Files.writeString(external, "rules changed")
        assertNotEquals(sourceChanged, NativeProviderInputs.capture(root, snapshot, listOf(root, sibling)))
        Files.delete(external)
        assertTrue(watcher.matches(root, external.toString(), false, emptySet()))
        assertThrows(InputSealException::class.java) { NativeProviderInputs.capture(root, snapshot, listOf(root, sibling)) }
    }

    @Test fun exactRecursiveNamesAreLiteralAndNeverAuthorizeExternalReads() {
        val root = temporary.newFolder("recursive").toPath().toRealPath()
        val target = Files.writeString(root.resolve("run[prod]"), "first")
        val outside = temporary.newFile("outside").toPath().toRealPath()
        val paths = observedProjectPaths(root, listOf("run[prod]", "../outside", outside.toString(), target.toString()))
        assertEquals(setOf(target), paths)
        val before = ProviderInputSealer.captureProjectFiles(root, paths.toList()).hashes
        Files.writeString(target, "second")
        assertNotEquals(before, ProviderInputSealer.captureProjectFiles(root, paths.toList()).hashes)
        assertTrue(DeclaredInputWatcher().matches(root, target.toString(), false, paths))
        assertFalse(DeclaredInputWatcher().matches(root, outside.toString(), false, paths))
    }

    @Test fun selectedModuleDirectoryChangesAreHashedAndSymlinkTreesRejected() {
        val root = temporary.newFolder("module-project").toPath().toRealPath()
        val module = temporary.newFolder("module").toPath().toRealPath()
        val file = Files.writeString(module.resolve("rules.psm1"), "first")
        val snapshot = DeclaredInputSnapshot(emptyList(), NativeProviderInputs.regularFiles(module), emptyList())
        val before = NativeProviderInputs.capture(root, snapshot, listOf(root))
        Files.writeString(file, "second")
        assertNotEquals(before, NativeProviderInputs.capture(root, snapshot, listOf(root)))
        Files.createSymbolicLink(module.resolve("linked.psm1"), file)
        assertThrows(IllegalArgumentException::class.java) { NativeProviderInputs.regularFiles(module) }
    }

    @Test fun metadataDeclarationPreservesLiteralArgumentsAndProjectBinding() {
        val root = temporary.newFolder("binding").toPath().toRealPath()
        val other = temporary.newFolder("other").toPath().toRealPath()
        val provider = CustomProviderState(id = "company.check", displayName = "Company check", executable = "/bin/echo",
            arguments = mutableListOf("a b", "", "\"quoted\"", "$(literal)"), category = "SAST", tags = mutableListOf("internal"),
            scopeDeclared = true, includes = mutableListOf("src/**/*.custom"), projectRoots = mutableListOf(root.toString()))
        val config = provider.command(ProviderPermissions(enabled = false, actions = setOf(VettingAction.WARNING)))
        assertEquals(listOf("/bin/echo", "a b", "", "\"quoted\"", "$(literal)"), config.command)
        assertEquals("SAST", config.descriptor().category)
        assertEquals(listOf("src/**/*.custom"), config.descriptor().inputScope!!.includes)
        assertFalse(config.permissions.enabled)
        assertEquals(setOf(VettingAction.WARNING), config.permissions.actions)
        assertTrue(CustomProviders.appliesTo(provider, root))
        assertFalse(CustomProviders.appliesTo(provider, other))
    }

    @Test fun disabledMissingAdapterCanBeEditedButCannotPassReadinessOrEscapeProjectBoundary() {
        val root = temporary.newFolder("repair-project").toPath().toRealPath()
        val outside = temporary.newFolder("removed-tools").toPath().toRealPath()
        val provider = CustomProviderState(id = "repair.provider", displayName = "Renamed provider", executable = outside.resolve("gone/tool").toString(),
            scopeDeclared = true, externalFiles = mutableListOf(outside.resolve("gone/report.json").toString()))
        CustomProviders.validate(provider, listOf(root), requireReady = false)
        assertThrows(Exception::class.java) { CustomProviders.validate(provider, listOf(root), requireReady = true) }
        assertThrows(IllegalArgumentException::class.java) { CustomProviders.validate(provider.copy(executable = root.resolve("gone/tool").toString()), listOf(root), requireReady = false) }
        assertThrows(IllegalArgumentException::class.java) { CustomProviders.validate(provider.copy(arguments = mutableListOf("invalid\u0000arg")), listOf(root), requireReady = false) }
    }

    @Test fun sdkOverlayKeepsProducerIdentityAndScopeUntilUserDeclaresReplacement() {
        val sdk = object : VettingProvider {
            override val descriptor = ProviderDescriptor("vendor.sdk", "SDK name", "9.1").copyWithMetadata(inputScope = ProviderInputScope(listOf("sdk/**")))
            override fun scan(request: VettingRequest) = ProviderResult()
        }
        val metadata = CustomProviderState(id = "vendor.sdk", displayName = "Changed name", version = "0", kind = "sdk", category = "SAST")
        val retained = CustomProviders.overlay(sdk, metadata).descriptor
        assertEquals("SDK name", retained.displayName); assertEquals("9.1", retained.version)
        assertEquals(listOf("sdk/**"), retained.inputScope!!.includes)
        val replaced = CustomProviders.overlay(sdk, metadata.copy(scopeDeclared = true, includes = mutableListOf("custom/**"))).descriptor
        assertEquals(listOf("custom/**"), replaced.inputScope!!.includes)
    }
}
