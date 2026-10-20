package com.sal7one.wrapperguard.intellij.settings

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ManagedStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(path: java.nio.file.Path) = ManagedArtifactState(path.toString(), "Method profile", ManagedStorage.fingerprint(path).digest)

    @Test fun inventoryPreservesOriginalChangedAndActiveFiles() {
        val root = temporary.newFolder("storage").toPath().toRealPath()
        val owned = Files.writeString(root.resolve("owned.profile.json"), "generated")
        val modified = Files.writeString(root.resolve("modified.profile.json"), "generated")
        val active = Files.writeString(root.resolve("active.profile.json"), "generated")
        val original = Files.writeString(root.resolve("user-original.json"), "user")
        val records = listOf(record(owned), record(modified), record(active))
        Files.writeString(modified, "user changed")
        val inventory = ManagedStorage.inventory(root, records, listOf(active)).associateBy { it.path }
        assertTrue(inventory.getValue(owned).removable)
        assertFalse(inventory.getValue(modified).removable)
        assertFalse(inventory.getValue(active).removable)
        assertFalse(inventory.getValue(original).removable)
        assertTrue(inventory.getValue(original).status.contains("unregistered"))
    }

    @Test fun activeProfileReferencesAndUnknownSdkConfigurationPreserveEnvironments() {
        val root = temporary.newFolder("profile-references").toPath().toRealPath()
        val environment = Files.createDirectory(root.resolve("sdk-owned"))
        val executable = Files.writeString(environment.resolve("analyzer"), "owned")
        val profile = Files.writeString(root.resolve("active.profile.json"), """{"runner":{"executable":"$executable"}}""")
        val state = WrapperGuardSettings.State(methodConnections = mutableListOf(MethodConnectionState(profilePath = profile.toString())))
        val owned = record(environment)
        assertFalse(ManagedStorage.inventory(root, listOf(owned), ManagedStorage.activePaths(state)).first { it.path == environment }.removable)
        state.methodConnections.clear()
        state.customProviders.add(CustomProviderState(id = "unknown.sdk", kind = "sdk"))
        state.providers.add(WrapperGuardSettings.ProviderState(id = "unknown.sdk", enabled = true))
        state.managedArtifacts.add(owned.copy(kind = "SDK environment"))
        assertTrue(ManagedStorage.activePaths(state).contains(environment))
    }

    @Test fun cleanupRechecksFingerprintAndActiveEnvironmentReferences() {
        val root = temporary.newFolder("recheck").toPath().toRealPath()
        val environment = Files.createDirectory(root.resolve("sdk-uuid"))
        val python = Files.writeString(environment.resolve("python"), "owned")
        val owned = record(environment)
        assertThrows(IllegalArgumentException::class.java) { ManagedStorage.removeOwned(root, owned, listOf(python)) }
        Files.writeString(python, "modified")
        assertThrows(IllegalArgumentException::class.java) { ManagedStorage.removeOwned(root, owned, emptyList()) }
        assertTrue(Files.exists(python))
    }

    @Test fun cleanupNeverFollowsOwnedDirectoryLinksIntoOriginalFiles() {
        val root = temporary.newFolder("safe-cleanup").toPath().toRealPath()
        val original = temporary.newFile("outside-original").toPath().toRealPath()
        Files.writeString(original, "original")
        val environment = Files.createDirectory(root.resolve("sdk-owned"))
        Files.writeString(environment.resolve("owned.txt"), "generated")
        Files.createSymbolicLink(environment.resolve("external-link"), original)
        val owned = record(environment)
        Files.newDirectoryStream(root).use { stream ->
            if (stream !is java.nio.file.SecureDirectoryStream<*>) {
                assertThrows(IllegalArgumentException::class.java) { ManagedStorage.removeOwned(root, owned, emptyList()) }
                assertTrue(Files.exists(environment)); return
            }
        }
        ManagedStorage.removeOwned(root, owned, emptyList())
        assertFalse(Files.exists(environment))
        assertEquals("original", Files.readString(original))
    }

    @Test fun replacedArtifactAndOutsidePathAreNeverRemoved() {
        val root = temporary.newFolder("boundaries").toPath().toRealPath()
        val file = Files.writeString(root.resolve("owned"), "same")
        val owned = record(file)
        Files.delete(file); Files.writeString(file, "same")
        assertThrows(IllegalArgumentException::class.java) { ManagedStorage.removeOwned(root, owned, emptyList()) }
        val outside = temporary.newFile("original").toPath().toRealPath()
        assertThrows(IllegalArgumentException::class.java) { ManagedStorage.removeOwned(root, record(outside), emptyList()) }
        assertTrue(Files.exists(outside))
    }
}
