package com.diegonmarcos.cloudnav.cockpit

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager

/**
 * Live attitude / heading / barometric altitude from phone sensors — the data
 * behind the cockpit instrument panel. All values are real (no mocks); a gauge
 * whose sensor is absent simply never updates (the panel omits it per mode).
 *
 *   • heading  — magnetic azimuth from the ROTATION_VECTOR, screen-rotation
 *                compensated, 0..360° (0 = north).
 *   • pitch/roll — degrees, for the artificial horizon (aircraft convention:
 *                positive pitch = nose up, positive roll = right wing down).
 *   • altitude — pressure altitude (m) from the barometer via the ISA formula,
 *                and its smoothed vertical speed (m/s). Null callback args when
 *                no barometer exists — GPS altitude is the fallback (fed by the
 *                fragment from location fixes).
 *
 * Start in onResume, [stop] in onPause. One instance per fragment.
 */
class CockpitSensors(context: Context) : SensorEventListener {

    var onHeading: ((Float) -> Unit)? = null
    var onAttitude: ((pitch: Float, roll: Float) -> Unit)? = null
    var onBaroAltitude: ((meters: Float, verticalMps: Float) -> Unit)? = null

    /** True when the device actually has a pressure sensor (drives baro gauges). */
    val hasBarometer: Boolean

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val pressure: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)

    private val rotMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private var lastAltM = Float.NaN
    private var lastAltNanos = 0L
    private var vSpeedMps = 0f

    init { hasBarometer = pressure != null }

    fun start() {
        rotationVector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        pressure?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sm.unregisterListener(this)
        lastAltM = Float.NaN; lastAltNanos = 0L; vSpeedMps = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotation(e)
            Sensor.TYPE_PRESSURE -> handlePressure(e)
        }
    }

    private fun handleRotation(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotMatrix, e.values)
        // Compensate for the current display rotation so the horizon stays level
        // in landscape as well as portrait.
        @Suppress("DEPRECATION")
        val axes = when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotMatrix, axes.first, axes.second, remapped)
        SensorManager.getOrientation(remapped, orientation)

        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0f) azimuth += 360f
        onHeading?.invoke(azimuth)

        // orientation[1] = pitch (rad, nose-down positive) → flip to nose-up.
        // orientation[2] = roll  (rad).
        val pitch = -Math.toDegrees(orientation[1].toDouble()).toFloat()
        val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
        onAttitude?.invoke(pitch, roll)
    }

    private fun handlePressure(e: SensorEvent) {
        val hPa = e.values[0]
        val altM = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hPa)
        val now = e.timestamp
        if (!lastAltM.isNaN() && lastAltNanos != 0L) {
            val dt = (now - lastAltNanos) / 1_000_000_000f
            if (dt > 0.05f) {
                val inst = (altM - lastAltM) / dt
                // Low-pass — barometric vertical speed is noisy.
                vSpeedMps += (inst - vSpeedMps) * 0.2f
                lastAltM = altM; lastAltNanos = now
            }
        } else { lastAltM = altM; lastAltNanos = now }
        onBaroAltitude?.invoke(altM, vSpeedMps)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
