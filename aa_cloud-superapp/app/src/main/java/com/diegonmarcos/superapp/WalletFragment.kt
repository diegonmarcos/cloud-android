package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.diegonmarcos.superapp.core.WalletStore

/**
 * Wallet — Compose surface. State machine across four modes:
 *   • Idle      — deck of cards in the circular swipe roll.
 *   • Selected  — one card lifted, the rest fade. Tap outside → Idle.
 *   • Full      — full-page card view with info panel + Back.
 *   • Config    — per-card edit screen (reached via the (i) overlay).
 *
 * The deck is a LazyColumn with [Int.MAX_VALUE] item slots and modulo
 * lookup, giving an effectively circular linked-list scroll without
 * piling 6 cards × 1000 copies into memory. A synthetic "Add card"
 * sentinel is appended at the end of the user's cards so it rides
 * the same loop and is reachable from any cycle.
 *
 * Storage: [WalletStore] (libs:core) is the only persistence layer.
 */
class WalletFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { WalletScreen() }
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
private fun WalletScreen() {
    val ctx = LocalContext.current
    var cards by remember { mutableStateOf(WalletStore.all(ctx)) }
    var mode  by remember { mutableStateOf<WalletMode>(WalletMode.Idle) }
    var showAddSheet by remember { mutableStateOf(false) }

    val refresh: () -> Unit = { cards = WalletStore.all(ctx) }
    fun cardOrIdle(id: String): WalletStore.Card? {
        val c = cards.firstOrNull { it.id == id }
        if (c == null) mode = WalletMode.Idle
        return c
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
        when (val m = mode) {
            is WalletMode.Full -> {
                cardOrIdle(m.cardId)?.let { card ->
                    WalletFullPage(card = card, onBack = { mode = WalletMode.Idle })
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
            else -> WalletDeck(
                cards        = cards,
                mode         = m,
                onModeChange = { mode = it },
                onAddTap     = { showAddSheet = true },
            )
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
