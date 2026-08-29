package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import org.fossify.phone.extensions.getPhoneAccountHandleModel
import org.fossify.phone.extensions.putPhoneAccountHandle
import org.fossify.phone.spam.SpamMatcher
import org.fossify.phone.models.SpeedDial
import androidx.core.content.edit
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(
            key = getKeyForCustomSIM(number),
            parcelable = handle
        ).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit().remove(getKeyForCustomSIM(number)).apply()
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

    private fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit().putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls).apply()

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit().putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad).apply()

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit().putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor).apply()

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit().putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer).apply()

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit().putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed).apply()

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit().putBoolean(DIALPAD_VIBRATION, dialpadVibration).apply()

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit().putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers).apply()

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit().putBoolean(DIALPAD_BEEPS, dialpadBeeps).apply()

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit().putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen).apply()

    // Cloud Dialer: block calls whose number matches the bundled offline spam
    // list (assets/spam_patterns.json). Defaults ON — "spam/robocall defence by
    // default" (build.json::forks.dialer.call_screening.rationale). Additive to
    // Fossify's own blockUnknownNumbers (non-contacts) + blockHiddenNumbers.
    var blockKnownSpam: Boolean
        get() = prefs.getBoolean(BLOCK_KNOWN_SPAM, true)
        set(blockKnownSpam) = prefs.edit().putBoolean(BLOCK_KNOWN_SPAM, blockKnownSpam).apply()

    // Cloud Dialer: single tri-mode view over the two screening prefs, per
    // build.json::forks.dialer.call_screening.modes. DERIVED — no third pref,
    // so commons' own blocked-numbers toggles stay in sync automatically.
    //   contacts_only    → blockUnknownNumbers=true  (non-contacts silently rejected)
    //   block_known_spam → blockUnknownNumbers=false, blockKnownSpam=true
    //   allow_all        → both false
    var callScreeningMode: String
        get() = when {
            blockUnknownNumbers -> SCREENING_CONTACTS_ONLY
            blockKnownSpam -> SCREENING_BLOCK_KNOWN_SPAM
            else -> SCREENING_ALLOW_ALL
        }
        set(mode) {
            blockUnknownNumbers = mode == SCREENING_CONTACTS_ONLY
            blockKnownSpam = mode != SCREENING_ALLOW_ALL
        }

    // Cloud Dialer (patch 0013): per-number allowlist — normalized numbers that
    // ALWAYS ring, overriding every screening rule (contacts_only, spam list,
    // even commons' blocked numbers). Managed from the screened-call history
    // ("Allow number") and Settings ▸ Call screening ▸ Allowed numbers.
    var allowedNumbers: Set<String>
        get() = prefs.getStringSet(ALLOWED_NUMBERS, emptySet())!!.toSet()
        set(allowedNumbers) = prefs.edit().putStringSet(ALLOWED_NUMBERS, allowedNumbers).apply()

    fun isNumberAllowed(number: String) = allowedNumbers.contains(SpamMatcher.normalize(number))

    fun addAllowedNumber(number: String) {
        allowedNumbers = allowedNumbers + SpamMatcher.normalize(number)
    }

    fun removeAllowedNumber(number: String) {
        allowedNumbers = allowedNumbers - SpamMatcher.normalize(number)
    }

    // Cloud Dialer: how outgoing calls are placed. "" = always show the provider
    // chooser; CALL_PROVIDER_CELLULAR = always plain cellular; any other value =
    // a provider id from assets/call_providers.json (fail-open to the chooser
    // when that provider can't reach the dialed number).
    var defaultCallProvider: String
        get() = prefs.getString(DEFAULT_CALL_PROVIDER, "")!!
        set(defaultCallProvider) = prefs.edit().putString(DEFAULT_CALL_PROVIDER, defaultCallProvider).apply()
}
