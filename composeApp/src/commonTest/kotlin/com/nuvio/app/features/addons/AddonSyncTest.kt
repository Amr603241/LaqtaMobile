package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AddonSyncTest {
    private val addon = ManagedAddon(manifestUrl = "https://example.test/manifest.json")

    @Test
    fun `remote pull does not overwrite local edits made during request`() {
        val signature = listOf(addon).syncSignature()
        val current = AddonsUiState(addons = listOf(addon.copy(enabled = false)))

        assertNull(reconcileAddonPull(current, emptyList(), signature))
    }

    @Test
    fun `empty remote list is applied when local data was not edited`() {
        val current = AddonsUiState(addons = listOf(addon))

        val result = reconcileAddonPull(current, emptyList(), current.addons.syncSignature())

        assertNotNull(result)
        assertEquals(emptyList(), result.addons)
    }

    @Test
    fun `manifest refresh does not block applying remote addon settings`() {
        val signature = listOf(addon).syncSignature()
        val current = AddonsUiState(addons = listOf(addon.copy(isRefreshing = true)))

        val result = reconcileAddonPull(
            current,
            listOf(AddonRow(url = "https://example.test", enabled = false)),
            signature,
        )

        assertNotNull(result)
        assertEquals(false, result.addons.single().enabled)
        assertEquals(addon.manifestUrl, result.addons.single().manifestUrl)
    }

    @Test
    fun `pending pushes replace only the same account and server snapshot`() {
        val original = PendingAddonPush(
            userId = "user-a",
            backendUrl = "https://server-a.test",
            addons = emptyList(),
        )
        val otherServer = original.copy(backendUrl = "https://server-b.test")
        val otherAccount = original.copy(userId = "user-b")
        val updated = original.copy(addons = listOf(AddonPushItem(url = addon.manifestUrl)))

        assertEquals(
            listOf(otherServer, otherAccount, updated),
            replacePendingAddonPush(listOf(original, otherServer, otherAccount), updated),
        )
    }

    @Test
    fun `stale push completion does not remove a newer snapshot`() {
        val old = PendingAddonPush(
            userId = "user-a",
            backendUrl = "https://server-a.test",
            addons = emptyList(),
        )
        val newer = old.copy(addons = listOf(AddonPushItem(url = addon.manifestUrl)))

        assertEquals(listOf(newer), removePendingAddonPush(listOf(newer), old))
        assertEquals(emptyList(), removePendingAddonPush(listOf(newer), newer))
    }
}
