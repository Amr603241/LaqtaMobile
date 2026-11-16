package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.rename
import platform.posix.rewind
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalForeignApi::class, ExperimentalUuidApi::class)
internal actual object SubtitleImportStorage {
    private val directory get() = "${NSHomeDirectory()}/Library/Application Support/Laqta/Subtitles"

    actual fun save(name: String, bytes: ByteArray): String {
        require(bytes.size in 1..MaxImportedSubtitleBytes)
        val extension = safeSubtitleExtension(name)
        check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
        val path = "$directory/${Uuid.random()}.$extension"
        val temporary = "$path.tmp"
        val file = requireNotNull(fopen(temporary, "wb"))
        try {
            bytes.usePinned {
                check(fwrite(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() == bytes.size)
            }
        } finally {
            fclose(file)
        }
        check(rename(temporary, path) == 0)
        return requireNotNull(NSURL.fileURLWithPath(path).absoluteString)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readImportedSubtitle(path: String): ByteArray {
    val file = requireNotNull(fopen(path, "rb")) { "Unable to open the selected subtitle" }
    try {
        check(fseek(file, 0, SEEK_END) == 0)
        val size = ftell(file)
        require(size in 1..MaxImportedSubtitleBytes.toLong()) { "Subtitle file exceeds the 10 MB limit or is empty" }
        rewind(file)
        return ByteArray(size.toInt()).also { bytes ->
            bytes.usePinned {
                check(fread(it.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt() == bytes.size)
            }
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberSubtitleFileImporter(
    onResult: (Result<ImportedSubtitleFile>) -> Unit,
): () -> Unit {
    val presenter = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val latestResult = rememberUpdatedState(onResult)
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
                scope.launch {
                    val result = try {
                        Result.success(withContext(Dispatchers.Default) {
                            val name = url.lastPathComponent.orEmpty()
                            require(safeSubtitleExtensionOrNull(name) != null) { "Unsupported subtitle file type" }
                            val accessed = url.startAccessingSecurityScopedResource()
                            try {
                                ImportedSubtitleFile(name, readImportedSubtitle(requireNotNull(url.path)))
                            } finally {
                                if (accessed) url.stopAccessingSecurityScopedResource()
                            }
                        })
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Result.failure(error)
                    }
                    latestResult.value(result)
                }
            }
        }
    }
    return {
        val picker = UIDocumentPickerViewController(
            documentTypes = listOf("public.text", "public.data"),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeImport,
        )
        picker.delegate = delegate
        picker.allowsMultipleSelection = false
        presenter.presentViewController(picker, true, null)
    }
}
