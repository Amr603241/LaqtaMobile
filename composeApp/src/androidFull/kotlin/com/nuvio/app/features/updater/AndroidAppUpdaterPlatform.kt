package com.nuvio.app.features.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_download_failed
import nuvio.composeapp.generated.resources.updates_downloaded_file_missing
import org.jetbrains.compose.resources.getString
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

object AndroidAppUpdaterPlatform {
    private const val preferencesName = "nuvio_updater"
    private const val ignoredTagKey = "ignored_release_tag"
    private const val updateChannelKey = "update_channel"
    private const val downloadIdKey = "download_id"
    private const val downloadUrlKey = "download_url"
    private const val downloadPathKey = "download_path"
    private val downloadLock = Any()

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

    fun savePendingUpdate(update: AppUpdate, channel: UpdateChannel) {
        check(preferences().edit()
            .putString("pending_channel", channel.storedValue)
            .putString("pending_tag", update.tag)
            .putString("pending_title", update.title)
            .putString("pending_notes", update.notes)
            .putString("pending_release_url", update.releaseUrl)
            .putString("pending_asset_name", update.assetName)
            .putString("pending_asset_url", update.assetUrl)
            .putLong("pending_asset_size", update.assetSizeBytes ?: -1L)
            .commit()
        ) { "Unable to save the pending update." }
    }

    fun getPendingUpdate(channel: UpdateChannel): AppUpdate? {
        val prefs = preferences()
        if (prefs.getString("pending_channel", null) != channel.storedValue) return null
        val tag = prefs.getString("pending_tag", null) ?: return null
        val assetName = prefs.getString("pending_asset_name", null) ?: return null
        val assetUrl = prefs.getString("pending_asset_url", null) ?: return null
        return AppUpdate(
            tag = tag,
            title = prefs.getString("pending_title", null) ?: tag,
            notes = prefs.getString("pending_notes", null).orEmpty(),
            releaseUrl = prefs.getString("pending_release_url", null),
            assetName = assetName,
            assetUrl = assetUrl,
            assetSizeBytes = prefs.getLong("pending_asset_size", -1L).takeIf { it >= 0L },
        )
    }

    fun deleteDownloadedApk(path: String) {
        synchronized(downloadLock) {
            val prefs = preferences()
            if (prefs.getString(downloadPathKey, null) == path) {
                val id = prefs.getLong(downloadIdKey, -1L)
                if (id >= 0) downloadManager().remove(id)
                prefs.edit().remove(downloadIdKey).remove(downloadUrlKey).remove(downloadPathKey).apply()
            }
            File(path).delete()
        }
    }

    suspend fun downloadApk(
        assetUrl: String,
        assetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val context = requireContext()
            val uri = Uri.parse(assetUrl)
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "The update URL must use HTTPS."
            }
            val name = downloadFileName(assetUrl, assetName)
            val baseDir = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) {
                "The update download directory is unavailable."
            }
            val directory = File(baseDir, "updates")
            check(directory.isDirectory || directory.mkdirs()) { "Unable to create the update download directory." }
            val destination = File(directory, name)
            val manager = downloadManager()
            val id = synchronized(downloadLock) {
                val existingId = findDownload(manager, assetUrl, destination)
                if (existingId != null) {
                    existingId
                } else {
                    if (destination.exists()) {
                        try {
                            verifyDownloadedApk(destination, context)
                        } catch (error: Exception) {
                            destination.delete()
                            throw error
                        }
                        return@withContext Result.success(destination.absolutePath)
                    }
                    val request = DownloadManager.Request(uri)
                        .setTitle("Laqta")
                        .setMimeType("application/vnd.android.package-archive")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "updates/$name")
                    manager.enqueue(request)
                }
            }
            check(
                preferences().edit()
                    .putLong(downloadIdKey, id)
                    .putString(downloadUrlKey, assetUrl)
                    .putString(downloadPathKey, destination.absolutePath)
                    .commit(),
            ) { "Unable to save the update download state." }

            while (true) {
                val query = DownloadManager.Query().setFilterById(id)
                val snapshot = manager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else DownloadSnapshot(
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                        downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    )
                } ?: error(getString(Res.string.updates_downloaded_file_missing))
                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        check(destination.isFile) { getString(Res.string.updates_downloaded_file_missing) }
                        try {
                            verifyDownloadedApk(destination, context)
                        } catch (error: Exception) {
                            manager.remove(id)
                            throw error
                        }
                        return@withContext Result.success(destination.absolutePath)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        manager.remove(id)
                        error("${getString(Res.string.updates_download_failed)} (${snapshot.reason})")
                    }
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED ->
                        onProgress(snapshot.downloaded.coerceAtLeast(0L), snapshot.total.takeIf { it > 0L })
                    else -> error("${getString(Res.string.updates_download_failed)} (${snapshot.status})")
                }
                delay(750)
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException("Download monitoring stopped unexpectedly."))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.failure(error)
        }
    }

    private data class DownloadSnapshot(val status: Int, val reason: Int, val downloaded: Long, val total: Long)

    private fun downloadManager(): DownloadManager =
        requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun findDownload(manager: DownloadManager, assetUrl: String, destination: File): Long? {
        val prefs = preferences()
        val savedId = prefs.getLong(downloadIdKey, -1L)
        if (prefs.getString(downloadUrlKey, null) == assetUrl &&
            prefs.getString(downloadPathKey, null) == destination.absolutePath && savedId >= 0
        ) {
            manager.query(DownloadManager.Query().setFilterById(savedId))?.use { cursor ->
                if (cursor.moveToFirst()) return savedId
            }
        }
        manager.query(DownloadManager.Query())?.use { cursor ->
            val uriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)
            val localColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) {
                if (cursor.getString(uriColumn) == assetUrl &&
                    cursor.getString(localColumn) == Uri.fromFile(destination).toString()
                ) return cursor.getLong(idColumn)
            }
        }
        return null
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

internal fun downloadFileName(assetUrl: String, assetName: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(assetUrl.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val safeName = assetName.replace(Regex("[^a-zA-Z0-9._-]"), "_").takeLast(120)
    require(safeName.endsWith(".apk", ignoreCase = true)) { "The update asset must be an APK." }
    return "$digest-$safeName"
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
