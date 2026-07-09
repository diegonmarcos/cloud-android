package com.diegonmarcos.superapp.wallet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

/**
 * Wallet — Compose surface. State machine across four modes:
 *   • Idle      — deck of cards in the circular swipe roll.
 *   • Selected  — one card lifted, the rest fade. Tap outside or any
 *                 different card → Idle. Tap the same card → Full.
 *   • Full      — full-page card view with info panel + Back.
 *   • Config    — per-card edit screen (reached via the (i) overlay).
 *
 * Mode state is hoisted to the Fragment (not local to WalletScreen)
 * so [tryHandleBack] can dismiss non-Idle modes when the user taps
 * the toolbar Back / hits system Back — without unwinding past
 * WalletFragment in the activity back-stack. MainActivity asks
 * BackHandler.tryHandleBack on the visible fragment first; only when
 * it returns false does popBackStack fire.
 */
class WalletFragment : Fragment(), BackHandler {

    private val modeState: MutableState<WalletMode> = mutableStateOf(WalletMode.Idle)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WalletScreen(modeState) }
        }

    override fun onViewCreated(view: View, s: Bundle?) {
        super.onViewCreated(view, s)
        // System Back: dismiss the Compose state instead of popping
        // WalletFragment off the activity back-stack — that's what
        // "the parent of a selected card is the deck, not Home" means
        // for the back gesture.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!tryHandleBack()) {
                        // Compose mode is already Idle — let the
                        // activity handle it (pop back stack).
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )
    }

    /** [BackHandler] entry point — MainActivity calls this from the
     *  toolbar action_back before popBackStack. Returns true when we
     *  consumed the back gesture by dismissing a non-Idle Compose
     *  state; false when we have nothing to dismiss. */
    override fun tryHandleBack(): Boolean {
        if (modeState.value != WalletMode.Idle) {
            modeState.value = WalletMode.Idle
            return true
        }
        return false
    }

    companion object { fun newInstance(): WalletFragment = WalletFragment() }
}

/** Wallet UI state. Single source of truth for everything the user can
 *  be looking at — deck/select/full/config — modeled as a sealed class
 *  so the dispatcher in [WalletScreen] is exhaustive. */
sealed class WalletMode {
    object Idle : WalletMode()
    data class Selected(val cardId: String) : WalletMode()
    data class Full(val cardId: String)     : WalletMode()
    data class Config(val cardId: String)   : WalletMode()
}

/** Sentinel for the synthetic "Add card" tile. Lives in the deck list
 *  but never in [WalletStore] — its id is checked everywhere the deck
 *  decides between rendering a real card vs the "+" placeholder. */
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
    // Stable sort: vCard(s) — the user's own Virtual Business Cards —
    // always pin to the TOP of the deck, regardless of when other
    // cards were imported. WalletStore.push() prepends new cards at
    // index 0; without this re-order the imported cards would bump
    // the vCard down on every import. partition() preserves relative
    // order within each bucket, so non-vCards keep their newest-first
    // ordering.
    val orderedCards: (List<WalletStore.Card>) -> List<WalletStore.Card> = { raw ->
        val (vc, rest) = raw.partition { it.kind == "vcard" }
        vc + rest
    }
    var cards by remember { mutableStateOf(orderedCards(WalletStore.all(ctx))) }
    var mode by modeState
    var tab  by remember { mutableStateOf(WalletTab.Cards) }
    var ticketsShowArchive by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }

    val refresh: () -> Unit = { cards = orderedCards(WalletStore.all(ctx)) }

    // Tab partitioning. Cards tab = long-lasting credentials
    // (eventAt == 0). Tickets / Calendar tabs share the same event-
    // bound subset (eventAt > 0) but render differently — deck vs
    // agenda view. The Tickets tab further splits into Upcoming
    // (default) and Archive (past events) via a bottom toggle button;
    // [ticketsShowArchive] flips the predicate.
    val cardsForTab = remember(cards, tab, ticketsShowArchive) {
        when (tab) {
            WalletTab.Cards    -> cards.filter { !it.isTicket }
            WalletTab.Tickets  -> cards.filter {
                it.isTicket && (if (ticketsShowArchive) it.isPastTicket else !it.isPastTicket)
            }
            WalletTab.Calendar -> cards.filter { it.isTicket && !it.isPastTicket }
        }
    }
    val pastCount = remember(cards) { cards.count { it.isPastTicket } }
    val upcomingCount = remember(cards) { cards.count { it.isTicket && !it.isPastTicket } }
    // vCard (kind="vcard") is special — it's NOT a stored, importable
    // card, it's the user's own identity card rendered from ProfilePrefs.
    // Tapping it skips the wallet's Compose Selected → Full state and
    // routes straight to the existing BusinessCardFragment (same
    // destination as the drawer-header identity row), so there's one
    // canonical Virtual Business Card surface in the app.
    val onOpenVcard: () -> Unit = {
        (ctx as? WalletHost)?.onOpenVcard()
    }
    fun cardOrIdle(id: String): WalletStore.Card? {
        val c = cards.firstOrNull { it.id == id }
        if (c == null) mode = WalletMode.Idle
        return c
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Tab strip is part of the wallet root chrome — hidden when
            // a card is opened Full or in Config (those are sub-views
            // of a card, not of the wallet itself).
            val hideChrome = mode is WalletMode.Full || mode is WalletMode.Config
            if (!hideChrome) {
                WalletTabStrip(
                    selected = tab,
                    onSelect = { next ->
                        if (next != tab) {
                            tab  = next
                            mode = WalletMode.Idle  // reset selection so the new tab opens clean
                            ticketsShowArchive = false  // any tab change drops out of Archive
                        }
                    },
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (val m = mode) {
                    is WalletMode.Full -> {
                        cardOrIdle(m.cardId)?.let { card ->
                            // Tickets get their own wider rectangular
                            // page (route, date/time, QR, location +
                            // map) — see WalletTicketPage. Cards keep
                            // the existing card-on-info-panel layout.
                            if (card.isTicket) {
                                WalletTicketPage(
                                    ticket = card,
                                    onBack = { mode = WalletMode.Idle },
                                )
                            } else {
                                WalletFullPage(
                                    card   = card,
                                    onBack = { mode = WalletMode.Idle },
                                )
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
                        WalletTab.Calendar -> WalletCalendarView(
                            tickets     = cardsForTab,
                            onTicketTap = { mode = WalletMode.Full(it.id) },
                        )
                        // Tickets tab: vertical list of "stub + info"
                        // strips, NOT the card-stack roll. Tap a strip
                        // → wallet Full state → WalletTicketPage. A
                        // bottom Archive button toggles between
                        // Upcoming (default) and past tickets.
                        WalletTab.Tickets -> Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                WalletTicketList(
                                    tickets     = cardsForTab,
                                    onTicketTap = { mode = WalletMode.Full(it.id) },
                                )
                            }
                            WalletArchiveToggle(
                                showingArchive    = ticketsShowArchive,
                                upcomingCount     = upcomingCount,
                                archiveCount      = pastCount,
                                onToggle          = { ticketsShowArchive = !ticketsShowArchive },
                            )
                        }
                        else -> WalletDeck(
                            cards        = cardsForTab,
                            mode         = m,
                            onModeChange = { mode = it },
                            onAddTap     = { showAddSheet = true },
                            onOpenVcard  = onOpenVcard,
                            showAddTile  = tab == WalletTab.Cards,
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
