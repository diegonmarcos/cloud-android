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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PageBg = Color(0xFF0B0414)

private fun bookingGlyph(kind: String): String = when (kind) {
    "hotel"      -> "🏨"
    "apartment"  -> "🏢"
    "hostel"     -> "🛏"
    "car_rental" -> "🚗"
    else         -> "🧳"
}

/** Bookings tab — vertical list of stay cards. Unlike tickets (single
 *  point in time) each booking spans a date range, so the strip leads
 *  with a check-in → check-out band and the nights count. */
@Composable
internal fun WalletBookingList(
    bookings: List<WalletStore.Card>,
    onBookingTap: (WalletStore.Card) -> Unit,
) {
    if (bookings.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No bookings yet.", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
        return
    }
    val sorted = remember(bookings) { bookings.sortedBy { it.eventAt } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(sorted, key = { it.id }) { b ->
            BookingStrip(booking = b, onClick = { onBookingTap(b) })
        }
    }
}

@Composable
private fun BookingStrip(booking: WalletStore.Card, onClick: () -> Unit) {
    val base   = Color(booking.accent.toULong().toLong())
    val accent = Color(
        red   = (base.red   + 0.18f).coerceAtMost(1f),
        green = (base.green + 0.18f).coerceAtMost(1f),
        blue  = (base.blue  + 0.18f).coerceAtMost(1f),
    )
    val dFmt = remember { SimpleDateFormat("EEE, MMM d", Locale.US) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, shape = RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(accent, base)))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(bookingGlyph(booking.kind), color = Color.White, fontSize = 24.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(booking.brand, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (booking.eventLocation.isNotBlank())
                    Text(booking.eventLocation, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${booking.nights}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(if (booking.nights == 1) "night" else "nights", color = Color(0xCCFFFFFF), fontSize = 10.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BookingDateChip("CHECK-IN",  dFmt.format(Date(booking.eventAt)))
            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp)
                .background(Color(0x66FFFFFF)))
            Text("→", color = Color(0xCCFFFFFF), fontSize = 14.sp)
            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp)
                .background(Color(0x66FFFFFF)))
            BookingDateChip("CHECK-OUT", dFmt.format(Date(booking.checkOutAt)), end = true)
        }
    }
}

@Composable
private fun BookingDateChip(label: String, value: String, end: Boolean = false) {
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, color = Color(0x99FFFFFF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Full booking page — check-in / check-out grid, nights, confirmation
 *  number, QR, and location. Range-oriented counterpart to
 *  [WalletTicketPage]. */
@Composable
internal fun WalletBookingPage(booking: WalletStore.Card, onBack: () -> Unit) {
    val ctx  = LocalContext.current
    val base = Color(booking.accent.toULong().toLong())
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
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Text("← Back", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onBack).padding(8.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .shadow(8.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(accent, base)))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bookingGlyph(booking.kind), color = Color.White, fontSize = 24.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(booking.brand, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(booking.tagline, color = Color(0xCCFFFFFF), fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${booking.nights}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(if (booking.nights == 1) "night" else "nights", color = Color(0xCCFFFFFF), fontSize = 10.sp)
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CHECK-IN", color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(dateFmt.format(Date(booking.eventAt)), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("from ${timeFmt.format(Date(booking.eventAt))}", color = Color(0xCCFFFFFF), fontSize = 11.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("CHECK-OUT", color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(dateFmt.format(Date(booking.checkOutAt)), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("by ${timeFmt.format(Date(booking.checkOutAt))}", color = Color(0xCCFFFFFF), fontSize = 11.sp)
                }
            }

            if (booking.number.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("CONFIRMATION", color = Color(0x99FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(booking.number, color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }

            if (booking.barcode.isNotBlank()) {
                Spacer(modifier = Modifier.height(20.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BookingQr(booking = booking)
                }
            }
        }

        if (booking.eventLocation.isNotBlank()) {
            Spacer(modifier = Modifier.height(20.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("Location", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, shape = RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x22FFFFFF))
                        .clickable {
                            val uri = Uri.parse("geo:0,0?q=${Uri.encode(booking.eventLocation)}")
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        },
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(140.dp).background(
                            Brush.linearGradient(listOf(Color(0xFF1E293B), Color(0xFF334155), Color(0xFF1E293B)))),
                        contentAlignment = Alignment.Center,
                    ) { Text("📍", fontSize = 36.sp, color = Color.White) }
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(booking.eventLocation, color = Color.White, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tap to open in Maps", color = Color(0x99FFFFFF), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookingQr(booking: WalletStore.Card) {
    val payload = remember(booking.id) {
        if (booking.barcode.isNotBlank()) booking.barcode
        else "${booking.brand}|${booking.kind}|${booking.eventAt}|${booking.number}"
    }
    val sizePx = 480
    val bitmap = remember(payload) {
        runCatching {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (y in 0 until sizePx) for (x in 0 until sizePx)
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "Booking QR",
            modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(8.dp),
        )
    }
}
