package com.diegonmarcos.superapp.wallet

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Dispatchers ─────────────────────────────────────────────────────────────

@Composable
internal fun IdCardView(card: WalletStore.Card, modifier: Modifier = Modifier) {
    when (card.kind) {
        "id_passport_br" -> PassportBrCard(card, modifier)
        "id_passport_es" -> PassportEsCard(card, modifier)
        "id_dni"         -> DniCard(card, modifier)
        "id_nie"         -> NieCard(card, modifier)
        "id_tsi"         -> TsiCard(card, modifier)
        "id_cin_br"      -> CinBrCard(card, modifier)
        "id_driving_es"  -> DriveEsCard(card, modifier)
        "id_boat_es"     -> BoatEsCard(card, modifier)
        else             -> GenericIdCard(card, modifier)
    }
}

/**
 * 3D-r: raw WebGL via AndroidView(WebView).
 * Page loads ONCE; card changes are sent via window.setCard() JS call only —
 * no URL reloads, no WebGL context leak.
 */
@Composable
internal fun IdCard3DReactView(card: WalletStore.Card, modifier: Modifier = Modifier) {
    val kind = card.kind
    AndroidView(
        factory = { ctx ->
            // Capture mutable state in closure so update() can mutate without reload
            var currentKind = kind
            var pageReady = false
            WebView(ctx).also { wv ->
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = true
                @Suppress("SetJavaScriptEnabled")
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        LogStore.add("[NAV]  onPageFinished: $url")
                        pageReady = true
                        wv.evaluateJavascript("window.setCard('$currentKind')", null)
                    }
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, url: String?) {
                        Log.e("Wallet3D", "[ERROR] $errorCode: $description @ $url")
                        LogStore.add("[ERROR] WebViewClient $errorCode: $description")
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                        val level = when (msg.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR   -> "[ERROR]"
                            ConsoleMessage.MessageLevel.WARNING -> "[WARN] "
                            ConsoleMessage.MessageLevel.DEBUG   -> "[DEBUG]"
                            else                               -> "[LOG]  "
                        }
                        val line = "$level ${msg.message()} (${msg.sourceId().substringAfterLast('/')}:${msg.lineNumber()})"
                        Log.d("Wallet3D", line)
                        LogStore.add(line)
                        return true
                    }
                }
                // Store setter in tag so update() can call setCard without reloading the page
                wv.tag = fun(k: String) {
                    currentKind = k
                    if (pageReady) wv.evaluateJavascript("window.setCard('$k')", null)
                }
                LogStore.add("[NAV]  loadUrl: file:///android_asset/wallet3d/index.html")
                wv.loadUrl("file:///android_asset/wallet3d/index.html")
            }
        },
        update = { wv ->
            @Suppress("UNCHECKED_CAST")
            (wv.tag as? ((String) -> Unit))?.invoke(kind)
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
        },
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
    )
}

/**
 * 3D-f: Google Filament via SceneView (Vulkan/OpenGL native).
 * Load models/blank_passport.glb; swap cover texture per card.kind at runtime.
 * See docs/3d-view-design.md for full material + shader mapping.
 */
@Composable
internal fun IdCard3DFilamentView(card: WalletStore.Card, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0A0D14)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("3D-f", color = Color(0x88FFFFFF), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(card.kind, color = Color(0x44FFFFFF), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text("Filament native stub", color = Color(0x33FFFFFF), fontSize = 9.sp)
        }
    }
}

@Composable
internal fun DocCardView(card: WalletStore.Card, modifier: Modifier = Modifier) {
    when (card.kind) {
        "doc_birth_es" -> BirthCertEsCard(card, modifier)
        "doc_birth_br" -> BirthCertBrCard(card, modifier)
        else           -> GenericDocCard(card, modifier)
    }
}

// ─── Colors ──────────────────────────────────────────────────────────────────

private val PaperCream   = Color(0xFFF5F0E8)
private val InkDark      = Color(0xFF111122)
private val InkMid       = Color(0xFF555577)
private val CardWhite    = Color(0xFFF4F4EC)
private val EuBlue       = Color(0xFF003399)
private val EuGold       = Color(0xFFFFCC00)
private val EsRed        = Color(0xFFAA151B)
private val EsYellow     = Color(0xFFF1BF00)
private val PassportRed  = Color(0xFF6E0010)
private val GoldText     = Color(0xFFC9A84C)
private val BrGreen      = Color(0xFF009C3B)
private val BrYellow     = Color(0xFFFEDD00)
private val PassportNavy = Color(0xFF022050)
private val TsiBlue      = Color(0xFF1E40AF)
private val DrivePink    = Color(0xFFF5C6C0)  // EU driving licence salmon-pink
private val DriveRed     = Color(0xFFC0392B)  // DGT accent
private val BoatBlue     = Color(0xFF0A4B8C)  // maritime navy
private val BoatSeaBlue  = Color(0xFFD8EEFF)  // sea background
private val HealthGreen  = Color(0xFF059669)
private val InkNavy      = Color(0xFF1E3A5F)
private val BrInkNavy    = Color(0xFF0A3161)

// ─── Shared card shell ───────────────────────────────────────────────────────

@Composable
private fun CardShell(modifier: Modifier, background: Brush, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(background),
    ) { content() }
}

// ─── Shared primitives ────────────────────────────────────────────────────────

/** Label + value field on ink-on-paper documents */
@Composable
private fun DocField(label: String, value: String, labelSp: Float = 5f, valueSp: Float = 8.5f, valueColor: Color = InkDark) {
    Column(modifier = Modifier.padding(bottom = 2.5.dp)) {
        Text(label, color = InkMid, fontSize = labelSp.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
        Text(value, color = valueColor, fontSize = valueSp.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** EU/Country badge: blue rect with ★★★/CC/★★★ */
@Composable
private fun EuBadge(country: String = "ES") {
    Box(
        modifier = Modifier.size(26.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(EuBlue),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("★★★", color = EuGold, fontSize = 3.5.sp, letterSpacing = (-0.3).sp, lineHeight = 5.sp)
            Text(country, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 8.sp)
            Text("★★★", color = EuGold, fontSize = 3.5.sp, letterSpacing = (-0.3).sp, lineHeight = 5.sp)
        }
    }
}

/** Laser-engraved B&W photo placeholder */
@Composable
private fun PhotoBox(modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(3.dp)).background(
            Brush.linearGradient(listOf(Color(0xFFBBBBBB), Color(0xFFDDDDDD)))
        ),
        contentAlignment = Alignment.Center,
    ) { Text("👤", fontSize = 20.sp) }
}

/** e-passport biometric chip badge */
@Composable
private fun ChipBadge() {
    Box(
        modifier = Modifier.size(14.dp, 10.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xFF888888)),
        contentAlignment = Alignment.Center,
    ) { Text("e", color = Color.White, fontSize = 5.5.sp, fontWeight = FontWeight.Bold) }
}

// ─── Flag strips ─────────────────────────────────────────────────────────────

@Composable
private fun SpanishFlagStrip(height: Int = 8) {
    Row(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        Box(modifier = Modifier.weight(1f).fillMaxSize().background(EsRed))
        Box(modifier = Modifier.weight(2f).fillMaxSize().background(EsYellow))
        Box(modifier = Modifier.weight(1f).fillMaxSize().background(EsRed))
    }
}

@Composable
private fun BrazilFlagStrip(height: Int = 3) {
    Row(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        Box(modifier = Modifier.weight(3f).fillMaxSize().background(BrGreen))
        Box(modifier = Modifier.weight(1f).fillMaxSize().background(BrYellow))
        Box(modifier = Modifier.weight(3f).fillMaxSize().background(BrGreen))
    }
}

// ─── MRZ builders ─────────────────────────────────────────────────────────────

private fun buildPassportMrz1(country: String, holder: String): String {
    val clean = holder.uppercase().replace(Regex("[^A-Z ]"), "").trim()
    val parts = clean.split(Regex("\\s+"))
    val sn = parts.firstOrNull().orEmpty()
    val gn = parts.drop(1).joinToString("<").ifBlank { "<" }
    return "P<$country$sn<<$gn".padEnd(44, '<').take(44)
}

private fun buildPassportMrz2(number: String, country: String, expiry: String): String {
    val num = number.filter { it.isLetterOrDigit() }.uppercase().padEnd(9, '<').take(9)
    val nat = country.padEnd(3, '<').take(3)
    val digits = expiry.filter { it.isDigit() }
    val exp = if (digits.length >= 8) digits.substring(2, 4) + digits.substring(4, 6) + digits.substring(6, 8) else "300101"
    return "${num}0${nat}0000000<${exp}0<<<<<<<<<<<<<<00".take(44)
}

// ─── Spanish Passport — real biodata page layout ──────────────────────────────
// Real design: cream paper, NO dark band — title text printed on cream, thin flag strip,
// bilingual fields, MRZ bottom. Same as actual interior biodata page.

@Composable
private fun PassportEsCard(card: WalletStore.Card, modifier: Modifier) {
    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val birth = parts.getOrElse(1) { "" }.removePrefix("Nasc. ")
    val expiry = parts.getOrElse(2) { "" }.removePrefix("Vál. ")
    val mrz1 = buildPassportMrz1("ESP", "$surnames $given")
    val mrz2 = buildPassportMrz2(card.number, "ESP", expiry)

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(PaperCream)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thin burgundy edge + flag — no full dark band
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(PassportRed))
            SpanishFlagStrip(3)
            // Title printed directly on cream (matching real biodata page)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EuBadge("ES")
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("UNIÓN EUROPEA · ESPAÑA", color = InkDark, fontSize = 6.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Medium)
                    Text("PASAPORTE · PASSPORT", color = InkDark, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                }
                Text(card.number, color = InkDark, fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            // Body: photo left, bilingual fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 2.dp)) {
                Column(modifier = Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(42.dp).weight(1f))
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChipBadge()
                        Spacer(Modifier.width(2.dp))
                        Text("⊞", color = Color(0xFF666666), fontSize = 8.sp)
                    }
                }
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("APELLIDOS / SURNAME", surnames.ifBlank { "—" })
                    DocField("NOMBRE / GIVEN NAMES", given.ifBlank { "—" })
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.3f)) { DocField("NACIONALIDAD", "ESPAÑOLA") }
                        Column(Modifier.weight(0.7f)) { DocField("SEXO / SEX", "M") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("F. NACIMIENTO", birth.ifBlank { "—" }) }
                        Column(Modifier.weight(1f)) { DocField("CADUCIDAD", expiry.ifBlank { "—" }) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.1f)) { DocField("Nº PASAPORTE", card.number.ifBlank { "—" }, valueSp = 9f) }
                        Column(Modifier.weight(0.9f)) { DocField("AUTORIDAD", "DGPYE") }
                    }
                }
            }
            // MRZ zone
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(mrz1, color = Color(0xFF7ABF7A), fontSize = 5.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, maxLines = 1)
                Text(mrz2, color = Color(0xFF7ABF7A), fontSize = 5.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, maxLines = 1)
            }
        }
    }
}

// ─── Brazilian Passport — real biodata page layout ────────────────────────────
// Real design: light blue-grey paper, NO dark band — title printed on light bg,
// coat of arms + "REPÚBLICA FEDERATIVA DO BRASIL" text, bilingual fields, MRZ bottom.

@Composable
private fun PassportBrCard(card: WalletStore.Card, modifier: Modifier) {
    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val birth = parts.getOrElse(1) { "" }.removePrefix("Nasc. ")
    val expiry = parts.getOrElse(2) { "" }.removePrefix("Vál. ")
    val mrz1 = buildPassportMrz1("BRA", "$surnames $given")
    val mrz2 = buildPassportMrz2(card.number, "BRA", expiry)

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF0F5F8))  // light blue-grey paper
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Thin green + flag strip — no dark header band
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(BrGreen))
            BrazilFlagStrip(3)
            // Title printed on light background (matching real biodata page)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Coat of arms simplified
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(BrGreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(BrYellow), contentAlignment = Alignment.Center) {
                        Text("◆", color = BrGreen, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("REPÚBLICA FEDERATIVA DO BRASIL", color = InkDark, fontSize = 5.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                    Text("PASSAPORTE · PASSPORT", color = InkDark, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChipBadge()
                    Spacer(Modifier.width(3.dp))
                    Text(card.number, color = InkDark, fontSize = 7.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
            // Body
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp)) {
                Column(modifier = Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(42.dp).weight(1f))
                    Spacer(Modifier.height(3.dp))
                    Box(
                        modifier = Modifier.size(16.dp, 11.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55D4AF37)),
                        contentAlignment = Alignment.Center,
                    ) { Text("▣", color = GoldText, fontSize = 7.sp) }
                }
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("SOBRENOME / SURNAME", surnames.ifBlank { "—" })
                    DocField("NOME / GIVEN NAMES", given.ifBlank { "—" })
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.3f)) { DocField("NACIONALIDADE", "BRASILEIRA") }
                        Column(Modifier.weight(0.7f)) { DocField("SEXO / SEX", "M") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("DATA NASC.", birth.ifBlank { "—" }) }
                        Column(Modifier.weight(1f)) { DocField("VALIDADE", expiry.ifBlank { "—" }) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.1f)) { DocField("NÚMERO", card.number.ifBlank { "—" }, valueSp = 9f) }
                        Column(Modifier.weight(0.9f)) { DocField("AUTORIDADE", "DPAS/DPF") }
                    }
                }
            }
            Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(mrz1, color = BrGreen.copy(alpha = 0.65f), fontSize = 5.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, maxLines = 1)
                Text(mrz2, color = BrGreen.copy(alpha = 0.65f), fontSize = 5.5.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp, maxLines = 1)
            }
        }
    }
}

// ─── DNI 4.0 — real EU-regulation card format ────────────────────────────────
// Real design (downloaded es_dni_front.webp):
// Cobalt-blue header bar full-width: EU badge left + "REINO DE ESPAÑA · DOCUMENTO NACIONAL
// DE IDENTIDAD" center + Spanish flag (red/yellow/red) right.
// Below header on pale ochre: "DNI 99999999R" large, then photo left / fields right.
// Fields: APELLIDOS / NOMBRE / SEXO+NACIONALIDAD+NACIMIENTO / EMISIÓN+VALIDEZ / NUM SOPORTE / FIRMA.
// Bottom footer bar: "DOCUMENTO NACIONAL DE IDENTIDAD · NATIONAL IDENTITY CARD".

@Composable
private fun DniCard(card: WalletStore.Card, modifier: Modifier) {
    val DniBlue   = Color(0xFF1A4395)   // cobalt blue header — real DNI
    val DniOchre  = Color(0xFFFCF3DA)   // pale ochre card body

    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry = parts.getOrElse(1) { "" }.removePrefix("Vál. ")

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(DniOchre)
    ) {
        // Coat of arms watermark — faint red, right-center
        Box(modifier = Modifier.fillMaxSize().padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
            Text("⊕", color = Color(0x14AA151B), fontSize = 80.sp)
        }
        Column(modifier = Modifier.fillMaxSize()) {
            // Blue header band — EU badge | title | Spanish flag (matches real DNI)
            Row(
                modifier = Modifier.fillMaxWidth().background(DniBlue).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EuBadge("ES")
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("REINO DE ESPAÑA", color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    Text("DOCUMENTO NACIONAL DE IDENTIDAD", color = Color(0xCCFFFFFF), fontSize = 4.sp, letterSpacing = 0.3.sp)
                }
                // Spanish flag — red / yellow / red strips
                Column(modifier = Modifier.width(20.dp)) {
                    Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFFAA151B)))
                    Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFFF1BF00)))
                    Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFFAA151B)))
                }
            }
            // DNI number — prominent below header, matching real card
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                Text(
                    "DNI ${card.number}",
                    color = InkDark,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }
            // Body: photo left | fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp)) {
                Column(modifier = Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(46.dp).weight(1f))
                    Spacer(Modifier.height(3.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x55000000)))
                    Spacer(Modifier.height(1.dp))
                    Text("FIRMA", color = InkMid, fontSize = 4.sp)
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("APELLIDOS", surnames.ifBlank { "—" })
                    DocField("NOMBRE", given.ifBlank { "—" })
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(0.6f)) { DocField("SEXO", "M") }
                        Column(Modifier.weight(1.1f)) { DocField("NACIONALIDAD", "ESP") }
                        Column(Modifier.weight(1.3f)) { DocField("NACIMIENTO", "01 01 1990") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("EMISIÓN", "—") }
                        Column(Modifier.weight(1f)) { DocField("VALIDEZ", expiry.ifBlank { "—" }) }
                    }
                    DocField("NUM. SOPORTE", "CAA000000")
                }
            }
            // Bottom footer bar — matches real DNI
            Box(
                modifier = Modifier.fillMaxWidth().background(DniBlue).padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "DOCUMENTO NACIONAL DE IDENTIDAD · NATIONAL IDENTITY CARD",
                    color = Color.White, fontSize = 4.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─── NIE / TIE — Tarjeta de Identidad de Extranjero (EU Residence Permit) ────
// Source: downloaded yourspanishpaperwork.com/images/TIE.jpg (152KB specimen)
// Real design: mint-green polycarbonate card with security map print in background.
// Top-left: "ESP" + biometric chip badge. Top-center: "PERMISO DE RESIDENCIA".
// Top-right: card number (monospace, large) + small secondary photo below.
// Below ESP badge: NIE number in smaller text.
// Body left (≈40%): large laser-engraved B&W photo, holographic circle overlay.
// Body right: bilingual APELLIDOS/SEXO+NACION+BIRTH DATE/TIPO PERMISO+VALIDEZ/NIE large + sig.
// Bottom green bar: "RESIDENCE PERMIT / TITRE DE SEJOUR"

@Composable
private fun NieCard(card: WalletStore.Card, modifier: Modifier) {
    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry = parts.getOrElse(1) { "" }.removePrefix("Vál. ")
    val TieMint    = Color(0xFFC2E8DC)   // real TIE mint-green polycarbonate
    val TieDark    = Color(0xFF142814)   // deep text color on mint card
    val TieBarGrn  = Color(0xFF88C4AE)   // bottom bar — slightly deeper mint

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(TieMint),
    ) {
        // Security map tint (map-contour pattern in background)
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x0A006040), Color(0x00C2E8DC), Color(0x08004870))),
        ))
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top header ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // "ESP" + biometric chip badge (top-left of real TIE)
                Box(
                    modifier = Modifier.size(36.dp, 26.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFEEF5FF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ESP", color = Color(0xFF003399), fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp)
                        Text("⊡", color = Color(0xFF1A8040), fontSize = 7.sp, lineHeight = 8.sp)
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "PERMISO DE RESIDENCIA",
                    color = TieDark, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                    modifier = Modifier.weight(1f),
                )
                // Card number + small 2nd photo (top-right of real TIE)
                Column(horizontalAlignment = Alignment.End) {
                    Text(card.number, color = TieDark, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier.size(20.dp, 25.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF8CBCAA)),
                        contentAlignment = Alignment.Center,
                    ) { Text("👤", fontSize = 10.sp) }
                }
            }
            // NIE number below ESP badge
            Text(
                card.number,
                color = TieDark.copy(alpha = 0.65f), fontSize = 5.5.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(start = 7.dp),
            )
            // ── Body: large photo left | fields right ────────────────────────
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 3.dp, bottom = 2.dp)) {
                // Large laser-engraved photo (≈40% width) with holographic circle
                Box(modifier = Modifier.width(58.dp).fillMaxHeight()) {
                    PhotoBox(modifier = Modifier.width(54.dp).fillMaxHeight())
                    Box(
                        modifier = Modifier.size(34.dp).align(Alignment.Center).clip(CircleShape)
                            .background(Color(0x1A0060A0)),
                        contentAlignment = Alignment.Center,
                    ) { Text("⊕", color = Color(0x330060A0), fontSize = 20.sp) }
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    // APELLIDOS / SURNAMES
                    Text("APELLIDOS Nombre / SURNAMES Forenames", color = TieDark.copy(alpha = 0.55f), fontSize = 4.sp)
                    Text(
                        "$surnames $given".trim().ifBlank { "—" },
                        color = TieDark, fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 9.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    // SEXO | NATIONALITY | BIRTH DATE row
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(0.45f)) {
                            Text("SEXO / SEX", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                            Text("M", color = TieDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(0.85f)) {
                            Text("NACION. / NATIONALITY", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                            Text("BRA", color = TieDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1.1f)) {
                            Text("FECHA NAC. / BIRTH DATE", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                            Text("01 01 1990", color = TieDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    // TIPO DE PERMISO | VALIDEZ row
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.3f)) {
                            Text("TIPO DE PERMISO / TYPE OF PERMIT", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                            Text("RESIDENCIA", color = TieDark, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                            Text("TRABAJO Y RESIDENCIA", color = TieDark, fontSize = 4.5.sp)
                        }
                        Column(Modifier.weight(0.7f)) {
                            Text("VALIDEZ TARJETA / CARD EXPIRY", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                            Text(expiry.ifBlank { "—" }, color = TieDark, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    // OBSERVACIONES — present on real TIE card (usually blank in specimen)
                    Text("OBSERVACIONES / REMARKS", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                    Text("—", color = TieDark, fontSize = 5.sp)
                    Spacer(Modifier.height(2.dp))
                    // NIE — large and prominent (same as real card)
                    Text("NIE / PERSONAL NUMBER", color = TieDark.copy(alpha = 0.55f), fontSize = 3.5.sp)
                    Text(
                        "NIE: ${card.number}",
                        color = TieDark, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Signature line + reference number (bottom-right of real TIE)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Box(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                            Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x66000000)).align(Alignment.BottomCenter))
                        }
                        Text("123456", color = TieDark.copy(alpha = 0.65f), fontSize = 7.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            // Bottom green bar
            Box(
                modifier = Modifier.fillMaxWidth().background(TieBarGrn).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text("RESIDENCE PERMIT / TITRE DE SÉJOUR", color = TieDark, fontSize = 5.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
            }
        }
    }
}

// ─── CIN (Carteira de Identidade Nacional) Brazil ────────────────────────────
// Real design (Wikimedia image): light/white card, green top header bar,
// "REPÚBLICA FEDERATIVA DO BRASIL" + "CARTEIRA DE IDENTIDADE" title,
// Brazil flag strip, photo left, dark-ink fields right (NOME/FILIAÇÃO/CPF/DOB),
// QR code bottom-right, MRZ at bottom. NOT dark-green all-over.

@Composable
private fun CinBrCard(card: WalletStore.Card, modifier: Modifier) {
    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val birth = parts.getOrElse(1) { "" }.removePrefix("Nasc. ")
    val expiry = parts.getOrElse(2) { "" }.removePrefix("Vál. ")

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF5F5F0))  // light cream/white — real CIN body is NOT dark green
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Green header bar + coat of arms + title
            Box(modifier = Modifier.fillMaxWidth().background(BrGreen).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF005C1F)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(BrYellow), contentAlignment = Alignment.Center) {
                            Text("◆", color = BrGreen, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("REPÚBLICA FEDERATIVA DO BRASIL", color = Color.White, fontSize = 5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                        Text("CARTEIRA DE IDENTIDADE NACIONAL", color = BrYellow, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            BrazilFlagStrip(2)
            // Body: photo left | dark-ink fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(44.dp).weight(1f))
                    Spacer(Modifier.height(4.dp))
                    // QR code indicator
                    Box(
                        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF222222)),
                        contentAlignment = Alignment.Center,
                    ) { Text("▦", color = Color.White, fontSize = 15.sp) }
                }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("NOME", "$given $surnames".trim().ifBlank { "—" })
                    DocField("FILIAÇÃO", "—")
                    DocField("DATA DE NASCIMENTO", birth.ifBlank { "—" })
                    // CPF — primary national identifier, printed large
                    Column(modifier = Modifier.padding(bottom = 2.dp)) {
                        Text("CPF", color = InkMid, fontSize = 5.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
                        Text(card.number.ifBlank { "—" }, color = InkDark, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("VALIDADE: ${expiry.ifBlank { "INDEFINIDA" }}", color = InkMid, fontSize = 5.5.sp)
                }
            }
        }
    }
}

// ─── TSI (Tarjeta Sanitaria Individual) ──────────────────────────────────────
// Real design (Wikipedia photo of Andalucia physical card — same layout for all regions):
// Top bold-color band: "TARJETA SANITARIA INDIVIDUAL" in white bold + SNS cross logo
// Body: cream paper with faint wave security print; gold chip left with direction arrow
// Center: NUSS number (primary), CIP card number, DNI/NIE fields, FARM-P designation
// Right: regional coat of arms placeholder
// Bottom band: same color — regional authority + Consejería de Sanidad
// Madrid/national variant: SNS blue (#006699) vs Andalucia green

// From actual Comunidad de Madrid official card image (comunidad.madrid/servicios/salud/tarjeta-sanitaria):
// White top section (~38%): SaludMadrid logo left (blue cross + red Madrid star badge + "SaludMadrid" text),
// right side has small Madrid star emblem + "Comunidad de Madrid" + large blue "TARJETA SANITARIA".
// Bottom ~62%: vivid #009FCA blue band, white monospace data rows:
//   Row1: CIP code (6-digit prefix + MDMD + long number + 2 digits)
//   Row2: NUSS 12-digit national SSN
//   Row3: 8-digit field (DOB or expiry ref)
//   Row4: NOMBRE APELLIDO APELLIDO (patient name, bold)
@Composable
private fun TsiCard(card: WalletStore.Card, modifier: Modifier) {
    val parts      = card.tagline.split(" · ")
    // tagline: "SURNAME, NAME · NUSS: 000000000000 · Comunidad de Madrid"
    val holderRaw  = parts.getOrElse(0) { "" }
    val nussPart   = parts.firstOrNull { it.startsWith("NUSS: ") }
    val nuss       = nussPart?.removePrefix("NUSS: ")?.ifBlank { "—" } ?: "—"
    val holderName = if (nuss == "—") holderRaw else holderRaw
    val ci         = holderName.indexOf(',')
    val surnames   = (if (ci >= 0) holderName.substring(0, ci) else holderName).trim().uppercase()
    val given      = (if (ci >= 0) holderName.substring(ci + 1) else "").trim().uppercase()
    val cipDisplay = card.number.ifBlank { "—" }
    val SaludBlue  = Color(0xFF009FCA)   // official SaludMadrid brand blue
    val MadridRed  = Color(0xFFAA151B)   // Comunidad de Madrid red

    Box(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF0F0EE)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── White header ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // SaludMadrid composite logo (left)
                Column(modifier = Modifier.width(66.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("✚", color = SaludBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier.size(20.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(MadridRed),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 7 stars (Madrid Comunidad emblem) — rendered as 3+4 tiny star rows
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("★★★", color = Color.White, fontSize = 4.sp, lineHeight = 5.sp)
                                Text("★★★★", color = Color.White, fontSize = 4.sp, lineHeight = 5.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row {
                        Text("Salud", color = SaludBlue, fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                        Text("Madrid", color = Color(0xFF222222), fontSize = 8.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                // Right: emblem + title
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(14.dp, 12.dp).clip(RoundedCornerShape(1.dp)).background(MadridRed),
                            contentAlignment = Alignment.Center,
                        ) { Text("★★★★", color = Color.White, fontSize = 3.sp, lineHeight = 4.sp) }
                        Spacer(Modifier.width(4.dp))
                        Text("Comunidad de Madrid", color = Color(0xFF1A1A1A), fontSize = 7.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("TARJETA SANITARIA", color = SaludBlue, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.2.sp)
                }
            }
            // ── Blue data band ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SaludBlue)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                // CIP code
                Text(cipDisplay, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(4.dp))
                // NUSS
                Text(nuss, color = Color.White, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                Spacer(Modifier.height(4.dp))
                // Auxiliary field (DOB/expiry ref — value not in current wallet.json; shown as placeholder)
                Text("— — — — — — — —", color = Color(0xCCFFFFFF), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                // Patient name — SURNAME GIVEN (matches real TSI card name row)
                Text("$surnames $given".trim(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ─── Generic ID fallback ──────────────────────────────────────────────────────

@Composable
private fun GenericIdCard(card: WalletStore.Card, modifier: Modifier) {
    val base = Color(card.accent.toULong().toLong())
    CardShell(modifier, Brush.linearGradient(listOf(base, Color(base.red * 0.7f, base.green * 0.7f, base.blue * 0.7f)))) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("ID", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(card.brand, color = Color(0xCCFFFFFF), fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(card.number, color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            Text(card.tagline, color = Color(0x99FFFFFF), fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ─── Birth Cert Spain ─────────────────────────────────────────────────────────

@Composable
internal fun BirthCertEsCard(card: WalletStore.Card, modifier: Modifier) {
    Box(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF5F0E8)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().background(InkNavy).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Column {
                    Text("REGISTRO CIVIL", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(card.brand, color = Color(0xCCFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            SpanishFlagStrip(4)
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Text("Núm. de Registro", color = InkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                Text(card.number, color = InkNavy, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Datos", color = InkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                card.tagline.split(" · ").forEach { line ->
                    Text(line.trim(), color = InkNavy, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0x22162A4A)),
                        contentAlignment = Alignment.Center,
                    ) { Text("⊕", color = InkNavy, fontSize = 20.sp) }
                }
            }
        }
    }
}

// ─── Birth Cert Brazil ────────────────────────────────────────────────────────

@Composable
internal fun BirthCertBrCard(card: WalletStore.Card, modifier: Modifier) {
    Box(
        modifier = modifier
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF0EAD6)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxWidth().background(BrInkNavy).padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Column {
                    Text("REPÚBLICA FEDERATIVA DO BRASIL", color = Color.White, fontSize = 6.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Text(card.brand, color = Color(0xCCFFFFFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            BrazilFlagStrip(4)
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Text("Matrícula", color = BrInkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                Text(card.number, color = BrInkNavy, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                Text("Dados", color = BrInkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                card.tagline.split(" · ").forEach { line ->
                    Text(line.trim(), color = BrInkNavy, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0x22023A1A)),
                        contentAlignment = Alignment.Center,
                    ) { Text("⊕", color = BrInkNavy, fontSize = 20.sp) }
                }
            }
        }
    }
}

// ─── Spanish Driving License — Permiso de Conducción (EU format, since 2013) ─
// Real design: pink/salmon card, EU "E" badge top-left (Spain's driving-licence country
// code is "E", not "ES"), "PERMISO DE CONDUCCIÓN · REINO DE ESPAÑA" text header,
// numbered fields 1./2./3./4a./4b./4c./5./7./9. per EU Directive 2006/126/EC.

@Composable
private fun DriveEsCard(card: WalletStore.Card, modifier: Modifier) {
    val parts  = card.tagline.split(" · ")
    val raw    = parts.getOrElse(0) { "" }
    val ci     = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given  = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val birth  = parts.getOrElse(1) { "" }.removePrefix("Nasc. ")
    val expiry = parts.getOrElse(2) { "" }.removePrefix("Exp. ")
    val cats   = parts.getOrElse(3) { "B" }

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(DrivePink)
    ) {
        // Subtle guilloché-style diagonal tint (real card has security background)
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x10C0392B), Color(0x00F5C6C0), Color(0x08003399)))
        ))
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: EU badge + title
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFECB0A8)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EuBadge("E")  // Spain's driving-licence code is "E", not "ES"
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("REINO DE ESPAÑA", color = InkDark, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Text("PERMISO DE CONDUCCIÓN · DRIVING LICENCE", color = InkMid, fontSize = 5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // DGT badge
                Box(
                    modifier = Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).background(DriveRed),
                    contentAlignment = Alignment.Center,
                ) { Text("DGT", color = Color.White, fontSize = 4.sp, fontWeight = FontWeight.Bold) }
            }
            // Body: photo left | fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                Column(modifier = Modifier.width(50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(44.dp).weight(1f))
                    Spacer(Modifier.height(3.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x55000000)))
                    Spacer(Modifier.height(1.dp))
                    Text("FIRMA", color = InkMid, fontSize = 4.sp)
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("1. APELLIDOS", surnames.ifBlank { "—" })
                    DocField("2. NOMBRE", given.ifBlank { "—" })
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1.2f)) { DocField("3. F. NACIMIENTO", birth.ifBlank { "01/01/1990" }) }
                        Column(Modifier.weight(0.8f)) { DocField("NACIÓN", "ESPAÑA") }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("4a. EXPEDICIÓN", "15/03/2021") }
                        Column(Modifier.weight(1f)) { DocField("4b. CADUCIDAD", expiry.ifBlank { "—" }) }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("4c. EXPEDIDA POR", "DGT") }
                        Column(Modifier.weight(1f)) { DocField("5. Nº PERMISO", card.number.ifBlank { "—" }) }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // Category strip
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("9.", color = InkMid, fontSize = 5.sp)
                        Spacer(Modifier.width(3.dp))
                        cats.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { cat ->
                            Box(
                                modifier = Modifier.padding(end = 3.dp).size(18.dp, 12.dp)
                                    .clip(RoundedCornerShape(2.dp)).background(DriveRed),
                                contentAlignment = Alignment.Center,
                            ) { Text(cat, color = Color.White, fontSize = 6.5.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

// ─── Spanish Boat License — Titulación Náutica de Recreo ─────────────────────
// Source: BOE Real Decreto 875/2014, Annex VII (BOE-A-2014-10344, pp.83076-83079).
// Python-decompressed PDF streams; official spec:
//   - A5 paper (not plastic) on special security paper with unique tracking reference
//   - "franja oblicua con los colores de la bandera nacional en su esquina superior derecha"
//     → oblique diagonal stripe (red/yellow/red) in upper-right corner only
//   - Bilingual Spanish/English throughout
//   - Front: title type, Titular, DOB, Nationality, Address, Certificate Nº, Issue date,
//     "Expedido por" (Ministerio de Fomento / CCAA), "ESPAÑA / SPAIN"
//   - Back: "VÁLIDA HASTA", holder + DG Marina Mercante signatures, official seal,
//     competences table (Básicas / Complementarias / Profesionales / Observaciones)

@Composable
private fun BoatEsCard(card: WalletStore.Card, modifier: Modifier) {
    val parts    = card.tagline.split(" · ")
    val raw      = parts.getOrElse(0) { "" }
    val ci       = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given    = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry   = parts.getOrElse(1) { "" }.removePrefix("Exp. ")
    val title    = parts.getOrElse(2) { "PNB" }
    val MitmaDark = Color(0xFF1A3A6E)
    val fullTitle = when (title) {
        "PNB" -> "PATRÓN PARA NAVEGACIÓN BÁSICA"
        "PER" -> "PATRÓN DE EMBARCACIONES DE RECREO"
        "PY"  -> "PATRÓN DE YATE"
        else  -> "TITULACIÓN NÁUTICA · $title"
    }

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF8F6F0)),
    ) {
        // Security background tint
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x06003399), Color(0x00F8F6F0), Color(0x04AA151B)))
        ))

        // Oblique Spanish flag stripe — upper-right corner (BOE Annex VII: "franja oblicua")
        // Red/Yellow/Red diagonal triangles stacked from outer to inner corner
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Canvas(modifier = Modifier.size(54.dp, 54.dp)) {
                val w = size.width; val h = size.height
                // Outer red band
                drawPath(path = Path().apply {
                    moveTo(w, 0f); lineTo(w, h * 0.56f); lineTo(w * 0.44f, 0f); close()
                }, color = Color(0xFFAA151B))
                // Yellow band
                drawPath(path = Path().apply {
                    moveTo(w, 0f); lineTo(w, h * 0.37f); lineTo(w * 0.63f, 0f); close()
                }, color = Color(0xFFF1BF00))
                // Inner red (top-right corner tip — 1:2:1 flag ratio)
                drawPath(path = Path().apply {
                    moveTo(w, 0f); lineTo(w, h * 0.19f); lineTo(w * 0.81f, 0f); close()
                }, color = Color(0xFFAA151B))
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Navy header: coat of arms + ministry + title badge
            Box(modifier = Modifier.fillMaxWidth().background(MitmaDark).padding(horizontal = 8.dp, vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x44FFD700)),
                        contentAlignment = Alignment.Center,
                    ) { Text("⊕", color = Color(0xEEFFD700), fontSize = 13.sp) }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MINISTERIO DE TRANSPORTES · ESPAÑA", color = Color(0x99FFFFFF), fontSize = 4.5.sp, letterSpacing = 0.2.sp)
                        Text("DIR. GRAL. MARINA MERCANTE", color = Color.White, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color.White).padding(horizontal = 4.dp, vertical = 2.dp),
                    ) { Text(title, color = MitmaDark, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold) }
                }
            }
            // Full bilingual title (BOE Annex VII template shows title prominently)
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFFE8E5DC)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Column {
                    Text(fullTitle, color = MitmaDark, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                    Text(
                        when (title) {
                            "PNB" -> "Basic Navigation Skipper"
                            "PER" -> "Recreational Craft Skipper"
                            "PY"  -> "Yacht Skipper"
                            else  -> "Recreational Nautical Certificate"
                        },
                        color = MitmaDark.copy(alpha = 0.65f), fontSize = 5.sp, fontStyle = FontStyle.Italic,
                    )
                }
            }
            // Body: bilingual fields per BOE Annex VII Anverso layout
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
                // Titular (holder name)
                Column(modifier = Modifier.padding(bottom = 3.dp)) {
                    Text("Titular / Certificate Holder", color = InkMid, fontSize = 4.5.sp, letterSpacing = 0.2.sp)
                    Text(
                        "$given $surnames".trim().ifBlank { "—" },
                        color = MitmaDark, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) { DocField("Fecha nac. / Date of Birth", "01/01/1990") }
                    Column(modifier = Modifier.weight(1f)) { DocField("Nacionalidad / Nationality", "ESPAÑOLA") }
                }
                // Certificate Nº + validity (BOE: "Tarjeta Nº / Certificate Nº" on front)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1.2f)) { DocField("Tarjeta Nº / Certificate Nº", card.number.ifBlank { "—" }, valueSp = 8f) }
                    Column(modifier = Modifier.weight(0.8f)) { DocField("Válida hasta / Valid until", expiry.ifBlank { "—" }) }
                }
                // Issuing authority (BOE: "Expedido por, el … Ministerio de Fomento / CCAA")
                DocField("Expedido por / Issued by", "Ministerio de Transportes · CAM")
                Spacer(modifier = Modifier.weight(1f))
                // Bottom: ESPAÑA / SPAIN + QR — BOE template ends with "ESPAÑA / SPAIN"
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(22.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF222222)),
                        contentAlignment = Alignment.Center,
                    ) { Text("▦", color = Color.White, fontSize = 12.sp) }
                    Spacer(Modifier.width(5.dp))
                    Text("VERIFICAR EN\nSEDEMARINA.ES", color = InkMid, fontSize = 4.sp, lineHeight = 6.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    Text("ESPAÑA / SPAIN", color = MitmaDark, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                }
            }
        }
    }
}

// ─── Generic Doc fallback ────────────────────────────────────────────────────

@Composable
private fun GenericDocCard(card: WalletStore.Card, modifier: Modifier) {
    Box(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF5F0E8)),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(card.brand, color = InkNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(card.number, color = InkNavy, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(card.tagline, color = Color(0xFF444444), fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
