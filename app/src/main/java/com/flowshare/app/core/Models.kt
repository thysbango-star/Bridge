package com.flowshare.app.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Describes one file in an offer. [id] is the Nearby payload id used to send it. */
data class FileMeta(
    val id: Long,
    val name: String,
    val size: Long,
    val mime: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("size", size).put("mime", mime)

    val kind: FileKind get() = FileKind.of(mime, name)

    companion object {
        fun fromJson(o: JSONObject) = FileMeta(
            id = o.getLong("id"),
            name = FileNames.sanitize(o.optString("name", "file")),
            size = o.optLong("size", -1L),
            mime = o.optString("mime", "application/octet-stream").ifBlank { "application/octet-stream" },
        )

        fun listToJson(list: List<FileMeta>) = JSONArray().apply { list.forEach { put(it.toJson()) } }
        fun listFromJson(arr: JSONArray) = (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
    }
}

/** A file picked on this phone, before it gets a payload id. */
data class LocalFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mime: String,
) {
    val kind: FileKind get() = FileKind.of(mime, name)
}

enum class FileKind(val label: String) {
    IMAGE("IMG"), VIDEO("VID"), AUDIO("AUD"), DOCUMENT("DOC"), APK("APK"), ARCHIVE("ZIP"), OTHER("FILE");

    companion object {
        fun of(mime: String, name: String): FileKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "apk" || mime == "application/vnd.android.package-archive" -> APK
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                ext in setOf("zip", "rar", "7z", "tar", "gz") -> ARCHIVE
                mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") ||
                    mime.contains("sheet") || mime.contains("presentation") ||
                    ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv") -> DOCUMENT
                else -> OTHER
            }
        }
    }
}

object FileNames {
    private val illegal = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

    /** Strips path components and control characters so a remote peer can't write outside our folder. */
    fun sanitize(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base.replace(illegal, "_").trim().trimStart('.')
        val safe = cleaned.ifBlank { "file" }
        return if (safe.length <= 120) safe else {
            val ext = safe.substringAfterLast('.', "")
            val stem = safe.take(110)
            if (ext.isNotEmpty() && ext.length <= 8) "$stem.$ext" else stem
        }
    }
}

object Format {
    fun bytes(b: Long): String {
        if (b < 0) return "—"
        if (b < 1024) return "$b B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
    }

    fun speed(bytesPerSec: Double): String = if (bytesPerSec <= 0) "—" else bytes(bytesPerSec.toLong()) + "/s"

    fun eta(seconds: Long): String = when {
        seconds < 0 -> "Calculating…"
        seconds < 60 -> "${seconds}s left"
        seconds < 3600 -> "%d:%02d left".format(seconds / 60, seconds % 60)
        else -> "%dh %02dm left".format(seconds / 3600, (seconds % 3600) / 60)
    }
}
