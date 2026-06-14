package com.diegonmarcos.cloudnav.places

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudnav.Icons
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.NavShellChild
import com.diegonmarcos.cloudnav.PlaceCategory
import com.google.android.material.card.MaterialCardView
import android.widget.ImageView

/**
 * Places tab — browse POIs by category (Bars, Beaches, Gyms, ATMs, …).
 * Categories are data-driven from build.json::ui.places_categories
 * (decoded by [NavConfig]). Tapping a category will run the active POI
 * provider's Overpass query (`category.query`) and drop pins on the map —
 * that provider call is the next phase; the grid is the scaffold.
 */
class PlacesFragment : Fragment(), NavShellChild {

    override fun wantsIslands(): Boolean = false  // search bar yes, islands no

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val scroll = ScrollView(requireContext())
        val grid = GridLayout(requireContext()).apply {
            columnCount = 2
            val p = dp(8f).toInt()
            setPadding(p, p, p, p)
        }
        NavConfig.placeCategories.forEach { cat -> grid.addView(card(cat)) }
        scroll.addView(grid)
        return scroll
    }

    private fun card(cat: PlaceCategory): View {
        val card = MaterialCardView(requireContext()).apply {
            radius = dp(14f); cardElevation = dp(2f); useCompatPadding = true
            setOnClickListener {
                Toast.makeText(
                    requireContext(),
                    "${cat.label} — ${cat.query}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt())
        }
        row.addView(ImageView(requireContext()).apply {
            setImageResource(Icons.place(requireContext(), cat.icon))
            val s = dp(28f).toInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
        })
        row.addView(TextView(requireContext()).apply {
            text = cat.label
            textSize = 15f
            setPadding(dp(12f).toInt(), 0, 0, 0)
        })
        card.addView(row)
        val lp = GridLayout.LayoutParams().apply {
            width = 0
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        }
        card.layoutParams = lp
        return card
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
