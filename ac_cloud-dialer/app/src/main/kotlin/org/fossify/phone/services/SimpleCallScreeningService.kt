package org.fossify.phone.services

import android.telecom.Call
import android.telecom.CallScreeningService
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.SCREENING_REASON_BLOCKED
import org.fossify.phone.helpers.SCREENING_REASON_HIDDEN
import org.fossify.phone.helpers.SCREENING_REASON_SPAM_PREFIX
import org.fossify.phone.helpers.SCREENING_REASON_UNKNOWN
import org.fossify.phone.helpers.ScreeningLogStore
import org.fossify.phone.spam.SpamMatcher

class SimpleCallScreeningService : CallScreeningService() {

    // Cloud Dialer: bundled offline spam rules (assets/spam_patterns.json),
    // parsed once per service instance. Empty on any read/parse error → fail
    // open (never blocks legit calls because the asset is missing/corrupt).
    private val spamRules: List<SpamMatcher.Rule> by lazy {
        runCatching {
            assets.open("spam_patterns.json").bufferedReader().use { it.readText() }
        }.map { SpamMatcher.parse(it) }.getOrDefault(emptyList())
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart
        when {
            // Cloud Dialer (patch 0013): the per-number allowlist beats every
            // block rule — an allowed number always rings, even in contacts_only.
            number != null && config.isNumberAllowed(number) -> {
                respondToCall(callDetails, isBlocked = false)
            }

            number != null && isNumberBlocked(number) -> {
                blockCall(callDetails, number, SCREENING_REASON_BLOCKED)
            }

            number != null && config.blockKnownSpam && SpamMatcher.isSpam(number, spamRules) -> {
                val category = SpamMatcher.match(number, spamRules)?.category.orEmpty()
                blockCall(callDetails, number, SCREENING_REASON_SPAM_PREFIX + category)
            }

            number != null && baseConfig.blockUnknownNumbers -> {
                val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                val result = SimpleContactsHelper(this).existsSync(number, privateCursor)
                if (result == ContactLookupResult.NotFound) {
                    blockCall(callDetails, number, SCREENING_REASON_UNKNOWN)
                } else {
                    respondToCall(callDetails, isBlocked = false)
                }
            }

            number == null && baseConfig.blockHiddenNumbers -> {
                blockCall(callDetails, "", SCREENING_REASON_HIDDEN)
            }

            else -> {
                respondToCall(callDetails, isBlocked = false)
            }
        }
    }

    // Cloud Dialer (patch 0013): every silent rejection lands in the screened-call
    // history, so the user can audit what was filtered and allow/block from there.
    private fun blockCall(callDetails: Call.Details, number: String, reason: String) {
        respondToCall(callDetails, isBlocked = true)
        ScreeningLogStore.record(this, number, reason)
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        val response = CallResponse.Builder()
            .setDisallowCall(isBlocked)
            .setRejectCall(isBlocked)
            .setSkipCallLog(isBlocked)
            .setSkipNotification(isBlocked)
            .build()

        respondToCall(callDetails, response)
    }
}
