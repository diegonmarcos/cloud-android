package com.diegonmarcos.superapp.wallet

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PageBg = Color(0xFF0B0414)

/** Single-character glyphs per ticket kind. Used in the list strip's
 *  left "stub" column and the ticket page header so each kind has a
 *  visual identity without needing a drawable per kind. */
private fun kindGlyph(kind: String): String = when (kind) {
    "flight"  -> "✈"
    "train"   -> "🚆"
    "music"   -> "♪"
    "theater" -> "🎭"
    else      -> "🎟"
}

/** Vertical list of ticket strips — replaces the card-stack roll on
 *  the Tickets tab. Each strip is a wide horizontal pill with a left
 *  stub (kind + glyph) and the brand / route / time on the right.
 *  Tap → wallet's Full state, which routes to [WalletTicketPage]. */
@Composable
internal fun WalletTicketList(
    tickets: List<WalletStore.Card>,
    onTicketTap: (WalletStore.Card) -> Unit,
) {
    if (tickets.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No tickets yet.", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
        return
    }
    val sorted = remember(tickets) { tickets.sortedBy { it.eventAt } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(sorted, key = { it.id }) { t ->
            TicketStrip(ticket = t, onClick = { onTicketTap(t) })
        }
    }
}

@Composable
private fun TicketStrip(ticket: WalletStore.Card, onClick: () -> Unit) {
    val base   = Color(ticket.accent.toULong().toLong())
    val accent = Color(
        red   = (base.red   + 0.18f).coerceAtMost(1f),
        green = (base.green + 0.18f).coerceAtMost(1f),
        blue  = (base.blue  + 0.18f).coerceAtMost(1f),
    )
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.US) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(accent, base)))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(76.dp)
                .background(Color(0x33000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(kindGlyph(ticket.kind), color = Color.White, fontSize = 26.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(ticket.kind.uppercase(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(ticket.brand,   color = Color.White,       fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(ticket.tagline, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 2)
            Spacer(modifier = Modifier.height(6.dp))
            Text(dateFmt.format(Date(ticket.eventAt)), color = Color(0xCCFFFFFF), fontSize = 11.sp)
        }
    }
}

/** Full ticket page — wider rectangular layout (no card-stack
 *  framing). Header → route/tagline → date/time grid → seat/number →
 *  tear-line separator → QR → map / location panel. Distinct from
 *  [WalletFullPage] which renders the card-stack card on top of an
 *  info panel. */
@Composable
internal fun WalletTicketPage(ticket: WalletStore.Card, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val base = Color(ticket.accent.toULong().toLong())
    val accent = Color(
        red   = (base.red   + 0.18f).coerceAtMost(1f),
        green = (base.green + 0.18f).coerceAtMost(1f),
        blue  = (base.blue  + 0.18f).coerceAtMost(1f),
    )
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.US) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "← Back",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // ── Ticket body ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .shadow(8.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(accent, base)))
                .padding(20.dp),
        ) {
            // Header row: kind + glyph + brand
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(kindGlyph(ticket.kind), color = Color.White, fontSize = 22.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    ticket.kind.uppercase(),
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    ticket.brand,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))

            // Route / show line
            Text(
                ticket.tagline,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Date / time grid
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("DATE", color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        dateFmt.format(Date(ticket.eventAt)),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("TIME", color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        timeFmt.format(Date(ticket.eventAt)),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (ticket.number.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("SEAT / NUMBER", color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    ticket.number,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Tear line — dashed border separates the info zone from
            // the QR strip, mirroring how a real torn paper ticket
            // looks once stamped.
            Spacer(modifier = Modifier.height(20.dp))
            TicketTearLine()
            Spacer(modifier = Modifier.height(20.dp))

            // QR code
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TicketQr(ticket = ticket)
            }
            if (ticket.barcode.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    ticket.barcode,
                    color = Color(0xCCFFFFFF),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── Map / location panel ─────────────────────────────────
        if (ticket.eventLocation.isNotBlank()) {
            Spacer(modifier = Modifier.height(20.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    "Location",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                MapPanel(
                    location = ticket.eventLocation,
                    onOpenInMaps = {
                        val uri = Uri.parse("geo:0,0?q=${Uri.encode(ticket.eventLocation)}")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
                )
            }
        }
    }
}

/** Static map placeholder — no Maps SDK integration yet, so we render
 *  a stylised grid + pin glyph + the location text. Tap launches a
 *  geo: intent so the system Maps app handles it (Google Maps / OSM
 *  / whatever the user has set as default). */
@Composable
private fun MapPanel(location: String, onOpenInMaps: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onOpenInMaps),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF1E293B)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("📍", fontSize = 36.sp, color = Color.White)
        }
        Column(modifier = Modifier.padding(12.dp)) {
            Text(location, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tap to open in Maps",
                color = Color(0x99FFFFFF),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun TicketTearLine() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Repeat short dashes across the width
        repeat(24) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(2.dp)
                    .background(Color(0x66FFFFFF)),
            )
            if (it < 23) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun TicketQr(ticket: WalletStore.Card) {
    // Encode the barcode if the ticket carries one (boarding passes,
    // transit barcodes…). Otherwise build a compact "ticket summary"
    // payload — brand|kind|epoch|seat — which is at least round-trip-
    // parseable by a future scan handler.
    val payload = remember(ticket.id) {
        if (ticket.barcode.isNotBlank()) ticket.barcode
        else "${ticket.brand}|${ticket.kind}|${ticket.eventAt}|${ticket.number}"
    }
    val sizePx = 480
    val bitmap = remember(payload) {
        runCatching {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (y in 0 until sizePx) for (x in 0 until sizePx) {
                bmp.setPixel(
                    x, y,
                    if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                )
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Ticket QR",
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(8.dp),
        )
    }
}
