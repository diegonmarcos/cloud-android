package com.diegonmarcos.superapp.adbdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the PURE [SfcVerdict.evaluate] — simulates every
 * charge-protocol case with canned `dumpsys battery` fixtures so the whole
 * detection/verdict logic is proven WITHOUT a device or a drained battery
 * (the point of the data-driven verdict tool). No Android/org.json on the
 * tested path — stdlib only.
 */
class SfcVerdictTest {

    private val caps = mapOf("SM-G996" to 25, "SM-G998" to 25, "SM-S928" to 45)
    private val HV = 9000
    private val FULL = 95

    private fun eval(model: String, dump: String) =
        SfcVerdict.evaluate(model, dump, caps, HV, FULL)

    /** Real S21+ capture shape: full battery, both toggles on, peak 5831mA seen. */
    private val DUMP_FULL = """
        Current Battery Service state:
          AC powered: false
          USB powered: true
          Max charging current: 0
          Max charging voltage: 0
          status: 5
          level: 100
          Adaptive Fast Charging Settings: true
          Super Fast Charging Settings: true
        BatteryInfoBackUp
          mSavedBatteryMaxCurrent: 5831
        [BattActionChangedLogBuffer]
        06-18 15:57:10.262  Sending ACTION_BATTERY_CHANGED: level:100, status:5, usb:true, charge_type:1, hvc:true, mcc:0, mcv:0, cc:4493000
    """.trimIndent()

    @Test fun fullBattery_isHealthyTapering_notDenied() {
        val v = eval("SM-G996B", DUMP_FULL)
        assertEquals(SfcVerdict.Tier.FULL_OR_TAPERING, v.tier)
        assertEquals(25, v.deviceMaxWatts)          // SM-G996 prefix → 25W
        assertFalse("full battery is never a cable fault", v.cableSuspect)
        assertTrue(v.reasons.any { it.contains("tapers") })
        assertTrue("saved peak proves the chain worked", v.savedMaxCurrentMa == 5831)
        assertTrue(v.toJson().contains("\"ok\":true"))
    }

    /** Low battery, charging, but Super Fast Charging toggle OFF. */
    private val DUMP_SFC_OFF = """
          USB powered: true
          Max charging current: 2000000
          Max charging voltage: 5000000
          status: 2
          level: 40
          Adaptive Fast Charging Settings: true
          Super Fast Charging Settings: false
        06-18 10:00:00.000  Sending ACTION_BATTERY_CHANGED: level:40, status:2, hvc:false, mcc:1800, mcv:0
    """.trimIndent()

    @Test fun sfcDisabled_isFlaggedAndSlow() {
        val v = eval("SM-G996B", DUMP_SFC_OFF)
        assertFalse(v.sfcSettingOn)
        assertEquals(SfcVerdict.Tier.SLOW_5V, v.tier)
        assertTrue(v.reasons.any { it.contains("Super Fast Charging is OFF") })
    }

    /** Low battery, high-voltage contract engaged (PPS/AFC) — fast charging. */
    private val DUMP_HV = """
          USB powered: true
          Max charging current: 2770000
          Max charging voltage: 9000000
          status: 2
          level: 30
          Super Fast Charging Settings: true
        06-18 10:00:00.000  Sending ACTION_BATTERY_CHANGED: level:30, status:2, hvc:true, mcc:2770, mcv:9000
    """.trimIndent()

    @Test fun highVoltageEngaged_isFastTier() {
        val v = eval("SM-G996B", DUMP_HV)
        assertEquals(SfcVerdict.Tier.FAST_HV, v.tier)
        assertTrue(v.highVoltageEngaged)
        assertEquals(9000, v.maxChargeVoltageMv)
        assertFalse(v.cableSuspect)
        assertTrue(v.verdict.contains("Fast charging engaged"))
    }

    /** Fair test (low, charging, SFC on) yet stuck at 5V → charger/cable suspect. */
    private val DUMP_STUCK_5V = """
          USB powered: true
          Max charging current: 1500000
          Max charging voltage: 5000000
          status: 2
          level: 35
          Super Fast Charging Settings: true
        06-18 10:00:00.000  Sending ACTION_BATTERY_CHANGED: level:35, status:2, hvc:false, mcc:1500, mcv:0
    """.trimIndent()

    @Test fun stuckAt5vDespiteSettings_flagsSuspect() {
        val v = eval("SM-G996B", DUMP_STUCK_5V)
        assertEquals(SfcVerdict.Tier.SLOW_5V, v.tier)
        assertTrue("fair test + 5V → suspect", v.cableSuspect)
        assertTrue(v.reasons.any { it.contains("PPS") })
    }

    @Test fun notPowered_isNotCharging() {
        val v = eval("SM-G996B", "  USB powered: false\n  AC powered: false\n  status: 3\n  level: 60")
        assertEquals(SfcVerdict.Tier.NOT_CHARGING, v.tier)
        assertFalse(v.powered)
    }

    @Test fun unknownModel_hasNullCeiling() {
        val v = eval("SM-X999Z", DUMP_FULL)
        assertNull("model not in device_caps → null, never a guess", v.deviceMaxWatts)
    }

    @Test fun deviceCap_usesLongestPrefix() {
        assertEquals(45, SfcVerdict.longestPrefixCap("SM-S928B", caps))
        assertEquals(25, SfcVerdict.longestPrefixCap("SM-G9960", caps))
        assertNull(SfcVerdict.longestPrefixCap("Pixel 8", caps))
    }

    @Test fun fieldParsers_tolerateSamsungFormat() {
        assertEquals(5831, SfcVerdict.intField("  mSavedBatteryMaxCurrent: 5831", "mSavedBatteryMaxCurrent"))
        assertTrue(SfcVerdict.boolField("  Super Fast Charging Settings: true", "Super Fast Charging Settings"))
        assertEquals(9000, SfcVerdict.kvInt("... hvc:true, mcc:2770, mcv:9000", "mcv"))
        assertTrue(SfcVerdict.kvBool("... hvc:true, mcc:0", "hvc"))
    }
}
