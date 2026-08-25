package com.diegonmarcos.superapp.health

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.diegonmarcos.superapp.core.HealthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Thin wrapper around [HealthConnectClient]. Hosts:
 *   • availability detection (HC installed? supported?)
 *   • the single canonical permission set ([ALL_READ_PERMISSIONS])
 *   • per-record-type suspend readers used by every page composable
 *   • a single "snapshot" reader the Dashboard composes from
 *
 * LOCAL-ONLY CONTRACT: this class makes ZERO network calls. Every read
 * is a query against the Health Connect SQLite store on-device. The
 * instrumented test enforces this with a StrictMode network policy
 * around the read path.
 *
 * Data fans into [HealthStore] (libs:core, SharedPreferences) for
 * rollup persistence — survives process death, drives the launcher
 * widget if it ever lands. Never leaves the device.
 */
object HealthConnectGateway {

    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    /** Single source of truth for the permission set — pages reference
     *  this directly, instead of hardcoding their own subsets. */
    val ALL_READ_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getReadPermission(MenstruationFlowRecord::class),
    )

    /** Map of (page id → permission subset) — pages use this to know
     *  which perms to request when the user opens them and HC hasn't
     *  granted them yet. Data-driven from [ALL_READ_PERMISSIONS] + a
     *  single mapping table; no copy of perm strings anywhere else. */
    val PAGE_PERMS: Map<String, Set<String>> = mapOf(
        "activity"  to permsFor(StepsRecord::class, DistanceRecord::class, FloorsClimbedRecord::class,
                                ElevationGainedRecord::class, ActiveCaloriesBurnedRecord::class,
                                TotalCaloriesBurnedRecord::class, ExerciseSessionRecord::class),
        "heart"     to permsFor(HeartRateRecord::class, RestingHeartRateRecord::class,
                                HeartRateVariabilityRmssdRecord::class, Vo2MaxRecord::class),
        "vitals"    to permsFor(BloodPressureRecord::class, BloodGlucoseRecord::class,
                                OxygenSaturationRecord::class, BodyTemperatureRecord::class,
                                RespiratoryRateRecord::class),
        "sleep"     to permsFor(SleepSessionRecord::class),
        "body"      to permsFor(WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class,
                                BoneMassRecord::class, BodyWaterMassRecord::class),
        "nutrition" to permsFor(NutritionRecord::class, HydrationRecord::class),
        "workouts"  to permsFor(ExerciseSessionRecord::class, HeartRateRecord::class),
        "cycle"     to permsFor(MenstruationFlowRecord::class),
    )

    private fun permsFor(vararg types: KClass<out Record>): Set<String> =
        types.map { HealthPermission.getReadPermission(it) }.toSet()

    /** Availability — split into three states so the UI can give the
     *  user a precise next-step button (install / update / open). */
    enum class Availability { Installed, NotInstalled, UpdateRequired, Unsupported }

    fun availability(context: Context): Availability =
        when (HealthConnectClient.getSdkStatus(context, PROVIDER_PACKAGE)) {
            HealthConnectClient.SDK_AVAILABLE                    -> Availability.Installed
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UpdateRequired
            HealthConnectClient.SDK_UNAVAILABLE                  -> Availability.NotInstalled
            else                                                 -> Availability.Unsupported
        }

    fun isProviderInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PROVIDER_PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun client(context: Context): HealthConnectClient? =
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()

    suspend fun grantedPermissions(context: Context): Set<String> =
        withContext(Dispatchers.IO) {
            client(context)?.permissionController?.getGrantedPermissions().orEmpty()
        }

    // ── Snapshot readers ─────────────────────────────────────────────
    // Each returns a small data class the pages render directly. The
    // gateway never hands raw HC `Record` types to the UI layer —
    // pages stay free of Health-Connect SDK types so the dependency
    // surface is one-way (UI → gateway, never back).

    data class DailySnapshot(
        val date: LocalDate,
        val steps: Long,
        val distanceM: Double,
        val activeKcal: Double,
        val totalKcal: Double,
        val floors: Double,
        val elevationM: Double,
        val heartRateAvg: Long,
        val restingHr: Long,
        val hrvRmssdAvg: Double,
        val sleepMinutes: Long,
        val workouts: Int,
        val sources: Set<String>,
    )

    suspend fun readDailySnapshot(context: Context, day: LocalDate = LocalDate.now()): DailySnapshot =
        withContext(Dispatchers.IO) {
            val c = client(context) ?: return@withContext emptySnapshot(day)
            val zone = ZoneId.systemDefault()
            val start = day.atStartOfDay(zone).toInstant()
            val end   = day.plusDays(1).atStartOfDay(zone).toInstant()
            val range = TimeRangeFilter.between(start, end)
            val sources = mutableSetOf<String>()

            val stepsRecs   = readSafe(c, StepsRecord::class, range)
            val distRecs    = readSafe(c, DistanceRecord::class, range)
            val activeRecs  = readSafe(c, ActiveCaloriesBurnedRecord::class, range)
            val totalRecs   = readSafe(c, TotalCaloriesBurnedRecord::class, range)
            val floorRecs   = readSafe(c, FloorsClimbedRecord::class, range)
            val elevRecs    = readSafe(c, ElevationGainedRecord::class, range)
            val hrRecs      = readSafe(c, HeartRateRecord::class, range)
            val rhrRecs     = readSafe(c, RestingHeartRateRecord::class, range)
            val hrvRecs     = readSafe(c, HeartRateVariabilityRmssdRecord::class, range)
            val sleepRecs   = readSafe(c, SleepSessionRecord::class, range)
            val workoutRecs = readSafe(c, ExerciseSessionRecord::class, range)

            (stepsRecs + distRecs + activeRecs + totalRecs + floorRecs + elevRecs +
                hrRecs + rhrRecs + hrvRecs + sleepRecs + workoutRecs).forEach {
                sources += it.metadata.dataOrigin.packageName
            }
            val hrSamples = hrRecs.flatMap { it.samples }
            val hrAvg = if (hrSamples.isEmpty()) 0L else hrSamples.sumOf { it.beatsPerMinute } / hrSamples.size

            val snap = DailySnapshot(
                date         = day,
                steps        = stepsRecs.sumOf { it.count },
                distanceM    = distRecs.sumOf { it.distance.inMeters },
                activeKcal   = activeRecs.sumOf { it.energy.inKilocalories },
                totalKcal    = totalRecs.sumOf { it.energy.inKilocalories },
                floors       = floorRecs.sumOf { it.floors },
                elevationM   = elevRecs.sumOf { it.elevation.inMeters },
                heartRateAvg = hrAvg,
                restingHr    = rhrRecs.lastOrNull()?.beatsPerMinute ?: 0L,
                hrvRmssdAvg  = if (hrvRecs.isEmpty()) 0.0 else hrvRecs.sumOf { it.heartRateVariabilityMillis } / hrvRecs.size,
                sleepMinutes = sleepRecs.sumOf {
                    (it.endTime.epochSecond - it.startTime.epochSecond) / 60
                },
                workouts     = workoutRecs.size,
                sources      = sources,
            )
            HealthStore.persistDaily(context, snap.toJson())
            snap
        }

    private fun emptySnapshot(day: LocalDate) = DailySnapshot(
        day, 0L, 0.0, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0.0, 0L, 0, emptySet(),
    )

    // ── Multi-day window aggregator ─────────────────────────────────
    // Used by SummaryPage's 7d / 30d average tables. Reads every
    // record in the metric's record list across [now-Ndays, now],
    // sums or averages per record type as appropriate, and returns
    // human-readable strings ready to render. No persistence to
    // HealthStore — these are computed on demand (the multi-day
    // window costs a few SQLite scans which are sub-second locally;
    // caching adds complexity that isn't worth it yet).

    data class WindowRow(val label: String, val value: String)

    suspend fun readMetricWindow(
        context: Context,
        metric: HealthMetrics.Metric,
        windowDays: Int,
    ): List<WindowRow> = withContext(Dispatchers.IO) {
        val c = client(context) ?: return@withContext emptyList()
        val end = Instant.now()
        val start = end.minusSeconds(windowDays.toLong() * 24 * 3600)
        val range = TimeRangeFilter.between(start, end)
        val rows = mutableListOf<WindowRow>()
        val effDays = windowDays.toDouble().coerceAtLeast(1.0)

        metric.records.forEach { type ->
            val recs = readSafe(c, type, range)
            val row = when (type) {
                StepsRecord::class -> {
                    val total = recs.filterIsInstance<StepsRecord>().sumOf { it.count }
                    WindowRow("Steps · avg/day", "%.0f".format(total / effDays))
                }
                DistanceRecord::class -> {
                    val total = recs.filterIsInstance<DistanceRecord>().sumOf { it.distance.inMeters }
                    WindowRow("Distance · avg/day", "%.2f km".format(total / 1000.0 / effDays))
                }
                FloorsClimbedRecord::class -> {
                    val total = recs.filterIsInstance<FloorsClimbedRecord>().sumOf { it.floors }
                    WindowRow("Floors · avg/day", "%.0f".format(total / effDays))
                }
                ElevationGainedRecord::class -> {
                    val total = recs.filterIsInstance<ElevationGainedRecord>().sumOf { it.elevation.inMeters }
                    WindowRow("Elevation · avg/day", "%.0f m".format(total / effDays))
                }
                ActiveCaloriesBurnedRecord::class -> {
                    val total = recs.filterIsInstance<ActiveCaloriesBurnedRecord>().sumOf { it.energy.inKilocalories }
                    WindowRow("Active kcal · avg/day", "%.0f".format(total / effDays))
                }
                TotalCaloriesBurnedRecord::class -> {
                    val total = recs.filterIsInstance<TotalCaloriesBurnedRecord>().sumOf { it.energy.inKilocalories }
                    WindowRow("Total kcal · avg/day", "%.0f".format(total / effDays))
                }
                HeartRateRecord::class -> {
                    val samples = recs.filterIsInstance<HeartRateRecord>().flatMap { it.samples }
                    val avg = if (samples.isEmpty()) 0L else samples.sumOf { it.beatsPerMinute } / samples.size
                    WindowRow("HR · avg", if (avg > 0) "$avg bpm" else "—")
                }
                RestingHeartRateRecord::class -> {
                    val list = recs.filterIsInstance<RestingHeartRateRecord>()
                    val avg = if (list.isEmpty()) 0L else list.sumOf { it.beatsPerMinute } / list.size
                    WindowRow("Resting HR · avg", if (avg > 0) "$avg bpm" else "—")
                }
                HeartRateVariabilityRmssdRecord::class -> {
                    val list = recs.filterIsInstance<HeartRateVariabilityRmssdRecord>()
                    val avg = if (list.isEmpty()) 0.0 else list.sumOf { it.heartRateVariabilityMillis } / list.size
                    WindowRow("HRV · avg", if (avg > 0) "%.0f ms".format(avg) else "—")
                }
                Vo2MaxRecord::class -> {
                    val list = recs.filterIsInstance<Vo2MaxRecord>()
                    val avg = if (list.isEmpty()) 0.0 else list.sumOf { it.vo2MillilitersPerMinuteKilogram } / list.size
                    WindowRow("VO₂max · avg", if (avg > 0) "%.1f".format(avg) else "—")
                }
                SleepSessionRecord::class -> {
                    val list = recs.filterIsInstance<SleepSessionRecord>()
                    val totalMin = list.sumOf { (it.endTime.epochSecond - it.startTime.epochSecond) / 60 }
                    val perDay = totalMin / effDays
                    WindowRow("Sleep · avg/night", if (perDay > 0) "${(perDay / 60).toInt()} h ${(perDay % 60).toInt()} m" else "—")
                }
                ExerciseSessionRecord::class -> {
                    val n = recs.filterIsInstance<ExerciseSessionRecord>().size
                    WindowRow("Workouts · avg/day", "%.1f".format(n / effDays))
                }
                WeightRecord::class -> {
                    val list = recs.filterIsInstance<WeightRecord>()
                    val last = list.maxByOrNull { it.time }?.weight?.inKilograms
                    WindowRow("Weight · latest", if (last != null) "%.1f kg".format(last) else "—")
                }
                BodyFatRecord::class -> {
                    val last = recs.filterIsInstance<BodyFatRecord>().maxByOrNull { it.time }?.percentage?.value
                    WindowRow("Body fat · latest", if (last != null) "%.1f %%".format(last) else "—")
                }
                BloodPressureRecord::class -> {
                    val list = recs.filterIsInstance<BloodPressureRecord>()
                    val sys = if (list.isEmpty()) 0.0 else list.sumOf { it.systolic.inMillimetersOfMercury } / list.size
                    val dia = if (list.isEmpty()) 0.0 else list.sumOf { it.diastolic.inMillimetersOfMercury } / list.size
                    WindowRow("BP · avg", if (sys > 0) "${sys.toInt()}/${dia.toInt()} mmHg" else "—")
                }
                BloodGlucoseRecord::class -> {
                    val list = recs.filterIsInstance<BloodGlucoseRecord>()
                    val avg = if (list.isEmpty()) 0.0 else list.sumOf { it.level.inMillimolesPerLiter } / list.size
                    WindowRow("Glucose · avg", if (avg > 0) "%.1f mmol/L".format(avg) else "—")
                }
                OxygenSaturationRecord::class -> {
                    val list = recs.filterIsInstance<OxygenSaturationRecord>()
                    val avg = if (list.isEmpty()) 0.0 else list.sumOf { it.percentage.value } / list.size
                    WindowRow("SpO₂ · avg", if (avg > 0) "%.1f %%".format(avg) else "—")
                }
                HydrationRecord::class -> {
                    val total = recs.filterIsInstance<HydrationRecord>().sumOf { it.volume.inMilliliters }
                    WindowRow("Hydration · avg/day", "%.0f mL".format(total / effDays))
                }
                NutritionRecord::class -> {
                    val total = recs.filterIsInstance<NutritionRecord>().sumOf { (it.energy?.inKilocalories ?: 0.0) }
                    WindowRow("Nutrition kcal · avg/day", "%.0f".format(total / effDays))
                }
                else -> WindowRow(type.simpleName ?: "?", "${recs.size} record(s)")
            }
            rows.add(row)
        }
        rows
    }

    /** Per-day snapshot for ONE metric — same shape as [WindowRow]
     *  but the values are the day's totals/averages (no per-day
     *  division). Used by TimelinePage's per-date drill-down. */
    suspend fun readMetricDay(
        context: Context,
        metric: HealthMetrics.Metric,
        day: LocalDate,
    ): List<WindowRow> = withContext(Dispatchers.IO) {
        val c = client(context) ?: return@withContext emptyList()
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant()
        val end   = day.plusDays(1).atStartOfDay(zone).toInstant()
        val range = TimeRangeFilter.between(start, end)
        val rows = mutableListOf<WindowRow>()
        metric.records.forEach { type ->
            val recs = readSafe(c, type, range)
            val row = when (type) {
                StepsRecord::class -> WindowRow("Steps", "${recs.filterIsInstance<StepsRecord>().sumOf { it.count }}")
                DistanceRecord::class -> WindowRow("Distance", "%.2f km".format(recs.filterIsInstance<DistanceRecord>().sumOf { it.distance.inMeters } / 1000.0))
                FloorsClimbedRecord::class -> WindowRow("Floors", "%.0f".format(recs.filterIsInstance<FloorsClimbedRecord>().sumOf { it.floors }))
                ActiveCaloriesBurnedRecord::class -> WindowRow("Active kcal", "%.0f".format(recs.filterIsInstance<ActiveCaloriesBurnedRecord>().sumOf { it.energy.inKilocalories }))
                TotalCaloriesBurnedRecord::class -> WindowRow("Total kcal", "%.0f".format(recs.filterIsInstance<TotalCaloriesBurnedRecord>().sumOf { it.energy.inKilocalories }))
                HeartRateRecord::class -> {
                    val samples = recs.filterIsInstance<HeartRateRecord>().flatMap { it.samples }
                    val avg = if (samples.isEmpty()) 0L else samples.sumOf { it.beatsPerMinute } / samples.size
                    WindowRow("HR · avg", if (avg > 0) "$avg bpm" else "—")
                }
                RestingHeartRateRecord::class -> {
                    val last = recs.filterIsInstance<RestingHeartRateRecord>().maxByOrNull { it.time }
                    WindowRow("Resting HR", if (last != null) "${last.beatsPerMinute} bpm" else "—")
                }
                SleepSessionRecord::class -> {
                    val totalMin = recs.filterIsInstance<SleepSessionRecord>().sumOf { (it.endTime.epochSecond - it.startTime.epochSecond) / 60 }
                    WindowRow("Sleep", if (totalMin > 0) "${totalMin / 60} h ${totalMin % 60} m" else "—")
                }
                ExerciseSessionRecord::class -> WindowRow("Workouts", "${recs.size}")
                WeightRecord::class -> {
                    val last = recs.filterIsInstance<WeightRecord>().maxByOrNull { it.time }?.weight?.inKilograms
                    WindowRow("Weight", if (last != null) "%.1f kg".format(last) else "—")
                }
                HydrationRecord::class -> WindowRow("Hydration", "%.0f mL".format(recs.filterIsInstance<HydrationRecord>().sumOf { it.volume.inMilliliters }))
                else -> WindowRow(type.simpleName ?: "?", "${recs.size}")
            }
            rows.add(row)
        }
        rows
    }

    /** Raw inspector — flatten a record type's recent window into
     *  inspector rows for the Raw page. Hands plain strings to the UI
     *  so the page never imports HC SDK types. */
    data class RawRow(val ts: Instant, val origin: String, val summary: String, val uid: String)

    suspend fun readRawWindow(
        context: Context,
        type: KClass<out Record>,
        windowDays: Int = 7,
    ): List<RawRow> = withContext(Dispatchers.IO) {
        val c = client(context) ?: return@withContext emptyList()
        val end = Instant.now()
        val start = end.minusSeconds(windowDays.toLong() * 24 * 3600)
        readSafe(c, type, TimeRangeFilter.between(start, end))
            .map { r ->
                RawRow(
                    ts      = recordTime(r),
                    origin  = r.metadata.dataOrigin.packageName,
                    summary = summarise(r),
                    uid     = r.metadata.id,
                )
            }
            .sortedByDescending { it.ts }
    }

    /** Per-source breakdown — pages "sources" & "stats" use this. */
    data class SourceBreakdown(val packageName: String, val recordCount: Int, val lastWrite: Instant?)

    suspend fun readSourceBreakdown(context: Context, windowDays: Int = 30): List<SourceBreakdown> =
        withContext(Dispatchers.IO) {
            val c = client(context) ?: return@withContext emptyList()
            val end = Instant.now()
            val start = end.minusSeconds(windowDays.toLong() * 24 * 3600)
            val range = TimeRangeFilter.between(start, end)
            val agg = mutableMapOf<String, Pair<Int, Instant>>()
            val countedTypes: List<KClass<out Record>> = listOf(
                StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class,
                HeartRateRecord::class, SleepSessionRecord::class, ExerciseSessionRecord::class,
                WeightRecord::class, NutritionRecord::class,
            )
            countedTypes.forEach { t ->
                readSafe(c, t, range).forEach { r ->
                    val pkg = r.metadata.dataOrigin.packageName
                    val ts  = recordTime(r)
                    val cur = agg[pkg]
                    agg[pkg] = if (cur == null) 1 to ts
                               else (cur.first + 1) to (if (ts.isAfter(cur.second)) ts else cur.second)
                }
            }
            agg.map { (pkg, v) -> SourceBreakdown(pkg, v.first, v.second) }
                .sortedByDescending { it.recordCount }
        }

    private suspend fun <T : Record> readSafe(
        c: HealthConnectClient,
        type: KClass<T>,
        range: TimeRangeFilter,
    ): List<T> = try {
        c.readRecords(ReadRecordsRequest(type, range)).records
    } catch (_: Throwable) { emptyList() }

    private fun recordTime(r: Record): Instant = when (r) {
        is StepsRecord                          -> r.endTime
        is DistanceRecord                       -> r.endTime
        is ActiveCaloriesBurnedRecord           -> r.endTime
        is TotalCaloriesBurnedRecord            -> r.endTime
        is FloorsClimbedRecord                  -> r.endTime
        is ElevationGainedRecord                -> r.endTime
        is HeartRateRecord                      -> r.endTime
        is RestingHeartRateRecord               -> r.time
        is HeartRateVariabilityRmssdRecord      -> r.time
        is Vo2MaxRecord                         -> r.time
        is BloodPressureRecord                  -> r.time
        is BloodGlucoseRecord                   -> r.time
        is OxygenSaturationRecord               -> r.time
        is BodyTemperatureRecord                -> r.time
        is RespiratoryRateRecord                -> r.time
        is SleepSessionRecord                   -> r.endTime
        is ExerciseSessionRecord                -> r.endTime
        is WeightRecord                         -> r.time
        is BodyFatRecord                        -> r.time
        is LeanBodyMassRecord                   -> r.time
        is BoneMassRecord                       -> r.time
        is BodyWaterMassRecord                  -> r.time
        is NutritionRecord                      -> r.endTime
        is HydrationRecord                      -> r.endTime
        is MenstruationFlowRecord               -> r.time
        else                                    -> Instant.EPOCH
    }

    private fun summarise(r: Record): String = when (r) {
        is StepsRecord                  -> "${r.count} steps"
        is DistanceRecord               -> "%.0f m".format(r.distance.inMeters)
        is ActiveCaloriesBurnedRecord   -> "%.0f kcal (active)".format(r.energy.inKilocalories)
        is TotalCaloriesBurnedRecord    -> "%.0f kcal (total)".format(r.energy.inKilocalories)
        is HeartRateRecord              -> "${r.samples.size} samples · ${r.samples.firstOrNull()?.beatsPerMinute ?: '?'} bpm"
        is RestingHeartRateRecord       -> "${r.beatsPerMinute} bpm (resting)"
        is HeartRateVariabilityRmssdRecord -> "%.1f ms RMSSD".format(r.heartRateVariabilityMillis)
        is SleepSessionRecord           -> "${(r.endTime.epochSecond - r.startTime.epochSecond) / 60} min · ${r.stages.size} stages"
        is ExerciseSessionRecord        -> "${r.exerciseType} · ${(r.endTime.epochSecond - r.startTime.epochSecond) / 60} min"
        is WeightRecord                 -> "%.2f kg".format(r.weight.inKilograms)
        is BodyFatRecord                -> "%.1f %%".format(r.percentage.value)
        is BloodPressureRecord          -> "${r.systolic.inMillimetersOfMercury.toInt()}/${r.diastolic.inMillimetersOfMercury.toInt()} mmHg"
        is BloodGlucoseRecord           -> "%.1f mmol/L".format(r.level.inMillimolesPerLiter)
        is OxygenSaturationRecord       -> "%.1f %%".format(r.percentage.value)
        is BodyTemperatureRecord        -> "%.1f °C".format(r.temperature.inCelsius)
        is HydrationRecord              -> "%.0f mL".format(r.volume.inMilliliters)
        is NutritionRecord              -> "%.0f kcal".format((r.energy?.inKilocalories ?: 0.0))
        is RespiratoryRateRecord        -> "%.1f /min".format(r.rate)
        is Vo2MaxRecord                 -> "%.1f mL/kg/min".format(r.vo2MillilitersPerMinuteKilogram)
        else                            -> r.metadata.id
    }

    private fun HealthConnectGateway.DailySnapshot.toJson(): String = """
        {"date":"$date","steps":$steps,"distanceM":$distanceM,"activeKcal":$activeKcal,
         "totalKcal":$totalKcal,"floors":$floors,"elevationM":$elevationM,
         "hrAvg":$heartRateAvg,"rhr":$restingHr,"hrvAvg":$hrvRmssdAvg,
         "sleepMin":$sleepMinutes,"workouts":$workouts,
         "sources":[${sources.joinToString(",") { "\"$it\"" }}]}
    """.trimIndent()
}
