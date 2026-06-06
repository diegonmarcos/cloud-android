package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.core.WalletStore

/**
 * First Compose surface in the app. Hosts a card-stack wallet that
 * mimics Apple Wallet's behaviour:
 *   • Cards stack with a small reveal of each card behind the front.
 *   • Tap a stacked card → it expands; the deck below compresses
 *     further. Tap the expanded card → it collapses back into the
 *     stack.
 *
 * Data comes from [WalletStore] (libs:core) so producers from other
 * modules can push cards into the wallet without depending on app code.
 */
class WalletFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val cards = remember { WalletStore.all(requireContext()) }
                WalletDeck(cards)
            }
        }

    companion object { fun newInstance(): WalletFragment = WalletFragment() }
}

@Composable
private fun WalletDeck(cards: List<WalletStore.Card>) {
    // Single "expanded" card id at a time. null means deck-collapsed.
    var expandedId by remember { mutableStateOf<String?>(null) }

    // peekHeight = how many dp of each stacked card is visible beneath
    // the one in front of it. cardHeight is the full card height when
    // expanded. The visible height for non-expanded cards is just the
    // peek strip, but the actual View is full height — we use a
    // negative offset to overlap.
    val peekHeight  = 96.dp
    val cardHeight  = 200.dp
    val stackOffset = cardHeight - peekHeight   // amount each card sits BEHIND the previous

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0414))
            .verticalScroll(rememberScrollState())
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            cards.forEachIndexed { index, card ->
                val isExpanded = (expandedId == card.id)
                // Translate this card up by (index * stackOffset) when
                // the deck is collapsed; when one card is expanded, the
                // ones above it stay put and the ones below "drop" so
                // the expanded card has room to breathe.
                val targetOffset by animateDpAsState(
                    targetValue = when {
                        expandedId == null              -> -(stackOffset) * index
                        isExpanded                      -> -(stackOffset) * index
                        index > cards.indexOfFirst { it.id == expandedId } ->
                            // Below the expanded card — push down.
                            -(stackOffset) * index + cardHeight - peekHeight
                        else                            -> -(stackOffset) * index
                    },
                    animationSpec = tween(durationMillis = 260),
                    label         = "card-offset-$index",
                )
                WalletCardView(
                    card = card,
                    cardHeight = cardHeight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset(y = targetOffset)
                        .clickable {
                            expandedId = if (isExpanded) null else card.id
                        },
                )
            }
        }
    }
}

@Composable
private fun WalletCardView(
    card: WalletStore.Card,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val base = Color(card.accent.toULong().toLong())
    val accent = Color(
        red   = (base.red   + 0.15f).coerceAtMost(1f),
        green = (base.green + 0.15f).coerceAtMost(1f),
        blue  = (base.blue  + 0.15f).coerceAtMost(1f),
    )
    Box(
        modifier = modifier
            .height(cardHeight)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent, base)))
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = card.brand,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.SansSerif,
            )
            Text(
                text = card.tagline,
                color = Color(0xCCFFFFFF),
                fontSize = 13.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (card.number.isNotBlank()) {
                Text(
                    text = card.number,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (card.barcode.isNotBlank()) {
                Text(
                    text = "⌫ ${card.barcode}",
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = card.kind.uppercase(),
                color = Color(0x88FFFFFF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}
