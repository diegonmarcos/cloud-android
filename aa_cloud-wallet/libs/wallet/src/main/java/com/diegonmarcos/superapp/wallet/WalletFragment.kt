package com.diegonmarcos.superapp.wallet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WalletFragment : Fragment(), BackHandler {

    private val modeState: MutableState<WalletMode> = mutableStateOf(WalletMode.Idle)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WalletScreen(modeState) }
        }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!tryHandleBack()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    override fun tryHandleBack(): Boolean {
        if (modeState.value != WalletMode.Idle) {
            modeState.value = WalletMode.Idle
            return true
        }
        return false
    }

    companion object { fun newInstance(): WalletFragment = WalletFragment() }
}

sealed class WalletMode {
    object Idle : WalletMode()
    data class Selected(val cardId: String) : WalletMode()
    data class Full(val cardId: String)     : WalletMode()
    data class Config(val cardId: String)   : WalletMode()
}

internal val AddCardSentinel = WalletStore.Card(
    id      = "__wallet_add_sentinel__",
    kind    = "add",
    brand   = "Add card",
    tagline = "Scan QR · Import .vcf / .pkpass / .pass",
    accent  = 0xFF000000L,
)

@Composable
private fun WalletScreen(modeState: MutableState<WalletMode>) {
    val ctx = LocalContext.current
    val orderedCards: (List<WalletStore.Card>) -> List<WalletStore.Card> = { raw ->
        val (vc, rest) = raw.partition { it.kind == "vcard" }
        vc + rest
    }
    var cards by remember { mutableStateOf(orderedCards(WalletStore.all(ctx))) }
    var mode  by modeState
    var tab   by remember { mutableStateOf(WalletTab.Cards) }
    var ticketsSub          by remember { mutableStateOf(TicketsSubTab.Events) }
    var ticketsShowArchive  by remember { mutableStateOf(false) }
    var calShowArchive      by remember { mutableStateOf(false) }
    var bookingsShowArchive by remember { mutableStateOf(false) }
    var showAddSheet        by remember { mutableStateOf(false) }

    val refresh: () -> Unit = { cards = orderedCards(WalletStore.all(ctx)) }

    // ── Tab filtering ──────────────────────────────────────────────────────
    val bankingKinds = setOf("credit", "debit", "virtual_debit")
    val vcardKinds   = setOf("vcard", "vcard_imported")
    val passKinds    = setOf("transit", "gym")

    val cardsForTab = remember(cards, tab, ticketsSub, ticketsShowArchive, bookingsShowArchive) {
        when (tab) {
            WalletTab.Cards   -> cards.filter { !it.isTicket && it.category.isEmpty() && it.kind in bankingKinds }
            WalletTab.IDs     -> cards.filter { it.category == "id" || it.category == "doc" || it.kind in vcardKinds }
            WalletTab.Tickets -> when (ticketsSub) {
                TicketsSubTab.Events   -> cards.filter {
                    it.isTicket && (if (ticketsShowArchive) it.isPastTicket else !it.isPastTicket)
                }
                TicketsSubTab.Bookings -> cards.filter {
                    it.isBooking && (if (bookingsShowArchive) it.isPastBooking else !it.isPastBooking)
                }
                TicketsSubTab.Passes   -> cards.filter { !it.isTicket && it.kind in passKinds }
                TicketsSubTab.Calendar -> cards.filter { it.isTicket }
            }
            WalletTab.Config  -> emptyList()
        }
    }

    // Calendar column needs the full ticket + booking sets (its own archive toggle filters them).
    val allTickets  = remember(cards) { cards.filter { it.isTicket } }
    val allBookings = remember(cards) { cards.filter { it.isBooking } }

    val pastCount     = remember(cards) { cards.count { it.isPastTicket } }
    val upcomingCount = remember(cards) { cards.count { it.isTicket && !it.isPastTicket } }
    val pastBookingCount     = remember(cards) { cards.count { it.isPastBooking } }
    val upcomingBookingCount = remember(cards) { cards.count { it.isBooking && !it.isPastBooking } }

    val onOpenVcard: () -> Unit = { (ctx as? WalletHost)?.onOpenVcard() }

    fun cardOrIdle(id: String): WalletStore.Card? {
        val c = cards.firstOrNull { it.id == id }
        if (c == null) mode = WalletMode.Idle
        return c
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
        Column(modifier = Modifier.fillMaxSize()) {
            val hideChrome = mode is WalletMode.Full || mode is WalletMode.Config
            if (!hideChrome) {
                WalletTabStrip(
                    selected = tab,
                    onSelect = { next ->
                        if (next != tab) {
                            tab  = next
                            mode = WalletMode.Idle
                            ticketsShowArchive = false
                            calShowArchive = false
                            bookingsShowArchive = false
                        }
                    },
                )
                // Tickets inner sub-tab strip
                if (tab == WalletTab.Tickets) {
                    TicketsSubTabStrip(
                        selected = ticketsSub,
                        onSelect = { next ->
                            if (next != ticketsSub) {
                                ticketsSub = next
                                ticketsShowArchive = false
                                bookingsShowArchive = false
                            }
                        },
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (val m = mode) {
                    is WalletMode.Full -> {
                        cardOrIdle(m.cardId)?.let { card ->
                            when {
                                card.isBooking -> WalletBookingPage(booking = card, onBack = { mode = WalletMode.Idle })
                                card.isTicket  -> WalletTicketPage(ticket = card, onBack = { mode = WalletMode.Idle })
                                else           -> WalletFullPage(card = card, onBack = { mode = WalletMode.Idle })
                            }
                        }
                    }
                    is WalletMode.Config -> {
                        cardOrIdle(m.cardId)?.let { card ->
                            WalletConfigPage(
                                card   = card,
                                onBack = { mode = WalletMode.Idle },
                                onSave = { updated ->
                                    WalletStore.update(ctx, updated)
                                    refresh()
                                },
                            )
                        }
                    }
                    else -> when (tab) {
                        WalletTab.Config -> WalletSystemConfigTab(onImported = refresh)

                        WalletTab.Tickets -> when (ticketsSub) {
                            TicketsSubTab.Calendar -> WalletCalendarView(
                                tickets          = allTickets,
                                bookings         = allBookings,
                                showArchive      = calShowArchive,
                                upcomingCount    = upcomingCount + upcomingBookingCount,
                                archiveCount     = pastCount + pastBookingCount,
                                onToggleArchive  = { calShowArchive = !calShowArchive },
                                onTicketTap      = { mode = WalletMode.Full(it.id) },
                                onBookingTap     = { mode = WalletMode.Full(it.id) },
                            )
                            TicketsSubTab.Passes -> WalletPassesTab(
                                passes    = cardsForTab,
                                onCardTap = { mode = WalletMode.Full(it.id) },
                            )
                            TicketsSubTab.Events -> Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    WalletTicketList(
                                        tickets     = cardsForTab,
                                        onTicketTap = { mode = WalletMode.Full(it.id) },
                                    )
                                }
                                WalletArchiveToggle(
                                    showingArchive = ticketsShowArchive,
                                    upcomingCount  = upcomingCount,
                                    archiveCount   = pastCount,
                                    onToggle       = { ticketsShowArchive = !ticketsShowArchive },
                                )
                            }
                            TicketsSubTab.Bookings -> Column(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    WalletBookingList(
                                        bookings     = cardsForTab,
                                        onBookingTap = { mode = WalletMode.Full(it.id) },
                                    )
                                }
                                WalletArchiveToggle(
                                    showingArchive = bookingsShowArchive,
                                    upcomingCount  = upcomingBookingCount,
                                    archiveCount   = pastBookingCount,
                                    onToggle       = { bookingsShowArchive = !bookingsShowArchive },
                                )
                            }
                        }

                        WalletTab.IDs -> WalletIdsTab(
                            allIds    = cardsForTab,
                            onCardTap = { mode = WalletMode.Full(it.id) },
                        )

                        WalletTab.Cards -> WalletDeck(
                            cards        = cardsForTab,
                            mode         = m,
                            onModeChange = { mode = it },
                            onAddTap     = { showAddSheet = true },
                            onOpenVcard  = onOpenVcard,
                            showAddTile  = true,
                        )
                    }
                }
            }
        }
        if (showAddSheet) {
            WalletAddSheet(
                onDismiss      = { showAddSheet = false },
                onCardImported = { card ->
                    WalletStore.push(ctx, card)
                    refresh()
                    showAddSheet = false
                },
            )
        }
    }
}

// ─── Config tab ──────────────────────────────────────────────────────────────

@Composable
private fun WalletSystemConfigTab(onImported: () -> Unit) {
    val ctx    = LocalContext.current
    var status by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        status = runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
            val result = WalletStore.importFromJson(ctx, text)
            onImported()
            result
        }.getOrElse { "Error: ${it.message}" }
    }

    LazyColumn(
        modifier       = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Wallet Config", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Manage your wallet data and import cards.", color = Color(0x99FFFFFF), fontSize = 13.sp)
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            ConfigSection(title = "Import") {
                ConfigAction(
                    label    = "Import wallet.json",
                    sublabel = "Loads IDs, Docs and Passes from a local JSON file",
                ) { launcher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            }
        }
        if (status.isNotBlank()) {
            item {
                StatusBanner(status)
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { MailConnectorSection(onImported = onImported) }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            ConfigSection(title = "Update") {
                ConfigAction(
                    label    = "Check for updates",
                    sublabel = "Downloads and installs the latest build from GHCR",
                ) { (ctx as? WalletHost)?.onCheckForUpdates() }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            val pi = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() }
            ConfigSection(title = "About") {
                AboutRow("Version", pi?.versionName ?: "—")
                AboutRow("Package", ctx.packageName)
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            ConfigSection(title = "Wallet Data") {
                ConfigAction(
                    label    = "Reset to defaults",
                    sublabel = "Clears all cards and reloads the built-in seed data",
                    danger   = true,
                ) {
                    WalletStore.clear(ctx)
                    onImported()
                    status = "Reset complete."
                }
            }
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { LogcatSection() }
    }
}

@Composable
private fun LogcatSection() {
    val lines by LogStore.lines.collectAsState()
    val ctx = LocalContext.current
    ConfigSection(title = "Debug Log (WebView)") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${lines.size} lines",
                color = Color(0x88FFFFFF),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Copy",
                color = Color(0xFF7C3AED),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable {
                        val clip = lines.joinToString("\n")
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("wallet_log", clip))
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "Clear",
                color = Color(0xFFFF6B6B),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { LogStore.clear() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF060606)),
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            "No logs yet — open the 3D-r card view.",
                            color = Color(0x44FFFFFF),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    } else {
                        lines.forEach { line ->
                            Text(
                                line,
                                color = when {
                                    line.startsWith("[ERROR]") -> Color(0xFFFF6B6B)
                                    line.startsWith("[WARN]")  -> Color(0xFFFFD93D)
                                    line.startsWith("[NAV]")   -> Color(0xFF64B5F6)
                                    else                      -> Color(0xFF8AFF8A)
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusBanner(message: String) {
    val isError = message.contains("Error", ignoreCase = true)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isError) Color(0x33FF4444) else Color(0x3344FF88))
            .padding(12.dp),
    ) {
        Text(message, color = Color.White, fontSize = 13.sp)
    }
}

// ─── Mail Connector section ───────────────────────────────────────────────────

@Composable
private fun MailConnectorSection(onImported: () -> Unit) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()

    var cfg     by remember { mutableStateOf(WalletMailConnector.loadConfig(ctx)) }
    var expanded by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var status   by remember { mutableStateOf("") }

    ConfigSection(title = "Mail Connector") {
        // Header row — tap to expand/collapse settings
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("IMAP Settings", color = Color.White, fontSize = 15.sp)
                Text(
                    if (cfg.email.isNotBlank()) cfg.email else "Not configured",
                    color = Color(0x66FFFFFF),
                    fontSize = 12.sp,
                )
            }
            Text(if (expanded) "▲" else "▼", color = Color(0x55FFFFFF), fontSize = 14.sp)
        }

        // Collapsible settings
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x12FFFFFF))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MailField("IMAP Host",  cfg.host)     { cfg = cfg.copy(host = it) }
                MailField("Port",       cfg.port.toString(), keyboard = KeyboardType.Number) {
                    cfg = cfg.copy(port = it.toIntOrNull() ?: cfg.port)
                }
                MailField("Email",      cfg.email,    keyboard = KeyboardType.Email) { cfg = cfg.copy(email = it) }
                MailField("Password",   cfg.password, password = true) { cfg = cfg.copy(password = it) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF7C3AED))
                        .clickable {
                            WalletMailConnector.saveConfig(ctx, cfg)
                            status = "Settings saved."
                            expanded = false
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Save", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Scan action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !scanning) {
                    if (cfg.email.isBlank() || cfg.password.isBlank()) {
                        status = "Configure email and password first."
                        expanded = true
                        return@clickable
                    }
                    scanning = true
                    status   = "Connecting to ${cfg.host}…"
                    scope.launch(Dispatchers.IO) {
                        val result = runCatching {
                            WalletMailConnector.scan(ctx)
                        }
                        withContext(Dispatchers.Main) {
                            scanning = false
                            result.onSuccess { r ->
                                status = r.message
                                if (r.cards.isNotEmpty()) {
                                    // Deduplicate by id before importing
                                    val existing = WalletStore.all(ctx).map { it.id }.toSet()
                                    val fresh    = r.cards.filter { it.id !in existing }
                                    fresh.forEach { WalletStore.push(ctx, it) }
                                    if (fresh.isNotEmpty()) {
                                        onImported()
                                        status = "${r.message} · imported ${fresh.size} new"
                                    }
                                }
                            }.onFailure { e ->
                                status = "Error: ${e.message}"
                            }
                        }
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (scanning) "Scanning…" else "Scan for Passes",
                    color = if (scanning) Color(0xAAFFFFFF) else Color.White,
                    fontSize = 15.sp,
                )
                Text(
                    "Scans INBOX for .pkpass and wallet.json attachments",
                    color    = Color(0x66FFFFFF),
                    fontSize = 12.sp,
                )
            }
            if (scanning) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF7C3AED)),
                )
            } else {
                Text("›", color = Color(0x55FFFFFF), fontSize = 22.sp)
            }
        }

        if (status.isNotBlank()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                StatusBanner(status)
            }
        }
    }
}

@Composable
private fun MailField(
    label:    String,
    value:    String,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    TextField(
        value         = value,
        onValueChange = onChange,
        label         = { Text(label, fontSize = 11.sp) },
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else
            androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = TextFieldDefaults.colors(
            focusedContainerColor   = Color(0x22FFFFFF),
            unfocusedContainerColor = Color(0x15FFFFFF),
            focusedTextColor        = Color.White,
            unfocusedTextColor      = Color(0xCCFFFFFF),
            focusedLabelColor       = Color(0x88FFFFFF),
            unfocusedLabelColor     = Color(0x55FFFFFF),
            focusedIndicatorColor   = Color(0xFF7C3AED),
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor             = Color(0xFF7C3AED),
        ),
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0x88FFFFFF), fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ConfigSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            color        = Color(0x88FFFFFF),
            fontSize     = 10.sp,
            fontWeight   = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier     = Modifier.padding(start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x18FFFFFF)),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun ConfigAction(
    label: String,
    sublabel: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = if (danger) Color(0xFFFF6B6B) else Color.White, fontSize = 15.sp)
            Text(sublabel, color = Color(0x66FFFFFF), fontSize = 12.sp)
        }
        Text("›", color = Color(0x55FFFFFF), fontSize = 22.sp)
    }
}
