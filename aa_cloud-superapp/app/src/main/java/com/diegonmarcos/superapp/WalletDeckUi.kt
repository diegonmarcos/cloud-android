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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.distinctUntilChanged
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
    onOpenVcard: () -> Unit,
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
    val density    = LocalDensity.current

    // True while WE are running an animateScrollToItem; the swipe-to-
    // dismiss collector below ignores scroll events during that window
    // so our programmatic centring doesn't accidentally dismiss the
    // selection we just made.
    var programmaticScrolling by remember { mutableStateOf(false) }

    // On Selected → animate the deck so the selected card is centred
    // in the viewport. Looks up the nearest visible slot whose card.id
    // matches; falls back to the first visible slot when the selected
    // card has already scrolled off (rare — only happens if the
    // selection survives a scroll, which the dismiss-on-scroll handler
    // below explicitly prevents).
    LaunchedEffect(selectedId) {
        if (selectedId == null) return@LaunchedEffect
        val info = listState.layoutInfo
        val slot = info.visibleItemsInfo
            .firstOrNull { deck[it.index % deck.size].id == selectedId }
            ?.index
            ?: listState.firstVisibleItemIndex
        val viewportH = info.viewportEndOffset - info.viewportStartOffset
        val cardPx    = with(density) { CardDp.toPx() }.toInt()
        val offsetPx  = -((viewportH - cardPx) / 2)
        programmaticScrolling = true
        runCatching { listState.animateScrollToItem(slot, offsetPx) }
        programmaticScrolling = false
    }

    // Swipe-to-dismiss while Selected: any user-initiated scroll →
    // back to Idle. distinctUntilChanged so we don't fire on every
    // sub-pixel update; the !programmaticScrolling gate exempts our
    // own centring animation above.
    LaunchedEffect(listState, selectedId) {
        if (selectedId == null) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && !programmaticScrolling) {
                    onModeChange(WalletMode.Idle)
                }
            }
    }

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
                    // Selected card must paint above the rest. LazyColumn
                    // draws items in slot order, so a later card (drawn
                    // last) sits on top by default — the previously-bug
                    // was the faded sibling beneath the lifted selection
                    // bleeding through and making it LOOK semi-transparent.
                    // zIndex(1f) lifts the selected box out of that stack.
                    .zIndex(if (isSelected) 1f else 0f)
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
                            // vCard is one-tap → the existing
                            // BusinessCardFragment (same destination as
                            // the drawer-header identity row).
                            //
                            // Every other card uses the two-tap pattern,
                            // but with a strict re-selection rule: while
                            // any card is Selected, tapping a DIFFERENT
                            // card counts as "tap outside the selection"
                            // → dismiss to Idle. The user has to tap
                            // again to pick that new card. Prevents the
                            // selection from invisibly hopping between
                            // cards on accidental taps.
                            when {
                                card.kind == "vcard"  -> onOpenVcard()
                                isSelected            -> onModeChange(WalletMode.Full(card.id))
                                selectedId != null    -> onModeChange(WalletMode.Idle)
                                else                  -> onModeChange(WalletMode.Selected(card.id))
                            }
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
    // Deck cards always render at TWO lines — Title (brand) + Subtitle
    // (tagline). For the user's vCard slots, brand = displayName and
    // subtitle is a kind-specific role label ("Profile Card" / "vCard
    // 3.0") so the deck stays scannable. The FULL identity (tel,
    // email, address, all URLs, birthday, socials) only renders when
    // the card is expanded (Selected on the deck or in the wallet's
    // Full page) — WalletCardView handles that inflation internally.
    val resolved = remember(card.id, card.kind) {
        if (card.kind == "vcard" || card.kind == "vcard_imported") {
            val q = QrcodesData.contact(ctx)
            if (q != null) {
                card.copy(
                    brand   = q.displayName,
                    tagline = when (card.kind) {
                        "vcard"          -> "Profile Card"
                        "vcard_imported" -> "vCard 3.0"
                        else             -> card.tagline
                    },
                    number  = "",
                )
            } else card
        } else card
    }
    Box(modifier = Modifier.fillMaxSize().clickable(onClick = onCardTap)) {
        WalletCardView(card = resolved, isExpanded = isSelected, modifier = Modifier.fillMaxSize())
        // (i) info button — top-right circle. No shadow (it cast a
        // soft gray halo that looked like a second, off-centre ring),
        // solid black 70% opacity background, and the "i" glyph is
        // the pre-circled-info Unicode ⓘ so we don't have to fight
        // letter-baseline maths to centre a Latin "i" inside a circle.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xB3000000))
                .clickable(onClick = onInfoTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "ⓘ",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal,
            )
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
        val ctx = LocalContext.current
        Row(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                Text(card.brand, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                // Deck (isExpanded=false) shows ONLY the short subtitle
                // already in card.tagline ("Profile Card", "vCard 3.0",
                // "Visa · IBAN ending 4582", …). When the card expands
                // (Selected on the deck or rendered in the wallet's
                // Full page) AND it's a vCard kind, inflate the
                // tagline to the full multi-line identity pulled from
                // QrcodesData — tight font so the rows fit.
                val isVcardLike = card.kind == "vcard" || card.kind == "vcard_imported"
                val expandedTagline = if (isExpanded && isVcardLike) {
                    QrcodesData.contact(ctx)?.fullCardTagline()
                } else null
                val displayTagline = expandedTagline ?: card.tagline
                Text(
                    displayTagline,
                    color = Color(0xCCFFFFFF),
                    fontSize  = if (expandedTagline != null) 10.sp else 13.sp,
                    lineHeight = if (expandedTagline != null) 13.sp else 16.sp,
                )
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
            // Show the QR for any vCard kind whenever the card is in
            // the expanded state (Selected / Full). The QR encodes the
            // full canonical vCard 3.0 payload (matches the .vcf the
            // front repo publishes), so scanning it reproduces the
            // same identity the card front displays.
            if ((card.kind == "vcard" || card.kind == "vcard_imported") && isExpanded) {
                Spacer(modifier = Modifier.size(12.dp))
                VcardQr(card = card)
            }
        }
    }
}

/** Live vCard 3.0 QR — encoded from the canonical QrcodesData contact
 *  block so it matches the .vcf file generated by the front repo
 *  (~/git/front/a-Portals/linktree/src/public/diegonmarcos-contact.vcf).
 *  Same data the wallet card front displays — the QR is a scannable
 *  copy of the card. */
@Composable
private fun VcardQr(card: WalletStore.Card) {
    val ctx = LocalContext.current
    val payload = remember(card.id) { QrcodesData.contact(ctx)?.let(::vcardPayload).orEmpty() }
    val sizePx = 256
    val bitmap = remember(payload) {
        if (payload.isBlank()) return@remember null
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

/** vCard 3.0 string built from the canonical QrcodesContact. Mirrors
 *  every field that lands in the .vcf file the front repo generates:
 *  N, FN, EMAIL;TYPE, TEL;TYPE, ADR;TYPE, URL;TYPE × N, BDAY. */
private fun vcardPayload(q: QrcodesContact): String = buildString {
    append("BEGIN:VCARD\n")
    append("VERSION:3.0\n")
    val nameParts = q.displayName.trim().split(Regex("\\s+"))
    if (nameParts.isNotEmpty()) {
        val given  = nameParts.first()
        val family = if (nameParts.size > 1) nameParts.drop(1).joinToString(" ") else ""
        append("N:$family;$given;\n")
    }
    if (q.displayName.isNotBlank()) append("FN:${q.displayName}\n")
    if (q.email.isNotBlank())       append("EMAIL;TYPE=PERSONAL:${q.email}\n")
    if (q.tel.isNotBlank())         append("TEL;TYPE=MOBILE:${q.tel}\n")
    if (q.street.isNotBlank() || q.city.isNotBlank() || q.country.isNotBlank()) {
        append("ADR;TYPE=HOME:;;${q.street};${q.city};${q.region};;${q.country}\n")
    }
    if (q.socials.isNotBlank())     append("URL;TYPE=Socials:${q.socials}\n")
    if (q.linktreeUrl.isNotBlank()) append("URL;TYPE=Linktree:${q.linktreeUrl}\n")
    if (q.telegramUrl.isNotBlank()) append("URL;TYPE=Telegram:${q.telegramUrl}\n")
    if (q.whatsappUrl.isNotBlank()) append("URL;TYPE=WhatsApp:${q.whatsappUrl}\n")
    if (q.landingUrl.isNotBlank())  append("URL;TYPE=Website:${q.landingUrl}\n")
    if (q.birthday.isNotBlank())    append("BDAY:${q.birthday}\n")
    append("END:VCARD")
}
