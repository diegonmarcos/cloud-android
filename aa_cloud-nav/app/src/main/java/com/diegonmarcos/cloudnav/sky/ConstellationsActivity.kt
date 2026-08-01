package com.diegonmarcos.cloudnav.sky

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Point-the-phone-at-the-sky constellation view (clean-room, not a Stardroid port --
 * see StarCatalog.kt for why). Uses TYPE_ROTATION_VECTOR for device pointing direction
 * (when present — drag/pinch on [SkyView] works regardless) and the last-known/fresh
 * location for the alt/az conversion. If location is truly unavailable, it says so
 * on-screen rather than silently rendering the wrong sky for (0,0).
 */
class ConstellationsActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var skyView: SkyView
    private lateinit var statusText: TextView

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skyView = SkyView(this)
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 13f
            setPadding(24, 24, 24, 24)
        }
        val root = FrameLayout(this).apply {
            addView(skyView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(statusText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START
            })
        }
        setContentView(root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        resolveLocation()
        updateStatus()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun resolveLocation() {
        if (!hasLocationPermission()) { updateStatus(); return }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        val providers = try { lm.getProviders(true) } catch (e: SecurityException) { emptyList() }
        val last = providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
        if (last != null) {
            skyView.observerLatDeg = last.latitude
            skyView.observerLonDeg = last.longitude
            updateStatus()
            return
        }
        val provider = providers.firstOrNull() ?: return
        try {
            lm.requestSingleUpdate(provider, { loc ->
                skyView.observerLatDeg = loc.latitude
                skyView.observerLonDeg = loc.longitude
                updateStatus()
            }, mainLooper)
        } catch (e: SecurityException) { /* permission revoked mid-flight — status text covers it */ }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        val hasFix = skyView.observerLatDeg != 0.0 || skyView.observerLonDeg != 0.0
        val locLine = if (hasFix) "%.2f, %.2f".format(skyView.observerLatDeg, skyView.observerLonDeg)
            else if (!hasLocationPermission()) "No location permission — using 0,0 (wrong sky)"
            else "Locating…"
        val sensorLine = if (rotationSensor == null) " · no orientation sensor, drag to look around" else ""
        statusText.text = "Constellations (v1: stars only, no planets)\n$locLine$sensorLine"
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
        skyView.sensorAzimuthDeg = Math.toDegrees(orientation[0].toDouble()).let { if (it < 0) it + 360 else it }
        skyView.sensorPitchDeg = -Math.toDegrees(orientation[1].toDouble())
        skyView.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
