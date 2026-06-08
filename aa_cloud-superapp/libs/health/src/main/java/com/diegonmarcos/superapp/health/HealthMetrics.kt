package com.diegonmarcos.superapp.health

import android.util.Base64
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import org.json.JSONArray
import org.json.JSONObject
import kotlin.reflect.KClass

/**
 * Metric taxonomy — decoded ONCE at process start from
 * BuildConfig.UI_HEALTH_METRICS_B64 (which app/gradle baked from
 * build.json::ui.sections[id=health].metrics[]).
 *
 * Each entry maps a logical metric (Activity, Heart, …) to:
 *   • a list of Health Connect record classes the gateway should
 *     query when populating the metric's summary / timeline,
 *   • a list of HC permission strings the metric depends on,
 *   • a tile label + icon resource name.
 *
 * The JSON carries record types as their Kotlin simple-name (e.g.
 * "StepsRecord"); the small lookup map [recordClassForName] resolves
 * them back to KClass<out Record>. The map IS the only static link
 * between JSON strings and the HC SDK; adding a record type means
 * one entry in this map AND one entry in build.json::metrics — the
 * compile would catch a forgotten side.
 */
object HealthMetrics {

    data class Metric(
        val id: String,
        val label: String,
        val iconName: String,
        val records: List<KClass<out Record>>,
        val perms: Set<String>,
    )

    val all: List<Metric> by lazy { parseFromBuildConfig() }

    fun byId(id: String): Metric? = all.firstOrNull { it.id == id }

    /** Union of every metric's perm set — drives the global
     *  "Request all" launcher on the Configs/Permissions tab. */
    val allPermissions: Set<String> by lazy {
        all.flatMap { it.perms }.toSet()
    }

    private fun parseFromBuildConfig(): List<Metric> {
        val raw = runCatching {
            String(Base64.decode(BuildConfig.UI_HEALTH_METRICS_B64, Base64.DEFAULT))
        }.getOrDefault("[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = mutableListOf<Metric>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val recs = mutableListOf<KClass<out Record>>()
            val recArr = o.optJSONArray("records") ?: JSONArray()
            for (j in 0 until recArr.length()) {
                recordClassForName(recArr.optString(j))?.let { recs.add(it) }
            }
            val perms = mutableSetOf<String>()
            val permArr = o.optJSONArray("perm_keys") ?: JSONArray()
            for (j in 0 until permArr.length()) {
                val key = permArr.optString(j).ifBlank { continue }
                // Resolve the canonical HC permission string from the
                // record class (instead of synthesising the string
                // from the perm_keys suffix — the HealthPermission
                // helper guarantees the SDK-correct constant).
                permForKey(key, recs)?.let { perms.add(it) }
            }
            out.add(
                Metric(
                    id       = o.optString("id"),
                    label    = o.optString("label"),
                    iconName = o.optString("icon"),
                    records  = recs.toList(),
                    perms    = perms,
                )
            )
        }
        return out
    }

    /** Map of Record class simple-name → KClass. The single static
     *  bridge between JSON-string and SDK type. Add a new HC record
     *  type ⇒ one line here + one entry in build.json::metrics. */
    private fun recordClassForName(name: String): KClass<out Record>? = when (name) {
        "StepsRecord"                         -> StepsRecord::class
        "DistanceRecord"                      -> DistanceRecord::class
        "FloorsClimbedRecord"                 -> FloorsClimbedRecord::class
        "ElevationGainedRecord"               -> ElevationGainedRecord::class
        "ActiveCaloriesBurnedRecord"          -> ActiveCaloriesBurnedRecord::class
        "TotalCaloriesBurnedRecord"           -> TotalCaloriesBurnedRecord::class
        "HeartRateRecord"                     -> HeartRateRecord::class
        "RestingHeartRateRecord"              -> RestingHeartRateRecord::class
        "HeartRateVariabilityRmssdRecord"     -> HeartRateVariabilityRmssdRecord::class
        "Vo2MaxRecord"                        -> Vo2MaxRecord::class
        "BloodPressureRecord"                 -> BloodPressureRecord::class
        "BloodGlucoseRecord"                  -> BloodGlucoseRecord::class
        "OxygenSaturationRecord"              -> OxygenSaturationRecord::class
        "BodyTemperatureRecord"               -> BodyTemperatureRecord::class
        "RespiratoryRateRecord"               -> RespiratoryRateRecord::class
        "SleepSessionRecord"                  -> SleepSessionRecord::class
        "ExerciseSessionRecord"               -> ExerciseSessionRecord::class
        "WeightRecord"                        -> WeightRecord::class
        "BodyFatRecord"                       -> BodyFatRecord::class
        "LeanBodyMassRecord"                  -> LeanBodyMassRecord::class
        "BoneMassRecord"                      -> BoneMassRecord::class
        "BodyWaterMassRecord"                 -> BodyWaterMassRecord::class
        "NutritionRecord"                     -> NutritionRecord::class
        "HydrationRecord"                     -> HydrationRecord::class
        "MenstruationFlowRecord"              -> MenstruationFlowRecord::class
        else                                  -> null
    }

    /** Resolve a `perm_keys` JSON entry (e.g. "READ_STEPS") to the
     *  HC permission string by finding the record class in this
     *  metric's record list whose getReadPermission(...) matches.
     *  Falls back to the canonical "android.permission.health.<KEY>"
     *  format so an unknown key doesn't silently drop. */
    private fun permForKey(key: String, recs: List<KClass<out Record>>): String? {
        recs.forEach { r ->
            val canonical = HealthPermission.getReadPermission(r)
            if (canonical.endsWith(".$key", ignoreCase = true)) return canonical
        }
        return "android.permission.health.$key"
    }
}
