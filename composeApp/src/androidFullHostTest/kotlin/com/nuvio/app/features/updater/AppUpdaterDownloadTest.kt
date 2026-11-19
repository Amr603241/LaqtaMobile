package com.nuvio.app.features.updater

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppUpdaterDownloadTest {
    @Test
    fun reattachesToSystemDownloadAfterMonitoringIsCancelled() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("nuvio_updater", Context.MODE_PRIVATE).edit().clear().commit()
        AndroidAppUpdaterPlatform.initialize(context)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val url = "https://example.com/v2/app.apk"
        val name = downloadFileName(url, "app.apk")
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates/$name")
        val request = DownloadManager.Request(Uri.parse(url))
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$name")
        val id = manager.enqueue(request)
        Shadow.extract<ShadowDownloadManager.ShadowRequest>(request).setStatus(DownloadManager.STATUS_RUNNING)
        context.getSharedPreferences("nuvio_updater", Context.MODE_PRIVATE).edit()
            .putLong("download_id", id)
            .putString("download_url", url)
            .putString("download_path", destination.absolutePath)
            .commit()

        suspend fun monitorAndCancel() {
            val progress = CompletableDeferred<Unit>()
            val job = launch {
                AndroidAppUpdaterPlatform.downloadApk(url, "app.apk") { _, _ -> progress.complete(Unit) }
            }
            withTimeout(5_000) { progress.await() }
            job.cancelAndJoin()
        }

        monitorAndCancel()
        AndroidAppUpdaterPlatform.initialize(context)
        monitorAndCancel()
        assertEquals(1, shadowOf(manager).requestCount)
    }
}
