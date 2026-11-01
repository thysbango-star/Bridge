package com.flowshare.app.ui.screens

import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowshare.app.core.FileKind
import com.flowshare.app.core.Format
import com.flowshare.app.core.LocalFile
import com.flowshare.app.nearby.Direction
import com.flowshare.app.nearby.SessionState
import com.flowshare.app.nearby.TransferSession
import com.flowshare.app.ui.components.Avatar
import com.flowshare.app.ui.components.GhostButton
import com.flowshare.app.ui.components.KindBadge
import com.flowshare.app.ui.components.PrimaryButton
import com.flowshare.app.ui.components.SecureChip
import com.flowshare.app.ui.liquid.LiquidTank
import com.flowshare.app.ui.liquid.SuccessMark
import com.flowshare.app.ui.theme.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val CardShape = RoundedCornerShape(30.dp)

fun Modifier.flowCard(): Modifier = this
    .fillMaxWidth()
    .clip(CardShape)
    .background(Brush.verticalGradient(listOf(Color(0xF2241A3A), Color(0xF5111017))))
    .border(1.dp, Flow.StrokeHi, CardShape)

/** A fanned stack of real photo thumbnails for the selected files. */
@Composable
fun ThumbStack(files: List<LocalFile>, modifier: Modifier = Modifier) {
    Box(modifier.height(84.dp).width(140.dp), contentAlignment = Alignment.Center) {
        val shown = files.take(3)
        shown.forEachIndexed { i, f ->
            val angle = (i - (shown.size - 1) / 2f) * 9f
            Thumb(
                f,
                Modifier.offset(x = ((i - (shown.size - 1) / 2f) * 26f).dp)
                    .rotate(angle).size(68.dp),
            )
        }
    }
}

@Composable
fun Thumb(file: LocalFile, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, file.uri) {
        value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (file.kind == FileKind.IMAGE || file.kind == FileKind.VIDEO)
        ) withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(file.uri, Size(240, 240), null) }.getOrNull()
        } else null
    }
    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Flow.SurfaceHi)
            .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            KindBadge(file.kind)
        }
    }
}

/** Compact transfer card used on top of other apps: mini liquid vessel + numbers. */
@Composable
fun CompactTransferCard(
    s: TransferSession,
    time: State<Float>,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onOpen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val receiving = s.direction == Direction.RECEIVE
    val p = s.progress
    Column(modifier.flowCard().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.peerName, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (receiving) "From ${s.peerName}" else "To ${s.peerName}",
                    color = Flow.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${s.files.size} ${if (s.files.size == 1) "item" else "items"} · ${Format.bytes(s.totalBytes)}",
                    color = Flow.Mist, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(22.dp)).background(Flow.Black),
            contentAlignment = Alignment.Center,
        ) {
            val level = when (s.state) {
                SessionState.TRANSFERRING -> if (receiving) 0.06f + 0.94f * p else 0.94f * (1f - p) + 0.03f
                SessionState.COMPLETE -> if (receiving) 1f else 0.03f
                SessionState.WAITING_ACCEPT, SessionState.CONNECTING -> if (receiving) 0.06f else 0.94f
                else -> 0.03f
            }
            val energy = if (s.state == SessionState.TRANSFERRING) 0.6f else if (s.state.isLive) 0.2f else 0f
            LiquidTank(level, energy, inflow = receiving, time = time, modifier = Modifier.fillMaxSize())
            AnimatedContent(
                s.state == SessionState.COMPLETE,
                transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) },
                label = "mini",
            ) { done ->
                if (done) {
                    SuccessMark(play = true, modifier = Modifier.size(72.dp))
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when (s.state) {
                                SessionState.TRANSFERRING -> "${(p * 100).toInt()}%"
                                SessionState.CONNECTING, SessionState.WAITING_ACCEPT -> "Waiting…"
                                SessionState.DECLINED -> "Declined"
                                SessionState.CANCELLED -> "Cancelled"
                                else -> "Stopped"
                            },
                            color = Flow.White, style = MaterialTheme.typography.headlineLarge,
                        )
                        if (s.state == SessionState.TRANSFERRING) {
                            Text(
                                "${Format.speed(s.bytesPerSecond)} · ${Format.eta(s.etaSeconds)}",
                                color = Flow.Lavender, style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (s.state.isLive) SecureChip(s.authCode, s.verifiedByQr)
        else if (!s.message.isNullOrBlank()) Text(s.message, color = Flow.Mist, style = MaterialTheme.typography.bodyMedium)
        else if (receiving && s.state == SessionState.COMPLETE) {
            Text("Saved to the Flow Share album", color = Flow.Lavender, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                s.state.isLive -> GhostButton("Cancel", onCancel, Modifier.weight(1f))
                onOpen != null && s.state == SessionState.COMPLETE -> {
                    GhostButton("View", onOpen, Modifier.weight(1f))
                    PrimaryButton("Done", onDone, Modifier.weight(1f))
                }
                else -> PrimaryButton("Done", onDone, Modifier.weight(1f))
            }
        }
    }
}
