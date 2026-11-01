package com.flowshare.app.ui.liquid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import com.flowshare.app.ui.theme.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The phone-to-phone "liquid contact" effect, recreated from the reference video.
 *
 * Frame analysis of the reference (30 fps):
 *   t = 0.00s  phones touch along their top edges
 *   t ≈ 0.20s  a bright bloom appears at the contact edge, with a warm iridescent rim
 *   t ≈ 0.60s  the bloom has swept ~half the screen; content behind it is bent, blurred and
 *              chromatically split; the screen behind the front washes out milky-bright
 *   t ≈ 1.10s  the front leaves the far edge; brightness peaks then recedes
 *   t ≈ 1.50s  the contact card has settled in, surface is calm
 *
 * On Android 13+ this runs as an AGSL RuntimeShader applied as a RenderEffect over the whole UI
 * (so the real screen pixels ripple). Older versions get a Canvas approximation.
 */
@Stable
class LiquidController internal constructor(
    private val scope: CoroutineScope,
    val time: State<Float>,
) {
    val radius = Animatable(0f)
    val strength = Animatable(0f)
    val fill = Animatable(0f)

    /** Contact point as a fraction of the screen (0.5, 0) = middle of the top edge. */
    var originX by mutableFloatStateOf(0.5f)
    var originY by mutableFloatStateOf(0f)

    private var job: Job? = null

    /** Full contact bloom — played on both phones the moment the encrypted link is established. */
    fun contact() = play {
        launch { radius.animateTo(1.22f, tween(1250, easing = BloomEasing)) }
        launch {
            strength.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
            delay(640)
            strength.animateTo(0f, tween(620, easing = FastOutSlowInEasing))
        }
        launch {
            delay(120)
            fill.animateTo(0.62f, tween(520, easing = FastOutSlowInEasing))
            fill.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        }
    }

    /** Gentler wave used when a transfer starts flowing. */
    fun pulse() = play {
        launch { radius.animateTo(1.25f, tween(1500, easing = BloomEasing)) }
        launch {
            strength.animateTo(0.7f, tween(300))
            delay(500)
            strength.animateTo(0f, tween(700, easing = FastOutSlowInEasing))
        }
        launch {
            fill.animateTo(0.28f, tween(500))
            fill.animateTo(0f, tween(800, easing = FastOutSlowInEasing))
        }
    }

    /** Long, low settling wave that rolls out across the screen when a transfer completes. */
    fun settle() = play {
        launch { radius.animateTo(1.35f, tween(1900, easing = BloomEasing)) }
        launch {
            strength.animateTo(0.55f, tween(380))
            delay(700)
            strength.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        }
        launch {
            fill.animateTo(0.35f, tween(600))
            fill.animateTo(0f, tween(1000, easing = FastOutSlowInEasing))
        }
    }

    private fun play(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch {
            radius.snapTo(0f); strength.snapTo(0f); fill.snapTo(0f)
            coroutineScope { block() }
        }
    }

    internal val active: Boolean get() = strength.value > 0.002f || fill.value > 0.002f

    companion object {
        /** Fast swell out of the contact edge, long liquid deceleration — matches the reference sweep. */
        val BloomEasing = CubicBezierEasing(0.25f, 0.1f, 0.12f, 1f)
    }
}

/** A continuously running clock (seconds) shared by every liquid drawing. */
@Composable
fun rememberFlowTime(): State<Float> {
    val t: MutableFloatState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> t.floatValue = ((now - start) / 1_000_000_000.0 % 3600.0).toFloat() }
        }
    }
    return t
}

@Composable
fun rememberLiquidController(time: State<Float>): LiquidController {
    val scope = rememberCoroutineScope()
    return remember(time) { LiquidController(scope, time) }
}

/** Applies the liquid contact distortion to everything drawn inside this modifier. */
fun Modifier.liquidSurface(c: LiquidController): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) liquidAgsl(c) else liquidFallback(c)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.liquidAgsl(c: LiquidController): Modifier = composed {
    val shader = remember { RuntimeShader(LIQUID_AGSL) }
    graphicsLayer {
        if (!c.active) {
            renderEffect = null
            return@graphicsLayer
        }
        shader.applyLiquid(c, size.width, size.height)
        renderEffect = android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.applyLiquid(c: LiquidController, w: Float, h: Float) {
    val diag = hypot(w, h)
    setFloatUniform("size", w, h)
    setFloatUniform("origin", c.originX * w, c.originY * h)
    setFloatUniform("radius", c.radius.value * diag)
    setFloatUniform("band", diag * 0.085f)
    setFloatUniform("time", c.time.value)
    setFloatUniform("strength", c.strength.value)
    setFloatUniform("fill", c.fill.value)
    setFloatUniform("liquid", 0.96f, 0.92f, 1.0f)        // luminous lavender-white body
    setFloatUniform("rimA", 0.545f, 0.361f, 0.965f)      // Flow violet
    setFloatUniform("rimB", 0.894f, 0.549f, 1.0f)        // orchid / iridescent pink
    setFloatUniform("rimC", 1.0f, 0.86f, 0.80f)          // warm white core (amber hint from reference)
}

/**
 * The same bloom, generated without any underlying content — used when Flow Share draws on top
 * of another app (Gallery etc.), whose pixels we are not allowed to read.
 */
@Composable
fun LiquidOverlay(c: LiquidController, modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = remember { RuntimeShader(LIQUID_OVERLAY_AGSL) }
        val brush = remember(shader) { ShaderBrush(shader) }
        Canvas(modifier) {
            if (!c.active) return@Canvas
            shader.applyLiquid(c, size.width, size.height)
            drawRect(brush)
        }
    } else {
        Canvas(modifier) { drawLiquidFallback(c) }
    }
}

/** Android 8–12: draw the bloom, rim and liquid body on top of the content with Canvas. */
private fun Modifier.liquidFallback(c: LiquidController): Modifier = drawWithContent {
    drawContent()
    drawLiquidFallback(c)
}

private fun DrawScope.drawLiquidFallback(c: LiquidController) {
    if (!c.active) return
    val s = c.strength.value
    val f = c.fill.value
    val t = c.time.value
    val diag = hypot(size.width, size.height)
    val r = c.radius.value * diag
    val o = Offset(c.originX * size.width, c.originY * size.height)
    val band = diag * 0.085f
    val path = Path()
    val steps = 96
    for (i in 0..steps) {
        val a = (i / steps.toFloat()) * 2f * PI.toFloat()
        val wob = sin(a * 5f + t * 1.7f) * 0.5f + sin(a * 11f - t * 2.6f) * 0.22f + sin(a * 2f + t * 0.8f) * 0.6f
        val rr = (r + wob * band * 0.16f * s).coerceAtLeast(0f)
        val p = Offset(o.x + cos(a) * rr / 0.8f, o.y + sin(a) * rr)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    path.close()
    drawPath(path, Color(0xFFEDE6FF).copy(alpha = (f * 0.85f).coerceIn(0f, 1f)))
    drawPath(
        path,
        Brush.sweepGradient(listOf(Flow.Violet, Flow.Orchid, Flow.Ember, Flow.Lavender, Flow.Violet), o),
        alpha = s.coerceIn(0f, 1f),
        style = Stroke(width = band * 0.55f),
    )
    val glowR = (r * 0.6f).coerceAtLeast(1f)
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * s), Color.Transparent), o, glowR),
        radius = glowR, center = o,
    )
}

/**
 * AGSL shader. `content` is the rendered UI. A wobbly, flattened circular front expands from
 * `origin`; ahead of it the pixels are lensed, on it they are pushed outward with a chromatic
 * split and an iridescent rim, behind it they are frosted and flooded with a milky liquid body
 * that keeps swelling like a slow ocean.
 */
private const val LIQUID_AGSL = """
uniform shader content;
uniform float2 size;
uniform float2 origin;
uniform float radius;
uniform float band;
uniform float time;
uniform float strength;
uniform float fill;
uniform float3 liquid;
uniform float3 rimA;
uniform float3 rimB;
uniform float3 rimC;

float3 tap(float2 p) {
    float2 q = clamp(p, float2(0.5), size - float2(0.5));
    return float3(content.eval(q).rgb);
}

half4 main(float2 p) {
    float2 v = p - origin;
    float2 ve = float2(v.x * 0.8, v.y);
    float d = length(ve);
    float ang = atan(ve.y, ve.x);

    float wob = sin(ang * 5.0 + time * 1.7) * 0.5
              + sin(ang * 11.0 - time * 2.6) * 0.22
              + sin(ang * 2.0 + time * 0.8) * 0.6;
    float r = radius + wob * band * 0.16 * strength;
    float x = (d - r) / band;

    float rim = exp(-x * x * 3.2);
    float inside = 1.0 - smoothstep(-0.7, 0.2, x);
    float ahead = (1.0 - inside) * exp(-max(x, 0.0) * 1.6);
    float2 dir = v / max(length(v), 0.001);

    float swell = sin(d * 0.032 - time * 4.0) * inside;
    float push = (rim * 54.0 + ahead * 18.0 + swell * 9.0) * strength;
    float2 off = dir * push;

    float r1 = tap(p - off).r;
    float g1 = tap(p - off * 0.8).g;
    float b1 = tap(p - off * 0.6).b;
    float3 base = float3(r1, g1, b1);

    float br = (inside * 9.0 + rim * 16.0) * strength + 0.001;
    float3 blur = (tap(p - off + float2(br, 0.0)) + tap(p - off - float2(br, 0.0))
                 + tap(p - off + float2(0.0, br)) + tap(p - off - float2(0.0, br))) * 0.25;
    float3 col = mix(base, blur, clamp(inside * 0.75 + rim * 0.5, 0.0, 1.0) * strength);

    float h = 0.5 + 0.5 * sin(ang * 1.6 + time * 1.2 - x * 1.5);
    float3 rc = mix(rimA, rimB, h);
    rc = mix(rc, rimC, (1.0 - smoothstep(-1.0, 0.0, x)) * 0.8);

    float caustic = 0.5 + 0.5 * sin(p.x * 0.018 + time * 1.1 + sin(p.y * 0.013 - time * 0.7) * 2.2);
    float3 body = liquid * (0.88 + 0.12 * caustic);
    // Brightest at the contact edge, thinning toward the far side (as in the reference).
    float near = 0.45 + 0.55 * exp(-d / (length(size) * 0.42));
    col = mix(col, body, clamp(inside * fill * near, 0.0, 1.0));

    float glow = exp(-d / max(r * 0.5, 1.0)) * (1.0 - fill * 0.5);
    float3 glowC = mix(rimC, rimA, 0.4);
    col += rc * rim * strength * 0.9 + glowC * glow * strength * 0.35;

    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}
"""

/** Generative variant of [LIQUID_AGSL]: same geometry, outputs premultiplied colour + alpha. */
private const val LIQUID_OVERLAY_AGSL = """
uniform float2 size;
uniform float2 origin;
uniform float radius;
uniform float band;
uniform float time;
uniform float strength;
uniform float fill;
uniform float3 liquid;
uniform float3 rimA;
uniform float3 rimB;
uniform float3 rimC;

half4 main(float2 p) {
    float2 v = p - origin;
    float2 ve = float2(v.x * 0.8, v.y);
    float d = length(ve);
    float ang = atan(ve.y, ve.x);

    float wob = sin(ang * 5.0 + time * 1.7) * 0.5
              + sin(ang * 11.0 - time * 2.6) * 0.22
              + sin(ang * 2.0 + time * 0.8) * 0.6;
    float r = radius + wob * band * 0.16 * strength;
    float x = (d - r) / band;
    float rim = exp(-x * x * 3.2);
    float inside = 1.0 - smoothstep(-0.7, 0.2, x);

    float h = 0.5 + 0.5 * sin(ang * 1.6 + time * 1.2 - x * 1.5);
    float3 rc = mix(rimA, rimB, h);
    rc = mix(rc, rimC, (1.0 - smoothstep(-1.0, 0.0, x)) * 0.8);

    float caustic = 0.5 + 0.5 * sin(p.x * 0.018 + time * 1.1 + sin(p.y * 0.013 - time * 0.7) * 2.2);
    float near = 0.45 + 0.55 * exp(-d / (length(size) * 0.42));
    float swell = 0.5 + 0.5 * sin(d * 0.032 - time * 4.0);
    float bodyA = clamp(inside * fill * near, 0.0, 1.0) * (0.78 + 0.22 * swell) * 0.85;
    float rimA2 = clamp(rim * strength, 0.0, 1.0) * 0.9;
    float glow = exp(-d / max(r * 0.5, 1.0)) * strength * 0.45;
    float3 glowC = mix(rimC, rimA, 0.4);

    float a = clamp(bodyA + rimA2 + glow, 0.0, 1.0);
    float3 col = liquid * (0.88 + 0.12 * caustic) * bodyA + rc * rimA2 + glowC * glow;
    col = min(col, float3(a));
    return half4(half3(col), half(a));
}
"""
