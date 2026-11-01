package com.flowshare.app.ui.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import com.flowshare.app.ui.theme.Flow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val TAU = (2 * PI).toFloat()

/** Superimposed sines → an organic, non-repeating ocean surface. */
fun waveY(x: Float, t: Float, amp: Float, wavelength: Float, speed: Float, phase: Float): Float {
    val k = TAU / wavelength
    return amp * (
        0.60f * sin(x * k + t * speed + phase) +
            0.28f * sin(x * k * 1.9f - t * speed * 1.35f + phase * 1.7f) +
            0.12f * sin(x * k * 0.45f + t * speed * 0.55f + phase * 0.3f)
        )
}

/** Fills between a wavy surface at [surfaceY] and a flat [baseY]. */
fun DrawScope.drawLiquidLayer(
    path: Path,
    surfaceY: Float,
    baseY: Float,
    t: Float,
    amp: Float,
    wavelength: Float,
    speed: Float,
    phase: Float,
    brush: Brush,
    alpha: Float = 1f,
) {
    path.reset()
    path.moveTo(0f, baseY)
    var x = 0f
    val step = 6f
    while (x <= size.width + step) {
        path.lineTo(x, surfaceY + waveY(x, t, amp, wavelength, speed, phase))
        x += step
    }
    path.lineTo(size.width, baseY)
    path.close()
    drawPath(path, brush, alpha = alpha)
}

private fun DrawScope.surfaceHighlight(surfaceY: Float, t: Float, amp: Float, wavelength: Float, speed: Float, phase: Float, alpha: Float) {
    val p = Path()
    var x = 0f
    while (x <= size.width + 6f) {
        val y = surfaceY + waveY(x, t, amp, wavelength, speed, phase)
        if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y)
        x += 6f
    }
    // Iridescent surface line: violet → orchid → warm white → lavender
    drawPath(
        p,
        Brush.horizontalGradient(listOf(Flow.Violet, Flow.Orchid, Flow.Ember, Color.White, Flow.Lavender, Flow.Violet)),
        alpha = alpha,
        style = Stroke(width = 2.2f * density, cap = StrokeCap.Round),
    )
    drawPath(
        p,
        Brush.horizontalGradient(listOf(Flow.Violet.copy(alpha = 0f), Flow.Lavender, Flow.Violet.copy(alpha = 0f))),
        alpha = alpha * 0.35f,
        style = Stroke(width = 14f * density, cap = StrokeCap.Round),
    )
}

/**
 * Full-screen transfer visual. Both phones touch at the top edge, so liquid pours *out of* the
 * sender's top edge (its level retreats upward) and *into* the receiver's top edge (its level
 * descends) — the two screens read as one connected liquid surface.
 *
 * @param level 0..1 fraction of the screen filled, measured from the top (contact) edge.
 * @param energy 0..1 how agitated the surface is (tracks transfer speed; decays on completion).
 * @param inflow true on the receiver (particles stream downward), false on the sender.
 */
@Composable
fun LiquidTank(
    level: Float,
    energy: Float,
    inflow: Boolean,
    time: State<Float>,
    modifier: Modifier = Modifier,
) {
    val smoothLevel by animateFloatAsState(level, spring(dampingRatio = 0.85f, stiffness = 40f), label = "level")
    val smoothEnergy by animateFloatAsState(energy, tween(900), label = "energy")
    val back = remember { Path() }
    val mid = remember { Path() }
    val front = remember { Path() }
    val bubbles = remember { List(34) { i -> Bubble.seed(i) } }

    Canvas(modifier) {
        val t = time.value
        val w = size.width
        val h = size.height
        // Liquid region spans from top edge down to the surface. Keep a sliver at 0 so the
        // contact meniscus is always visible.
        val surface = (smoothLevel.coerceIn(0f, 1f) * (h + 40f * density)) - 20f * density
        val amp = (6f + 18f * smoothEnergy) * density
        val wl = w * 0.9f

        // Contact-edge bloom (always present while linked) — echoes the reference's edge glow.
        val bloomR = w * (0.75f + 0.08f * sin(t * 1.6f))
        drawCircle(
            Brush.radialGradient(
                0f to Flow.Lavender.copy(alpha = 0.55f),
                0.35f to Flow.Violet.copy(alpha = 0.35f),
                1f to Color.Transparent,
                center = Offset(w / 2f, 0f), radius = bloomR,
            ),
            radius = bloomR, center = Offset(w / 2f, 0f),
        )

        if (surface > -10f * density) {
            val body = Brush.verticalGradient(
                0f to Flow.VioletBright,
                0.25f to Flow.Violet,
                0.75f to Flow.VioletDeep,
                1f to Flow.VioletInk,
                startY = 0f, endY = surface.coerceAtLeast(1f) + amp,
            )
            drawLiquidLayer(back, surface + 10f * density, 0f, t, amp * 1.25f, wl * 1.3f, 0.9f, 2.1f,
                SolidColor(Flow.Orchid), alpha = 0.22f)
            drawLiquidLayer(mid, surface + 4f * density, 0f, t, amp * 1.1f, wl * 0.8f, 1.25f, 4.2f,
                SolidColor(Flow.Violet), alpha = 0.45f)
            drawLiquidLayer(front, surface, 0f, t, amp, wl, 1.6f, 0f, body)

            // Bubbles stream in the direction of flow, clipped to the liquid.
            clipPath(front) {
                bubbles.forEach { b ->
                    val travel = ((t * b.speed + b.offset) % 1f)
                    val yFrac = if (inflow) travel else 1f - travel
                    val y = yFrac * (surface + amp)
                    val x = (b.x + 0.03f * sin(t * b.wobble + b.offset * TAU)) * w
                    val a = 0.18f + 0.5f * b.bright * (1f - travel * 0.6f)
                    drawCircle(Color.White.copy(alpha = a), radius = b.r * density, center = Offset(x, y))
                }
                // Caustic light bands
                for (i in 0 until 3) {
                    val cx = w * (0.2f + 0.3f * i) + sin(t * 0.6f + i) * w * 0.1f
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), Offset(cx, surface * 0.5f), w * 0.4f),
                        radius = w * 0.4f, center = Offset(cx, surface * 0.5f),
                    )
                }
            }
            surfaceHighlight(surface, t, amp, wl, 1.6f, 0f, alpha = 0.9f)
        }
    }
}

private class Bubble(val x: Float, val r: Float, val speed: Float, val offset: Float, val wobble: Float, val bright: Float) {
    companion object {
        fun seed(i: Int): Bubble {
            fun h(n: Int): Float { val s = sin((i * 12.9898f + n * 78.233f)) * 43758.547f; return s - kotlin.math.floor(s) }
            return Bubble(h(1), 1.2f + h(2) * 2.8f, 0.08f + h(3) * 0.22f, h(4), 0.6f + h(5) * 1.6f, h(6))
        }
    }
}

/** The large central "Share Nearby" orb: a contained, breathing pool of liquid with an iridescent rim. */
@Composable
fun LiquidOrb(time: State<Float>, pressed: Float, modifier: Modifier = Modifier) {
    val p1 = remember { Path() }
    val p2 = remember { Path() }
    val clip = remember { Path() }
    Canvas(modifier) {
        val t = time.value
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)

        // Outer glow
        drawCircle(
            Brush.radialGradient(
                0.55f to Flow.Violet.copy(alpha = 0.35f + 0.15f * pressed),
                1f to Color.Transparent,
                center = c, radius = r * 1.5f,
            ),
            radius = r * 1.5f, center = c,
        )
        // Glass body
        drawCircle(
            Brush.radialGradient(listOf(Flow.VioletInk, Flow.Black), center = c + Offset(0f, -r * 0.4f), radius = r * 1.3f),
            radius = r, center = c,
        )
        clip.reset()
        clip.addOval(androidx.compose.ui.geometry.Rect(c, r))
        clipPath(clip) {
            val level = c.y - r * (0.05f + 0.08f * sin(t * 0.7f)) - r * 0.25f * pressed
            val amp = r * (0.06f + 0.05f * pressed)
            drawLiquidLayer(p1, level - r * 0.05f, size.height, t, amp * 1.2f, r * 2.4f, 1.1f, 1.9f,
                SolidColor(Flow.Orchid), alpha = 0.35f)
            drawLiquidLayer(
                p2, level, size.height, t, amp, r * 1.8f, 1.5f, 0f,
                Brush.verticalGradient(listOf(Flow.VioletBright, Flow.Violet, Flow.VioletDeep), startY = level - amp, endY = c.y + r),
            )
            // Specular highlight
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), c + Offset(-r * 0.35f, -r * 0.45f), r * 0.6f),
                radius = r * 0.6f, center = c + Offset(-r * 0.35f, -r * 0.45f),
            )
        }
        // Rotating iridescent rim
        rotateRim(c, r, t)
    }
}

private fun DrawScope.rotateRim(c: Offset, r: Float, t: Float) {
    val a = t * 0.6f
    val colors = listOf(Flow.Violet, Flow.Orchid, Flow.Ember, Flow.Lavender, Flow.Violet, Flow.VioletDeep, Flow.Violet)
    val brush = Brush.sweepGradient(colors, center = c)
    rotate(a * 57.2958f, c) {
        drawCircle(brush, radius = r - 1.2f * density, center = c, style = Stroke(width = 2.4f * density))
        drawCircle(brush, radius = r + 3f * density, center = c, alpha = 0.25f, style = Stroke(width = 8f * density))
    }
}

/**
 * Discovery rings — wobbly liquid rings that spread out from the center, like ripples on a
 * surface, used while searching for nearby phones.
 */
@Composable
fun LiquidRings(time: State<Float>, modifier: Modifier = Modifier, count: Int = 4) {
    val path = remember { Path() }
    Canvas(modifier) {
        val t = time.value
        val c = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension / 2f
        for (i in 0 until count) {
            val f = ((t * 0.22f + i / count.toFloat()) % 1f)
            val r = maxR * (0.25f + 0.75f * f)
            val alpha = (1f - f) * (1f - f) * 0.7f
            path.reset()
            val steps = 90
            for (s in 0..steps) {
                val a = s / steps.toFloat() * TAU
                val wob = 1f + 0.035f * sin(a * 4f + t * 1.4f + i) + 0.02f * sin(a * 7f - t * 2f)
                val p = Offset(c.x + cos(a) * r * wob, c.y + sin(a) * r * wob)
                if (s == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(
                path,
                Brush.sweepGradient(listOf(Flow.Violet, Flow.Orchid, Flow.Lavender, Flow.Violet), c),
                alpha = alpha,
                style = Stroke(width = (2.5f - 1.5f * f) * density),
            )
        }
    }
}

/**
 * Completion: a ring is drawn around the center while the check stroke writes itself in,
 * followed by a single soft shock ring that rolls outward and fades.
 */
@Composable
fun SuccessMark(play: Boolean, modifier: Modifier = Modifier) {
    val ring = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val shock = remember { Animatable(0f) }
    LaunchedEffect(play) {
        if (!play) { ring.snapTo(0f); check.snapTo(0f); shock.snapTo(0f); return@LaunchedEffect }
        ring.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        shock.snapTo(0f)
        coroutineScope {
            launch { check.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow)) }
            launch { shock.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        }
    }
    val checkPath = remember { Path() }
    val seg = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * 0.82f
        val sw = 3.2f * density
        // shock ring
        if (shock.value > 0f && shock.value < 1f) {
            drawCircle(
                Flow.Lavender, radius = r * (1f + 0.9f * shock.value), center = c,
                alpha = (1f - shock.value) * 0.5f, style = Stroke(width = sw * (1f - shock.value) + 0.5f),
            )
        }
        drawCircle(
            Brush.radialGradient(listOf(Flow.Violet.copy(alpha = 0.45f * ring.value), Color.Transparent), c, r * 1.4f),
            radius = r * 1.4f, center = c,
        )
        drawArc(
            Brush.sweepGradient(listOf(Flow.Lavender, Flow.Orchid, Color.White, Flow.Lavender), c),
            startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
            topLeft = c - Offset(r, r), size = Size(r * 2, r * 2),
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        checkPath.reset()
        checkPath.moveTo(c.x - r * 0.38f, c.y + r * 0.02f)
        checkPath.lineTo(c.x - r * 0.1f, c.y + r * 0.3f)
        checkPath.lineTo(c.x + r * 0.42f, c.y - r * 0.28f)
        measure.setPath(checkPath, false)
        seg.reset()
        measure.getSegment(0f, measure.length * check.value.coerceIn(0f, 1f), seg, true)
        drawPath(seg, Color.White, style = Stroke(width = sw * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A liquid band hanging from the contact (top) edge — the transfer progress indicator used when
 * Flow Share is drawn on top of another app. Its depth follows [progress].
 */
@Composable
fun EdgeFlow(progress: Float, energy: Float, time: State<Float>, modifier: Modifier = Modifier) {
    val depth by animateFloatAsState(progress, spring(dampingRatio = 0.85f, stiffness = 50f), label = "edge")
    val e by animateFloatAsState(energy, tween(900), label = "edgeE")
    val p1 = remember { Path() }
    val p2 = remember { Path() }
    Canvas(modifier) {
        val t = time.value
        val w = size.width
        val surface = (10f + 70f * depth) * density
        val amp = (4f + 7f * e) * density
        drawCircle(
            Brush.radialGradient(
                0f to Flow.Lavender.copy(alpha = 0.5f * (0.4f + 0.6f * e)),
                1f to Color.Transparent,
                center = Offset(w / 2f, 0f), radius = w * 0.55f,
            ),
            radius = w * 0.55f, center = Offset(w / 2f, 0f),
        )
        drawLiquidLayer(p1, surface + 6f * density, 0f, t, amp * 1.2f, w * 1.1f, 1.0f, 2.3f, SolidColor(Flow.Orchid), alpha = 0.35f)
        drawLiquidLayer(
            p2, surface, 0f, t, amp, w * 0.8f, 1.6f, 0f,
            Brush.verticalGradient(listOf(Flow.VioletBright, Flow.Violet, Flow.VioletDeep), startY = 0f, endY = surface + amp),
            alpha = 0.92f,
        )
        surfaceHighlight(surface, t, amp, w * 0.8f, 1.6f, 0f, alpha = 0.9f)
    }
}
