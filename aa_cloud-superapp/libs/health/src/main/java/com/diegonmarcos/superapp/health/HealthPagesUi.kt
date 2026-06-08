package com.diegonmarcos.superapp.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.diegonmarcos.superapp.core.HealthStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import androidx.health.connect.client.records.Record

/**
 * MyHealth page surface. ONE composable per pageId, all routed through
 * [HealthScreen]. Shared widgets ([MetricCard], [SectionHeader],
 * [EmptyState]) live at the bottom; pages compose them top-down without
 * any further indirection. No data is invented — every value reads
 * either from [HealthConnectGateway] (live) or [HealthStore] (cached
 * rollup) and degrades to "—" on empty.
 */
@Composable
fun HealthScreen(pageId: String) {
    when (pageId) {
        HealthFragment.PAGE_DASHBOARD   -> DashboardPage()
        HealthFragment.PAGE_ACTIVITY    -> ActivityPage()
        HealthFragment.PAGE_HEART       -> HeartPage()
        HealthFragment.PAGE_SLEEP       -> SleepPage()
        HealthFragment.PAGE_BODY        -> BodyPage()
        HealthFragment.PAGE_VITALS      -> VitalsPage()
        HealthFragment.PAGE_NUTRITION   -> NutritionPage()
        HealthFragment.PAGE_WORKOUTS    -> WorkoutsPage()
        HealthFragment.PAGE_CYCLE       -> CyclePage()
        HealthFragment.PAGE_TRENDS      -> TrendsPage()
        HealthFragment.PAGE_SOURCES     -> SourcesPage()
        HealthFragment.PAGE_PERMISSIONS -> PermissionsPage()
        HealthFragment.PAGE_RAW         -> RawPage()
        HealthFragment.PAGE_STATS       -> StatsPage()
        HealthFragment.PAGE_ABOUT       -> AboutPage()
        else                            -> EmptyState("Unknown page: $pageId")
    }
}

// ── Dashboard ────────────────────────────────────────────────────────
@Composable
private fun DashboardPage() {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<HealthConnectGateway.DailySnapshot?>(null) }
    var availability by remember { mutableStateOf(HealthConnectGateway.Availability.Unsupported) }

    LaunchedEffect(Unit) {
        availability = HealthConnectGateway.availability(ctx)
        if (availability == HealthConnectGateway.Availability.Installed) {
            snap = HealthConnectGateway.readDailySnapshot(ctx)
        }
    }

    PageScroll {
        SectionHeader("Today · ${LocalDate.now()}")
        when (availability) {
            HealthConnectGateway.Availability.NotInstalled  ->
                InfoCard("Health Connect not installed", "Install from the Play Store, then enable sync in Garmin Connect → Settings → Health Connect, and let Google Fit / Samsung Health publish too.")
            HealthConnectGateway.Availability.UpdateRequired ->
                InfoCard("Health Connect needs an update", "Open the Play Store and update the Health Connect app.")
            HealthConnectGateway.Availability.Unsupported   ->
                InfoCard("Unsupported device", "Health Connect requires Android 9+. This device can't aggregate health data.")
            HealthConnectGateway.Availability.Installed     -> {
                val s = snap
                if (s == null) {
                    InfoCard("Loading…", "Querying Health Connect…")
                } else {
                    MetricCard("Steps",          if (s.steps        > 0) "${s.steps}"              else "—", "today")
                    MetricCard("Distance",       if (s.distanceM    > 0) "%.1f km".format(s.distanceM / 1000) else "—", "today")
                    MetricCard("Active calories",if (s.activeKcal   > 0) "%.0f kcal".format(s.activeKcal)     else "—", "today")
                    MetricCard("Heart rate avg", if (s.heartRateAvg > 0) "${s.heartRateAvg} bpm"   else "—", "today")
                    MetricCard("Resting HR",     if (s.restingHr    > 0) "${s.restingHr} bpm"      else "—", "last reading")
                    MetricCard("HRV (RMSSD)",    if (s.hrvRmssdAvg  > 0) "%.0f ms".format(s.hrvRmssdAvg) else "—", "avg")
                    MetricCard("Sleep",          if (s.sleepMinutes > 0) "${s.sleepMinutes / 60} h ${s.sleepMinutes % 60} m" else "—", "last night")
                    MetricCard("Workouts",       "${s.workouts}", "today")
                    MetricCard("Floors",         if (s.floors > 0) "%.0f".format(s.floors)            else "—", "today")
                    MetricCard("Elevation",      if (s.elevationM > 0) "%.0f m".format(s.elevationM)  else "—", "today")
                    Spacer(Modifier.height(12.dp))
                    InfoCard("Sources", if (s.sources.isEmpty()) "No producers seen today." else s.sources.joinToString("\n"))
                }
            }
        }
    }
}

// ── Activity ─────────────────────────────────────────────────────────
@Composable
private fun ActivityPage() = DataPage("Activity") { s ->
    MetricCard("Steps",        if (s.steps      > 0) "${s.steps}"             else "—", "today")
    MetricCard("Distance",     if (s.distanceM  > 0) "%.2f km".format(s.distanceM / 1000) else "—", "today")
    MetricCard("Floors",       if (s.floors > 0) "%.0f".format(s.floors)       else "—", "climbed today")
    MetricCard("Elevation",    if (s.elevationM > 0) "%.0f m".format(s.elevationM)  else "—", "gained today")
    MetricCard("Active kcal",  if (s.activeKcal > 0) "%.0f".format(s.activeKcal)    else "—", "today")
    MetricCard("Total kcal",   if (s.totalKcal  > 0) "%.0f".format(s.totalKcal)     else "—", "today")
}

// ── Heart ────────────────────────────────────────────────────────────
@Composable
private fun HeartPage() = DataPage("Heart") { s ->
    MetricCard("Heart-rate avg",  if (s.heartRateAvg > 0) "${s.heartRateAvg} bpm" else "—", "today")
    MetricCard("Resting HR",      if (s.restingHr    > 0) "${s.restingHr} bpm"    else "—", "last reading")
    MetricCard("HRV (RMSSD)",     if (s.hrvRmssdAvg  > 0) "%.1f ms".format(s.hrvRmssdAvg) else "—", "today avg")
    InfoCard("Heart-rate samples", "Switch to Raw → HeartRateRecord for the per-sample stream.")
}

// ── Sleep ────────────────────────────────────────────────────────────
@Composable
private fun SleepPage() = DataPage("Sleep") { s ->
    MetricCard("Duration", if (s.sleepMinutes > 0) "${s.sleepMinutes / 60} h ${s.sleepMinutes % 60} m" else "—", "last night")
    InfoCard("Stages", "Sleep stages (REM / Deep / Light / Awake) live in SleepSessionRecord.stages. Open Raw → SleepSessionRecord for the per-stage timeline.")
}

// ── Body ─────────────────────────────────────────────────────────────
@Composable
private fun BodyPage() = SimplePage("Body composition", subtitle = "Latest weight / fat % / lean mass / hydration") {
    InfoCard("Coming online", "Body-comp records (Weight, BodyFat, LeanBodyMass, BoneMass, BodyWaterMass) read from Health Connect on demand. Open Raw → WeightRecord to verify your scale is publishing.")
}

// ── Vitals ───────────────────────────────────────────────────────────
@Composable
private fun VitalsPage() = SimplePage("Vitals", subtitle = "BP / Glucose / SpO2 / Temperature / Respiratory rate") {
    InfoCard("Source-driven", "Each vital surfaces only when a producer (continuous glucose monitor, blood-pressure cuff, pulse-ox watch) syncs to Health Connect. Check Sources to see what's writing.")
}

// ── Nutrition ────────────────────────────────────────────────────────
@Composable
private fun NutritionPage() = SimplePage("Nutrition", subtitle = "Calories in · Macros (P/C/F) · Hydration · Caffeine") {
    InfoCard("Apps that write here", "MyFitnessPal, Lifesum, Cronometer, Samsung Food — all can sync to Health Connect. Hydration tracker apps publish HydrationRecord.")
}

// ── Workouts ─────────────────────────────────────────────────────────
@Composable
private fun WorkoutsPage() = DataPage("Workouts") { s ->
    MetricCard("Workouts today", "${s.workouts}", "ExerciseSessionRecord")
    InfoCard("Detail", "Each workout carries exerciseType, optional HR-zone breakdown, Power/Cadence/Speed series, and (if recorded) a GPS route. Open Raw → ExerciseSessionRecord for the per-session inspector.")
}

// ── Cycle ────────────────────────────────────────────────────────────
@Composable
private fun CyclePage() = SimplePage("Cycle", subtitle = "Menstruation · Ovulation · Cervical mucus · Bleeding") {
    InfoCard("Privacy note", "If you'd rather not surface this category, revoke the perm in Permissions — the page will then render empty. No data leaves the device either way.")
}

// ── Trends ───────────────────────────────────────────────────────────
@Composable
private fun TrendsPage() {
    val ctx = LocalContext.current
    val history = remember { HealthStore.history(ctx) }
    PageScroll {
        SectionHeader("Trends · cached history")
        if (history.length() == 0) {
            InfoCard("No history yet", "Trends accumulate every time the Dashboard reads a fresh snapshot. Open the Dashboard a few days in a row and 7/30/90/365-day rollups will fill in.")
        } else {
            InfoCard("Snapshots stored", "${history.length()} daily snapshot(s) cached locally (capped at 365). All in HealthStore.history(), never uploaded.")
        }
    }
}

// ── Sources ──────────────────────────────────────────────────────────
@Composable
private fun SourcesPage() {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<HealthConnectGateway.SourceBreakdown>>(emptyList()) }
    LaunchedEffect(Unit) { rows = HealthConnectGateway.readSourceBreakdown(ctx) }
    PageScroll {
        SectionHeader("Sources · last 30 days")
        if (rows.isEmpty()) {
            InfoCard("No producers seen", "No Health Connect records found in the last 30 days. Make sure Garmin Connect / Google Fit / Samsung Health are configured to sync into HC.")
        } else {
            rows.forEach { row ->
                MetricCard(
                    title    = row.packageName,
                    value    = "${row.recordCount}",
                    subtitle = row.lastWrite?.let { "last write " + DateTimeFormatter.ISO_INSTANT.format(it).take(19) } ?: "—",
                )
            }
        }
    }
}

// ── Permissions ──────────────────────────────────────────────────────
@Composable
private fun PermissionsPage() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grants ->
        granted = grants
        HealthStore.recordPermissionGrant(ctx)
    }
    LaunchedEffect(Unit) { granted = HealthConnectGateway.grantedPermissions(ctx) }

    PageScroll {
        SectionHeader("Health Connect · permissions")
        val lastGrant = HealthStore.lastPermissionGrantTs(ctx)
        InfoCard("Status", "${granted.size} / ${HealthConnectGateway.ALL_READ_PERMISSIONS.size} read perms granted.")
        OutlinedButton(
            onClick = { launcher.launch(HealthConnectGateway.ALL_READ_PERMISSIONS) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) { Text("Request all read perms") }
        if (lastGrant > 0L) InfoCard("Last asked", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastGrant)))
        HealthConnectGateway.ALL_READ_PERMISSIONS.sorted().forEach { perm ->
            MetricCard(
                title = perm.removePrefix("android.permission.health."),
                value = if (perm in granted) "✓" else "—",
                subtitle = if (perm in granted) "granted" else "not granted",
            )
        }
    }
}

// ── Raw inspector ────────────────────────────────────────────────────
@Composable
private fun RawPage() {
    val ctx = LocalContext.current
    val types: List<Pair<String, KClass<out Record>>> = listOf(
        "Steps"          to StepsRecord::class,
        "Heart rate"     to HeartRateRecord::class,
        "Sleep sessions" to SleepSessionRecord::class,
        "Weight"         to WeightRecord::class,
    )
    var picked by remember { mutableStateOf(types.first()) }
    var rows by remember { mutableStateOf<List<HealthConnectGateway.RawRow>>(emptyList()) }
    LaunchedEffect(picked) {
        rows = HealthConnectGateway.readRawWindow(ctx, picked.second)
    }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SectionHeader("Raw · ${picked.first} · last 7 days")
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { t ->
                OutlinedButton(onClick = { picked = t }) { Text(t.first, fontSize = 12.sp) }
            }
        }
        if (rows.isEmpty()) {
            InfoCard("Empty window", "No ${picked.first} records in the last 7 days.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(rows) { r ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(r.summary, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${r.ts}", color = Color(0xFFAAA0CC), fontSize = 11.sp)
                            Text("from ${r.origin}", color = Color(0xFFAAA0CC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("uid ${r.uid}", color = Color(0xFF6E5C95), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

// ── Stats (meta-geek) ────────────────────────────────────────────────
@Composable
private fun StatsPage() {
    val ctx = LocalContext.current
    val historyLen = remember { HealthStore.history(ctx).length() }
    val lastTs     = remember { HealthStore.lastSnapshotTs(ctx) }
    val footprint  = remember { HealthStore.footprintBytes(ctx) }
    val sources    = remember { HealthStore.sourceLastSeen(ctx) }
    PageScroll {
        SectionHeader("Stats · local cache")
        MetricCard("Snapshots cached",   "$historyLen", "of 365 cap")
        MetricCard("Cache footprint",    "$footprint B", "HealthStore SharedPrefs file")
        MetricCard("Last refresh",       if (lastTs > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastTs)) else "—", "Dashboard reads")
        SectionHeader("Producers seen")
        if (sources.isEmpty()) {
            InfoCard("None yet", "Run the Dashboard once with HC permissions granted to populate this list.")
        } else {
            sources.entries.sortedByDescending { it.value }.forEach { (pkg, ts) ->
                MetricCard(pkg, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(ts)), "last write")
            }
        }
    }
}

// ── About ────────────────────────────────────────────────────────────
@Composable
private fun AboutPage() = PageScroll {
    SectionHeader("MyHealth · About")
    InfoCard("Local-only", "Every value rendered here came from a local SQLite query against Health Connect. No HTTPS, no OAuth, no upload. The instrumented test asserts this with StrictMode.")
    InfoCard("Sources", "Health Connect aggregates: Garmin Connect, Google Fit, Samsung Health, Fitbit (via the new Health API bridge), Oura, Whoop. Toggle sync inside each producer app.")
    InfoCard("Setup", "1) Install Health Connect (Play Store).\n2) Inside Garmin Connect → Settings → Health Connect → enable read+write.\n3) Repeat for any other tracker.\n4) Open MyHealth → Permissions → Request all read perms.")
    InfoCard("Why a separate app", "Garmin's web API (Connect Developer Program) and Google's Health API (web) would each need their own OAuth + webhook backend. Health Connect collapses both onto one local SDK with zero cloud surface.")
}

// ── Shared building blocks ───────────────────────────────────────────
@Composable
private fun PageScroll(content: @Composable () -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { content() }
    }
}

@Composable
private fun DataPage(title: String, body: @Composable (HealthConnectGateway.DailySnapshot) -> Unit) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf<HealthConnectGateway.DailySnapshot?>(null) }
    LaunchedEffect(Unit) { snap = HealthConnectGateway.readDailySnapshot(ctx) }
    PageScroll {
        SectionHeader(title)
        val s = snap
        if (s == null) InfoCard("Loading…", "Querying Health Connect…") else body(s)
    }
}

@Composable
private fun SimplePage(title: String, subtitle: String, body: @Composable () -> Unit) = PageScroll {
    SectionHeader(title)
    InfoCard("Scope", subtitle)
    body()
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text       = text,
        color      = Color(0xFFEDE7FF),
        fontSize   = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier   = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun MetricCard(title: String, value: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title,    color = Color(0xFFCFC2F0), fontSize = 13.sp)
                Text(subtitle, color = Color(0xFF8A7DAC), fontSize = 11.sp)
            }
            Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = Color(0xFFEDE7FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body,  color = Color(0xFFB6A8DC), fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(text, color = Color(0xFFCFC2F0))
    }
}
