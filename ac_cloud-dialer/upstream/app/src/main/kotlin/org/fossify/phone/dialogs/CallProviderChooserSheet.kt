package org.fossify.phone.dialogs

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.FragmentManager
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.fragments.BaseBottomSheetDialogFragment
import org.fossify.phone.R
import org.fossify.phone.databinding.ItemCallProviderBinding

/**
 * Cloud Dialer: bottom-sheet call-provider chooser — one row per way to reach
 * the dialed number (cellular + every available VoIP/deep-link provider from
 * assets/call_providers.json), each with the provider app's real launcher icon.
 *
 * Exists because commons' SimpleListItem sheet only supports resource-id text
 * and icons, while provider labels/icons are runtime values. Rows are passed
 * as a property (not fragment arguments): on system re-creation the lambdas
 * are gone, so the sheet dismisses itself — the same trade-off upstream's
 * DynamicBottomSheetChooserDialog.onItemClick already makes.
 */
class CallProviderChooserSheet : BaseBottomSheetDialogFragment() {

    class Row(val icon: Drawable?, val label: String, val onTap: () -> Unit)

    var rows: List<Row> = emptyList()

    override fun setupContentView(parent: ViewGroup) {
        if (rows.isEmpty()) {
            dismissAllowingStateLoss()
            return
        }

        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        val textColor = requireContext().getProperTextColor()
        rows.forEach { row ->
            val item = ItemCallProviderBinding.inflate(layoutInflater, list, false)
            item.callProviderIcon.setImageDrawable(row.icon)
            item.callProviderLabel.text = row.label
            item.callProviderLabel.setTextColor(textColor)
            item.root.setOnClickListener {
                row.onTap()
                dismissAllowingStateLoss()
            }
            list.addView(item.root)
        }
        parent.addView(list)
    }

    companion object {
        private const val TAG = "CallProviderChooserSheet"

        fun show(fragmentManager: FragmentManager, rows: List<Row>) {
            CallProviderChooserSheet().apply {
                arguments = Bundle().apply {
                    putInt(BOTTOM_SHEET_TITLE, R.string.call_using)
                }
                this.rows = rows
                show(fragmentManager, TAG)
            }
        }
    }
}
