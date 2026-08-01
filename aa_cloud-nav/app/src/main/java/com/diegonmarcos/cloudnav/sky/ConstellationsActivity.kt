package com.diegonmarcos.cloudnav.sky

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Point-the-phone-at-the-sky constellation view (clean-room, not a Stardroid port --
 * see StarCatalog.kt for why). Uses TYPE_ROTATION_VECTOR for device pointing direction
 * and last-known location for the alt/az conversion; falls back to (0,0) if location
 * is unavailable, which just points the sky at the wrong stars rather than crashing.
 */
class ConstellationsActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var skyView: SkyView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skyView = SkyView(this)
        setContentView(skyView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
        val last = try {
            lm?.getProviders(true)?.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
        } catch (e: SecurityException) { null }
        skyView.observerLatDeg = last?.latitude ?: 0.0
        skyView.observerLonDeg = last?.longitude ?: 0.0
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMatrix, orientation)
        skyView.deviceAzimuthDeg = Math.toDegrees(orientation[0].toDouble()).let { if (it < 0) it + 360 else it }
        skyView.devicePitchDeg = -Math.toDegrees(orientation[1].toDouble())
        skyView.nowMillis = System.currentTimeMillis()
        skyView.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
