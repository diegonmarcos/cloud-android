package com.diegonmarcos.superapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.core.WalletStore

private val BgColor = Color(0xFF0B0414)

/** Full-page card view — card on top, info panel beneath. Back button
 *  in the top bar returns to the deck (NOT to the host section). */
@Composable
internal fun WalletFullPage(card: WalletStore.Card, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Brand from canonical QrcodesData; subtitle stays a short role
    // label here too. WalletCardView is rendered with isExpanded=true
    // below, which inflates the full multi-line identity inside the
    // card body via QrcodesData.fullCardTagline().
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        WalletTopBar(left = "← Back", onLeft = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(220.dp),
        ) {
            WalletCardView(card = resolved, isExpanded = true, modifier = Modifier.fillMaxSize())
        }
        Spacer(modifier = Modifier.height(20.dp))
        WalletInfoPanel(card = resolved)
    }
}

@Composable
private fun WalletInfoPanel(card: WalletStore.Card) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Info", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        WalletInfoRow("Type", card.kind.uppercase())
        if (card.number.isNotBlank())  WalletInfoRow("Number",  card.number)
        if (card.barcode.isNotBlank()) WalletInfoRow("Barcode", card.barcode)
        WalletInfoRow("Added",     "—")
        WalletInfoRow("Last used", "—")
        WalletInfoRow("Used",      "0 times")
        Spacer(modifier = Modifier.height(20.dp))
        Text("Activity", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "No activity yet — usage logging lands in a future update.",
            color = Color(0x88FFFFFF),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun WalletInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Color(0xAAFFFFFF), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White,       fontSize = 14.sp, fontFamily = FontFamily.Monospace)
    }
}

/** Per-card config — editable brand / tagline / number. Save persists
 *  via [WalletStore.update] (caller wires that through). */
@Composable
internal fun WalletConfigPage(
    card: WalletStore.Card,
    onBack: () -> Unit,
    onSave: (WalletStore.Card) -> Unit,
) {
    var brand   by remember(card.id) { mutableStateOf(card.brand) }
    var tagline by remember(card.id) { mutableStateOf(card.tagline) }
    var number  by remember(card.id) { mutableStateOf(card.number) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 32.dp),
    ) {
        WalletTopBar(
            left   = "← Cancel",
            right  = "Save",
            onLeft = onBack,
            onRight = {
                onSave(card.copy(brand = brand, tagline = tagline, number = number))
                onBack()
            },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Card settings",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        WalletConfigField(label = "Brand",   value = brand,   onChange = { brand = it })
        WalletConfigField(label = "Tagline", value = tagline, onChange = { tagline = it })
        WalletConfigField(label = "Number",  value = number,  onChange = { number = it })
    }
}

@Composable
private fun WalletConfigField(label: String, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, color = Color(0xAAFFFFFF), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value         = value,
            onValueChange = onChange,
            textStyle     = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush   = SolidColor(Color(0xFF7C3AED)),
            modifier      = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
    }
}

/** Shared top bar for Full / Config — left "← Back" + optional right
 *  action text (Save in Config, none in Full). */
@Composable
private fun WalletTopBar(
    left: String,
    onLeft: () -> Unit,
    right: String? = null,
    onRight: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            left,
            color = Color.White,
            fontSize = 16.sp,
            modifier = Modifier.clickable(onClick = onLeft).padding(8.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        if (right != null && onRight != null) {
            Text(
                right,
                color = Color(0xFF7C3AED),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.clickable(onClick = onRight).padding(8.dp),
            )
        }
    }
}
