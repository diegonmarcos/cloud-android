package com.diegonmarcos.superapp

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.core.WalletStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Card height + visible "peek" of the next card behind it. The negative
 *  arrangement spacing on the LazyColumn = cardHeight − peek so each
 *  card sits BEHIND the previous with only [PeekDp] showing. */
private val CardDp = 200.dp
private val PeekDp = 96.dp

@Composable
internal fun WalletDeck(
    cards: List<WalletStore.Card>,
    mode: WalletMode,
    onModeChange: (WalletMode) -> Unit,
    onAddTap: () -> Unit,
) {
    // Append the synthetic "Add card" tile so it cycles with the rest.
    val deck = remember(cards) { cards + AddCardSentinel }

    // Circular scroll: enormous slot count, modulo to fetch actual card.
    // Start mid-list so user can scroll up or down equally before hitting
    // the artificial cap (Int.MAX_VALUE / 2 ≈ 1B slots in either direction).
    val slotCount  = Int.MAX_VALUE
    val initialIdx = remember(deck.size) {
        if (deck.isEmpty()) 0 else (slotCount / 2) - ((slotCount / 2) % deck.size)
    }
    val listState  = rememberLazyListState(initialFirstVisibleItemIndex = initialIdx)
    val selectedId = (mode as? WalletMode.Selected)?.cardId

    LazyColumn(
        state               = listState,
        verticalArrangement = Arrangement.spacedBy(-(CardDp - PeekDp)),
        contentPadding      = PaddingValues(top = 32.dp, bottom = 96.dp),
        modifier = Modifier
            .fillMaxSize()
            // Background tap dismisses Selected — but Compose clickables
            // on child cards consume the gesture first, so this only fires
            // when the user actually taps outside any card.
            .clickable(
                enabled            = selectedId != null,
                interactionSource  = remember { MutableInteractionSource() },
                indication         = null,
            ) { onModeChange(WalletMode.Idle) },
    ) {
        items(count = slotCount) { slot ->
            val card        = deck[slot % deck.size]
            val isSelected  = selectedId == card.id
            val isFaded     = selectedId != null && !isSelected

            val alpha by animateFloatAsState(
                targetValue = if (isFaded) 0.22f else 1f,
                animationSpec = tween(260),
                label = "alpha",
            )
            val lift by animateDpAsState(
                targetValue = if (isSelected) (-18).dp else 0.dp,
                animationSpec = tween(260),
                label = "lift",
            )
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1.04f else 1f,
                animationSpec = tween(260),
                label = "scale",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(CardDp)
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = lift.toPx()
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                if (card.id == AddCardSentinel.id) {
                    WalletAddTile(onClick = onAddTap)
                } else {
                    WalletDeckCard(
                        card       = card,
                        isSelected = isSelected,
                        onCardTap  = {
                            if (isSelected) onModeChange(WalletMode.Full(card.id))
                            else            onModeChange(WalletMode.Selected(card.id))
                        },
                        onInfoTap  = { onModeChange(WalletMode.Config(card.id)) },
                    )
                }
            }
        }
    }
}

/** A real wallet card with the (i) info button overlay. Card body click
 *  → [onCardTap]; (i) overlay click → [onInfoTap]. The overlay sits in
 *  the top-right corner and its `clickable` consumes the gesture so the
 *  card's underlying tap does NOT fire. */
@Composable
private fun WalletDeckCard(
    card: WalletStore.Card,
    isSelected: Boolean,
    onCardTap: () -> Unit,
    onInfoTap: () -> Unit,
) {
    val ctx = LocalContext.current
    val resolved = remember(card.id, card.kind) {
        if (card.kind == "vcard") {
            val profile = ProfilePrefs(ctx)
            card.copy(
                brand   = profile.name.ifBlank { "Virtual Business Card" },
                tagline = profile.titles.ifBlank { profile.email },
                number  = profile.email,
            )
        } else card
    }
    Box(modifier = Modifier.fillMaxSize().clickable(onClick = onCardTap)) {
        WalletCardView(card = resolved, isExpanded = isSelected, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(28.dp)
                .shadow(2.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(Color(0x55000000))
                .clickable(onClick = onInfoTap),
            contentAlignment = Alignment.Center,
        ) {
            Text("i", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

/** Black "+" tile for adding a new card. Tap → bottom sheet. */
@Composable
private fun WalletAddTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0A0A0A))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Light)
    }
}

/** Shared card visual — used in both the deck and the full-page view. */
@Composable
internal fun WalletCardView(
    card: WalletStore.Card,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val base   = Color(card.accent.toULong().toLong())
    val accent = Color(
        red   = (base.red   + 0.15f).coerceAtMost(1f),
        green = (base.green + 0.15f).coerceAtMost(1f),
        blue  = (base.blue  + 0.15f).coerceAtMost(1f),
    )
    Box(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent, base)))
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                Text(card.brand,   color = Color.White,       fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(card.tagline, color = Color(0xCCFFFFFF), fontSize = 13.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (card.number.isNotBlank()) {
                    Text(card.number, color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                }
                if (card.barcode.isNotBlank()) {
                    Text("⌫ ${card.barcode}", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.size(4.dp))
                Text(card.kind.uppercase(), color = Color(0x88FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (card.kind == "vcard" && isExpanded) {
                Spacer(modifier = Modifier.size(12.dp))
                VcardQr(card = card)
            }
        }
    }
}

/** Live vCard 3.0 QR — encoded from ProfilePrefs at compose time so
 *  edits in Configs → Profile reflect on next open. */
@Composable
private fun VcardQr(card: WalletStore.Card) {
    val ctx = LocalContext.current
    val payload = remember(card.id) { vcardSelfPayload(ProfilePrefs(ctx)) }
    val sizePx = 256
    val bitmap = remember(payload) {
        runCatching {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (y in 0 until sizePx) for (x in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "vCard QR",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .padding(4.dp),
        )
    }
}

/** vCard 3.0 string built from ProfilePrefs (the user's own identity). */
private fun vcardSelfPayload(profile: ProfilePrefs): String = buildString {
    append("BEGIN:VCARD\n")
    append("VERSION:3.0\n")
    if (profile.name.isNotBlank())     append("FN:${profile.name}\n")
    if (profile.email.isNotBlank())    append("EMAIL:${profile.email}\n")
    if (profile.company.isNotBlank())  append("ORG:${profile.company}\n")
    if (profile.titles.isNotBlank())   append("TITLE:${profile.titles}\n")
    if (profile.website.isNotBlank())  append("URL:${profile.website}\n")
    append("END:VCARD")
}
