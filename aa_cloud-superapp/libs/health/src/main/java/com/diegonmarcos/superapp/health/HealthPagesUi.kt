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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.Record
import com.diegonmarcos.superapp.core.HealthStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/**
 * MyHealth — Compose surface.
 *
 * Three top-level pages, declared in build.json::ui.sections[id=health]
 * .pages[]:
 *   • Summary  — metric grid → tap a metric → 7d + 30d average tables
 *   • Timeline — date picker (default today) → metric list with that
 *                day's values → tap a metric → per-day detail
 *   • Configs  — sub-tabs (Sources / Permissions / Stats / Raw / About)
 *
 * Per-metric drill-down is INTERNAL Compose state; no Fragment swap.
 * Metric taxonomy is data-driven from
 * build.json::ui.sections[id=health].metrics[] via [HealthMetrics].
 */
@Composable
fun HealthScreen(pageId: String) {
    when (pageId) {
        HealthFragment.PAGE_SUMMARY  -> SummaryPage()
        HealthFragment.PAGE_TIMELINE -> TimelinePage()
        HealthFragment.PAGE_CONFIGS  -> ConfigsPage()
        else                         -> EmptyState("Unknown page: $pageId")
    }
}

// ── Summary ──────────────────────────────────────────────────────────
@Composable
private fun SummaryPage() {
    var selected by remember { mutableStateOf<String?>(null) }
    if (selected == null) {
        MetricGrid(title = "Summary") { metricId ->
            selected = metricId
        }
    } else {
        val metric = HealthMetrics.byId(selected!!)
        if (metric == null) {
            selected = null
        } else {
            MetricSummaryDetail(metric = metric, onBack = { selected = null })
        }
    }
}

@Composable
private fun MetricSummaryDetail(metric: HealthMetrics.Metric, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rows7  by remember { mutableStateOf<List<HealthConnectGateway.WindowRow>>(emptyList()) }
    var rows30 by remember { mutableStateOf<List<HealthConnectGateway.WindowRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(metric.id) {
        loading = true
        rows7  = HealthConnectGateway.readMetricWindow(ctx, metric, 7)
        rows30 = HealthConnectGateway.readMetricWindow(ctx, metric, 30)
        loading = false
    }
    PageScroll {
        BackBar(text = "Summary · ${metric.label}", onBack = onBack)
        if (loading) {
            InfoCard("Loading…", "Reading the last 30 days from Health Connect…")
        } else {
            SectionHeader("7-day averages")
            if (rows7.isEmpty()) InfoCard("Empty", "No records in the last 7 days.")
            else rows7.forEach { MetricCard(it.label, it.value, "7d avg") }

            Spacer(Modifier.height(8.dp))
            SectionHeader("30-day averages")
            if (rows30.isEmpty()) InfoCard("Empty", "No records in the last 30 days.")
            else rows30.forEach { MetricCard(it.label, it.value, "30d avg") }
        }
    }
}

// ── Timeline ─────────────────────────────────────────────────────────
@Composable
private fun TimelinePage() {
    var date by remember { mutableStateOf(LocalDate.now()) }
    var selected by remember { mutableStateOf<String?>(null) }
    if (selected == null) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            DatePickerStrip(date = date, onChange = { date = it })
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Timeline · ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}",
                color = Color(0xFFEDE7FF), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            MetricGridInline { metricId -> selected = metricId }
        }
    } else {
        val metric = HealthMetrics.byId(selected!!)
        if (metric == null) selected = null
        else MetricTimelineDetail(metric = metric, day = date, onBack = { selected = null })
    }
}

@Composable
private fun DatePickerStrip(date: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(date.minusDays(1)) }) { Text("‹") }
        Spacer(Modifier.width(8.dp))
        Text(
            text = date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")),
            color = Color(0xFFEDE7FF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onChange(date.plusDays(1)) }, enabled = date.isBefore(LocalDate.now())) { Text("›") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(LocalDate.now()) }) { Text("Today", fontSize = 12.sp) }
    }
}

@Composable
private fun MetricTimelineDetail(metric: HealthMetrics.Metric, day: LocalDate, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<HealthConnectGateway.WindowRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(metric.id, day) {
        loading = true
        rows = HealthConnectGateway.readMetricDay(ctx, metric, day)
        loading = false
    }
    PageScroll {
        BackBar(text = "Timeline · ${metric.label} · ${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}", onBack = onBack)
        if (loading) InfoCard("Loading…", "Reading Health Connect for $day…")
        else if (rows.isEmpty()) InfoCard("Empty", "No ${metric.label.lowercase()} records on $day.")
        else rows.forEach { MetricCard(it.label, it.value, day.format(DateTimeFormatter.ISO_LOCAL_DATE)) }
    }
}

// ── Configs ──────────────────────────────────────────────────────────
private enum class ConfigsTab(val label: String) {
    Sources("Sources"), Permissions("Permissions"), Stats("Stats"),
    Raw("Raw"), About("About"),
}

@Composable
private fun ConfigsPage() {
    var tab by remember { mutableStateOf(ConfigsTab.Sources) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ConfigsTab.values().forEach { t ->
                val selected = t == tab
                OutlinedButton(
                    onClick = { tab = t },
                    modifier = Modifier.weight(1f),
                ) { Text(t.label, fontSize = 11.sp, color = if (selected) Color.White else Color(0xFFCFC2F0)) }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                ConfigsTab.Sources     -> ConfigsSources()
                ConfigsTab.Permissions -> ConfigsPermissions()
                ConfigsTab.Stats       -> ConfigsStats()
                ConfigsTab.Raw         -> ConfigsRaw()
                ConfigsTab.About       -> ConfigsAbout()
            }
        }
    }
}

@Composable
private fun ConfigsSources() {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<HealthConnectGateway.SourceBreakdown>>(emptyList()) }
    LaunchedEffect(Unit) { rows = HealthConnectGateway.readSourceBreakdown(ctx) }
    PageScroll {
        SectionHeader("Producers · last 30 days")
        if (rows.isEmpty()) {
            InfoCard("No producers seen", "Make sure Garmin Connect / Google Fit / Samsung Health are configured to sync into Health Connect.")
        } else {
            rows.forEach { row ->
                MetricCard(
                    title    = row.packageName,
                    value    = "${row.recordCount}",
                    subtitle = row.lastWrite?.let { "last write " + DateTimeFormatter.ISO_INSTANT.format(it).take(19) } ?: "—",
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        InfoCard("Per-metric toggle", "Source-disable toggles per metric will land here — until then, every detected producer contributes to every metric it writes.")
    }
}

@Composable
private fun ConfigsPermissions() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grants ->
        granted = grants
        HealthStore.recordPermissionGrant(ctx)
    }
    LaunchedEffect(Unit) { granted = HealthConnectGateway.grantedPermissions(ctx) }
    val lastGrant = HealthStore.lastPermissionGrantTs(ctx)

    PageScroll {
        SectionHeader("Health Connect · permissions")
        InfoCard("Status", "${granted.size} / ${HealthMetrics.allPermissions.size} read perms granted.")
        OutlinedButton(
            onClick = { launcher.launch(HealthMetrics.allPermissions) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) { Text("Request all read perms") }
        if (lastGrant > 0L) {
            InfoCard("Last asked", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastGrant)))
        }

        SectionHeader("Per-metric perm state")
        HealthMetrics.all.forEach { m ->
            val mGranted = m.perms.count { it in granted }
            MetricCard(
                title    = m.label,
                value    = "$mGranted / ${m.perms.size}",
                subtitle = if (mGranted == m.perms.size && mGranted > 0) "all granted"
                           else if (mGranted == 0)             "none granted"
                           else                                "partial",
            )
        }
    }
}

@Composable
private fun ConfigsStats() {
    val ctx = LocalContext.current
    val historyLen = remember { HealthStore.history(ctx).length() }
    val lastTs     = remember { HealthStore.lastSnapshotTs(ctx) }
    val footprint  = remember { HealthStore.footprintBytes(ctx) }
    val sources    = remember { HealthStore.sourceLastSeen(ctx) }
    PageScroll {
        SectionHeader("Cache · local")
        MetricCard("Snapshots cached", "$historyLen", "of 365 cap")
        MetricCard("Cache footprint",  "$footprint B", "HealthStore SharedPrefs file")
        MetricCard("Last refresh",
            if (lastTs > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastTs)) else "—",
            "Summary reads")

        SectionHeader("Producers — write-rate")
        if (sources.isEmpty()) {
            InfoCard("None yet", "Open the Summary tab and drill into a metric once with HC perms granted to populate.")
        } else {
            sources.entries.sortedByDescending { it.value }.forEach { (pkg, ts) ->
                MetricCard(pkg, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(ts)), "last write")
            }
        }
    }
}

@Composable
private fun ConfigsRaw() {
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
                OutlinedButton(onClick = { picked = t }) { Text(t.first, fontSize = 11.sp) }
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
                            Text(r.summary,         color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${r.ts}",         color = Color(0xFFAAA0CC), fontSize = 11.sp)
                            Text("from ${r.origin}", color = Color(0xFFAAA0CC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            Text("uid ${r.uid}",    color = Color(0xFF6E5C95), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigsAbout() = PageScroll {
    SectionHeader("MyHealth · About")
    InfoCard("Local-only", "Every value rendered here came from a local SQLite query against Health Connect. No HTTPS, no OAuth, no upload. The instrumented test asserts this with StrictMode.")
    InfoCard("Sources", "Health Connect aggregates: Garmin Connect, Google Fit, Samsung Health, Fitbit (Health API bridge), Oura, Whoop. Toggle sync inside each producer app.")
    InfoCard("Setup", "1) Install Health Connect (Play Store).\n2) In Garmin Connect → Settings → Health Connect → enable read+write.\n3) Repeat for any other tracker.\n4) Open MyHealth → Configs → Permissions → Request all read perms.")
    InfoCard("Why a separate app", "Garmin's web API + Google's Health API would each need their own OAuth + webhook backend. Health Connect collapses both onto one local SDK with zero cloud surface.")
}

// ── Metric grid (used by Summary + Timeline) ────────────────────────
@Composable
private fun MetricGrid(title: String, onPick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SectionHeader(title)
        MetricGridInline(onPick = onPick)
    }
}

@Composable
private fun MetricGridInline(onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val metrics = remember { HealthMetrics.all }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(metrics) { m ->
            MetricTile(label = m.label, iconName = m.iconName, onClick = { onPick(m.id) })
        }
    }
}

@Composable
private fun MetricTile(label: String, iconName: String, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val iconId = remember(iconName) {
        ctx.resources.getIdentifier(iconName, "drawable", ctx.packageName).takeIf { it != 0 }
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(96.dp).clickable { onClick() },
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            if (iconId != null) {
                androidx.compose.foundation.Image(
                    painter  = painterResource(id = iconId),
                    contentDescription = label,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

// ── Shared building blocks ───────────────────────────────────────────
@Composable
private fun PageScroll(content: @Composable () -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { item { content() } }
}

@Composable
private fun BackBar(text: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onBack) { Text("‹ Back", fontSize = 12.sp) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFFEDE7FF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
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
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color(0xFFCFC2F0))
    }
}
