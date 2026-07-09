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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

/** Top-level Wallet partitioning. Cards = long-lasting credentials
 *  (debit, transit, gym, …). Tickets = event-bound passes (flight,
 *  train, music, theatre). Calendar = same Tickets data, regrouped
 *  by date into an agenda view. */
enum class WalletTab(val label: String) {
    Cards("Cards"),
    Tickets("Tickets"),
    Calendar("Calendar"),
}

/** Bottom button rendered on the Tickets tab that toggles between
 *  Upcoming (default) and Archive (past-event tickets). Mirrors the
 *  pill aesthetic of [WalletTabStrip] so the chrome reads consistent.
 *  Label shows the count of the other view so the user knows what's
 *  on the far side of the toggle. */
@Composable
internal fun WalletArchiveToggle(
    showingArchive: Boolean,
    upcomingCount: Int,
    archiveCount:  Int,
    onToggle: () -> Unit,
) {
    val label = if (showingArchive) "Back to Upcoming  ($upcomingCount)"
                else                "Archive  ($archiveCount)"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (showingArchive) Color(0xFF7C3AED) else Color(0x22FFFFFF))
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = if (showingArchive) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** Pill tab strip rendered above the deck. Three options — Cards /
 *  Tickets / Calendar. Tap to switch; the active pill fills with the
 *  brand purple, the rest sit on a 13% white wash. */
@Composable
internal fun WalletTabStrip(
    selected: WalletTab,
    onSelect: (WalletTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WalletTab.values().forEach { tab ->
            val isActive = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isActive) Color(0xFF7C3AED) else Color(0x22FFFFFF))
                    .clickable { onSelect(tab) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.label,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Agenda view: tickets sorted by [WalletStore.Card.eventAt],
 *  grouped by calendar day with a "Today / Tomorrow / EEE, MMM d"
 *  header per group. Tapping a row opens the wallet's Full page for
 *  that ticket. */
@Composable
internal fun WalletCalendarView(
    tickets: List<WalletStore.Card>,
    onTicketTap: (WalletStore.Card) -> Unit,
) {
    if (tickets.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No upcoming tickets.",
                color = Color(0x99FFFFFF),
                fontSize = 14.sp,
            )
        }
        return
    }
    val sorted = remember(tickets) { tickets.sortedBy { it.eventAt } }
    val grouped = remember(sorted) {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sorted.groupBy { dayKey.format(Date(it.eventAt)) }
            .toSortedMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
            Text(
                timeFmt.format(Date(ticket.eventAt)),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                ticket.kind.uppercase(),
                color = Color(0x88FFFFFF),
                fontSize = 9.sp,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(ticket.brand,   color = Color.White,       fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(ticket.tagline, color = Color(0xCCFFFFFF), fontSize = 12.sp)
            if (ticket.eventLocation.isNotBlank()) {
                Text(ticket.eventLocation, color = Color(0x88FFFFFF), fontSize = 11.sp)
            }
        }
        Box(modifier = Modifier
            .size(width = 6.dp, height = 36.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(accent)
        )
    }
}

/** "Today" / "Tomorrow" / "Mon, Jun 9 · 2026" — day-of-week-aware
 *  header for the agenda groupings. */
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
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}
