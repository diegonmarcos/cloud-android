package org.fossify.phone

import org.fossify.phone.helpers.CallProvider
import org.fossify.phone.helpers.OptionKind
import org.fossify.phone.helpers.availableOptions
import org.fossify.phone.helpers.normalizeNumberForDeepLink
import org.fossify.phone.helpers.parseProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallProvidersTest {
    private val providers = listOf(
        CallProvider("whatsapp", "WhatsApp", "com.whatsapp", "wa.mime", "https://wa.me/{number}"),
        CallProvider("telegram", "Telegram", "org.telegram.messenger", "tg.mime", "tg://resolve?phone={number}"),
        CallProvider("signal", "Signal", "org.thoughtcrime.securesms", "sig.mime", null),
        CallProvider("meet", "Google Meet", "com.google.android.apps.tachyon", "meet.mime", null),
    )

    @Test
    fun voipWhenRowPresent_deepLinkWhenInstalled_elseOmitted() {
        val opts = availableOptions(
            providers = providers,
            installedPackages = setOf("com.whatsapp", "org.telegram.messenger"),
            voipDataIds = mapOf("whatsapp" to 42L), // only WhatsApp has a call row for this contact
        )
        val byId = opts.associateBy { it.provider.id }

        assertEquals(OptionKind.VOIP, byId["whatsapp"]!!.kind)
        assertEquals(42L, byId["whatsapp"]!!.dataId)
        assertEquals(OptionKind.DEEPLINK, byId["telegram"]!!.kind) // installed + has deepLink
        assertNull(byId["telegram"]!!.dataId)
        assertTrue("signal omitted: no row, no deepLink", byId["signal"] == null)
        assertTrue("meet omitted: not installed, no deepLink", byId["meet"] == null)
        assertEquals(2, opts.size)
    }

    @Test
    fun parsesAssetShape() {
        val raw = """
            [{"id":"whatsapp","label":"WhatsApp","packageName":"com.whatsapp",
              "voipMimetype":"m","deepLink":"https://wa.me/{number}"},
             {"id":"signal","label":"Signal","packageName":"org.thoughtcrime.securesms",
              "voipMimetype":"m2"}]
        """.trimIndent()
        val parsed = parseProviders(raw)
        assertEquals(2, parsed.size)
        assertEquals("com.whatsapp", parsed[0].packageName)
        assertNull("deepLink defaults to null when absent", parsed[1].deepLink)
    }

    @Test
    fun normalizeStripsNonDigits() {
        assertEquals("4915123456789", normalizeNumberForDeepLink("+49 151 23456789"))
    }
}
