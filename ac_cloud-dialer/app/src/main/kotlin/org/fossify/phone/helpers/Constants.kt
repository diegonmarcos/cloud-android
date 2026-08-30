package org.fossify.phone.helpers

import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val BLOCK_KNOWN_SPAM = "block_known_spam"
const val DEFAULT_CALL_PROVIDER = "default_call_provider"

// Cloud Dialer: reserved defaultCallProvider value meaning "always plain cellular"
// ("" = always ask; any other value = a provider id from assets/call_providers.json).
const val CALL_PROVIDER_CELLULAR = "cellular"

// Cloud Dialer: call-screening modes. Values mirror
// build.json::forks.dialer.call_screening.modes — keep in sync (contract).
const val SCREENING_CONTACTS_ONLY = "contacts_only"
const val SCREENING_ALLOW_ALL = "allow_all"
const val SCREENING_BLOCK_KNOWN_SPAM = "block_known_spam"

// Cloud Dialer (patch 0013): allowlist pref + screened-call reason codes
// persisted in the screening log (spam reasons are "spam:<category>").
const val ALLOWED_NUMBERS = "allowed_numbers"
const val SCREENING_REASON_BLOCKED = "blocked_number"
const val SCREENING_REASON_SPAM_PREFIX = "spam:"
const val SCREENING_REASON_UNKNOWN = "not_in_contacts"
const val SCREENING_REASON_HIDDEN = "hidden_number"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"

const val ALL_TABS_MASK = TAB_CONTACTS or TAB_FAVORITES or TAB_CALL_HISTORY

val tabsList = arrayListOf(TAB_CONTACTS, TAB_FAVORITES, TAB_CALL_HISTORY)

private const val PATH = "org.fossify.phone.action."
const val ACCEPT_CALL = PATH + "ACCEPT_CALL"
const val DECLINE_CALL = PATH + "DECLINE_CALL"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds
