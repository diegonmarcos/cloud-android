package com.diegonmarcos.superapp

/**
 * Shell-out reader for `dumpsys battery` — surfaces the negotiated
 * MAX charger spec (current + voltage + power-source flags) which is
 * NOT available via BatteryManager.
 *
 * Why this exists: BatteryManager exposes the instantaneous battery-
 * side current (V × I going INTO the cell), but Android has no public
 * API for the live charger input wattage. `dumpsys battery` does
 * expose two relevant lines on most OEMs:
 *
 *     max charging current: 3000000   (µA, sometimes mA)
 *     max charging voltage: 5000000   (µV, sometimes mV)
 *     USB powered: true
 *     AC powered: false
 *     Wireless powered: false
 *
 * Multiplying current × voltage gives the negotiated max input (e.g.
 * 15 W on a USB-C PD 3.0 charger). That's not the LIVE input, but it
 * IS more informative than just showing battery storage and pretending
 * it's the charger output.
 *
 * Permissions: `dumpsys` invocation requires android.permission.DUMP
 * (declared in AndroidManifest with tools:ignore). On most non-rooted
 * devices that perm is only auto-granted to platform-signed apps or
 * to debuggable APKs after a one-time
 *     adb shell pm grant com.diegonmarcos.superapp android.permission.DUMP
 * runtime grant. We try regardless and fall back to a [Spec] with
 * `maxPowerW=0` so the UI can render "—".
 */
object BatteryChargerSpec {

    data class Spec(
        val maxCurrentUa: Int,    // raw value from dumpsys (µA or mA — normalised)
        val maxVoltageUv: Int,    // raw value from dumpsys (µV or mV — normalised)
        val maxPowerW: Double,    // current_A × voltage_V — 0 when read failed
        val usbPowered: Boolean,
        val acPowered: Boolean,
        val wirelessPowered: Boolean,
    ) {
        /** Human-readable power source — empty when none active. */
        fun sourceLabel(): String = when {
            acPowered       -> "AC"
            wirelessPowered -> "Wireless"
            usbPowered      -> "USB"
            else            -> ""
        }
    }

    fun read(): Spec = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "battery"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        parse(out)
    }.getOrDefault(EMPTY)

    private fun parse(text: String): Spec {
        var maxC = 0
        var maxV = 0
        var usb = false
        var ac = false
        var wire = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("max charging current:") ->
                    maxC = line.substringAfter(":").trim().toIntOrNull() ?: 0
                line.startsWith("max charging voltage:") ->
                    maxV = line.substringAfter(":").trim().toIntOrNull() ?: 0
                line.startsWith("USB powered:") ->
                    usb = line.endsWith("true")
                line.startsWith("AC powered:") ->
                    ac = line.endsWith("true")
                line.startsWith("Wireless powered:") ->
                    wire = line.endsWith("true")
            }
        }
        // Normalise to A and V. Most OEMs report in µA/µV; some report
        // in mA/mV. Heuristic: |x| < 100_000 → mA/mV; else µA/µV.
        val currentA = if (kotlin.math.abs(maxC) < 100_000) maxC / 1_000.0    else maxC / 1_000_000.0
        val voltageV = if (kotlin.math.abs(maxV) < 100_000) maxV / 1_000.0    else maxV / 1_000_000.0
        val power = if (currentA > 0.0 && voltageV > 0.0) currentA * voltageV else 0.0
        return Spec(maxC, maxV, power, usb, ac, wire)
    }

    private val EMPTY = Spec(0, 0, 0.0, false, false, false)
}
