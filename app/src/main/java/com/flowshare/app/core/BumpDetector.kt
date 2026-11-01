package com.flowshare.app.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects a deliberate "tap phones together" bump using the accelerometer.
 *
 * Phones can't measure the exact distance to each other through public Android APIs, so Flow Share
 * uses this as its phone-approach trigger: bump the top edges together while the other phone is
 * discovered nearby, and the connection + liquid contact animation starts.
 */
class BumpDetector(context: Context, private val onBump: () -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val sensor = linear ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastBump = 0L
    private var armedAt = 0L

    fun start() {
        armedAt = SystemClock.elapsedRealtime()
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        var magnitude = sqrt(x * x + y * y + z * z)
        if (linear == null) magnitude = abs(magnitude - SensorManager.GRAVITY_EARTH)
        val now = SystemClock.elapsedRealtime()
        // Ignore the first moment after arming (the user is still tapping the screen).
        if (now - armedAt < 600) return
        if (magnitude > THRESHOLD && now - lastBump > COOLDOWN_MS) {
            lastBump = now
            onBump()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD = 13.5f
        const val COOLDOWN_MS = 2500L
    }
}
