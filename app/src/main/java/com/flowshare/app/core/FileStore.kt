package com.flowshare.app.core

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileStore {
    const val FOLDER = "Flow Share"

    /** Reads display name, size and type for picked/shared URIs. */
    suspend fun describe(context: Context, uris: List<Uri>): List<LocalFile> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        uris.distinct().mapNotNull { uri ->
            runCatching {
                var name: String? = null
                var size = -1L
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0) name = c.getString(ni)
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                if (size < 0) {
                    size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                }
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                LocalFile(uri, FileNames.sanitize(name ?: uri.lastPathSegment ?: "file"), size, mime)
            }.getOrNull()
        }
    }

    /**
     * Moves a completed Nearby file payload into Downloads/Flow Share and deletes the temp copy.
     * Returns a content:// Uri that can be opened by other apps.
     */
    suspend fun saveIncoming(context: Context, source: Uri, meta: FileMeta): Uri? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = FileNames.sanitize(meta.name)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Photos → Pictures/Flow Share and videos → Movies/Flow Share, so they show up as a
                // "Flow Share" album in Samsung Gallery. Everything else → Downloads/Flow Share.
                val (collection, dir) = when (meta.kind) {
                    FileKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
                    FileKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, meta.mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val dest = resolver.insert(collection, values)
                    ?: error("MediaStore insert failed")
                try {
                    resolver.openInputStream(source).use { input ->
                        resolver.openOutputStream(dest).use { output ->
                            requireNotNull(input); requireNotNull(output)
                            input.copyTo(output, 1 shl 16)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(dest, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(dest, null, null)
                    throw t
                }
                runCatching { resolver.delete(source, null, null) }
                dest
            } else {
                @Suppress("DEPRECATION")
                val publicDir = when (meta.kind) {
                    FileKind.IMAGE -> Environment.DIRECTORY_PICTURES
                    FileKind.VIDEO -> Environment.DIRECTORY_MOVIES
                    else -> Environment.DIRECTORY_DOWNLOADS
                }
                val dir = File(Environment.getExternalStoragePublicDirectory(publicDir), FOLDER)
                dir.mkdirs()
                val target = uniqueFile(dir, name)
                val src = File(requireNotNull(source.path))
                if (!src.renameTo(target)) {
                    src.inputStream().use { i -> target.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    src.delete()
                }
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(meta.mime), null)
                FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            }
        }.getOrNull()
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) { f = File(dir, "$stem ($i)$ext"); i++ }
        return f
    }

    fun open(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    fun openDownloads(context: Context) {
        val intent = Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        }
    }
}
