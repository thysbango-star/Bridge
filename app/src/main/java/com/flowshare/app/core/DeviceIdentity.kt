package com.flowshare.app.core

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.security.SecureRandom

/**
 * This phone's identity on the Flow Share network.
 *
 * The advertised endpoint name is "<display name>|<pairing token>". The token is random per
 * app launch and is what the QR code carries, so a scanned QR maps to exactly one advertiser.
 */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("flow_identity", Context.MODE_PRIVATE)

    var displayName: String = prefs.getString(KEY_NAME, null) ?: defaultName(context)
        private set

    val pairingToken: String = randomToken()

    val advertisedName: String get() = "${displayName.replace(SEP, ' ')}$SEP$pairingToken"

    val qrPayload: String
        get() = Uri.Builder().scheme("flowshare").authority("pair")
            .appendQueryParameter("n", displayName)
            .appendQueryParameter("t", pairingToken)
            .build().toString()

    fun rename(name: String) {
        val clean = name.replace(SEP, ' ').trim().take(32)
        if (clean.isNotEmpty()) {
            displayName = clean
            prefs.edit().putString(KEY_NAME, clean).apply()
        }
    }

    companion object {
        private const val KEY_NAME = "name"
        const val SEP = '|'

        fun parseAdvertised(raw: String): Pair<String, String?> {
            val i = raw.lastIndexOf(SEP)
            return if (i <= 0) raw to null else raw.substring(0, i) to raw.substring(i + 1)
        }

        /** Parses "flowshare://pair?n=...&t=..." -> (name, token). */
        fun parseQr(text: String): Pair<String, String>? = runCatching {
            val uri = Uri.parse(text.trim())
            if (uri.scheme != "flowshare" || uri.host != "pair") return null
            val token = uri.getQueryParameter("t") ?: return null
            (uri.getQueryParameter("n") ?: "Phone") to token
        }.getOrNull()

        private fun defaultName(context: Context): String {
            // Samsung stores the user-visible "Device name" (e.g. "Galaxy S24 Ultra") here.
            val global = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            val name = global?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android phone"
            return name.replace(SEP, ' ').take(32)
        }

        private fun randomToken(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
