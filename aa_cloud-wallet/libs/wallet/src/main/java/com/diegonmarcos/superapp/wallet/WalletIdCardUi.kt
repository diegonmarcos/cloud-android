package com.diegonmarcos.superapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Public dispatchers ─────────────────────────────────────────────────────

@Composable
internal fun IdCardView(card: WalletStore.Card, modifier: Modifier = Modifier) {
    when (card.kind) {
        "id_passport_br" -> PassportBrCard(card, modifier)
        "id_passport_es" -> PassportEsCard(card, modifier)
        "id_dni"         -> DniCard(card, modifier)
        "id_nie"         -> NieCard(card, modifier)
        "id_tsi"         -> TsiCard(card, modifier)
        else             -> GenericIdCard(card, modifier)
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

// ─── Helpers ────────────────────────────────────────────────────────────────

/** Split "NAME · Vál. DATE" → Pair(name, expiry). */
private fun WalletStore.Card.holderAndExpiry(): Pair<String, String> {
    val parts = tagline.split(" · ")
    return Pair(
        parts.firstOrNull()?.trim().orEmpty(),
        parts.lastOrNull()?.trim().orEmpty(),
    )
}

private val NavyBlue     = Color(0xFF1B3A6B)
private val BrGreen      = Color(0xFF009C3B)
private val BrYellow     = Color(0xFFFEDD00)
private val EsRed        = Color(0xFFAA151B)
private val EsYellow     = Color(0xFFF1BF00)
private val BurgundyDark = Color(0xFF6B0F0F)
private val GoldText     = Color(0xFFC9A84C)
private val DniRed       = Color(0xFF9B1C1C)
private val NieGreen     = Color(0xFF065F46)
private val TsiBlue      = Color(0xFF1E40AF)
private val HealthGreen  = Color(0xFF059669)
private val PaperCream   = Color(0xFFF5F0E8)
private val InkNavy      = Color(0xFF1E3A5F)
private val BrInkNavy    = Color(0xFF0A3161)

// ─── Shared card shell ───────────────────────────────────────────────────────

@Composable
private fun CardShell(
    modifier: Modifier,
    background: Brush,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(background),
    ) { content() }
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
private fun BrazilFlagStrip(height: Int = 8) {
    Row(modifier = Modifier.fillMaxWidth().height(height.dp)) {
        Box(modifier = Modifier.weight(5f).fillMaxSize().background(BrGreen))
        Box(modifier = Modifier.weight(2f).fillMaxSize().background(BrYellow))
        Box(modifier = Modifier.weight(5f).fillMaxSize().background(BrGreen))
    }
}

// ─── Passport shared helpers ──────────────────────────────────────────────────

@Composable
private fun PassportDataField(label: String, value: String) {
    Column(modifier = Modifier.padding(bottom = 1.dp)) {
        Text(label, color = Color(0x66FFFFFF), fontSize = 5.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        Text(value, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

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
    val exp = if (digits.length >= 8) {
        // YYYYMMDD → YYMMDD
        digits.substring(2, 4) + digits.substring(4, 6) + digits.substring(6, 8)
    } else "300101"
    // Positions: num(9) cd(1) nat(3) dob(6) cd(1) sex(1) exp(6) cd(1) personal(14) cd(1) = 43 + final_cd(1) = 44
    return "${num}0${nat}0000000<${exp}0<<<<<<<<<<<<<<00".take(44)
}

// ─── Passport Brazil (biographical data page) ────────────────────────────────

@Composable
private fun PassportBrCard(card: WalletStore.Card, modifier: Modifier) {
    val (holder, expiry) = card.holderAndExpiry()
    val mrz1 = buildPassportMrz1("BRA", holder)
    val mrz2 = buildPassportMrz2(card.number, "BRA", expiry)

    Box(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF04254F), Color(0xFF083C82), Color(0xFF042050)))),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Brazilian green top band
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(BrGreen))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(BrGreen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⊕", color = BrYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("REPÚBLICA FEDERATIVA DO BRASIL", color = Color.White, fontSize = 6.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
                    Text("PASSAPORTE  ·  PASSPORT", color = BrYellow, fontSize = 5.5.sp, letterSpacing = 0.6.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("e", color = Color(0x66FFFFFF), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    Text("⊡", color = Color(0x44FFFFFF), fontSize = 8.sp)
                }
            }

            // Body: photo + biographic data
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 5.dp),
            ) {
                // Photo column
                Column(
                    modifier = Modifier.width(54.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.linearGradient(listOf(Color(0x30FFFFFF), Color(0x12FFFFFF)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("👤", fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // Biometric chip indicator
                    Box(
                        modifier = Modifier.size(16.dp, 11.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55D4AF37)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▣", color = GoldText, fontSize = 6.5.sp)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Data fields
                Column(modifier = Modifier.weight(1f)) {
                    PassportDataField("SOBRENOME / SURNAME", holder.ifBlank { "—" })
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("NACIONALIDADE", "BRASILEIRA") }
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("SEXO / SEX", "—") }
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("NÚMERO / NUMBER", card.number.ifBlank { "——" }) }
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("VALIDADE / EXPIRY", expiry.ifBlank { "—" }) }
                    }
                }
            }

            // MRZ zone
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x55000000))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(mrz1, color = BrGreen.copy(alpha = 0.55f), fontSize = 6.sp, fontFamily = FontFamily.Monospace, maxLines = 1, letterSpacing = 0.sp)
                Text(mrz2, color = BrGreen.copy(alpha = 0.55f), fontSize = 6.sp, fontFamily = FontFamily.Monospace, maxLines = 1, letterSpacing = 0.sp)
            }
        }
    }
}

// ─── Passport Spain (biographical data page) ─────────────────────────────────

@Composable
private fun PassportEsCard(card: WalletStore.Card, modifier: Modifier) {
    val (holder, expiry) = card.holderAndExpiry()
    val mrz1 = buildPassportMrz1("ESP", holder)
    val mrz2 = buildPassportMrz2(card.number, "ESP", expiry)

    Box(
        modifier = modifier
            .shadow(16.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF5C0010), Color(0xFF820016), Color(0xFF5C0010)))),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Spanish flag strip
            SpanishFlagStrip(height = 5)

            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape).background(EsYellow),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⚜", color = EsRed, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row {
                        Text("UNIÓN EUROPEA  ·  ", color = Color(0xBBFFFFFF), fontSize = 5.5.sp, letterSpacing = 0.3.sp)
                        Text("ESPAÑA", color = GoldText, fontSize = 5.5.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp)
                    }
                    Text("PASAPORTE  ·  PASSPORT", color = Color(0xAAFFFFFF), fontSize = 5.5.sp, letterSpacing = 0.6.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("e", color = Color(0x66FFFFFF), fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    Text("⊡", color = Color(0x44FFFFFF), fontSize = 8.sp)
                }
            }

            // Body: photo + biographic data
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 5.dp),
            ) {
                // Photo column
                Column(
                    modifier = Modifier.width(54.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .width(50.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Brush.linearGradient(listOf(Color(0x30FFFFFF), Color(0x12FFFFFF)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("👤", fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier.size(16.dp, 11.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55D4AF37)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("▣", color = GoldText, fontSize = 6.5.sp)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Data fields
                Column(modifier = Modifier.weight(1f)) {
                    PassportDataField("APELLIDOS / SURNAME", holder.ifBlank { "—" })
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("NACIONALIDAD", "ESPAÑOLA") }
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("SEXO / SEX", "—") }
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("NÚMERO / NUMBER", card.number.ifBlank { "——" }) }
                        Column(modifier = Modifier.weight(1f)) { PassportDataField("CADUCIDAD / EXPIRY", expiry.ifBlank { "—" }) }
                    }
                }
            }

            // MRZ zone
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x55000000))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(mrz1, color = GoldText.copy(alpha = 0.5f), fontSize = 6.sp, fontFamily = FontFamily.Monospace, maxLines = 1, letterSpacing = 0.sp)
                Text(mrz2, color = GoldText.copy(alpha = 0.5f), fontSize = 6.sp, fontFamily = FontFamily.Monospace, maxLines = 1, letterSpacing = 0.sp)
            }
        }
    }
}

// ─── DNI ─────────────────────────────────────────────────────────────────────

@Composable
private fun DniCard(card: WalletStore.Card, modifier: Modifier) {
    val (holder, expiry) = card.holderAndExpiry()
    CardShell(modifier, Brush.linearGradient(listOf(DniRed, Color(0xFF7F1D1D)))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Spanish flag top band
            SpanishFlagStrip(height = 10)
            Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // Left: DNI big text + number
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Text(
                        "ESPAÑA",
                        color = GoldText,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        "D.N.I.",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        "DOCUMENTO NACIONAL\nDE IDENTIDAD",
                        color = Color(0xCCFFFFFF),
                        fontSize = 7.sp,
                        letterSpacing = 0.5.sp,
                        lineHeight = 10.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        card.number,
                        color = GoldText,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        holder.ifBlank { "NOMBRE Y APELLIDOS" },
                        color = Color.White,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        expiry.ifBlank { "Vál. hasta" },
                        color = Color(0xCCFFFFFF),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Right: photo placeholder
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .fillMaxSize(0.7f)
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x33000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("👤", fontSize = 24.sp)
                }
            }
        }
    }
}

// ─── NIE ─────────────────────────────────────────────────────────────────────

@Composable
private fun NieCard(card: WalletStore.Card, modifier: Modifier) {
    val (holder, expiry) = card.holderAndExpiry()
    CardShell(modifier, Brush.linearGradient(listOf(Color(0xFF0A7A56), NieGreen))) {
        Column(modifier = Modifier.fillMaxSize()) {
            SpanishFlagStrip(height = 8)
            Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Text(
                        "ESPAÑA",
                        color = Color(0xCCFFFFFF),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        "N.I.E.",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        "NÚMERO DE IDENTIDAD\nDE EXTRANJERO",
                        color = Color(0xBBFFFFFF),
                        fontSize = 7.sp,
                        letterSpacing = 0.5.sp,
                        lineHeight = 10.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        card.number,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        holder.ifBlank { "NOMBRE Y APELLIDOS" },
                        color = Color(0xEEFFFFFF),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        expiry.ifBlank { "VALID UNTIL" },
                        color = Color(0xAAFFFFFF),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .fillMaxSize(0.7f)
                        .align(Alignment.CenterVertically)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0x33000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("👤", fontSize = 24.sp)
                }
            }
        }
    }
}

// ─── TSI ─────────────────────────────────────────────────────────────────────

@Composable
private fun TsiCard(card: WalletStore.Card, modifier: Modifier) {
    CardShell(modifier, Brush.linearGradient(listOf(Color(0xFF2563EB), TsiBlue))) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top flag strip
            SpanishFlagStrip(height = 8)
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Health cross
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(HealthGreen),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            "SISTEMA NACIONAL DE SALUD",
                            color = Color.White,
                            fontSize = 7.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        )
                        Text(
                            "TARJETA SANITARIA INDIVIDUAL",
                            color = Color(0xCCFFFFFF),
                            fontSize = 6.sp,
                            letterSpacing = 0.3.sp,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text("SNS", color = Color(0xAAFFFFFF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.weight(1f))
                // TSI number
                Text(
                    card.number.chunked(4).joinToString(" "),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                // tagline: NUSS + community
                val parts = card.tagline.split(" · ")
                parts.forEach { part ->
                    Text(
                        part.trim(),
                        color = Color(0xCCFFFFFF),
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
            Spacer(modifier = Modifier.height(4.dp))
            Text(card.brand, color = Color(0xCCFFFFFF), fontSize = 10.sp)
            Spacer(modifier = Modifier.weight(1f))
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
            .background(PaperCream),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header band
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InkNavy)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Column {
                    Text(
                        "REGISTRO CIVIL",
                        color = Color.White,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                    )
                    Text(
                        card.brand,
                        color = Color(0xCCFFFFFF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Flag band
            SpanishFlagStrip(height = 4)
            // Body
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Text("Núm. de Registro", color = InkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                Text(
                    card.number,
                    color = InkNavy,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Datos", color = InkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                val lines = card.tagline.split(" · ")
                lines.forEach { line ->
                    Text(
                        line.trim(),
                        color = InkNavy,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                // Official seal placeholder
                Row(verticalAlignment = Alignment.Bottom) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0x22162A4A)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⊕", color = InkNavy, fontSize = 20.sp)
                    }
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
            // Header band
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrInkNavy)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Column {
                    Text(
                        "REPÚBLICA FEDERATIVA DO BRASIL",
                        color = Color.White,
                        fontSize = 6.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                    Text(
                        card.brand,
                        color = Color(0xCCFFFFFF),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Brazil flag strip
            BrazilFlagStrip(height = 4)
            // Body
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Text("Matrícula", color = BrInkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                Text(
                    card.number,
                    color = BrInkNavy,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Dados", color = BrInkNavy, fontSize = 8.sp, fontStyle = FontStyle.Italic)
                val lines = card.tagline.split(" · ")
                lines.forEach { line ->
                    Text(
                        line.trim(),
                        color = BrInkNavy,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0x22023A1A)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⊕", color = BrInkNavy, fontSize = 20.sp)
                    }
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
            .background(PaperCream),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(card.brand, color = InkNavy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(card.number, color = InkNavy, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(card.tagline, color = Color(0xFF444444), fontSize = 9.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
