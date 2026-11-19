package com.nuvio.app.features.updater

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_download_failed_http
import nuvio.composeapp.generated.resources.updates_downloaded_file_missing
import nuvio.composeapp.generated.resources.updates_empty_download_body
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.compose.resources.getString
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

object AndroidAppUpdaterPlatform {
    private const val preferencesName = "nuvio_updater"
    private const val ignoredTagKey = "ignored_release_tag"
    private const val updateChannelKey = "update_channel"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun getSupportedAbis(): List<String> = Build.SUPPORTED_ABIS?.toList().orEmpty()

    fun isDebugBuild(): Boolean {
        val context = appContext ?: return false
        return context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    fun getIgnoredTag(): String? =
        preferences().getString(ignoredTagKey, null)

    fun setIgnoredTag(tag: String?) {
        preferences().edit().apply {
            if (tag == null) remove(ignoredTagKey) else putString(ignoredTagKey, tag)
        }.apply()
    }

    fun getUpdateChannel(): String? = preferences().getString(updateChannelKey, null)

    fun setUpdateChannel(channel: String) {
        preferences().edit().putString(updateChannelKey, channel).apply()
    }

    fun deleteDownloadedApk(path: String) {
        File(path).delete()
    }

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val context = requireContext()
            val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val destination = File(File(context.cacheDir, "updates"), safeName)
            destination.parentFile?.mkdirs()
            if (destination.exists()) {
                destination.delete()
            }

            val request = Request.Builder()
                .url(assetUrl)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error(runBlocking { getString(Res.string.updates_download_failed_http, response.code) })
                }

                val body = response.body ?: error(runBlocking { getString(Res.string.updates_empty_download_body) })
                val totalBytes = body.contentLength().takeIf { it > 0L }
                body.byteStream().use { input ->
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloadedBytes = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            currentCoroutineContext().ensureActive()
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            onProgress(downloadedBytes, totalBytes)
                        }
                        output.flush()
                    }
                }
            }

            destination.absolutePath
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        val context = appContext ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.packageManager.canRequestPackageInstalls()
            } catch (_: SecurityException) {
            
                true
            }
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installDownloadedApk(path: String): Result<Unit> = runCatching {
        val context = requireContext()
        val apkFile = File(path)
        check(apkFile.isFile) { runBlocking { getString(Res.string.updates_downloaded_file_missing) } }
        verifyDownloadedApk(apkFile, context)

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        context.startActivity(intent)
    }

    private fun preferences() = requireContext().getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun requireContext(): Context =
        requireNotNull(appContext) { "AndroidAppUpdaterPlatform.initialize must be called before use." }

    @Suppress("DEPRECATION")
    private fun verifyDownloadedApk(apkFile: File, context: Context) {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val candidate = requireNotNull(packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)) {
            "The downloaded file is not a valid APK."
        }
        val installed = packageManager.getPackageInfo(context.packageName, flags)
        check(candidate.packageName == context.packageName) {
            "The downloaded APK belongs to a different application."
        }
        val candidateVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            candidate.longVersionCode
        } else {
            candidate.versionCode.toLong()
        }
        val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            installed.longVersionCode
        } else {
            installed.versionCode.toLong()
        }
        check(candidateVersion > installedVersion) {
            "The downloaded APK is not a newer version."
        }
        check(
            haveSameSignerCertificates(
                installed.signerCertificates(),
                candidate.signerCertificates(),
            ),
        ) {
            "The downloaded APK signing certificate does not match this installation."
        }

        val supportedAbiDirectories = Build.SUPPORTED_ABIS.map { "lib/$it/" }
        val hasCompatibleAbi = ZipFile(apkFile).use { archive ->
            val entries = archive.entries()
            var compatible = false
            while (entries.hasMoreElements() && !compatible) {
                val entryName = entries.nextElement().name
                compatible = supportedAbiDirectories.any(entryName::startsWith)
            }
            compatible
        }
        check(hasCompatibleAbi) {
            "The downloaded APK does not contain native libraries for this device."
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerCertificates(): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.map(Signature::toByteArray).orEmpty()
        } else {
            signatures?.map(Signature::toByteArray).orEmpty()
        }
}

internal fun haveSameSignerCertificates(
    installed: List<ByteArray>,
    candidate: List<ByteArray>,
): Boolean =
    installed.isNotEmpty() &&
        installed.size == candidate.size &&
        installed.all { installedCertificate ->
            candidate.any { candidateCertificate -> installedCertificate.contentEquals(candidateCertificate) }
        }
