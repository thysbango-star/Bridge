package com.flowshare.app.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flowshare.app.FlowShareApp
import com.flowshare.app.MainActivity
import com.flowshare.app.R
import com.flowshare.app.core.Format
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and the Nearby link) alive while a large transfer
 * runs, so it continues with the screen off or the app in the background.
 */
class TransferService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build("Connecting…", 0, true), type)
        }.onFailure { stopSelf(); return START_NOT_STICKY }

        val nearby = (application as FlowShareApp).nearby
        lifecycleScope.launch {
            nearby.session
                .map { s -> s?.let { Triple(it.peerName to it.direction, (it.progress * 100).toInt(), it.state) } }
                .distinctUntilChanged()
                .collect { t ->
                    if (t == null || t.third.isEnded) {
                        ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collect
                    }
                    val (peerDir, pct, _) = t
                    val verb = if (peerDir.second == Direction.SEND) "Sending to" else "Receiving from"
                    val speed = nearby.session.value?.bytesPerSecond ?: 0.0
                    val text = "$verb ${peerDir.first} · ${Format.speed(speed)}"
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, build(text, pct, false))
                }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String, pct: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle("Flow Share")
            .setContentText(text)
            .setColor(0xFF8B5CF6.toInt())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Transfers", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of files being sent or received"
                },
            )
        }
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 42

        fun start(context: Context) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, TransferService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}
