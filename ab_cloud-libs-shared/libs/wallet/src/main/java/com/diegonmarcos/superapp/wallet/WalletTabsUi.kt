package com.diegonmarcos.superapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.size
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

/** Top-level wallet tabs. Events is a container with its own inner nav
 *  (Tickets / Bookings / Passes / Cal). */
enum class WalletTab(val label: String) {
    IDs("IDs"),
    /** Payment cards — the tab is called Pay because that is what you open it
     *  to do; the deck it holds is still the banking cards. */
    Pay("Pay"),
    /** Vcards sits between the documents you carry and the events you go to:
     *  it is the public half of the same wallet — one personal card per social,
     *  mirroring the pages of front-diegonmarcos/b-Media/mySocials. Declared,
     *  not stored: it holds no WalletStore cards, so it is the one tab whose
     *  content the user cannot add to from the app. */
    Vcards("Vcards"),
    Tickets("Events"),
    /** Still a destination, no longer a pill: it is the gear at the right end
     *  of the strip. Kept in the enum because it is where [WalletFragment]
     *  routes to render WalletSystemConfigTab — a mode, not a menu entry. */
    Config("Config"),
}

/** Sub-tabs rendered inside the Events section. */
enum class TicketsSubTab(val label: String) {
    Events("Tickets"),
    Bookings("Bookings"),
    Passes("Passes"),
    Calendar("Cal"),
}

// ─── Tab strips ───────────────────────────────────────────────────────────────

/**
 * Top-level strip: IDs · Pay · Me · Vcards · Events.
 *
 * NOT built from WalletTab.values() any more, because the strip and the enum
 * no longer describe the same set. Config has no pill at all — it is
 * [WalletConfigGear], down in the opposite corner — and Me is a pill without a
 * destination: it launches cloud-me, the separate app that owns the
 * personal-administration surface this one deliberately does not.
 *
 * The pills scroll horizontally. Five at widthIn(min = 70.dp) plus their gaps
 * already exceed a 360dp phone, and a Row that overflows clips silently — the
 * last pill simply is not there.
 */
@Composable
internal fun WalletTabStrip(
    selected: WalletTab,
    onSelect: (WalletTab) -> Unit,
    onOpenMe: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill(WalletTab.IDs.label, R.drawable.ic_tab_ids, WalletTab.IDs == selected) { onSelect(WalletTab.IDs) }
        Pill(WalletTab.Pay.label, R.drawable.ic_tab_pay, WalletTab.Pay == selected) { onSelect(WalletTab.Pay) }
        // Never "selected": tapping it leaves this app entirely.
        Pill("Me", R.drawable.ic_tab_me, active = false, onClick = onOpenMe)
        Pill(WalletTab.Vcards.label, R.drawable.ic_tab_vcards, WalletTab.Vcards == selected) { onSelect(WalletTab.Vcards) }
        Pill(WalletTab.Tickets.label, R.drawable.ic_tab_events, WalletTab.Tickets == selected) { onSelect(WalletTab.Tickets) }
    }
}

/**
 * Config, as a gear in the bottom-right corner.
 *
 * It floats over the content rather than taking a row of its own: a wallet is
 * a stack of cards and one settings button does not deserve a permanent 44dp
 * band under them. The one control it could cover is [WalletArchiveToggle],
 * which reserves this corner for exactly that reason.
 */
@Composable
internal fun WalletConfigGear(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(end = 16.dp, bottom = 16.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFF7C3AED) else Color(0xE62A2140))
            .clickable { onClick() }
            .padding(11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tab_config),
            contentDescription = "Config",
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One strip pill: icon over label, so the strip is readable at a glance and
 *  still says what it means — an icon-only bar makes IDs and Vcards a guess. */
@Composable
private fun Pill(label: String, @DrawableRes icon: Int, active: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 66.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) Color(0xFF7C3AED) else Color(0x22FFFFFF))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (active) Color.White else Color(0xCCFFFFFF),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        )
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
            // end reserves WalletConfigGear's corner, which floats over this
            // bar's right edge — a settings tap that lands on Archive instead
            // is worse than an off-centre bar.
            .padding(start = 16.dp, end = 68.dp, top = 10.dp, bottom = 10.dp),
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
    bookings: List<WalletStore.Card>,
    showArchive: Boolean,
    upcomingCount: Int,
    archiveCount: Int,
    onToggleArchive: () -> Unit,
    onTicketTap: (WalletStore.Card) -> Unit,
    onBookingTap: (WalletStore.Card) -> Unit,
) {
    val tFiltered = remember(tickets, showArchive) {
        (if (showArchive) tickets.filter { it.isPastTicket } else tickets.filter { !it.isPastTicket })
            .sortedBy { it.eventAt }
    }
    val bFiltered = remember(bookings, showArchive) {
        (if (showArchive) bookings.filter { it.isPastBooking } else bookings.filter { !it.isPastBooking })
            .sortedBy { it.eventAt }
    }
    val grouped = remember(tFiltered) {
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        tFiltered.groupBy { dayKey.format(Date(it.eventAt)) }.toSortedMap()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Left — event tickets, grouped by day
            CalendarColumn(
                title = "Tickets",
                empty = if (showArchive) "No past events." else "No upcoming events.",
                isEmpty = tFiltered.isEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                grouped.forEach { (dateKey, dayTickets) ->
                    item(key = "h-$dateKey") {
                        Text(
                            text = humanDate(dayTickets.first().eventAt),
                            color = Color(0xCCFFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(dayTickets, key = { it.id }) { t ->
                        CalendarRow(ticket = t, onClick = { onTicketTap(t) })
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color(0x22FFFFFF)))
            // Right — bookings as date-range bars
            CalendarColumn(
                title = "Bookings",
                empty = if (showArchive) "No past stays." else "No upcoming stays.",
                isEmpty = bFiltered.isEmpty(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                items(bFiltered, key = { it.id }) { b ->
                    BookingRangeRow(booking = b, onClick = { onBookingTap(b) })
                    Spacer(modifier = Modifier.height(8.dp))
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

/** One calendar column with a sticky-ish header and a scrolling body.
 *  [content] supplies LazyColumn items; empties render the [empty] hint. */
@Composable
private fun CalendarColumn(
    title: String,
    empty: String,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            title.uppercase(),
            color = Color(0x88FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(empty, color = Color(0x99FFFFFF), fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                content()
            }
        }
    }
}

/** Compact booking row for the calendar column — check-in → check-out
 *  with the nights count, distinct from the point-in-time ticket rows. */
@Composable
private fun BookingRangeRow(booking: WalletStore.Card, onClick: () -> Unit) {
    val dayFmt = remember { SimpleDateFormat("MMM d", Locale.US) }
    val accent = Color(booking.accent.toULong().toLong())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Text(booking.brand, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dayFmt.format(Date(booking.eventAt)), color = Color.White, fontSize = 11.sp)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(accent))
            Text(dayFmt.format(Date(booking.checkOutAt)), color = Color.White, fontSize = 11.sp)
        }
        Text("${booking.nights} night${if (booking.nights == 1) "" else "s"}",
            color = Color(0x88FFFFFF), fontSize = 10.sp)
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
