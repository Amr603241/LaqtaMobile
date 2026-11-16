package com.nuvio.app.features.player

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

internal actual object SubtitleImportStorage {
    actual fun save(name: String, bytes: ByteArray): String {
        require(bytes.size in 1..MaxImportedSubtitleBytes)
        val extension = safeSubtitleExtension(name)
        val context = requireNotNull(PlayerSettingsStorage.applicationContext())
        val directory = File(context.filesDir, "imported-subtitles").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.$extension")
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeBytes(bytes)
        check(temporary.renameTo(target))
        return target.toURI().toString()
    }
}

@Composable
internal actual fun rememberSubtitleFileImporter(
    onResult: (Result<ImportedSubtitleFile>) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: error("Unable to read the selected subtitle name")
                    require(safeSubtitleExtensionOrNull(name) != null) { "Unsupported subtitle file type" }
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MaxImportedSubtitleBytes) {
                                "Subtitle file exceeds the 10 MB limit"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.isNotEmpty()) { "Subtitle file is empty" }
                    ImportedSubtitleFile(name, bytes)
                })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Result.failure(error)
            }
            latestResult.value(result)
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}
