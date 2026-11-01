package com.flowshare.app.nearby

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowshare.app.FlowPrefs
import com.flowshare.app.FlowShareApp
import com.flowshare.app.MainActivity
import com.flowshare.app.R
import com.flowshare.app.core.FileStore
import com.flowshare.app.core.Format
import com.flowshare.app.core.Haptics
import com.flowshare.app.ui.liquid.EdgeFlow
import com.flowshare.app.ui.liquid.LiquidOverlay
import com.flowshare.app.ui.liquid.rememberFlowTime
import com.flowshare.app.ui.liquid.rememberLiquidController
import com.flowshare.app.ui.screens.CompactTransferCard
import com.flowshare.app.ui.screens.IncomingCard
import com.flowshare.app.ui.theme.FlowShareTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * "Receive over other apps". Keeps Flow Share discoverable while you're in Gallery (or anywhere),
 * and when someone bumps their phone against yours:
 *  - with "Display over other apps": the liquid bloom plays on top of the current app and the
 *    accept card drops in from the contact edge; progress flows along the top edge;
 *  - without it: a heads-up notification with Accept / Decline.
 */
class ReceiveService : LifecycleService() {

    private val app get() = application as FlowShareApp
    private val nearby get() = app.nearby
    private var overlay: FlowOverlay? = null
    private var offerNotified: String? = null
    private var completeNotified = false
    private var settledFor: Long? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            FlowPrefs(this).receiveAnywhere = false
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val ok = runCatching { ServiceCompat.startForeground(this, NOTIF_READY, readyNotification(), type) }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        if (overlay == null) start()
        return START_STICKY
    }

    private fun start() {
        overlay = FlowOverlay(this)
        nearby.setReceiving(true)
        nearby.start()

        // Contact bloom on top of whatever app is open.
        lifecycleScope.launch {
            nearby.events.collect { e ->
                if (e is FlowEvent.Contact && e.incoming && !app.mainVisible.value) {
                    Haptics.contact(this@ReceiveService)
                    if (FlowPrefs.canDrawOverlays(this@ReceiveService)) overlay?.playContact()
                }
            }
        }

        lifecycleScope.launch {
            combine(nearby.offer, nearby.session, app.mainVisible) { o, s, mv -> Triple(o, s, mv) }
                .collect { (offer, session, mainVisible) -> update(offer, session, mainVisible) }
        }
    }

    private fun update(offer: IncomingOffer?, session: TransferSession?, mainVisible: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        val receiving = session?.takeIf { it.direction == Direction.RECEIVE }
        if (mainVisible) {
            overlay?.hideAll()
            nm.cancel(NOTIF_OFFER)
            offerNotified = null
            return
        }
        if (FlowPrefs.canDrawOverlays(this)) {
            if (offer != null || receiving != null) overlay?.showCard() else overlay?.hideCard()
            if (receiving?.state?.isLive == true) overlay?.showBloom()
            if (receiving?.state == SessionState.COMPLETE && settledFor != receiving.startedAt) {
                settledFor = receiving.startedAt
                Haptics.success(this)
                overlay?.playSettle()
            }
        } else {
            if (offer != null && offerNotified != offer.endpointId) {
                offerNotified = offer.endpointId
                nm.notify(NOTIF_OFFER, offerNotification(offer))
            } else if (offer == null && offerNotified != null) {
                nm.cancel(NOTIF_OFFER)
                offerNotified = null
            }
            if (receiving?.state == SessionState.COMPLETE && !completeNotified) {
                completeNotified = true
                nm.notify(NOTIF_DONE, doneNotification(receiving))
            }
            if (receiving == null) completeNotified = false
        }
    }

    override fun onDestroy() {
        overlay?.destroy()
        overlay = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ notifications

    private fun readyNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_READY)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle("Ready to receive")
            .setContentText("Phones nearby can send to ${app.identity.displayName}")
            .setColor(0xFF8B5CF6.toInt())
            .setOngoing(true)
            .setContentIntent(openApp(this))
            .addAction(0, "Turn off", stop)
            .build()
    }

    private fun offerNotification(o: IncomingOffer): Notification {
        val accept = PendingIntent.getBroadcast(
            this, 2, Intent(this, OfferActionReceiver::class.java).setAction(ACTION_ACCEPT), PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            this, 3, Intent(this, OfferActionReceiver::class.java).setAction(ACTION_DECLINE), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = o.files.size
        return NotificationCompat.Builder(this, CH_OFFER)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle("${o.senderName} wants to share $n ${if (n == 1) "item" else "items"}")
            .setContentText("${Format.bytes(o.totalBytes)} · code ${o.authCode}")
            .setColor(0xFF8B5CF6.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .setContentIntent(openApp(this))
            .addAction(0, "Decline", decline)
            .addAction(0, "Accept", accept)
            .build()
    }

    private fun doneNotification(s: TransferSession): Notification {
        val first = s.files.firstOrNull()
        val view = first?.savedUri?.let {
            PendingIntent.getActivity(
                this, 4,
                Intent(Intent.ACTION_VIEW).setDataAndType(it, first.meta.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE,
            )
        }
        return NotificationCompat.Builder(this, CH_OFFER)
            .setSmallIcon(R.drawable.ic_stat_flow)
            .setContentTitle("Received ${s.files.size} ${if (s.files.size == 1) "item" else "items"} from ${s.peerName}")
            .setContentText("Saved to the Flow Share album")
            .setColor(0xFF8B5CF6.toInt())
            .setAutoCancel(true)
            .setContentIntent(view ?: openApp(this))
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.flowshare.app.STOP_RECEIVE"
        const val ACTION_ACCEPT = "com.flowshare.app.ACCEPT"
        const val ACTION_DECLINE = "com.flowshare.app.DECLINE"
        private const val CH_READY = "ready"
        private const val CH_OFFER = "incoming"
        private const val NOTIF_READY = 7
        private const val NOTIF_OFFER = 8
        private const val NOTIF_DONE = 9

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReceiveService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReceiveService::class.java)) }
        }

        fun cancelOfferNotification(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_OFFER)

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH_READY) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_READY, "Ready to receive", NotificationManager.IMPORTANCE_MIN),
                )
            }
            if (nm.getNotificationChannel(CH_OFFER) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_OFFER, "Incoming files", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Requests to receive files, shown while you're in other apps"
                    },
                )
            }
        }
    }
}

/** Handles Accept / Decline tapped on the incoming-files notification. */
class OfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nearby = (context.applicationContext as FlowShareApp).nearby
        when (intent.action) {
            ReceiveService.ACTION_ACCEPT -> nearby.acceptOffer()
            ReceiveService.ACTION_DECLINE -> nearby.declineOffer()
        }
        ReceiveService.cancelOfferNotification(context)
    }
}

/**
 * Two windows drawn over other apps:
 *  - bloom: full screen, touch-through (alpha 0.8 so Android 12+ still passes touches to Gallery);
 *  - card: wraps its content at the top edge, touches outside it go to the app underneath.
 */
private class FlowOverlay(private val service: ReceiveService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val nearby = (service.application as FlowShareApp).nearby
    private val owner = OverlayOwner()
    private val contactTick = mutableIntStateOf(0)
    private val settleTick = mutableIntStateOf(0)
    private var bloomView: ComposeView? = null
    private var cardView: ComposeView? = null
    private var hideBloomJob: Job? = null

    private val overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    fun playContact() {
        showBloom()
        contactTick.intValue++
        scheduleBloomHide(2200)
    }

    fun playSettle() {
        showBloom()
        settleTick.intValue++
        scheduleBloomHide(2600)
    }

    fun showBloom() {
        hideBloomJob?.cancel()
        if (bloomView != null) return
        val view = composeView {
            val time = rememberFlowTime()
            val liquid = rememberLiquidController(time)
            val tick by contactTick
            val settle by settleTick
            val session by nearby.session.collectAsState()
            LaunchedEffect(tick) { if (tick > 0) liquid.contact() }
            LaunchedEffect(settle) { if (settle > 0) liquid.settle() }
            Box(Modifier.fillMaxSize()) {
                val s = session?.takeIf { it.direction == Direction.RECEIVE && it.state.isLive }
                if (s != null) {
                    EdgeFlow(s.progress, if (s.state == SessionState.TRANSFERRING) 0.7f else 0.2f, time, Modifier.fillMaxWidth().height(130.dp))
                }
                LiquidOverlay(liquid, Modifier.fillMaxSize())
            }
        }
        val lp = baseParams(WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            alpha = 0.8f
        }
        runCatching { wm.addView(view, lp) }.onSuccess { bloomView = view }
    }

    private fun scheduleBloomHide(after: Long) {
        hideBloomJob?.cancel()
        hideBloomJob = service.lifecycleScope.launch {
            delay(after)
            val s = nearby.session.value
            if (s == null || s.direction != Direction.RECEIVE || !s.state.isLive) removeBloom()
        }
    }

    fun showCard() {
        if (cardView != null) return
        val view = composeView {
            val time = rememberFlowTime()
            val offer by nearby.offer.collectAsState()
            val session by nearby.session.collectAsState()
            val receiving = session?.takeIf { it.direction == Direction.RECEIVE }
            AnimatedContent(
                targetState = when {
                    offer != null -> 1
                    receiving != null -> 2
                    else -> 0
                },
                transitionSpec = {
                    (fadeIn(tween(350, delayMillis = 350)) + slideInVertically(spring(dampingRatio = 0.72f, stiffness = 260f)) { -it })
                        .togetherWith(fadeOut(tween(200)))
                },
                modifier = Modifier.statusBarsPadding().padding(12.dp),
                label = "overlayCard",
            ) { mode ->
                when (mode) {
                    1 -> offer?.let {
                        IncomingCard(it, onAccept = { nearby.acceptOffer() }, onDecline = { nearby.declineOffer() })
                    }
                    2 -> receiving?.let { s ->
                        LaunchedEffect(s.state) {
                            if (s.state.isEnded) {
                                delay(6000)
                                nearby.clearSession()
                            }
                        }
                        CompactTransferCard(
                            s, time,
                            onCancel = { nearby.cancel() },
                            onDone = { nearby.clearSession() },
                            onOpen = s.files.firstOrNull()?.savedUri?.let { uri ->
                                {
                                    FileStore.open(service, uri, s.files.first().meta.mime)
                                    nearby.clearSession()
                                }
                            },
                        )
                    }
                    else -> Box(Modifier)
                }
            }
        }
        val lp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP
        }
        runCatching { wm.addView(view, lp) }.onSuccess { cardView = view }
    }

    fun hideCard() {
        cardView?.let { runCatching { wm.removeView(it) } }
        cardView = null
    }

    private fun removeBloom() {
        bloomView?.let { runCatching { wm.removeView(it) } }
        bloomView = null
    }

    fun hideAll() {
        hideCard()
        removeBloom()
    }

    fun destroy() {
        hideAll()
        owner.destroy()
    }

    private fun baseParams(height: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        height,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP }

    private fun composeView(content: @androidx.compose.runtime.Composable () -> Unit) = ComposeView(service).apply {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        setContent { FlowShareTheme { content() } }
    }
}

/** Lifecycle + saved-state owner so Compose can run inside a window owned by a Service. */
private class OverlayOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val controller = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    init {
        controller.performAttach()
        controller.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
