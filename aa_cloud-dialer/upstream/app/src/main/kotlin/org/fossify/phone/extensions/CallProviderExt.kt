package org.fossify.phone.extensions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.dialogs.CallProviderChooserSheet
import org.fossify.phone.helpers.CALL_PROVIDER_CELLULAR
import org.fossify.phone.helpers.OptionKind
import org.fossify.phone.helpers.ProviderOption
import org.fossify.phone.helpers.availableOptions
import org.fossify.phone.helpers.isPackageInstalled
import org.fossify.phone.helpers.loadCallProviders
import org.fossify.phone.helpers.normalizeNumberForDeepLink
import org.fossify.phone.helpers.queryVoipDataIds

/**
 * Entry point for every call: offer "Cellular / Call with WhatsApp / Open in Telegram / …".
 * Falls straight through to a normal cellular call when no provider can handle the number.
 * A configured default provider (Settings ▸ Calling) skips the chooser; fail-open back to
 * the chooser when the default can't reach this number.
 */
fun SimpleActivity.startCallWithProviderChooser(number: String, name: String) {
    val providers = loadCallProviders(this)
    val installed = providers.map { it.packageName }.filter { isPackageInstalled(this, it) }.toSet()
    val voipDataIds = queryVoipDataIds(this, number, providers)
    val options = availableOptions(providers, installed, voipDataIds)

    if (options.isEmpty()) {
        startCallWithConfirmationCheck(number, name)
        return
    }

    when (val default = config.defaultCallProvider) {
        "" -> showProviderChooserSheet(options, number, name)
        CALL_PROVIDER_CELLULAR -> startCallWithConfirmationCheck(number, name)
        else -> {
            val option = options.firstOrNull { it.provider.id == default }
            if (option != null) {
                launchProviderCall(option, number)
            } else {
                showProviderChooserSheet(options, number, name)
            }
        }
    }
}

private fun SimpleActivity.showProviderChooserSheet(
    options: List<ProviderOption>,
    number: String,
    name: String,
) {
    val rows = ArrayList<CallProviderChooserSheet.Row>(options.size + 1)
    val cellularIcon = resources.getColoredDrawableWithColor(R.drawable.ic_phone_vector, getProperTextColor())
    rows.add(
        CallProviderChooserSheet.Row(cellularIcon, getString(R.string.call_via_cellular)) {
            startCallWithConfirmationCheck(number, name)
        }
    )
    options.forEach { option ->
        val icon = try {
            packageManager.getApplicationIcon(option.provider.packageName)
        } catch (e: Exception) {
            null
        }
        val template = if (option.kind == OptionKind.VOIP) R.string.call_with else R.string.open_in
        rows.add(
            CallProviderChooserSheet.Row(icon, getString(template, option.provider.label)) {
                launchProviderCall(option, number)
            }
        )
    }
    CallProviderChooserSheet.show(supportFragmentManager, rows)
}

private fun SimpleActivity.launchProviderCall(option: ProviderOption, number: String) {
    val intent = when (option.kind) {
        OptionKind.VOIP -> Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, option.dataId.toString()),
                option.provider.voipMimetype,
            )
            setPackage(option.provider.packageName)
        }

        OptionKind.DEEPLINK -> Intent(
            Intent.ACTION_VIEW,
            Uri.parse(option.provider.deepLink!!.replace("{number}", normalizeNumberForDeepLink(number))),
        ).apply { setPackage(option.provider.packageName) }
    }

    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        toast(getString(R.string.call_provider_unavailable, option.provider.label))
    }
}
