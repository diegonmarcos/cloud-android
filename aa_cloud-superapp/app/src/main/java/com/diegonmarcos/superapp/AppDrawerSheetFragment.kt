package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(host)
        childFragmentManager.beginTransaction()
            .replace(host.id, TileGridFragment.newInstance(title, sectionTiles + actionTiles))
            .commit()

        // Pull-down to dismiss (closes the sheet via back stack pop).
        val gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                vX: Float, vY: Float,
            ): Boolean {
                if (e1 == null) return false
                val dy = e2.y - e1.y
                if (dy > 120 && vY > 600f) {
                    activity?.supportFragmentManager?.popBackStack("app_drawer",
                        androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    return true
                }
                return false
            }
        })
        root.setOnTouchListener { _, ev -> gesture.onTouchEvent(ev) }
        return root
    }

    companion object { fun newInstance() = AppDrawerSheetFragment() }
}
