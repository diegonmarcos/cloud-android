package com.diegonmarcos.superapp.wallet

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ─── Top-level tabs ───────────────────────────────────────────────────────────

/** Four top-level wallet tabs. Tickets is a container with its own inner nav. */
enum class WalletTab(val label: String) {
    Cards("Cards"),
    IDs("IDs"),
    Tickets("Tickets"),
    Config("Config"),
}

/** Sub-tabs rendered inside the Tickets section. */
enum class TicketsSubTab(val label: String) {
    Events("Tickets"),
    Passes("Passes"),
    Calendar("Cal"),
}

// ─── Tab strips ───────────────────────────────────────────────────────────────

/** Top-level pill strip (Cards / IDs / Tickets / Config). */
@Composable
internal fun WalletTabStrip(
    selected: WalletTab,
    onSelect: (WalletTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        WalletTab.values().forEach { tab ->
            val isActive = tab == selected
            Box(
                modifier = Modifier
                    .widthIn(min = 70.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isActive) Color(0xFF7C3AED) else Color(0x22FFFFFF))
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Inner sub-tab strip inside the Tickets section. */
@Composable
internal fun TicketsSubTabStrip(
    selected: TicketsSubTab,
    onSelect: (TicketsSubTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        TicketsSubTab.values().forEach { sub ->
            val isActive = sub == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActive) Color(0x44FFFFFF) else Color(0x15FFFFFF))
                    .clickable { onSelect(sub) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sub.label,
                    color = if (isActive) Color.White else Color(0xAAFFFFFF),
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ─── Archive toggle ───────────────────────────────────────────────────────────

@Composable
internal fun WalletArchiveToggle(
    showingArchive: Boolean,
    upcomingCount: Int,
    archiveCount: Int,
    onToggle: () -> Unit,
) {
    val label = if (showingArchive) "← Back to Upcoming  ($upcomingCount)"
                else               "Archive  ($archiveCount)"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (showingArchive) Color(0xFF7C3AED) else Color(0x22FFFFFF))
                .clickable(onClick = onToggle)
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (showingArchive) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

// ─── Calendar agenda ─────────────────────────────────────────────────────────

@Composable
internal fun WalletCalendarView(
    tickets: List<WalletStore.Card>,
    showArchive: Boolean,
    upcomingCount: Int,
    archiveCount: Int,
    onToggleArchive: () -> Unit,
    onTicketTap: (WalletStore.Card) -> Unit,
) {
    val filtered = remember(tickets, showArchive) {
        if (showArchive) tickets.filter { it.isPastTicket } else tickets.filter { !it.isPastTicket }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (filtered.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (showArchive) "No past events." else "No upcoming events.",
                        color = Color(0x99FFFFFF),
                        fontSize = 14.sp,
                    )
                }
            } else {
                val sorted  = remember(filtered) { filtered.sortedBy { it.eventAt } }
                val grouped = remember(sorted) {
                    val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    sorted.groupBy { dayKey.format(Date(it.eventAt)) }.toSortedMap()
                }
                LazyColumn(
                    modifier       = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    grouped.forEach { (dateKey, dayTickets) ->
                        item(key = "h-$dateKey") {
                            Text(
                                text = humanDate(dayTickets.first().eventAt),
                                color = Color(0xCCFFFFFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
                            )
                        }
                        items(dayTickets, key = { it.id }) { ticket ->
                            CalendarRow(ticket = ticket, onClick = { onTicketTap(ticket) })
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
        WalletArchiveToggle(
            showingArchive = showArchive,
            upcomingCount  = upcomingCount,
            archiveCount   = archiveCount,
            onToggle       = onToggleArchive,
        )
    }
}

@Composable
private fun CalendarRow(ticket: WalletStore.Card, onClick: () -> Unit) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val accent  = Color(ticket.accent.toULong().toLong())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(60.dp)) {
            Text(timeFmt.format(Date(ticket.eventAt)), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(ticket.kind.uppercase(), color = Color(0x88FFFFFF), fontSize = 9.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(ticket.brand,   color = Color.White,       fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(ticket.tagline, color = Color(0xCCFFFFFF), fontSize = 12.sp)
            if (ticket.eventLocation.isNotBlank()) Text(ticket.eventLocation, color = Color(0x88FFFFFF), fontSize = 11.sp)
        }
        Box(modifier = Modifier.width(6.dp).height(36.dp).clip(RoundedCornerShape(3.dp)).background(accent))
    }
}

private fun humanDate(millis: Long): String {
    val today    = Calendar.getInstance().apply { clearTime() }
    val tomorrow = Calendar.getInstance().apply { clearTime(); add(Calendar.DAY_OF_YEAR, 1) }
    val target   = Calendar.getInstance().apply { timeInMillis = millis; clearTime() }
    val pretty   = SimpleDateFormat("EEE, MMM d · yyyy", Locale.US).format(Date(millis))
    return when (target.timeInMillis) {
        today.timeInMillis    -> "Today · $pretty"
        tomorrow.timeInMillis -> "Tomorrow · $pretty"
        else                  -> pretty
    }
}

private fun Calendar.clearTime() {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0);      set(Calendar.MILLISECOND, 0)
}
