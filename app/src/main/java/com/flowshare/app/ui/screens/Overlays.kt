package com.flowshare.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.flowshare.app.core.FlowPermissions
import com.flowshare.app.core.Format
import com.flowshare.app.nearby.IncomingOffer
import com.flowshare.app.ui.components.Avatar
import com.flowshare.app.ui.components.GhostButton
import com.flowshare.app.ui.components.KindBadge
import com.flowshare.app.ui.components.PrimaryButton
import com.flowshare.app.ui.components.TopBar
import com.flowshare.app.ui.components.Wordmark
import com.flowshare.app.ui.liquid.LiquidOrb
import com.flowshare.app.ui.liquid.LiquidRings
import com.flowshare.app.ui.theme.Flow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The accept / decline prompt. It emerges from the contact edge as the liquid bloom settles. */
@Composable
fun IncomingCard(offer: IncomingOffer, onAccept: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val n = offer.files.size
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF241A3A), Flow.Surface)))
            .border(1.dp, Flow.StrokeHi, RoundedCornerShape(32.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(offer.senderName, 56.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(offer.senderName, color = Flow.White, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "wants to share ${n} ${if (n == 1) "file" else "files"} · ${Format.bytes(offer.totalBytes)}",
                    color = Flow.Mist, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        offer.files.take(3).forEach { f ->
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                KindBadge(f.kind)
                Spacer(Modifier.width(12.dp))
                Text(f.name, color = Flow.White, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Format.bytes(f.size), color = Flow.Mist, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (n > 3) {
            Text("+ ${n - 3} more", color = Flow.Mist, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Flow.Violet.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Lock, null, tint = Flow.Lavender, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                buildString {
                    append("Encrypted. ")
                    if (offer.authCode.isNotBlank()) append("Both phones should show ${offer.authCode}.")
                    if (offer.verifiedByQr) append(" Verified by your QR code.")
                },
                color = Flow.Lavender, style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("Decline", onDecline, Modifier.weight(1f))
            PrimaryButton("Accept", onAccept, Modifier.weight(1f))
        }
    }
}

/** Shows this phone's pairing QR code. Scanning it on the sender connects straight to this phone. */
@Composable
fun ShowQrScreen(time: State<Float>, payload: String, deviceName: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("Receive via QR", onBack)
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            LiquidRings(time, Modifier.size(380.dp), count = 3)
            Box(
                Modifier.size(248.dp).clip(RoundedCornerShape(36.dp)).background(Color.White).padding(22.dp),
            ) { QrCode(payload, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(8.dp))
        Text(deviceName, color = Flow.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Text(
            "On the other phone: open Flow Share, tap Share Nearby,\npick files, then “Scan QR code instead”.",
            color = Flow.Mist, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.weight(1.3f))
    }
}

@Composable
fun QrCode(content: String, modifier: Modifier = Modifier) {
    val matrix = remember(content) {
        QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
    }
    Canvas(modifier.aspectRatio(1f)) {
        val n = matrix.width
        val cell = size.minDimension / n
        fun finder(x: Int, y: Int) = (x < 7 && y < 7) || (x >= n - 7 && y < 7) || (x < 7 && y >= n - 7)
        for (y in 0 until n) for (x in 0 until n) {
            if (!matrix.get(x, y) || finder(x, y)) continue
            drawRoundRect(
                Flow.VioletInk,
                topLeft = Offset(x * cell + cell * 0.08f, y * cell + cell * 0.08f),
                size = Size(cell * 0.84f, cell * 0.84f),
                cornerRadius = CornerRadius(cell * 0.3f),
            )
        }
        // Rounded finder eyes in brand violet
        listOf(Offset(0f, 0f), Offset((n - 7) * cell, 0f), Offset(0f, (n - 7) * cell)).forEach { o ->
            drawRoundRect(Flow.VioletDeep, o, Size(cell * 7, cell * 7), CornerRadius(cell * 2f))
            drawRoundRect(Color.White, o + Offset(cell, cell), Size(cell * 5, cell * 5), CornerRadius(cell * 1.4f))
            drawRoundRect(Flow.Violet, o + Offset(cell * 2, cell * 2), Size(cell * 3, cell * 3), CornerRadius(cell))
        }
    }
}

@Composable
fun PermissionScreen(time: State<Float>, onGranted: () -> Unit) {
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (FlowPermissions.allGranted(context)) onGranted() else denied = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (FlowPermissions.allGranted(context)) onGranted()
    }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        LiquidOrb(time, 0f, Modifier.size(150.dp))
        Spacer(Modifier.height(36.dp))
        Wordmark()
        Spacer(Modifier.height(16.dp))
        Text(
            "Flow Share sends files directly between phones over Bluetooth and Wi-Fi — no internet, no cloud.",
            color = Flow.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "To find phones beside you it needs Nearby devices access. Android also asks for Location " +
                "because Bluetooth scanning can reveal it — Flow Share never reads or stores your location.",
            color = Flow.Mist, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (denied) {
            GhostButton("Open settings", {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        PrimaryButton(
            if (denied) "Try again" else "Allow nearby access",
            { launcher.launch(FlowPermissions.required + FlowPermissions.optional) },
            Modifier.fillMaxWidth(),
        )
    }
}
