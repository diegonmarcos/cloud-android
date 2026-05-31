package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * Android-launcher-style "all apps" drawer revealed by pulling up from
 * [Home3DFragment]. Wraps a [TileGridFragment] of every section + the
 * home actions — same content the old flat-home view used. Pull-down
 * (or back press) closes the sheet and restores the 3D cube.
 */
class AppDrawerSheetFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx).apply {
            // Solid black so the sheet feels like a separate surface
            // over the gradient; gradient still bleeds at the top edge
            // because the slide-in_up animation reveals it from below.
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val sectionTiles = Sections.all()
            .filter { !it.isMasterIndex }
            .map { sec ->
                TileGridFragment.Tile(
                    id      = "section:${sec.id}",
                    label   = sec.label,
                    iconRes = Sections.iconResFor(requireContext(), sec.iconName),
                )
            }
        val actionTiles = Sections.homeActions().map { act ->
            TileGridFragment.Tile(
                id      = "action:${act.actionType}",
                label   = act.label,
                iconRes = Sections.iconResFor(requireContext(), act.iconName),
            )
        }
        val title = getString(R.string.section_home)

        val host = FrameLayout(ctx).apply {
            // Stable resource id — required so the FragmentManager can
            // restore the embedded TileGridFragment after process death.
            // View.generateViewId() previously crashed on back-press from
            // a restored stack with "No view found for id 0x1".
            id = R.id.app_drawer_grid_host
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(host)
        // Only commit a fresh child on first creation; the FragmentManager
        // re-attaches the existing TileGridFragment on restore.
        if (s == null && childFragmentManager.findFragmentById(host.id) == null) {
            childFragmentManager.beginTransaction()
                .replace(host.id, TileGridFragment.newInstance(title, sectionTiles + actionTiles))
                .commit()
        }
        // Pull-down dismiss handled by the activity-level gesture detector
        // — see MainActivity.installNavSwipeGesture.
        return root
    }

    companion object {
        /** Tag used both for the back-stack entry name and for activity-side
         *  presence detection. Don't rename without updating MainActivity. */
        const val BACK_STACK_TAG = "app_drawer"
        fun newInstance() = AppDrawerSheetFragment()
    }
}
