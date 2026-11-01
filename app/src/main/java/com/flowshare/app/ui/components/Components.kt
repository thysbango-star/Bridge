package com.flowshare.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flowshare.app.core.FileKind
import com.flowshare.app.ui.theme.Flow
import kotlin.math.absoluteValue

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Flow.White, fontWeight = FontWeight.SemiBold)) { append("flow") }
            withStyle(SpanStyle(color = Flow.VioletBright, fontWeight = FontWeight.Light)) { append(" share") }
        },
        fontSize = 22.sp,
        letterSpacing = (-0.5).sp,
        modifier = modifier,
    )
}

@Composable
fun pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Float {
    val isPressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "press")
    return s
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .scale(pressScale(src))
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Flow.Violet, Flow.VioletBright))
                else Brush.horizontalGradient(listOf(Flow.SurfaceHi, Flow.SurfaceHi)),
            )
            .clickable(src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Flow.White)
    }
}

@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Flow.White,
) {
    val src = remember { MutableInteractionSource() }
    Row(
        modifier
            .scale(pressScale(src))
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Flow.Stroke, RoundedCornerShape(26.dp))
            .clickable(src, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val src = remember { MutableInteractionSource() }
        Box(
            Modifier.size(44.dp).scale(pressScale(src, 0.88f)).clip(CircleShape)
                .clickable(src, indication = null, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            @Suppress("DEPRECATION")
            Icon(Icons.Filled.ArrowBack, "Back", tint = Flow.White)
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = Flow.White, modifier = Modifier.weight(1f))
        action()
    }
}

private val avatarPalettes = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFFE48CFF)),
    listOf(Color(0xFF6D28D9), Color(0xFFA78BFA)),
    listOf(Color(0xFF7C3AED), Color(0xFFFFB98F)),
    listOf(Color(0xFF4C1D95), Color(0xFF8B5CF6)),
    listOf(Color(0xFFA855F7), Color(0xFFDDD2FF)),
)

@Composable
fun Avatar(name: String, size: Dp = 48.dp) {
    val colors = avatarPalettes[name.hashCode().absoluteValue % avatarPalettes.size]
    val initials = name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Flow.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp)
    }
}

@Composable
fun KindBadge(kind: FileKind, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(Flow.Violet.copy(alpha = 0.16f))
            .border(1.dp, Flow.Violet.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.label, color = Flow.Lavender, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "dot")
    val a by inf.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dotA",
    )
    Canvas(modifier.size(8.dp)) {
        drawCircle(color.copy(alpha = if (pulsing) a * 0.35f else 0.25f), radius = size.minDimension)
        drawCircle(color.copy(alpha = if (pulsing) a else 1f), radius = size.minDimension / 2f)
    }
}

/** "Encrypted · 4821" — the UKEY2 verification code both phones must show. */
@Composable
fun SecureChip(code: String, verifiedByQr: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.35f))
            .border(1.dp, Flow.StrokeHi, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Lock, null, tint = Flow.Lavender, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            buildString {
                append("Encrypted")
                if (code.isNotBlank()) append(" · code $code")
                if (verifiedByQr) append(" · QR verified")
            },
            color = Flow.Lavender, style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ThinProgress(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.12f))) {
        Box(
            Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp)
                .background(Brush.horizontalGradient(listOf(Flow.Lavender, Color.White))),
        )
    }
}
