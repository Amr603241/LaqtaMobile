package com.nuvio.app.features.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaybackTransferSpeedTest {
    private var timeMs = 0L
    private val meter = PlaybackTransferSpeed { timeMs }
    private val source = ByteArrayDataSource(byteArrayOf(1))
    private val remote = DataSpec(Uri.parse("https://example.com/video"))

    @Test
    fun reportsMeasuredBytesOverTimeAndExpires() {
        assertNull(meter.currentBytesPerSecond())
        meter.onBytesTransferred(source, remote, true, 1_000)
        timeMs = 1_000
        meter.onBytesTransferred(source, remote, true, 1_000)
        assertEquals(2_000L, meter.currentBytesPerSecond())
        timeMs = 6_001
        assertNull(meter.currentBytesPerSecond())
    }

    @Test
    fun ignoresLocalAndLoopbackTransfers() {
        val local = DataSpec(Uri.parse("content://media/video"))
        val loopback = DataSpec(Uri.parse("http://127.0.0.1:8080/video"))
        meter.onBytesTransferred(source, local, false, 1_000)
        meter.onBytesTransferred(source, loopback, true, 1_000)
        timeMs = 1_000
        meter.onBytesTransferred(source, local, false, 1_000)
        meter.onBytesTransferred(source, loopback, true, 1_000)
        assertNull(meter.currentBytesPerSecond())
    }

    @Test
    fun doesNotCountAnIdleGapAsActiveTransferTime() {
        meter.onBytesTransferred(source, remote, true, 1_000)
        timeMs = 6_000
        meter.onBytesTransferred(source, remote, true, 1_000)
        assertNull(meter.currentBytesPerSecond())
        timeMs = 7_000
        meter.onBytesTransferred(source, remote, true, 1_000)
        assertEquals(2_000L, meter.currentBytesPerSecond())
    }
}
