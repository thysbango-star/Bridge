package com.flowshare.app.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Liquid-feeling haptic patterns that accompany the animations. */
object Haptics {
    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** A soft "touch" followed by a swelling, decaying ripple — matches the contact bloom. */
    fun contact(context: Context) = play(
        context,
        timings = longArrayOf(0, 14, 70, 22, 60, 30, 70, 40),
        amplitudes = intArrayOf(0, 200, 0, 140, 0, 90, 0, 45),
    )

    fun tick(context: Context) = play(context, longArrayOf(0, 8), intArrayOf(0, 90))

    fun success(context: Context) = play(
        context,
        timings = longArrayOf(0, 18, 90, 30, 120, 60),
        amplitudes = intArrayOf(0, 160, 0, 220, 0, 70),
    )

    fun reject(context: Context) = play(context, longArrayOf(0, 25, 60, 25), intArrayOf(0, 180, 0, 180))

    private fun play(context: Context, timings: LongArray, amplitudes: IntArray) {
        val v = vibrator(context) ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        }
    }
}
