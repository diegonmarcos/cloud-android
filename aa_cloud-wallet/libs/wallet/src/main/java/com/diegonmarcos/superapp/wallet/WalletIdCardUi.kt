package com.diegonmarcos.superapp.wallet

import androidx.compose.foundation.background
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
// Real design: cream paper, dark red header band, bilingual fields, MRZ bottom

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
            // Header: dark red band
            Box(
                modifier = Modifier.fillMaxWidth().background(PassportRed).padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EuBadge("ES")
                    Spacer(Modifier.width(7.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("UNIÓN EUROPEA · ESPAÑA", color = Color(0xBBFFFFFF), fontSize = 6.sp, letterSpacing = 0.4.sp)
                        Text("PASAPORTE · PASSPORT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    }
                    Text("⚜", color = GoldText, fontSize = 18.sp)
                }
            }
            SpanishFlagStrip(3)
            // Body: photo left, bilingual fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 2.dp)) {
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
// Real design: cream-blue paper, dark navy header, Portuguese bilingual fields

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
            .background(Color(0xFFF0F4F8))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(BrGreen))
            // Header: dark navy
            Box(
                modifier = Modifier.fillMaxWidth().background(PassportNavy).padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(BrGreen),
                        contentAlignment = Alignment.Center,
                    ) { Text("◆", color = BrYellow, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(7.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("REPÚBLICA FEDERATIVA DO BRASIL", color = Color(0xBBFFFFFF), fontSize = 5.5.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.Bold)
                        Text("PASSAPORTE · PASSPORT", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
                    }
                    Text("e", color = Color(0x88FFFFFF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            BrazilFlagStrip(3)
            // Body
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 2.dp)) {
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
                        Column(Modifier.weight(0.9f)) { DocField("AUTORIDADE", "DGPF") }
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
// Real design: white/cream card, EU badge top-left, B&W photo left,
// DNI number below photo, data fields right, signature line bottom

@Composable
private fun DniCard(card: WalletStore.Card, modifier: Modifier) {
    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry = parts.getOrElse(1) { "" }.removePrefix("Vál. ")

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(CardWhite)
    ) {
        // Holographic shimmer (real DNI has DOVID patch)
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x09AA1515), Color(0x06003399), Color(0x00FFFFFF)))
        ))
        Column(modifier = Modifier.fillMaxSize()) {
            // Top: EU badge + title
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFEEEEE8)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EuBadge("ES")
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("ESPAÑA", color = InkDark, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    Text("Documento Nacional de Identidad · National Identity Card", color = InkMid, fontSize = 5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Body: photo + number left | fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                Column(modifier = Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(46.dp).weight(1f))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        card.number,
                        color = InkDark,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("APELLIDOS", surnames.ifBlank { "—" })
                    DocField("NOMBRE", given.ifBlank { "—" })
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("SEXO", "M") }
                        Column(Modifier.weight(1.5f)) { DocField("NACIONALIDAD", "ESP") }
                    }
                    DocField("FECHA DE NACIMIENTO", "01 01 1990")
                    DocField("VÁLIDO HASTA", expiry.ifBlank { "—" })
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x55000000)))
                    Spacer(Modifier.height(1.dp))
                    Text("FIRMA / SIGNATURE", color = InkMid, fontSize = 4.5.sp)
                }
            }
        }
    }
}

// ─── NIE — same EU-regulation card format as DNI 4.0 ─────────────────────────
// Same polycarbonate card layout, different title text per EU Reg 2019/1157

@Composable
private fun NieCard(card: WalletStore.Card, modifier: Modifier) {
    val parts = card.tagline.split(" · ")
    val raw = parts.getOrElse(0) { "" }
    val ci = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry = parts.getOrElse(1) { "" }.removePrefix("Vál. ")

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(CardWhite)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x09065F46), Color(0x06003399), Color(0x00FFFFFF)))
        ))
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFFEEEEE8)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EuBadge("ES")
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("ESPAÑA", color = InkDark, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    Text("Número de Identidad de Extranjero · Foreigners Identity Number", color = InkMid, fontSize = 5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                Column(modifier = Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoBox(modifier = Modifier.width(46.dp).weight(1f))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        card.number,
                        color = InkDark,
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("APELLIDOS", surnames.ifBlank { "—" })
                    DocField("NOMBRE", given.ifBlank { "—" })
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("SEXO", "M") }
                        Column(Modifier.weight(1.5f)) { DocField("NACIÓN", "BRA") }
                    }
                    DocField("FECHA DE NACIMIENTO", "01 01 1990")
                    DocField("VÁLIDO HASTA", expiry.ifBlank { "—" })
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x55000000)))
                    Spacer(Modifier.height(1.dp))
                    Text("FIRMA / SIGNATURE", color = InkMid, fontSize = 4.5.sp)
                }
            }
        }
    }
}

// ─── CIN (Carteira de Identidade Nacional) Brazil ────────────────────────────
// Real design: green/yellow, color photo left, CPF prominent, QR code corner

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
            .background(Brush.linearGradient(listOf(Color(0xFF006B2D), Color(0xFF009C3B))))
    ) {
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x00FEDD00), Color(0x18FEDD00), Color(0x00FEDD00)))
        ))
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF004D1F)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(BrGreen),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(BrYellow), contentAlignment = Alignment.Center) {
                            Text("◆", color = BrGreen, fontSize = 8.sp)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("REPÚBLICA FEDERATIVA DO BRASIL", color = Color(0xCCFFFFFF), fontSize = 5.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
                        Text("CARTEIRA DE IDENTIDADE NACIONAL", color = BrYellow, fontSize = 6.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            // Body: color photo left, fields right
            Row(modifier = Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
                Box(
                    modifier = Modifier.width(46.dp).fillMaxSize().clip(RoundedCornerShape(3.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFBBDDBB), Color(0xFFCCEECC)))),
                    contentAlignment = Alignment.Center,
                ) { Text("👤", fontSize = 20.sp) }
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    DocField("NOME", "$given $surnames".trim().ifBlank { "—" }, valueSp = 8f, valueColor = Color.White)
                    // CPF — primary national identifier, displayed prominently
                    Column(modifier = Modifier.padding(bottom = 2.dp)) {
                        Text("CPF", color = BrYellow.copy(alpha = 0.9f), fontSize = 5.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.Medium)
                        Text(card.number.ifBlank { "—" }, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("DATA NASC.", color = BrYellow.copy(alpha = 0.9f), fontSize = 5.sp)
                            Text(birth.ifBlank { "—" }, color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(0.5f)) {
                            Text("SEXO", color = BrYellow.copy(alpha = 0.9f), fontSize = 5.sp)
                            Text("M", color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("VALIDADE", color = BrYellow.copy(alpha = 0.9f), fontSize = 5.sp)
                            Text(expiry.ifBlank { "INDEFINIDA" }, color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        // QR code (real CIN has QR on back but shown as indicator)
                        Box(
                            modifier = Modifier.size(26.dp).clip(RoundedCornerShape(2.dp)).background(Color.White),
                            contentAlignment = Alignment.Center,
                        ) { Text("▦", color = InkDark, fontSize = 15.sp) }
                    }
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
            SpanishFlagStrip(height = 8)
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(HealthGreen),
                        contentAlignment = Alignment.Center,
                    ) { Text("+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("SISTEMA NACIONAL DE SALUD", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Text("TARJETA SANITARIA INDIVIDUAL", color = Color(0xCCFFFFFF), fontSize = 6.sp, letterSpacing = 0.3.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("SNS", color = Color(0xAAFFFFFF), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
                Text(card.number.chunked(4).joinToString(" "), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                card.tagline.split(" · ").forEach { part ->
                    Text(part.trim(), color = Color(0xCCFFFFFF), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
// Pink/salmon card, EU badge top-left, DGT authority, EU numbered fields 1-9

@Composable
private fun DriveEsCard(card: WalletStore.Card, modifier: Modifier) {
    val parts  = card.tagline.split(" · ")
    val raw    = parts.getOrElse(0) { "" }
    val ci     = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given  = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry = parts.getOrElse(1) { "" }.removePrefix("Exp. ")
    val cats   = parts.getOrElse(2) { "B" }

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
                EuBadge("ES")
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
// Maritime blue card, Ministry of Transport, title + zone + expiry

@Composable
private fun BoatEsCard(card: WalletStore.Card, modifier: Modifier) {
    val parts  = card.tagline.split(" · ")
    val raw    = parts.getOrElse(0) { "" }
    val ci     = raw.indexOf(',')
    val surnames = (if (ci >= 0) raw.substring(0, ci) else raw).trim()
    val given  = (if (ci >= 0) raw.substring(ci + 1) else "").trim()
    val expiry = parts.getOrElse(1) { "" }.removePrefix("Exp. ")
    val title  = parts.getOrElse(2) { "PNB" }

    Box(
        modifier = modifier.shadow(16.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
            .background(BoatSeaBlue)
    ) {
        // Wave pattern overlay
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0x08FFFFFF), Color(0x180A4B8C), Color(0x04FFFFFF)))
        ))
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: maritime navy
            Box(
                modifier = Modifier.fillMaxWidth().background(BoatBlue).padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚓", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MINISTERIO DE TRANSPORTES · ESPAÑA", color = Color(0xBBFFFFFF), fontSize = 5.sp, letterSpacing = 0.3.sp)
                        Text("TITULACIÓN NÁUTICA DE RECREO", color = Color.White, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
                    }
                    // Title badge
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(title, color = BoatBlue, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold) }
                }
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
                    DocField("TITULAR", "$given $surnames".trim().ifBlank { "—" }, valueColor = BoatBlue)
                    DocField("DNI/NIE", card.number.ifBlank { "—" })
                    DocField("TÍTULO", title)
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) { DocField("EXPEDICIÓN", "30/01/2020") }
                        Column(Modifier.weight(1f)) { DocField("CADUCIDAD", expiry.ifBlank { "—" }) }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ZONA:", color = InkMid, fontSize = 5.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(3.dp))
                        Text("AGUAS INTERIORES / COSTERAS", color = BoatBlue, fontSize = 5.sp, fontWeight = FontWeight.SemiBold)
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
