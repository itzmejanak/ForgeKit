package com.forgekit.platform.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.forgekit.core.common.MarkdownDocumentBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Loads the split bundled guide and publishes one merged `Docs.md` in Downloads. */
public class DeveloperDocsExporter(
    private val context: Context,
) {
    public data class ExportResult(
        public val displayPath: String,
        public val uri: Uri?,
        public val bytesWritten: Int,
    )

    public suspend fun export(): ExportResult = withContext(Dispatchers.IO) {
        val bytes = mergedDocument().toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(bytes)
        } else {
            exportLegacy(bytes)
        }
    }

    public fun mergedDocument(): String {
        val assets = context.assets
        val order = assets.open("$ASSET_ROOT/$ORDER_FILE").bufferedReader().use { it.readText() }
        return MarkdownDocumentBundle.merge(order) { name ->
            assets.open("$ASSET_ROOT/$name").bufferedReader().use { it.readText() }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun exportWithMediaStore(bytes: ByteArray): ExportResult {
        val resolver = context.contentResolver
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val previous = preferences.getString(KEY_LAST_URI, null)?.let(Uri::parse)

        if (previous != null) {
            val replaced = runCatching {
                resolver.openOutputStream(previous, "wt")?.use { output ->
                    output.write(bytes)
                    output.flush()
                } ?: error("stored Downloads entry is no longer writable")
            }.isSuccess
            if (replaced) return ExportResult(DISPLAY_PATH, previous, bytes.size)
            preferences.edit().remove(KEY_LAST_URI).apply()
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIRECTORY)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Android Downloads provider refused the document")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: error("Android Downloads provider returned no output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            preferences.edit().putString(KEY_LAST_URI, uri.toString()).apply()
            return ExportResult(DISPLAY_PATH, uri, bytes.size)
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(bytes: ByteArray): ExportResult {
        val permission = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        check(permission == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "storage permission is required to write Downloads on Android 9 or earlier"
        }
        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .toPath().resolve("ForgeKit")
        Files.createDirectories(directory)
        val destination = directory.resolve(FILE_NAME)
        val temporary = Files.createTempFile(directory, ".Docs-", ".part")
        try {
            FileOutputStream(temporary.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return ExportResult(DISPLAY_PATH, Uri.fromFile(destination.toFile()), bytes.size)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val ASSET_ROOT = "developer-docs"
        const val ORDER_FILE = "order.txt"
        const val FILE_NAME = "Docs.md"
        const val MIME_TYPE = "text/markdown"
        const val RELATIVE_DIRECTORY = "Download/ForgeKit"
        const val DISPLAY_PATH = "Download/ForgeKit/Docs.md"
        const val PREFERENCES = "developer-docs-export"
        const val KEY_LAST_URI = "last-uri"
    }
}
