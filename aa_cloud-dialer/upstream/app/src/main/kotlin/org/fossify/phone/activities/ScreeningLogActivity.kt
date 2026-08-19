package org.fossify.phone.activities

import android.os.Bundle
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.addBlockedNumber
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.ActivityScreeningLogBinding
import org.fossify.phone.databinding.ItemScreenedCallBinding
import org.fossify.phone.extensions.config
import org.fossify.phone.extensions.startCallWithConfirmationCheck
import org.fossify.phone.helpers.SCREENING_REASON_BLOCKED
import org.fossify.phone.helpers.SCREENING_REASON_HIDDEN
import org.fossify.phone.helpers.SCREENING_REASON_SPAM_PREFIX
import org.fossify.phone.helpers.SCREENING_REASON_UNKNOWN
import org.fossify.phone.helpers.ScreeningLogStore
import org.fossify.phone.spam.ScreeningLog

/**
 * Cloud Dialer (patch 0013): the screened-call history. Every call the
 * CallScreeningService silently rejected (blocked number, spam rule,
 * not-in-contacts, hidden number) is listed newest-first with its reason;
 * tapping an entry offers call back / allow / block / copy.
 */
class ScreeningLogActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityScreeningLogBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(screeningLogList))
            setupMaterialScrollListener(screeningLogList, screeningLogAppbar)
        }
        binding.screeningLogToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.clear_screening_log) {
                ScreeningLogStore.clear(this)
                refreshItems()
                true
            } else {
                false
            }
        }
        updateTextColors(binding.screeningLogCoordinator)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.screeningLogAppbar, NavigationIcon.Arrow)
        refreshItems()
    }

    private fun refreshItems() {
        ensureBackgroundThread {
            val entries = ScreeningLogStore.read(this)
            runOnUiThread {
                binding.screeningLogPlaceholder.beVisibleIf(entries.isEmpty())
                binding.screeningLogList.adapter = EntriesAdapter(entries)
            }
        }
    }

    private fun reasonText(reason: String): String = when {
        reason.startsWith(SCREENING_REASON_SPAM_PREFIX) -> {
            val category = reason.removePrefix(SCREENING_REASON_SPAM_PREFIX)
            val base = getString(R.string.reason_known_spam)
            if (category.isEmpty()) base else "$base · $category"
        }

        reason == SCREENING_REASON_BLOCKED -> getString(R.string.reason_blocked_number)
        reason == SCREENING_REASON_UNKNOWN -> getString(R.string.reason_not_in_contacts)
        reason == SCREENING_REASON_HIDDEN -> getString(R.string.reason_hidden_number)
        else -> reason
    }

    private fun showEntryActions(entry: ScreeningLog.Entry) {
        if (entry.number.isEmpty()) {
            return
        }
        val isAllowed = config.isNumberAllowed(entry.number)
        val items = arrayOf(
            getString(R.string.call_back_number),
            getString(if (isAllowed) R.string.disallow_number else R.string.allow_number),
            getString(R.string.block_number),
            getString(R.string.copy_number_to_clipboard),
        )
        getAlertDialogBuilder()
            .setTitle(entry.number)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startCallWithConfirmationCheck(entry.number, entry.number)
                    1 -> {
                        if (isAllowed) config.removeAllowedNumber(entry.number) else config.addAllowedNumber(entry.number)
                        toast(if (isAllowed) R.string.number_disallowed else R.string.number_allowed)
                    }

                    2 -> ensureBackgroundThread { addBlockedNumber(entry.number) }
                    3 -> copyToClipboard(entry.number)
                }
            }
            .show()
    }

    // ponytail: bare RecyclerView.Adapter — commons' list adapter machinery
    // (selection, CAB) is overkill for a capped read-mostly log.
    private inner class EntriesAdapter(private val entries: List<ScreeningLog.Entry>) :
        RecyclerView.Adapter<EntriesAdapter.ViewHolder>() {

        inner class ViewHolder(val itemBinding: ItemScreenedCallBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemScreenedCallBinding.inflate(layoutInflater, parent, false)
        )

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.itemBinding.apply {
                itemScreenedNumber.text = entry.number.ifEmpty { getString(R.string.reason_hidden_number) }
                val time = (entry.timestamp / 1000).toInt()
                    .formatDateOrTime(this@ScreeningLogActivity, hideTimeAtOtherDays = false, showYearEvenIfCurrent = true)
                itemScreenedDetails.text = "$time • ${reasonText(entry.reason)}"
                root.setOnClickListener { showEntryActions(entry) }
            }
            updateTextColors(holder.itemBinding.root)
        }
    }
}
