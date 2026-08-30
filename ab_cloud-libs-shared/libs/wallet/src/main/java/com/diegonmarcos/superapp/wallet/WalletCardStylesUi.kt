package com.diegonmarcos.superapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Bank card (credit / debit) ──────────────────────────────────────────────

@Composable
internal fun BankCardView(
    card: WalletStore.Card,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val base  = Color(card.accent.toULong().toLong())
    val light = Color(
        red   = (base.red   + 0.18f).coerceAtMost(1f),
        green = (base.green + 0.12f).coerceAtMost(1f),
        blue  = (base.blue  + 0.08f).coerceAtMost(1f),
    )
    val isCredit = card.kind == "credit"

    Box(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(light, base)))
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    card.brand,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x33000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (isCredit) "CREDIT" else "DEBIT",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            ChipIcon()
            Spacer(modifier = Modifier.height(10.dp))
            if (card.number.isNotBlank()) {
                Text(
                    card.number,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                card.tagline,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ChipIcon() {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 28.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFFD4B44A))
            .border(0.5.dp, Color(0xFF9A7500), RoundedCornerShape(5.dp)),
    ) {
        // Center cross contact pattern
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color(0xFF9A7500)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(0.5.dp)
                .fillMaxHeight()
                .background(Color(0xFF9A7500)),
        )
        // Outer contact rails (top + bottom 20%)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.22f)
                .background(Color(0xFF9A7500).copy(alpha = 0.3f)),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.22f)
                .background(Color(0xFF9A7500).copy(alpha = 0.3f)),
        )
    }
}

// ─── Transit pass ────────────────────────────────────────────────────────────

@Composable
internal fun TransitPassView(
    card: WalletStore.Card,
    modifier: Modifier = Modifier,
) {
    val accent = Color(card.accent.toULong().toLong())
    val dark   = Color(
        red   = (accent.red   * 0.7f),
        green = (accent.green * 0.7f),
        blue  = (accent.blue  * 0.7f),
    )

    Box(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(dark, accent))),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header band
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    card.brand,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.White.copy(0.2f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("PASS", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(card.tagline, color = Color.White.copy(0.9f), fontSize = 12.sp, lineHeight = 17.sp)
            }
            // Barcode strip at bottom
            if (card.barcode.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(0.12f))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val widths = intArrayOf(3, 1, 2, 1, 3, 1, 1, 2, 1, 3, 2, 1, 1, 3, 1, 2, 3, 1)
                        widths.forEachIndexed { i, w ->
                            Box(
                                modifier = Modifier
                                    .width(w.dp)
                                    .fillMaxHeight(if (i % 3 == 0) 1f else 0.75f)
                                    .background(if (i % 2 == 0) Color.White else Color.Transparent),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(card.barcode, color = Color.White.copy(0.45f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ─── Gym / membership card ───────────────────────────────────────────────────

@Composable
internal fun MembershipCardView(
    card: WalletStore.Card,
    modifier: Modifier = Modifier,
) {
    val accent = Color(card.accent.toULong().toLong())
    val light  = Color(
        red   = (accent.red   + 0.22f).coerceAtMost(1f),
        green = (accent.green + 0.22f).coerceAtMost(1f),
        blue  = (accent.blue  + 0.22f).coerceAtMost(1f),
    )

    Box(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent, light, accent))),
    ) {
        // Diagonal gloss strip top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(90.dp)
                .fillMaxHeight()
                .background(Color.White.copy(0.05f)),
        )
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                "MEMBERSHIP",
                color = Color.White.copy(0.55f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(card.brand, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(card.tagline, color = Color.White.copy(0.8f), fontSize = 12.sp)
            Spacer(modifier = Modifier.weight(1f))
            if (card.number.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33000000))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "MEMBER",
                            color = Color.White.copy(0.6f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            card.number,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
