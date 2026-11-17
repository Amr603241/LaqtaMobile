package com.nuvio.app.core.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NuvioAccountLinkTest {
    private val firstOwner = NuvioLinkOwner("first", "https://laqta.example")

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

    private class FakeSession(
        private val onSignIn: () -> Unit = {},
    ) : NuvioLinkSession {
        val calls = mutableListOf<String>()

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
