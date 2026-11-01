package com.flowshare.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flowshare.app.core.Format
import com.flowshare.app.nearby.Direction
import com.flowshare.app.nearby.FileStatus
import com.flowshare.app.nearby.SessionState
import com.flowshare.app.nearby.TransferSession
import com.flowshare.app.ui.components.GhostButton
import com.flowshare.app.ui.components.KindBadge
import com.flowshare.app.ui.components.PrimaryButton
import com.flowshare.app.ui.components.SecureChip
import com.flowshare.app.ui.components.ThinProgress
import com.flowshare.app.ui.liquid.LiquidTank
import com.flowshare.app.ui.liquid.SuccessMark
import com.flowshare.app.ui.theme.Flow
import kotlin.math.ln

@Composable
fun TransferScreen(
    s: TransferSession,
    time: State<Float>,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onOpenFile: () -> Unit,
    onShowFiles: () -> Unit,
) {
    val receiving = s.direction == Direction.RECEIVE
    val p = s.progress
    val level = when (s.state) {
        SessionState.CONNECTING, SessionState.WAITING_ACCEPT -> if (receiving) 0.04f else 0.9f
        SessionState.TRANSFERRING -> if (receiving) 0.04f + 0.96f * p else 0.9f * (1f - p) + 0.02f
        SessionState.COMPLETE -> if (receiving) 1f else 0.02f
        else -> 0.02f
    }
    // Faster transfers → livelier surface. Log-scaled: 100 KB/s .. 50 MB/s.
    val speedEnergy = if (s.bytesPerSecond > 0) ((ln(s.bytesPerSecond) - ln(1e5)) / (ln(5e7) - ln(1e5))).toFloat().coerceIn(0f, 1f) else 0f
    val energy = when (s.state) {
        SessionState.TRANSFERRING -> 0.35f + 0.65f * speedEnergy
        SessionState.WAITING_ACCEPT, SessionState.CONNECTING -> 0.18f
        else -> 0f
    }

    Box(Modifier.fillMaxSize().background(Flow.Black)) {
        LiquidTank(level, energy, inflow = receiving, time = time, modifier = Modifier.fillMaxSize())

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            Text(
                if (receiving) "RECEIVING FROM" else "SENDING TO",
                color = Flow.Lavender.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(s.peerName, color = Flow.White, style = MaterialTheme.typography.headlineLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            SecureChip(s.authCode, s.verifiedByQr)

            Spacer(Modifier.weight(1f))

            AnimatedContent(
                targetState = s.state.bucket(),
                transitionSpec = { (fadeIn(tween(500)) + scaleIn(tween(600), 0.92f)) togetherWith fadeOut(tween(250)) },
                label = "center",
            ) { bucket ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (bucket) {
                        Bucket.Waiting -> {
                            Text("Waiting for ${s.peerName}", color = Flow.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (s.authCode.isNotBlank()) "Check that both phones show code ${s.authCode}, then accept on the other phone."
                                else "Accept on the other phone to start.",
                                color = Flow.Lavender, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            )
                        }
                        Bucket.Active -> {
                            Text("${(p * 100).toInt()}%", color = Flow.White, style = MaterialTheme.typography.displayLarge)
                            Text(
                                "${Format.speed(s.bytesPerSecond)}  ·  ${Format.eta(s.etaSeconds)}",
                                color = Flow.Lavender, style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${Format.bytes(s.transferredBytes)} of ${Format.bytes(s.totalBytes)}",
                                color = Flow.Lavender.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Bucket.Done -> {
                            SuccessMark(play = true, modifier = Modifier.size(128.dp))
                            Spacer(Modifier.height(18.dp))
                            val n = s.files.size
                            Text(
                                (if (receiving) "Received " else "Sent ") + "$n ${if (n == 1) "file" else "files"}",
                                color = Flow.White, style = MaterialTheme.typography.headlineSmall,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (receiving) (if (s.files.all { it.meta.kind == com.flowshare.app.core.FileKind.IMAGE || it.meta.kind == com.flowshare.app.core.FileKind.VIDEO }) "Saved to the Flow Share album in Gallery" else "Saved to Flow Share folders (Pictures, Movies, Downloads)") else "${Format.bytes(s.totalBytes)} delivered to ${s.peerName}",
                                color = Flow.Lavender, style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Bucket.Ended -> {
                            Text(
                                when (s.state) {
                                    SessionState.DECLINED -> "Declined"
                                    SessionState.CANCELLED -> "Cancelled"
                                    else -> "Transfer stopped"
                                },
                                color = Flow.White, style = MaterialTheme.typography.headlineSmall,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(s.message.orEmpty(), color = Flow.Mist, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            FileList(s, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))

            when {
                s.state.isLive -> GhostButton("Cancel", onCancel)
                s.state == SessionState.COMPLETE && receiving -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.files.size == 1) GhostButton("Open", onOpenFile) else GhostButton("Show files", onShowFiles)
                    PrimaryButton("Done", onDone)
                }
                else -> PrimaryButton("Done", onDone, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private enum class Bucket { Waiting, Active, Done, Ended }

private fun SessionState.bucket() = when (this) {
    SessionState.CONNECTING, SessionState.WAITING_ACCEPT -> Bucket.Waiting
    SessionState.TRANSFERRING -> Bucket.Active
    SessionState.COMPLETE -> Bucket.Done
    else -> Bucket.Ended
}

@Composable
private fun FileList(s: TransferSession, modifier: Modifier = Modifier) {
    val shown = 3
    val current = s.currentIndex.coerceAtLeast(0)
    val start = (current - 1).coerceIn(0, (s.files.size - shown).coerceAtLeast(0))
    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.38f)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        s.files.drop(start).take(shown).forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(f.meta.kind)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.meta.name, color = Flow.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    val fp = if (f.meta.size > 0) f.transferred / f.meta.size.toFloat() else if (f.status == FileStatus.DONE) 1f else 0f
                    ThinProgress(if (f.status == FileStatus.DONE) 1f else fp, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    when (f.status) {
                        FileStatus.DONE -> "Done"
                        FileStatus.FAILED -> "Failed"
                        else -> Format.bytes(f.meta.size)
                    },
                    color = if (f.status == FileStatus.FAILED) Flow.Danger else Flow.Lavender,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        if (s.files.size > shown) {
            Text(
                "${s.doneCount} of ${s.files.size} complete",
                color = Flow.Mist, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
