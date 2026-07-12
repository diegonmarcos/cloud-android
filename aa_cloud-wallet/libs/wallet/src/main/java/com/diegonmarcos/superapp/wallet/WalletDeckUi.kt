package com.diegonmarcos.superapp.wallet

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch
import kotlin.math.abs

private val CardDp = 200.dp

/**
 * Cards-tab deck — horizontal pager with 3-D perspective tilt.
 *
 * Swipe left/right: the centred card is the "current" card. Tapping
 * a non-current card scrolls the pager to it. Tapping the current
 * card opens Full view (or vCard → BusinessCardFragment). The
 * synthetic "+" Add tile lives at the end of the deck.
 */
@Composable
internal fun WalletDeck(
    cards: List<WalletStore.Card>,
    mode: WalletMode,
    onModeChange: (WalletMode) -> Unit,
    onAddTap: () -> Unit,
    onOpenVcard: () -> Unit,
    showAddTile: Boolean = true,
) {
    val deck = remember(cards, showAddTile) {
        if (showAddTile) cards + AddCardSentinel else cards
    }
    if (deck.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No items on this tab.", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { deck.size })
    val scope      = rememberCoroutineScope()
    val density    = LocalDensity.current

    // When the wallet returns to Idle from a Full/Config page, snap
    // back to the card that was open so the deck feels anchored.
    val fullCardId = (mode as? WalletMode.Full)?.cardId
        ?: (mode as? WalletMode.Config)?.cardId

    Column(modifier = Modifier.fillMaxSize()) {
        // Current card name caption above the pager
        val currentCard = deck.getOrNull(pagerState.currentPage)
        Text(
            text = currentCard?.brand.orEmpty(),
            color = Color(0xCCFFFFFF),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 6.dp),
        )

        HorizontalPager(
            state = pagerState,
            // Show a sliver of the adjacent cards on each side
            contentPadding  = PaddingValues(horizontal = 40.dp),
            pageSpacing     = 16.dp,
            beyondViewportPageCount = 2,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            val card = deck[page]
            // pageOffset > 0 means this page is to the RIGHT of current;
            // < 0 means to the LEFT. Used for tilt and parallax.
            val rawOffset   = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            val pageOffset  = rawOffset.coerceIn(-2f, 2f)
            val absOffset   = abs(pageOffset)

            val scale by animateFloatAsState(
                targetValue   = 1f - (absOffset * 0.08f).coerceAtMost(0.18f),
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label         = "scale",
            )
            val alpha by animateFloatAsState(
                targetValue   = 1f - (absOffset * 0.3f).coerceAtMost(0.55f),
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label         = "alpha",
            )
            val rotY by animateFloatAsState(
                targetValue   = pageOffset * -22f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                label         = "rotY",
            )
            val elevation by animateFloatAsState(
                targetValue   = if (absOffset < 0.1f) 24f else 8f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
                label         = "elevation",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CardDp)
                    .graphicsLayer {
                        scaleX         = scale
                        scaleY         = scale
                        this.alpha     = alpha
                        rotationY      = rotY
                        cameraDistance = 14f * density.density
                        shadowElevation = elevation
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                    ) {
                        if (page != pagerState.currentPage) {
                            // Tap non-current → scroll to it
                            scope.launch { pagerState.animateScrollToPage(page) }
                        } else {
                            // Tap current → action
                            when {
                                card.id == AddCardSentinel.id -> onAddTap()
                                card.kind == "vcard"          -> onOpenVcard()
                                else                          -> onModeChange(WalletMode.Full(card.id))
                            }
                        }
                    },
            ) {
                if (card.id == AddCardSentinel.id) {
                    WalletAddTile(onClick = onAddTap)
                } else {
                    WalletDeckCard(
                        card      = card,
                        onInfoTap = { onModeChange(WalletMode.Config(card.id)) },
                    )
                }
            }
        }

        // Dot indicators
        Spacer(modifier = Modifier.height(12.dp))
        PagerDots(count = deck.size, current = pagerState.currentPage)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

/** Small dot row — shows position inside the deck. */
@Composable
private fun PagerDots(count: Int, current: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        val show = count.coerceAtMost(12) // don't render > 12 dots
        repeat(show) { i ->
            val isCurrent = i == current || (current >= show && i == show - 1)
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 8.dp else 5.dp)
                    .clip(CircleShape)
                    .background(if (isCurrent) Color(0xFF7C3AED) else Color(0x44FFFFFF)),
            )
            if (i < show - 1) Spacer(modifier = Modifier.width(5.dp))
        }
    }
}

// ─── IDs tab ─────────────────────────────────────────────────────────────────

/** IDs tab — country filter toggle + horizontal card pager. */
@Composable
internal fun WalletIdsTab(
    allIds: List<WalletStore.Card>,
    onCardTap: (WalletStore.Card) -> Unit,
) {
    var country by remember { mutableStateOf("") }
    val filtered = remember(allIds, country) {
        if (country.isEmpty()) allIds else allIds.filter { it.country == country }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        CountryToggle(selected = country, onSelect = { country = it })
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No IDs for this filter.", color = Color(0x99FFFFFF), fontSize = 14.sp)
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { filtered.size })
            val scope = rememberCoroutineScope()
            Column(modifier = Modifier.fillMaxSize()) {
                val currentCard = filtered.getOrNull(pagerState.currentPage)
                Text(
                    text = currentCard?.brand.orEmpty(),
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                )
                HorizontalPager(
                    state = pagerState,
                    contentPadding  = PaddingValues(horizontal = 40.dp),
                    pageSpacing     = 16.dp,
                    beyondViewportPageCount = 2,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { page ->
                    val card = filtered[page]
                    val rawOffset  = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                    val absOffset  = abs(rawOffset.coerceIn(-2f, 2f))
                    val scale by animateFloatAsState(1f - absOffset * 0.08f, spring(Spring.DampingRatioMediumBouncy), "s")
                    val alpha by animateFloatAsState(1f - absOffset * 0.3f, spring(Spring.DampingRatioNoBouncy), "a")
                    val rotY  by animateFloatAsState(rawOffset * -20f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), "r")
                    val density = LocalDensity.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CardDp)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                this.alpha = alpha
                                rotationY = rotY
                                cameraDistance = 14f * density.density
                            }
                            .clickable(remember { MutableInteractionSource() }, null) {
                                if (page != pagerState.currentPage)
                                    scope.launch { pagerState.animateScrollToPage(page) }
                                else
                                    onCardTap(card)
                            },
                    ) {
                        IdCardView(card = card, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                PagerDots(count = filtered.size, current = pagerState.currentPage)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun CountryToggle(selected: String, onSelect: (String) -> Unit) {
    val options = listOf("" to "All", "es" to "🇪🇸 Spain", "br" to "🇧🇷 Brazil")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, label) ->
            val active = key == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (active) Color(0xFF7C3AED) else Color(0x22FFFFFF))
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

// ─── Docs tab ────────────────────────────────────────────────────────────────

/** Docs tab — vertical list of document cards (birth certs, etc.). */
@Composable
internal fun WalletDocsTab(
    docs: List<WalletStore.Card>,
    onDocTap: (WalletStore.Card) -> Unit,
) {
    if (docs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No documents.", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        docs.forEach { doc ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CardDp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clickable { onDocTap(doc) },
            ) {
                DocCardView(card = doc, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Card in the pager with the (ⓘ) info overlay. The card body tap is
 *  handled by the pager item wrapper above; only the info button needs
 *  its own clickable here. */
@Composable
private fun WalletDeckCard(
    card: WalletStore.Card,
    onInfoTap: () -> Unit,
) {
    val ctx = LocalContext.current
    val resolved = remember(card.id, card.kind) {
        if (card.kind == "vcard" || card.kind == "vcard_imported") {
            val q = QrcodesData.contact(ctx)
            if (q != null) card.copy(
                brand   = q.displayName,
                tagline = when (card.kind) {
                    "vcard"          -> "Profile Card"
                    "vcard_imported" -> "vCard 3.0"
                    else             -> card.tagline
                },
                number  = "",
            ) else card
        } else card
    }
    Box(modifier = Modifier.fillMaxSize()) {
        WalletCardView(card = resolved, isExpanded = true, modifier = Modifier.fillMaxSize())
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
            Text("ⓘ", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Normal)
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

/** Shared card visual — used in both the deck and the full-page view.
 *  ID and Doc cards are dispatched to their own composables. */
@Composable
internal fun WalletCardView(
    card: WalletStore.Card,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    when (card.category) {
        "id"  -> { IdCardView(card = card, modifier = modifier); return }
        "doc" -> { DocCardView(card = card, modifier = modifier); return }
    }

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
