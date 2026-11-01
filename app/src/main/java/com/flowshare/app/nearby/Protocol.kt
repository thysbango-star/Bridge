package com.flowshare.app.nearby

import com.flowshare.app.core.FileMeta
import org.json.JSONObject

/**
 * Tiny control protocol carried in BYTES payloads over the (already UKEY2-encrypted) Nearby link.
 *
 *   sender   -> OFFER  { files: [ {id, name, size, mime} ], qr }
 *   receiver -> ACCEPT | DECLINE
 *   sender   -> FILE payloads, one per offered id
 *   either   -> CANCEL
 */
sealed interface Message {
    data class Offer(val senderName: String, val files: List<FileMeta>, val qrToken: String?) : Message
    data object Accept : Message
    data object Decline : Message
    data object Cancel : Message

    fun encode(): ByteArray {
        val o = JSONObject().put("v", VERSION)
        when (this) {
            is Offer -> o.put("t", "offer").put("name", senderName)
                .put("files", FileMeta.listToJson(files))
                .apply { if (qrToken != null) put("qr", qrToken) }
            Accept -> o.put("t", "accept")
            Decline -> o.put("t", "decline")
            Cancel -> o.put("t", "cancel")
        }
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val VERSION = 1
        private const val MAX_BYTES = 256 * 1024
        private const val MAX_FILES = 1000

        fun decode(bytes: ByteArray): Message? {
            if (bytes.size > MAX_BYTES) return null
            return runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                when (o.getString("t")) {
                    "offer" -> {
                        val files = FileMeta.listFromJson(o.getJSONArray("files"))
                        if (files.isEmpty() || files.size > MAX_FILES) return null
                        Offer(o.optString("name", "Phone").take(40), files, o.optString("qr").ifBlank { null })
                    }
                    "accept" -> Accept
                    "decline" -> Decline
                    "cancel" -> Cancel
                    else -> null
                }
            }.getOrNull()
        }
    }
}
