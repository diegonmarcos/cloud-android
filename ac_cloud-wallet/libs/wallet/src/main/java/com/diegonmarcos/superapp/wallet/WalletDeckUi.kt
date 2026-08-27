package com.diegonmarcos.superapp.wallet

import android.graphics.Bitmap
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.distinctUntilChanged

private val CardDp = 200.dp
private val PeekDp = 96.dp

// ─── Cards tab deck (vertical peek-stack) ────────────────────────────────────

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

    val slotCount  = Int.MAX_VALUE
    val initialIdx = remember(deck.size) {
        (slotCount / 2) - ((slotCount / 2) % deck.size)
    }
    val listState  = rememberLazyListState(initialFirstVisibleItemIndex = initialIdx)
    val selectedId = (mode as? WalletMode.Selected)?.cardId
    val density    = LocalDensity.current

    var programmaticScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(selectedId) {
        if (selectedId == null) return@LaunchedEffect
        val info      = listState.layoutInfo
        val slot      = info.visibleItemsInfo.firstOrNull { deck[it.index % deck.size].id == selectedId }?.index
            ?: listState.firstVisibleItemIndex
        val viewportH = info.viewportEndOffset - info.viewportStartOffset
        val cardPx    = with(density) { CardDp.toPx() }.toInt()
        programmaticScrolling = true
        runCatching { listState.animateScrollToItem(slot, -((viewportH - cardPx) / 2)) }
        programmaticScrolling = false
    }

    LaunchedEffect(listState, selectedId) {
        if (selectedId == null) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (scrolling && !programmaticScrolling) onModeChange(WalletMode.Idle) }
    }

    LazyColumn(
        state               = listState,
        verticalArrangement = Arrangement.spacedBy(-(CardDp - PeekDp)),
        contentPadding      = PaddingValues(top = 32.dp, bottom = 96.dp),
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled           = selectedId != null,
                interactionSource = remember { MutableInteractionSource() },
                indication        = null,
            ) { onModeChange(WalletMode.Idle) },
    ) {
        items(count = slotCount) { slot ->
            val card       = deck[slot % deck.size]
            val isSelected = selectedId == card.id
            val isFaded    = selectedId != null && !isSelected

            val alpha by animateFloatAsState(
                targetValue   = if (isFaded) 0.16f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                label         = "alpha",
            )
            val lift by animateDpAsState(
                targetValue   = if (isSelected) (-22).dp else 0.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label         = "lift",
            )
            val scale by animateFloatAsState(
                targetValue   = if (isSelected) 1.06f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label         = "scale",
            )
            val elevation by animateFloatAsState(
                targetValue   = if (isSelected) 24f else 8f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
                label         = "elev",
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(CardDp)
                    .zIndex(if (isSelected) 1f else 0f)
                    .graphicsLayer {
                        this.alpha      = alpha
                        translationY    = lift.toPx()
                        scaleX          = scale
                        scaleY          = scale
                        shadowElevation = elevation
                    },
            ) {
                if (card.id == AddCardSentinel.id) {
                    WalletAddTile(onClick = {
                        if (selectedId != null) onModeChange(WalletMode.Idle) else onAddTap()
                    })
                } else {
                    WalletDeckCard(
                        card       = card,
                        isSelected = isSelected,
                        onCardTap  = {
                            when {
                                card.kind == "vcard" -> onOpenVcard()
                                isSelected           -> onModeChange(WalletMode.Full(card.id))
                                selectedId != null   -> onModeChange(WalletMode.Idle)
                                else                 -> onModeChange(WalletMode.Selected(card.id))
                            }
                        },
                        onInfoTap  = { onModeChange(WalletMode.Config(card.id)) },
                    )
                }
            }
        }
    }
}

// ─── IDs tab (IDs + Docs merged, vertical scroll) ────────────────────────────

@Composable
internal fun WalletIdsTab(
    allIds: List<WalletStore.Card>,
    onCardTap: (WalletStore.Card) -> Unit,
) {
    var countries by remember { mutableStateOf(setOf("es", "br")) }
    var types    by remember { mutableStateOf(setOf("id", "license")) }
    var viewMode by remember { mutableStateOf("2d") }  // "2d" | "3dr" | "3df"
    val licenseKinds = setOf("id_driving_es", "id_boat_es")
    val filtered = remember(allIds, countries, types) {
        allIds.filter { card ->
            val isVcard    = card.kind == "vcard" || card.kind == "vcard_imported"
            val countryOk  = isVcard || card.country.isEmpty() || card.country in countries
            val cardType   = if (card.kind in licenseKinds) "license" else "id"
            val typeOk     = cardType in types
            countryOk && typeOk
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter strip: [ES] [BR]  |  [ID] [Licenses]  ···  [2D|3D]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("es" to "ES", "br" to "BR").forEach { (key, label) ->
                val active = key in countries
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) Color(0xFF7C3AED) else Color(0x20FFFFFF))
                        .clickable { countries = if (active) countries - key else countries + key }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.size(1.dp, 18.dp).background(Color(0x44FFFFFF)))
            listOf("id" to "ID", "license" to "Licenses").forEach { (key, label) ->
                val active = key in types
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) Color(0xFF7C3AED) else Color(0x20FFFFFF))
                        .clickable { types = if (active) types - key else types + key }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color.White, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
            Spacer(Modifier.weight(1f))
            // 2D / 3D-r / 3D-f view toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x20FFFFFF))
                    .padding(2.dp),
            ) {
                listOf("2d" to "2D", "3dr" to "3D-r", "3df" to "3D-f").forEach { (key, label) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (viewMode == key) Color(0xFF7C3AED) else Color.Transparent)
                            .clickable { viewMode = key }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = if (viewMode == key) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No IDs for this filter.", color = Color(0x99FFFFFF), fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize(),
                contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(filtered) { card ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(CardDp)
                            .shadow(8.dp, RoundedCornerShape(16.dp))
                            .clickable { onCardTap(card) },
                    ) {
                        when {
                            card.category == "doc" -> DocCardView(card = card, modifier = Modifier.fillMaxSize())
                            card.kind == "vcard" || card.kind == "vcard_imported" ->
                                WalletCardView(card = card, isExpanded = false, modifier = Modifier.fillMaxSize())
                            viewMode == "3dr" -> IdCard3DReactView(card = card, modifier = Modifier.fillMaxSize())
                            viewMode == "3df" -> IdCard3DFilamentView(card = card, modifier = Modifier.fillMaxSize())
                            else              -> IdCardView(card = card, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

// ─── Passes tab (transit + gym, vertical scroll) ─────────────────────────────

@Composable
internal fun WalletPassesTab(
    passes: List<WalletStore.Card>,
    onCardTap: (WalletStore.Card) -> Unit,
) {
    if (passes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No passes.", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
        return
    }
    LazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(passes) { pass ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CardDp)
                    .shadow(8.dp, RoundedCornerShape(16.dp))
                    .clickable { onCardTap(pass) },
            ) {
                WalletCardView(card = pass, isExpanded = false, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ─── Deck card wrapper ────────────────────────────────────────────────────────

@Composable
private fun WalletDeckCard(
    card: WalletStore.Card,
    isSelected: Boolean,
    onCardTap: () -> Unit,
    onInfoTap: () -> Unit,
) {
    val ctx = LocalContext.current
    val resolved = remember(card.id, card.kind) {
        if (card.kind == "vcard" || card.kind == "vcard_imported") {
            val q = QrcodesData.contact(ctx)
            if (q != null) card.copy(
                brand   = q.displayName,
                tagline = if (card.kind == "vcard") "Profile Card" else "vCard 3.0",
                number  = "",
            ) else card
        } else card
    }

    val borderAlpha by animateFloatAsState(
        targetValue   = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label         = "glow",
    )

    Box(modifier = Modifier.fillMaxSize().clickable(onClick = onCardTap)) {
        WalletCardView(card = resolved, isExpanded = isSelected, modifier = Modifier.fillMaxSize())
        if (borderAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.dp, Color.White.copy(alpha = 0.55f * borderAlpha), RoundedCornerShape(20.dp)),
            )
        }
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
            Text("i", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

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

// ─── Shared card visual dispatch ─────────────────────────────────────────────

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
    when (card.kind) {
        "credit", "debit", "virtual_debit" -> { BankCardView(card = card, isExpanded = isExpanded, modifier = modifier); return }
        "transit"                           -> { TransitPassView(card = card, modifier = modifier); return }
        "gym"                               -> { MembershipCardView(card = card, modifier = modifier); return }
    }
    GenericCardView(card = card, isExpanded = isExpanded, modifier = modifier)
}

@Composable
private fun GenericCardView(
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
                val isVcardLike     = card.kind == "vcard" || card.kind == "vcard_imported"
                val expandedTagline = if (isExpanded && isVcardLike) QrcodesData.contact(ctx)?.fullCardTagline() else null
                val displayTagline  = expandedTagline ?: card.tagline
                Text(
                    displayTagline,
                    color      = Color(0xCCFFFFFF),
                    fontSize   = if (expandedTagline != null) 10.sp else 13.sp,
                    lineHeight = if (expandedTagline != null) 13.sp else 16.sp,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (card.number.isNotBlank()) Text(card.number, color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                if (card.barcode.isNotBlank()) Text("⌫ ${card.barcode}", color = Color(0xCCFFFFFF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.size(4.dp))
                Text(card.kind.uppercase(), color = Color(0x88FFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if ((card.kind == "vcard" || card.kind == "vcard_imported") && isExpanded) {
                Spacer(modifier = Modifier.size(12.dp))
                VcardQr(card = card)
            }
        }
    }
}

@Composable
private fun VcardQr(card: WalletStore.Card) {
    val ctx     = LocalContext.current
    val payload = remember(card.id) { QrcodesData.contact(ctx)?.let(::vcardPayload).orEmpty() }
    val sizePx  = 256
    val bitmap  = remember(payload) {
        if (payload.isBlank()) return@remember null
        runCatching {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val bmp    = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
            for (y in 0 until sizePx) for (x in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap             = bitmap,
            contentDescription = "vCard QR",
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White)
                .padding(4.dp),
        )
    }
}

private fun vcardPayload(q: QrcodesContact): String = buildString {
    append("BEGIN:VCARD\nVERSION:3.0\n")
    val parts = q.displayName.trim().split(Regex("\\s+"))
    if (parts.isNotEmpty()) append("N:${if (parts.size > 1) parts.drop(1).joinToString(" ") else ""};${parts.first()};\n")
    if (q.displayName.isNotBlank()) append("FN:${q.displayName}\n")
    if (q.email.isNotBlank())       append("EMAIL;TYPE=PERSONAL:${q.email}\n")
    if (q.tel.isNotBlank())         append("TEL;TYPE=MOBILE:${q.tel}\n")
    if (q.street.isNotBlank() || q.city.isNotBlank() || q.country.isNotBlank())
        append("ADR;TYPE=HOME:;;${q.street};${q.city};${q.region};;${q.country}\n")
    if (q.socials.isNotBlank())     append("URL;TYPE=Socials:${q.socials}\n")
    if (q.linktreeUrl.isNotBlank()) append("URL;TYPE=Linktree:${q.linktreeUrl}\n")
    if (q.telegramUrl.isNotBlank()) append("URL;TYPE=Telegram:${q.telegramUrl}\n")
    if (q.whatsappUrl.isNotBlank()) append("URL;TYPE=WhatsApp:${q.whatsappUrl}\n")
    if (q.landingUrl.isNotBlank())  append("URL;TYPE=Website:${q.landingUrl}\n")
    if (q.birthday.isNotBlank())    append("BDAY:${q.birthday}\n")
    append("END:VCARD")
}
