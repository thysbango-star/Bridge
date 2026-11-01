package com.flowshare.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowshare.app.ui.components.GhostButton
import com.flowshare.app.ui.components.StatusDot
import com.flowshare.app.ui.components.Wordmark
import com.flowshare.app.ui.liquid.LiquidOrb
import com.flowshare.app.ui.theme.Flow

@Composable
fun HomeScreen(
    time: State<Float>,
    deviceName: String,
    receiving: Boolean,
    radioError: String?,
    onShare: () -> Unit,
    onShowQr: () -> Unit,
    onToggleReceiving: (Boolean) -> Unit,
    onRename: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark()
            Spacer(Modifier.weight(1f))
            Text("Visible", color = Flow.Mist, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = receiving,
                onCheckedChange = onToggleReceiving,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Flow.Violet,
                    checkedThumbColor = Flow.White,
                    uncheckedTrackColor = Flow.SurfaceHi,
                    uncheckedThumbColor = Flow.Mist,
                    uncheckedBorderColor = Flow.Stroke,
                ),
            )
        }

        Spacer(Modifier.weight(1f))

        ShareNearbyButton(time, onShare)

        Spacer(Modifier.height(36.dp))
        Text(
            "Photos, videos, documents, APKs —\nanything, straight to a phone beside you.",
            color = Flow.Mist, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("Receive via QR", onShowQr, icon = Icons.Filled.Share)
            GhostButton("Rename", { renaming = true }, icon = Icons.Filled.Edit)
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { renaming = true }.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (radioError != null) Flow.Danger else if (receiving) Flow.VioletBright else Flow.Dim, pulsing = receiving && radioError == null)
            Spacer(Modifier.width(10.dp))
            Text(
                radioError ?: if (receiving) "Ready to receive as $deviceName" else "Hidden — others can't see $deviceName",
                color = Flow.Mist, style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    if (renaming) RenameDialog(deviceName, onDismiss = { renaming = false }) { onRename(it); renaming = false }
}

@Composable
private fun ShareNearbyButton(time: State<Float>, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 300f), label = "orbPress")
    Box(
        Modifier.size(272.dp).scale(1f - 0.05f * press)
            .clip(CircleShape)
            .clickable(src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LiquidOrb(time, press, Modifier.size(232.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Share", color = Flow.White, fontSize = 30.sp, style = MaterialTheme.typography.headlineLarge)
            Text("Nearby", color = Flow.Lavender, fontSize = 30.sp, style = MaterialTheme.typography.displayMedium.copy(fontSize = 30.sp))
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Flow.SurfaceHi,
        title = { Text("Device name") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(32) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Flow.Violet, cursorColor = Flow.Violet,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = Flow.VioletBright) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Flow.Mist) } },
    )
}
