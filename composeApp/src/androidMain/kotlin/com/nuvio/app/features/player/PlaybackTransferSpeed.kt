package com.nuvio.app.features.player

import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackTransferSpeed(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : TransferListener {
    private var windowStartMs: Long? = null
    private var windowBytes = 0L
    private var sampleBytesPerSecond: Long? = null
    private var sampleTimeMs = 0L

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    @Synchronized
    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        val uri = dataSpec.uri
        if (!isNetwork || bytesTransferred <= 0 ||
            uri.scheme?.lowercase() !in listOf("http", "https") ||
            uri.host?.lowercase() in listOf("localhost", "127.0.0.1", "::1", "[::1]")
        ) return

        val now = nowMs()
        val start = windowStartMs
        if (start == null || now < start || now - start > FRESHNESS_MS) {
            windowStartMs = now
            windowBytes = bytesTransferred.toLong()
            return
        }
        windowBytes += bytesTransferred
        val elapsed = now - start
        if (elapsed >= SAMPLE_INTERVAL_MS) {
            sampleBytesPerSecond = windowBytes * 1_000L / elapsed
            sampleTimeMs = now
            windowStartMs = now
            windowBytes = 0L
        }
    }

    @Synchronized
    fun currentBytesPerSecond(): Long? {
        val now = nowMs()
        return sampleBytesPerSecond?.takeIf { it > 0 && now >= sampleTimeMs && now - sampleTimeMs <= FRESHNESS_MS }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val FRESHNESS_MS = 5_000L
    }
}
