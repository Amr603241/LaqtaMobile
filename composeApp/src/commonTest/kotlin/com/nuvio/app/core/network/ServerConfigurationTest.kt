package com.nuvio.app.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigurationTest {
    @Test
    fun emailPasswordAuthRequiresEnabledCapabilityAndCompleteConfiguration() {
        assertTrue(configuration(url = "https://accounts.example", key = "public-key").hasEmailPasswordAuth)
        assertFalse(configuration(url = "", key = "public-key").hasEmailPasswordAuth)
        assertFalse(configuration(url = "https://accounts.example", key = "").hasEmailPasswordAuth)
        assertFalse(
            configuration(
                url = "https://accounts.example",
                key = "public-key",
                enabled = false,
            ).hasEmailPasswordAuth,
        )
    }

    @Test
    fun googleOAuthRequiresExplicitCapabilityAndCompleteConfiguration() {
        assertTrue(configuration(url = "https://accounts.example", key = "public-key", googleOAuth = true).hasGoogleOAuth)
        assertFalse(configuration(url = "https://accounts.example", key = "public-key").hasGoogleOAuth)
        assertFalse(configuration(url = "", key = "public-key", googleOAuth = true).hasGoogleOAuth)
        assertFalse(configuration(url = "https://accounts.example", key = "", googleOAuth = true).hasGoogleOAuth)
    }

    private fun configuration(
        url: String,
        key: String,
        enabled: Boolean = true,
        googleOAuth: Boolean = false,
    ) = ServerConfiguration(
        backendUrl = url,
        publishableKey = key,
        capabilities = ServerCapabilities(
            emailPasswordAuth = enabled,
            tvLogin = false,
            watchParty = false,
            googleOAuth = googleOAuth,
        ),
        isCustom = false,
    )
}
