package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdaterSignatureTest {
    @Test
    fun requiresExactNonEmptySignerSets() {
        val firstSigner = byteArrayOf(1, 2, 3)
        val secondSigner = byteArrayOf(4, 5, 6)

        assertTrue(haveSameSignerCertificates(listOf(firstSigner), listOf(firstSigner.copyOf())))
        assertTrue(haveSameSignerCertificates(listOf(firstSigner, secondSigner), listOf(secondSigner, firstSigner)))
        assertFalse(haveSameSignerCertificates(emptyList(), emptyList()))
        assertFalse(haveSameSignerCertificates(listOf(firstSigner), listOf(secondSigner)))
        assertFalse(haveSameSignerCertificates(listOf(firstSigner), listOf(firstSigner, secondSigner)))
    }
}
