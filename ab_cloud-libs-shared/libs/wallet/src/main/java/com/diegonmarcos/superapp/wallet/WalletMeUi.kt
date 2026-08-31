package com.diegonmarcos.superapp.wallet

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 * Me tab — one card per social.
 *
 * The cards are DECLARED (build.json::ui.socials), not stored: unlike every
 * other tab this one holds no WalletStore cards, because these are not things
 * the user adds to a wallet — they are the public half of the same identity,
 * mirroring the pages of front-diegonmarcos/b-Media/mySocials. Tapping one
 * opens that page, which stays the canonical profile; this is its wallet face.
 */
internal data class Social(
    val id: String,
    val label: String,
    val handle: String,
    val stat: String,
    val accent: Long,
    val url: String,
)

internal object Socials {
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

@Composable
internal fun WalletMeTab(socials: List<Social> = remember { Socials.all() }) {
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
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(socials, key = { it.id }) { s -> SocialCard(s) { open(ctx, s.url) } }
    }
}

private fun open(ctx: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
private fun SocialCard(s: Social, onTap: () -> Unit) {
    // `or 0xFF000000` so an entry that declares a bare RGB still paints opaque
    // instead of invisible.
    val accent = Color(s.accent or 0xFF000000L)
    Card(
        // Modifier.clickable, not the Card(onClick=) overload: that one is
        // ExperimentalMaterial3Api and nothing else in this module opts in.
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = accent),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    s.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (s.handle.isNotBlank()) Text(
                    s.handle,
                    color = Color(0xCCFFFFFF),
                    fontSize = 13.sp,
                )
                if (s.stat.isNotBlank()) Text(
                    s.stat,
                    color = Color(0x99FFFFFF),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
