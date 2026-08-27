package com.diegonmarcos.superapp.wallet

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

/** Bottom sheet shown when the user taps the synthetic "Add card" tile.
 *  Two options today:
 *    • Scan QR     — stubbed (camera scanner pending).
 *    • Import file — opens ACTION_GET_CONTENT. .vcf parses inline
 *      (vCard 3.0 → contact card). .pkpass / .pass accepted but stubbed
 *      with a "coming soon" toast — APK doesn't ship a zip-parser yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletAddSheet(
    onDismiss: () -> Unit,
    onCardImported: (WalletStore.Card) -> Unit,
) {
    val ctx = LocalContext.current

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment.orEmpty()
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull().orEmpty()

        when {
            text.contains("BEGIN:VCARD", ignoreCase = true) -> {
                val card = parseVcard(text)
                if (card != null) onCardImported(card)
                else Toast.makeText(
                    ctx,
                    "Could not parse vCard — needs at least an FN: line.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            name.endsWith(".pkpass", ignoreCase = true)
                || name.endsWith(".pass",   ignoreCase = true) -> {
                Toast.makeText(
                    ctx,
                    "Apple / Google Wallet pass import is coming soon.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            else -> {
                Toast.makeText(
                    ctx,
                    "Unsupported file. Supported now: .vcf (vCard 3.0). " +
                        "Coming soon: .pkpass (Apple), .pass (Google).",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = Color(0xFF161126),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                "Add card",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            WalletAddChoice(
                title    = "Scan QR",
                subtitle = "Scan a vCard / loyalty QR with the camera",
                onClick  = {
                    Toast.makeText(
                        ctx,
                        "QR camera scanner — coming soon. Use Import file with a .vcf for now.",
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
            WalletAddChoice(
                title    = "Import file",
                subtitle = ".vcf (vCard 3.0) today · .pkpass / .pass next",
                onClick  = { pickFile.launch("*/*") },
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WalletAddChoice(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x22FFFFFF))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(subtitle, color = Color(0xAAFFFFFF), fontSize = 12.sp)
    }
}

/** Minimal vCard 3.0 parser — captures FN, EMAIL, ORG, TITLE, TEL, URL.
 *  Builds a "contact" card; kind="contact" so the deck doesn't try to
 *  overlay ProfilePrefs on it (that's reserved for kind="vcard", the
 *  user's OWN business card). Returns null when there's no FN — without
 *  a display name the card would be useless. */
internal fun parseVcard(text: String): WalletStore.Card? {
    // Unfold continuation lines per RFC 2426: lines starting with space
    // / tab are continuations of the previous logical line.
    val unfolded = text.lineSequence().fold(StringBuilder()) { acc, raw ->
        if (raw.startsWith(" ") || raw.startsWith("\t")) {
            acc.append(raw.trimStart())
        } else {
            if (acc.isNotEmpty()) acc.append('\n')
            acc.append(raw)
        }
        acc
    }.toString()

    fun line(field: String): String? =
        Regex("(?im)^${field}(?:;[^:]*)?:(.+)\$").find(unfolded)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }

    val fn    = line("FN") ?: return null
    val email = line("EMAIL")
    val org   = line("ORG")
    val title = line("TITLE")
    val tel   = line("TEL")
    val url   = line("URL")

    val taglineParts = listOfNotNull(title, org).filter { it.isNotBlank() }
    val tagline = when {
        taglineParts.isNotEmpty() -> taglineParts.joinToString(" · ")
        !url.isNullOrBlank()      -> url
        !email.isNullOrBlank()    -> email
        else                      -> "Contact"
    }
    val number = email ?: tel ?: ""

    return WalletStore.Card(
        id      = UUID.randomUUID().toString(),
        kind    = "contact",
        brand   = fn,
        tagline = tagline,
        accent  = 0xFF334155L,
        number  = number,
    )
}
