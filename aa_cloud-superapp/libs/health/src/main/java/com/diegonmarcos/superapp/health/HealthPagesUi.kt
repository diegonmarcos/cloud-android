package com.diegonmarcos.superapp.health

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import com.diegonmarcos.superapp.core.HealthStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

/**
 * MyHealth — Compose surface.
 *
 * Single Compose entry [HealthScreen]: renders a STICKY top tab strip
 * (Summary | Timeline | Configs) that's visible at all times, plus a
 * body that swaps based on the selected tab. The `initialPageId` arg
 * sets the initial tab — Suite tile lands on Summary, drawer menu
 * lands on whichever page-id was tapped — but once the user is inside
 * MyHealth the strip lets them flip between the three.
 *
 *   ┌──────────────────────────────────────────┐
 *   │  Summary  │  Timeline  │  Configs        │   ← sticky tab strip
 *   ├──────────────────────────────────────────┤
 *   │                                          │
 *   │  Activity (10)                           │
 *   │    Steps        ·  7d 8420  · 30d 7890   │
 *   │    Distance     ·  7d 6.4km · 30d 5.9km  │
 *   │    …                                     │
 *   │  Vitals (9)                              │
 *   │    HR avg       ·  7d 72bpm · 30d 71bpm  │
 *   │    …                                     │
 *   │                                          │
 *   └──────────────────────────────────────────┘
 */
@Composable
fun HealthScreen(initialPageId: String) {
    var tab by remember { mutableStateOf(tabFromPageId(initialPageId)) }
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
        TopTabStrip(selected = tab, onSelect = { tab = it })
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                TopTab.Summary  -> SummaryBody()
                TopTab.Timeline -> TimelineBody()
                TopTab.Configs  -> ConfigsBody()
            }
        }
    }
}

private enum class TopTab(val label: String) {
    Summary("Summary"), Timeline("Timeline"), Configs("Configs"),
}

private fun tabFromPageId(pageId: String): TopTab = when (pageId) {
    HealthFragment.PAGE_TIMELINE -> TopTab.Timeline
    HealthFragment.PAGE_CONFIGS  -> TopTab.Configs
    else                         -> TopTab.Summary
}

@Composable
private fun TopTabStrip(selected: TopTab, onSelect: (TopTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TopTab.values().forEach { t ->
            val isSel = t == selected
            Card(
                modifier = Modifier.weight(1f).height(38.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = CardDefaults.cardColors(
                    containerColor = if (isSel) Color(0xFF6E2BD9) else Color(0xFF1A0F2A),
                ),
                onClick = { onSelect(t) },
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = t.label,
                        color = Color.White,
                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

// ── Summary tab ─────────────────────────────────────────────────────
// Flat scroll: every metric → every record, each row shows Avg 7d
// and Avg 30d side-by-side. No drill-down — everything visible at once.
@Composable
private fun SummaryBody() {
    val ctx = LocalContext.current
    val metrics = remember { HealthMetrics.all }
    var rows7d  by remember { mutableStateOf<Map<String, List<HealthConnectGateway.WindowRow>>>(emptyMap()) }
    var rows30d by remember { mutableStateOf<Map<String, List<HealthConnectGateway.WindowRow>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        val r7 = mutableMapOf<String, List<HealthConnectGateway.WindowRow>>()
        val r30 = mutableMapOf<String, List<HealthConnectGateway.WindowRow>>()
        metrics.forEach { m ->
            r7[m.id]  = HealthConnectGateway.readMetricWindow(ctx, m, 7)
            r30[m.id] = HealthConnectGateway.readMetricWindow(ctx, m, 30)
        }
        rows7d = r7
        rows30d = r30
        loading = false
    }

    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) {
            item { InfoCard("Loading…", "Reading the last 30 days from Health Connect…") }
        }
        metrics.forEach { m ->
            item(key = "h-${m.id}") {
                SectionHeader("${m.label} · ${m.records.size}")
            }
            val seven  = rows7d[m.id].orEmpty()
            val thirty = rows30d[m.id].orEmpty()
            val merged: List<Triple<String, String, String>> = (0 until maxOf(seven.size, thirty.size)).map { i ->
                val s = seven.getOrNull(i)
                val t = thirty.getOrNull(i)
                val label = s?.label ?: t?.label ?: "—"
                Triple(label, s?.value ?: "—", t?.value ?: "—")
            }
            if (merged.isEmpty() && !loading) {
                item(key = "e-${m.id}") {
                    InfoCard("${m.label} — no data", "No records found for any of this metric's types.")
                }
            } else {
                items(merged, key = { "r-${m.id}-${it.first}" }) { (lbl, v7, v30) ->
                    DualValueRow(label = lbl, value7d = v7, value30d = v30)
                }
            }
        }
    }
}

// ── Timeline tab ────────────────────────────────────────────────────
// Date picker at the top + flat list of every metric → every record
// with THAT day's value. Use ← / → / Today buttons to navigate.
@Composable
private fun TimelineBody() {
    val ctx = LocalContext.current
    val metrics = remember { HealthMetrics.all }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var rows by remember { mutableStateOf<Map<String, List<HealthConnectGateway.WindowRow>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(day) {
        loading = true
        val r = mutableMapOf<String, List<HealthConnectGateway.WindowRow>>()
        metrics.forEach { m -> r[m.id] = HealthConnectGateway.readMetricDay(ctx, m, day) }
        rows = r
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        DatePickerStrip(date = day, onChange = { day = it })
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            if (loading) item { InfoCard("Loading…", "Reading $day from Health Connect…") }
            metrics.forEach { m ->
                item(key = "h-${m.id}") { SectionHeader("${m.label} · ${m.records.size}") }
                val list = rows[m.id].orEmpty()
                if (list.isEmpty() && !loading) {
                    item(key = "e-${m.id}") {
                        InfoCard("${m.label} — no data on $day", "Nothing recorded for any of this metric's types.")
                    }
                } else {
                    items(list, key = { "r-${m.id}-${it.label}" }) { wr ->
                        SingleValueRow(label = wr.label, value = wr.value)
                    }
                }
            }
        }
    }
}

@Composable
private fun DatePickerStrip(date: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
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

// ── Configs tab — sub-tabs Sources / Permissions / Stats / Raw / About
private enum class ConfigsSubTab(val label: String) {
    Sources("Sources"), Permissions("Permissions"), Stats("Stats"),
    Raw("Raw"), About("About"),
}

@Composable
private fun ConfigsBody() {
    var sub by remember { mutableStateOf(ConfigsSubTab.Sources) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ConfigsSubTab.values().forEach { t ->
                val sel = t == sub
                OutlinedButton(
                    onClick = { sub = t },
                    modifier = Modifier.weight(1f),
                ) { Text(t.label, fontSize = 10.sp, color = if (sel) Color.White else Color(0xFFCFC2F0)) }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (sub) {
                ConfigsSubTab.Sources     -> ConfigsSources()
                ConfigsSubTab.Permissions -> ConfigsPermissions()
                ConfigsSubTab.Stats       -> ConfigsStats()
                ConfigsSubTab.Raw         -> ConfigsRaw()
                ConfigsSubTab.About       -> ConfigsAbout()
            }
        }
    }
}

@Composable
private fun ConfigsSources() {
    val ctx = LocalContext.current
    var sources by remember { mutableStateOf<List<HealthConnectGateway.SourceBreakdown>>(emptyList()) }
    LaunchedEffect(Unit) { sources = HealthConnectGateway.readSourceBreakdown(ctx) }
    PageScroll {
        SectionHeader("Producers · last 30 days")
        if (sources.isEmpty()) {
            InfoCard("No producers seen", "Garmin Connect / Google Fit / Samsung Health / Fitbit / Oura must be configured to sync into Health Connect.")
        } else {
            sources.forEach { row ->
                MetricCard(
                    title    = row.packageName,
                    value    = "${row.recordCount}",
                    subtitle = row.lastWrite?.let { "last write " + DateTimeFormatter.ISO_INSTANT.format(it).take(19) } ?: "—",
                )
            }
        }
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

    PageScroll {
        SectionHeader("Health Connect · permissions")
        InfoCard("Status", "${granted.size} / ${HealthMetrics.allPermissions.size} read perms granted.")
        OutlinedButton(
            onClick = { launcher.launch(HealthMetrics.allPermissions) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) { Text("Request all read perms") }

        SectionHeader("Per-metric perm state")
        HealthMetrics.all.forEach { m ->
            val ok = m.perms.count { it in granted }
            MetricCard(
                title    = m.label,
                value    = "$ok / ${m.perms.size}",
                subtitle = if (ok == m.perms.size && ok > 0) "all granted"
                           else if (ok == 0)               "none granted"
                           else                            "partial",
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
        SectionHeader("Local cache")
        MetricCard("Snapshots cached", "$historyLen", "of 365 cap")
        MetricCard("Cache footprint",  "$footprint B", "HealthStore SharedPrefs")
        MetricCard("Last refresh",
            if (lastTs > 0L) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date(lastTs)) else "—",
            "")
        SectionHeader("Producers — last seen")
        if (sources.isEmpty()) {
            InfoCard("None yet", "Open the Summary tab once with HC perms granted to populate.")
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
    LaunchedEffect(picked) { rows = HealthConnectGateway.readRawWindow(ctx, picked.second) }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        SectionHeader("Raw · ${picked.first} · last 7 days")
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { t ->
                OutlinedButton(onClick = { picked = t }) { Text(t.first, fontSize = 11.sp) }
            }
        }
        if (rows.isEmpty()) InfoCard("Empty window", "No ${picked.first} records in the last 7 days.")
        else LazyColumn(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun ConfigsAbout() = PageScroll {
    SectionHeader("MyHealth · About")
    InfoCard("Local-only", "Every value rendered here came from a local SQLite query against Health Connect. No HTTPS, no OAuth, no upload. The instrumented test asserts this with StrictMode.")
    InfoCard("Sources", "Health Connect aggregates: Garmin Connect, Google Fit, Samsung Health, Fitbit (Health API bridge), Oura, Whoop. Toggle sync inside each producer app.")
    InfoCard("Setup", "1) Install Health Connect (Play Store).\n2) Garmin Connect → Settings → Health Connect → enable.\n3) Repeat for any other tracker.\n4) Open MyHealth → Configs → Permissions → Request all.")
}

// ── Shared rows + helpers ───────────────────────────────────────────
@Composable
private fun DualValueRow(label: String, value7d: String, value30d: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFEDE7FF), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("7d  $value7d",  color = Color.White,        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text("30d $value30d", color = Color(0xFFB6A8DC), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SingleValueRow(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A0F2A)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFFEDE7FF), fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PageScroll(content: @Composable () -> Unit) {
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { item { content() } }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text       = text,
        color      = Color(0xFFEDE7FF),
        fontSize   = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier   = Modifier.padding(top = 12.dp, bottom = 4.dp),
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
                if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFF8A7DAC), fontSize = 11.sp)
            }
            Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
