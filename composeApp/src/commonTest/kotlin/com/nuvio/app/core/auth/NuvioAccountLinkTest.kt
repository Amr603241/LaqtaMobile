package com.nuvio.app.core.auth

import com.nuvio.app.features.library.LibraryItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NuvioAccountLinkTest {
    private val firstOwner = NuvioLinkOwner("first", "https://laqta.example")

    @Test
    fun `profile response ignores extra fields but requires pin status`() {
        val json = """[{"profile_index":2,"name":"Viewer","pin_enabled":false,"id":"unused"}]"""
        assertEquals(listOf(NuvioRemoteProfile(2, "Viewer", false)), decodeNuvioProfiles(json))
        assertFailsWith<Exception> { decodeNuvioProfiles("""[{"profile_index":2,"name":"Viewer"}]""") }
    }

    @Test
    fun `link and disconnect use only the independent session`() = runTest {
        val session = FakeSession()
        val controller = NuvioLinkController({ firstOwner }, { session })

        controller.connect(" user@example.test ", "password")
        assertEquals(NuvioLinkState.Connected("user@example.test"), controller.state.value)

        controller.disconnect()
        assertEquals(NuvioLinkState.Disconnected, controller.state.value)
        assertEquals(listOf("signIn", "signOut", "close"), session.calls)
    }

    @Test
    fun `account change during sign in revokes and closes the candidate`() = runTest {
        var owner: NuvioLinkOwner? = firstOwner
        val session = FakeSession(onSignIn = { owner = NuvioLinkOwner("other", firstOwner.serverUrl) })
        val controller = NuvioLinkController({ owner }, { session })

        assertFailsWith<IllegalStateException> { controller.connect("user@example.test", "password") }
        assertEquals(NuvioLinkState.Disconnected, controller.state.value)
        assertEquals(listOf("signIn", "signOut", "close"), session.calls)
    }

    @Test
    fun `failed sign in closes candidate and leaves account disconnected`() = runTest {
        val session = FakeSession(onSignIn = { error("sign-in failed") })
        val controller = NuvioLinkController({ firstOwner }, { session })

        assertFailsWith<IllegalStateException> { controller.connect("user@example.test", "password") }
        assertEquals(NuvioLinkState.Disconnected, controller.state.value)
        assertEquals(listOf("signIn", "close"), session.calls)
    }

    @Test
    fun `changed owner disconnects the previous account`() = runTest {
        var owner: NuvioLinkOwner? = firstOwner
        val session = FakeSession()
        val controller = NuvioLinkController({ owner }, { session })
        controller.connect("user@example.test", "password")

        owner = null
        controller.disconnectIfOwnerChanged()

        assertEquals(NuvioLinkState.Disconnected, controller.state.value)
        assertEquals(listOf("signIn", "signOut", "close"), session.calls)
    }

    @Test
    fun `library sync copies missing items both ways without deleting`() = runTest {
        val remoteOnly = LibraryItem(id = "tt1", type = "movie", name = "Remote", savedAtEpochMs = 1)
        val shared = LibraryItem(id = "tt2", type = "movie", name = "Shared", savedAtEpochMs = 1)
        val localOnly = LibraryItem(id = "tt3", type = "series", name = "Local", savedAtEpochMs = 1)
        val session = FakeSession(remote = listOf(remoteOnly, shared))
        val controller = NuvioLinkController({ firstOwner }, { session })
        controller.connect("user@example.test", "password")

        var imported: List<LibraryItem> = emptyList()
        val result = controller.syncLibrary(
            profileId = 2,
            localItems = { listOf(shared.copy(type = " Movie "), localOnly) },
            checkLocal = { true },
            importItems = { imported = it; 1 },
        )

        assertEquals(NuvioLibrarySyncResult(imported = 1, exported = 1), result)
        assertEquals(listOf(localOnly), session.pushed)
        assertEquals(listOf(remoteOnly, shared), imported)
        assertEquals(2, session.pulledProfile)
        assertEquals(2, session.pushedProfile)
    }

    @Test
    fun `library sync requires a connected session`() = runTest {
        val controller = NuvioLinkController({ firstOwner }, { FakeSession() })
        assertFailsWith<IllegalStateException> {
            controller.syncLibrary(1, { emptyList() }, { true }, { 0 })
        }
    }

    @Test
    fun `unknown or locked remote profiles cannot be synced`() = runTest {
        val session = FakeSession()
        val controller = NuvioLinkController({ firstOwner }, { session })
        controller.connect("user@example.test", "password")
        for (profileId in listOf(3, 99)) {
            assertFailsWith<IllegalStateException> {
                controller.syncLibrary(profileId, { emptyList() }, { true }, { 0 })
            }
        }
        assertEquals(null, session.pulledProfile)
        assertEquals(null, session.pushedProfile)
    }

    @Test
    fun `account or local profile change during pull prevents writes`() = runTest {
        for (changeOwner in listOf(true, false)) {
            var owner: NuvioLinkOwner? = firstOwner
            var localCurrent = true
            val session = FakeSession(onPull = {
                if (changeOwner) owner = null else localCurrent = false
            })
            val controller = NuvioLinkController({ owner }, { session })
            controller.connect("user@example.test", "password")
            var imported = false
            assertFailsWith<IllegalStateException> {
                controller.syncLibrary(
                    2,
                    { listOf(LibraryItem("x", "movie", "x", savedAtEpochMs = 1)) },
                    { localCurrent },
                ) {
                    imported = true
                    0
                }
            }
            assertEquals(null, session.pushedProfile)
            assertEquals(false, imported)
        }
    }

    private class FakeSession(
        private val onSignIn: () -> Unit = {},
        private val onPull: () -> Unit = {},
        private val remote: List<LibraryItem> = emptyList(),
    ) : NuvioLinkSession {
        val calls = mutableListOf<String>()
        var pushed: List<LibraryItem> = emptyList()
        var pulledProfile: Int? = null
        var pushedProfile: Int? = null

        override suspend fun pullProfiles(): List<NuvioRemoteProfile> = listOf(
            NuvioRemoteProfile(2, "Nuvio", pinEnabled = false),
            NuvioRemoteProfile(3, "Locked", pinEnabled = true),
        )

        override suspend fun pullLibrary(profileId: Int): List<LibraryItem> {
            pulledProfile = profileId
            onPull()
            return remote
        }

        override suspend fun pushLibrary(profileId: Int, items: List<LibraryItem>) {
            pushedProfile = profileId
            pushed = items
        }

        override suspend fun signIn(email: String, password: String): String {
            calls += "signIn"
            onSignIn()
            return email
        }

        override suspend fun signOut() {
            calls += "signOut"
        }

        override suspend fun close() {
            calls += "close"
        }
    }
}
