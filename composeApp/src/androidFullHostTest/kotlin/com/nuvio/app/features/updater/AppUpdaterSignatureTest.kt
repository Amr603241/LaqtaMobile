package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    @Test
    fun downloadIdentitySeparatesReleasesAndSanitizesNames() {
        val first = downloadFileName("https://example.com/v1/app.apk", "../app.apk")
        assertEquals(first, downloadFileName("https://example.com/v1/app.apk", "../app.apk"))
        assertNotEquals(first, downloadFileName("https://example.com/v2/app.apk", "../app.apk"))
        assertFalse('/' in first)
        assertFailsWith<IllegalArgumentException> { downloadFileName("https://example.com/v1/file", "file.zip") }
    }
}
