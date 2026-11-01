package com.flowshare.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowshare.app.core.BumpDetector
import com.flowshare.app.core.Format
import com.flowshare.app.core.LocalFile
import com.flowshare.app.nearby.Peer
import com.flowshare.app.nearby.PeerStatus
import com.flowshare.app.ui.components.Avatar
import com.flowshare.app.ui.components.GhostButton
import com.flowshare.app.ui.components.KindBadge
import com.flowshare.app.ui.components.StatusDot
import com.flowshare.app.ui.components.TopBar
import com.flowshare.app.ui.components.pressScale
import com.flowshare.app.ui.liquid.LiquidRings
import com.flowshare.app.ui.theme.Flow

@Composable
fun NearbyScreen(
    time: State<Float>,
    files: List<LocalFile>,
    peers: List<Peer>,
    pendingQrName: String?,
    radioError: String?,
    onBack: () -> Unit,
    onChangeFiles: () -> Unit,
    onPeer: (Peer) -> Unit,
    onBump: () -> Boolean,
    onScanQr: () -> Unit,
    onCancelQr: () -> Unit,
    onRescan: () -> Unit,
) {
    val context = LocalContext.current
    val bump by rememberUpdatedState(onBump)
    DisposableEffect(Unit) {
        val detector = BumpDetector(context) { bump() }
        detector.start()
        onDispose { detector.stop() }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar("Share Nearby", onBack) {
            val src = remember { MutableInteractionSource() }
            Box(
                Modifier.size(44.dp).scale(pressScale(src, 0.88f)).clip(CircleShape)
                    .clickable(src, indication = null, onClick = onRescan),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Refresh, "Refresh", tint = Flow.Mist) }
        }

        SelectedFiles(files, onChangeFiles, Modifier.padding(horizontal = 20.dp))

        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
            LiquidRings(time, Modifier.size(190.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (peers.isEmpty()) "Looking for phones" else "${peers.size} nearby",
                    color = Flow.White, style = MaterialTheme.typography.titleMedium,
                )
                Text("Bluetooth · Wi-Fi", color = Flow.Dim, style = MaterialTheme.typography.labelMedium)
            }
        }

        Text(
            radioError ?: "Hold the phones top-to-top and tap one below —\nor give them a gentle bump together.",
            color = if (radioError != null) Flow.Danger else Flow.Mist,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(pendingQrName != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)).background(Flow.Violet.copy(alpha = 0.15f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Flow.Lavender, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text("Looking for ${pendingQrName.orEmpty()}…", color = Flow.Lavender, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.Close, "Cancel", tint = Flow.Mist,
                    modifier = Modifier.size(20.dp).clickable(onClick = onCancelQr),
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(peers, key = { it.endpointId }) { peer ->
                DeviceCard(peer, onClick = { onPeer(peer) }, modifier = Modifier.animateItem())
            }
            if (peers.isEmpty()) {
                item {
                    Text(
                        "Open Flow Share on the other phone and keep “Visible” on.",
                        color = Flow.Dim, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            GhostButton("Scan QR code instead", onScanQr, icon = Icons.Filled.Search)
        }
    }
}

@Composable
private fun SelectedFiles(files: List<LocalFile>, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val total = files.sumOf { it.size.coerceAtLeast(0) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Flow.Surface).border(1.dp, Flow.Stroke, RoundedCornerShape(24.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${files.size} ${if (files.size == 1) "file" else "files"} · ${Format.bytes(total)}",
                color = Flow.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            Text("Change", color = Flow.VioletBright, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onChange).padding(6.dp))
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files.take(30)) { f ->
                Row(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Flow.SurfaceHi).padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KindBadge(f.kind)
                    Spacer(Modifier.width(8.dp))
                    Text(f.name, color = Flow.Mist, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                }
            }
        }
    }
}

@Composable
fun DeviceCard(peer: Peer, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val enabled = peer.status == PeerStatus.AVAILABLE
    val (statusText, color, pulse) = when (peer.status) {
        PeerStatus.AVAILABLE -> Triple("Available · tap to send", Flow.VioletBright, false)
        PeerStatus.CONNECTING -> Triple("Connecting securely…", Flow.Orchid, true)
        PeerStatus.CONNECTED -> Triple("Connected", Flow.Lavender, true)
        PeerStatus.BUSY -> Triple("Busy", Flow.Dim, false)
    }
    Row(
        modifier.fillMaxWidth().scale(pressScale(src, 0.97f))
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(listOf(Flow.SurfaceHi, Flow.Surface)))
            .border(1.dp, if (pulse) Flow.Violet.copy(alpha = 0.6f) else Flow.Stroke, RoundedCornerShape(24.dp))
            .clickable(src, indication = null, enabled = enabled, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(peer.name, 50.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = Flow.White, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color, pulse)
                Spacer(Modifier.width(8.dp))
                Text(statusText, color = Flow.Mist, style = MaterialTheme.typography.labelMedium)
            }
        }
        @Suppress("DEPRECATION")
        Icon(Icons.Filled.KeyboardArrowRight, null, tint = if (enabled) Flow.Mist else Flow.Dim)
    }
}
