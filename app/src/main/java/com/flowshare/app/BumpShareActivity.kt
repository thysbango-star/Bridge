package com.flowshare.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.flowshare.app.core.BumpDetector
import com.flowshare.app.core.FileStore
import com.flowshare.app.core.FlowPermissions
import com.flowshare.app.core.Format
import com.flowshare.app.core.Haptics
import com.flowshare.app.core.LocalFile
import com.flowshare.app.nearby.FlowEvent
import com.flowshare.app.nearby.NearbyManager
import com.flowshare.app.nearby.Peer
import com.flowshare.app.nearby.PeerStatus
import com.flowshare.app.nearby.SessionState
import com.flowshare.app.nearby.TransferSession
import com.flowshare.app.ui.components.Avatar
import com.flowshare.app.ui.components.StatusDot
import com.flowshare.app.ui.liquid.EdgeFlow
import com.flowshare.app.ui.liquid.LiquidOverlay
import com.flowshare.app.ui.liquid.rememberFlowTime
import com.flowshare.app.ui.liquid.rememberLiquidController
import com.flowshare.app.ui.screens.CompactTransferCard
import com.flowshare.app.ui.screens.ThumbStack
import com.flowshare.app.ui.screens.flowCard
import com.flowshare.app.ui.theme.Flow
import com.flowshare.app.ui.theme.FlowShareTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The share-sheet target. Select photos in Gallery → Share → Flow Share and this see-through
 * screen appears *on top of Gallery*: bump the phones together and the liquid bloom plays over
 * the photos, a compact card shows the transfer, then it closes back into Gallery.
 */
class BumpShareActivity : ComponentActivity() {
    private val nearby: NearbyManager get() = (application as FlowShareApp).nearby
    private var files by mutableStateOf<List<LocalFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FlowPermissions.allGranted(this)) {
            // First run: hand over to the full app, which asks for permissions.
            startActivity(Intent(intent).setClass(this, MainActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val uris = sharedUris(intent)
        lifecycleScope.launch {
            files = FileStore.describe(this@BumpShareActivity, uris)
            if (files.isEmpty()) finish()
        }
        nearby.start()
        nearby.rescan()
        setContent { FlowShareTheme { BumpShareScreen(files, nearby, onClose = ::finish) } }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    override fun onDestroy() {
        if (isFinishing) {
            val s = nearby.session.value
            if (s != null && s.direction == com.flowshare.app.nearby.Direction.SEND) {
                if (s.state.isLive) nearby.cancel()
                nearby.clearSession()
            }
        }
        super.onDestroy()
    }
}

@Composable
private fun BumpShareScreen(files: List<LocalFile>, nearby: NearbyManager, onClose: () -> Unit) {
    val context = LocalContext.current
    val time = rememberFlowTime()
    val liquid = rememberLiquidController(time)
    val peersMap by nearby.peers.collectAsStateWithLifecycle()
    val session by nearby.session.collectAsStateWithLifecycle()
    val peers = peersMap.values.sortedBy { it.foundAt }
    var hint by remember { mutableStateOf<String?>(null) }

    val send: (Peer) -> Unit = { p -> if (p.status == PeerStatus.AVAILABLE && files.isNotEmpty()) nearby.send(p.endpointId, files) }

    // Bump the phones together → connect to the phone beside you.
    val currentPeers by rememberUpdatedState(peers)
    val currentSend by rememberUpdatedState(send)
    DisposableEffect(Unit) {
        val detector = BumpDetector(context) {
            val available = currentPeers.filter { it.status == PeerStatus.AVAILABLE }
            when {
                nearby.busy() -> Unit
                available.size == 1 -> { Haptics.tick(context); currentSend(available.first()) }
                available.isEmpty() -> hint = "No phone found yet — keep Flow Share visible on the other phone."
                else -> hint = "Several phones nearby — tap the one you're touching."
            }
        }
        detector.start()
        onDispose { detector.stop() }
    }

    LaunchedEffect(Unit) {
        nearby.events.collect { e ->
            if (e is FlowEvent.Contact && !e.incoming) {
                Haptics.contact(context)
                liquid.contact()
            }
        }
    }
    val state = session?.state
    LaunchedEffect(state) {
        when (state) {
            SessionState.TRANSFERRING -> liquid.pulse()
            SessionState.COMPLETE -> {
                Haptics.success(context)
                liquid.settle()
                delay(2400)
                onClose()
            }
            SessionState.DECLINED, SessionState.FAILED -> Haptics.reject(context)
            else -> Unit
        }
    }
    BackHandler { onClose() }

    val scrim by animateFloatAsState(if (session != null) 0.55f else 0.35f, tween(600), label = "scrim")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))) {
        val live: TransferSession? = session?.takeIf { it.state != SessionState.CONNECTING }
        if (live != null && live.state.isLive) {
            EdgeFlow(live.progress, if (live.state == SessionState.TRANSFERRING) 0.7f else 0.2f, time, Modifier.fillMaxWidth().height(140.dp))
        }
        LiquidOverlay(liquid, Modifier.fillMaxSize())

        AnimatedContent(
            targetState = live != null,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            transitionSpec = {
                (fadeIn(tween(450, delayMillis = 380)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = 280f)) { it / 3 })
                    .togetherWith(fadeOut(tween(220, delayMillis = 250)))
            },
            label = "bump",
        ) { inSession ->
            val s = session
            if (inSession && s != null) {
                CompactTransferCard(
                    s, time,
                    onCancel = { nearby.cancel() },
                    onDone = onClose,
                    onOpen = null,
                )
            } else {
                ReadyCard(files, peers, hint, time, onPeer = send, onClose = onClose)
            }
        }
    }
}

@Composable
private fun ReadyCard(
    files: List<LocalFile>,
    peers: List<Peer>,
    hint: String?,
    time: androidx.compose.runtime.State<Float>,
    onPeer: (Peer) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.flowCard().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Icon(
                Icons.Filled.Close, "Close", tint = Flow.Mist,
                modifier = Modifier.align(Alignment.TopEnd).size(24.dp).clickable(onClick = onClose),
            )
        }
        ThumbStack(files)
        Spacer(Modifier.height(10.dp))
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        Text(
            "${files.size} ${if (files.size == 1) "item" else "items"} ready · ${Format.bytes(total)}",
            color = Flow.Mist, style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(18.dp))
        PhonesTouchingGlyph(time)
        Spacer(Modifier.height(12.dp))
        Text("Tap phones together", color = Flow.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            hint ?: "Touch the top edges, or tap a phone below.",
            color = if (hint != null) Flow.Orchid else Flow.Mist,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        if (peers.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Flow.VioletBright, pulsing = true)
                Spacer(Modifier.width(8.dp))
                Text("Looking for phones…", color = Flow.Dim, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(peers, key = { it.endpointId }) { p -> PeerChip(p) { onPeer(p) } }
            }
        }
    }
}

@Composable
private fun PeerChip(peer: Peer, onClick: () -> Unit) {
    val connecting = peer.status != PeerStatus.AVAILABLE
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (connecting) Flow.Violet.copy(alpha = 0.3f) else Flow.SurfaceHi)
            .clickable(enabled = !connecting, onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(peer.name, 30.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            if (connecting) "Connecting…" else peer.name,
            color = Flow.White, style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two phone tops gently nudging into each other with a spark of liquid between them. */
@Composable
private fun PhonesTouchingGlyph(time: androidx.compose.runtime.State<Float>) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 120.dp, height = 56.dp)) {
        val t = time.value
        val gap = (6f + 6f * kotlin.math.sin(t * 2.4f).coerceAtLeast(0f)) * density
        val pw = 34f * density
        val ph = 54f * density
        val cy = size.height / 2f
        val r = androidx.compose.ui.geometry.CornerRadius(9f * density)
        val leftX = size.width / 2f - gap / 2f - ph
        // Phones drawn lying sideways, top edges facing each other.
        drawRoundRect(Flow.Lavender, androidx.compose.ui.geometry.Offset(leftX, cy - pw / 2f),
            androidx.compose.ui.geometry.Size(ph, pw), r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * density))
        drawRoundRect(Flow.Lavender, androidx.compose.ui.geometry.Offset(size.width / 2f + gap / 2f, cy - pw / 2f),
            androidx.compose.ui.geometry.Size(ph, pw), r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f * density))
        val glow = (1f - (gap / density - 6f) / 6f).coerceIn(0f, 1f)
        drawCircle(
            androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(Flow.Orchid.copy(alpha = 0.9f * glow), Color.Transparent),
                androidx.compose.ui.geometry.Offset(size.width / 2f, cy), 22f * density,
            ),
            radius = 22f * density, center = androidx.compose.ui.geometry.Offset(size.width / 2f, cy),
        )
    }
}
