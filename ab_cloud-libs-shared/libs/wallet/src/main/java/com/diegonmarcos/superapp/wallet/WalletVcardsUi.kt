package com.diegonmarcos.superapp.wallet

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

/**
 * Vcards tab — one personal card per social.
 *
 * The cards are DECLARED (build.json::ui.socials), not stored: unlike every
 * other tab this one holds no WalletStore cards, because these are not things
 * the user adds to a wallet — they are the public half of the same identity,
 * mirroring the pages of front-diegonmarcos/b-Media/mySocials. Tapping one
 * opens that page, which stays the canonical profile; this is its wallet face.
 *
 * Each card is laid out as a business card rather than a list row — monogram
 * and name on the left, a small snapshot of the page on the right — because
 * that is what these are: the card you would hand someone for that profile.
 */
data class Social(
    val id: String,
    val label: String,
    val handle: String,
    val stat: String,
    val accent: Long,
    val url: String,
)

object Socials {
    /** Parsed once per process — the list is baked into BuildConfig, so it
     *  cannot change under a running app. */
    private val cached: List<Social> by lazy { parse() }

    fun all(): List<Social> = cached

    private fun parse(): List<Social> = runCatching {
        val json = String(Base64.decode(BuildConfig.UI_SOCIALS_B64, Base64.NO_WRAP))
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Social(
                id     = o.optString("id"),
                label  = o.optString("label", o.optString("id")),
                handle = o.optString("handle"),
                stat   = o.optString("stat"),
                // Long, not Int: an ARGB colour with the alpha bit set does not
                // fit a signed Int, which is why WalletStore.Card packs it the
                // same way.
                accent = o.optLong("accent", 0xFF374151L),
                url    = o.optString("url"),
            )
        }
    }.getOrDefault(emptyList())
}

/**
 * Public, not internal: Cloud Me hosts this exact composable as its Me
 * section. Cloud Wallet and Cloud Me show the same deck from the same
 * `ui.socials` list, so the tab is shared code rather than a second
 * implementation that would drift the moment one of them gained a profile.
 */
@Composable
fun WalletVcardsTab(socials: List<Social> = remember { Socials.all() }) {
    val ctx = LocalContext.current
    if (socials.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("No socials declared", fontWeight = FontWeight.SemiBold)
            Text(
                "Add entries to build.json::ui.socials",
                fontSize = 12.sp,
                color = Color(0x99FFFFFF),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(socials, key = { it.id }) { s -> VcardCard(s) { open(ctx, s.url) } }
    }
}

private fun open(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun VcardCard(s: Social, onTap: () -> Unit) {
    // `or 0xFF000000` so an entry that declares a bare RGB still paints opaque
    // instead of invisible.
    val accent = Color(s.accent or 0xFF000000L)
    Card(
        // Modifier.clickable, not the Card(onClick=) overload: that one is
        // ExperimentalMaterial3Api and nothing else in this module opts in.
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .card3d(shape = RoundedCornerShape(16.dp), elevation = 12f)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accent),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Monogram stands in for a brand logo: shipping 16 marks would mean
            // vendoring 16 trademarks into the APK for a tab that is a launcher.
            Column(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.label.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    s.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (s.handle.isNotBlank()) Text(
                    s.handle,
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp,
                    maxLines = 1,
                )
                if (s.stat.isNotBlank()) Text(
                    s.stat,
                    color = Color(0x99FFFFFF),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            Snapshot(s)
        }
    }
}

/**
 * A thumbnail of the page the card links to.
 *
 * Drawn, not bundled: the profiles are 16 separate sites and the only image any
 * of them ships in-tree is one shared portrait, so a real screenshot set would
 * be 16 assets to capture by hand and re-capture whenever a page changed. This
 * is the honest stand-in — a header band, an avatar, two text bars and a photo
 * grid, laid out from the entry's own id so no two cards look alike, and it
 * costs nothing to keep current.
 *
 * ponytail: swap the Column for an Image the day ui.socials carries a real
 * `snapshot` asset name; the card layout around it does not change.
 */
@Composable
private fun Snapshot(s: Social) {
    // Stable per social, so a card looks the same on every open.
    val seed = remember(s.id) { s.id.fold(0) { a, c -> a * 31 + c.code } and 0x7FFFFFFF }
    Column(
        modifier = Modifier
            .width(58.dp)
            .height(84.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xF2FFFFFF))
            .padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header band: the profile page's cover strip.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(13.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(s.accent or 0xFF000000L).copy(alpha = 0.65f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color(s.accent or 0xFF000000L)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Bar(width = 26.dp)
                Bar(width = 18.dp)
            }
        }
        // Photo grid — 2 or 3 rows, varying so the deck does not read as one
        // template repeated sixteen times.
        val rows = 2 + seed % 2
        repeat(rows) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(3) { c ->
                    Box(
                        modifier = Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                Color(s.accent or 0xFF000000L)
                                    .copy(alpha = 0.18f + 0.10f * ((seed shr (r * 3 + c)) and 3)),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bar(width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(Color(0x33000000)),
    )
}
