package com.diegonmarcos.cloudnav.maps

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen day-detail map — opened by tapping a row in the Daily
 * timeline. Pins every Stop the user visited that calendar day (green
 * dots) on a [MapsMapFragment] and frames the camera around them. A
 * floating top card shows the date + a back chevron.
 *
 * Overlaid on android.R.id.content with the back stack, so the system
 * back button / the chevron dismisses it back to the timeline.
 */
class MapsDayMapFragment : Fragment() {

    private val dayMs: Long get() = arguments?.getLong(ARG_DAY_MS, 0L) ?: 0L

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            setBackgroundColor(0xFFFFFFFF.toInt())   // opaque — covers the timeline beneath.
            isClickable = true                        // swallow taps to the layer below.
        }

        // Embedded interactive map.
        val mapHost = FrameLayout(ctx).apply { id = View.generateViewId() }
        root.addView(mapHost, FrameLayout.LayoutParams(MATCH, MATCH))

        val dayStart = dayMs
        val dayEnd = dayStart + 24L * 3600_000L - 1L
        val stops = MapsDb.get(ctx).stopsBetween(dayStart, dayEnd)
        val pins = stops.map { MapsMapFragment.Pin(it.lat, it.lon, MapsMapFragment.COLOR_DAY) }

        val mapFragment = MapsMapFragment.newInstance(nav3d = false, fab = true).apply {
            onMapReady = {
                setPins(pins)
                if (pins.isNotEmpty()) fitTo(pins)
            }
        }
        if (childFragmentManager.findFragmentById(mapHost.id) == null) {
            childFragmentManager.beginTransaction().replace(mapHost.id, mapFragment).commit()
        }

        // Floating top card: back chevron + date + stop count.
        val dayFmt = SimpleDateFormat("EEE  ·  dd MMM yyyy", Locale.US)
        val card = MaterialCardView(ctx).apply {
            radius = dp(16f); cardElevation = dp(6f); useCompatPadding = true
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
        }
        row.addView(TextView(ctx).apply {
            text = "◂"
            textSize = 22f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
            setPadding(0, 0, dp(12f).toInt(), 0)
            isClickable = true
            setOnClickListener { dismiss() }
        })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(ctx).apply {
            text = dayFmt.format(Date(dayStart))
            textSize = 15f
            setTextColor(MapsStopsFragment.COL_PRIMARY)
        })
        col.addView(TextView(ctx).apply {
            text = if (stops.isEmpty()) "No mapped stops this day"
                   else "${stops.size} place${if (stops.size == 1) "" else "s"} this day"
            textSize = 12f
            setTextColor(MapsStopsFragment.COL_SECONDARY)
        })
        row.addView(col)
        card.addView(row)
        root.addView(card, FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.TOP
            setMargins(dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt(), 0)
        })
        return root
    }

    private fun dismiss() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP  = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val ARG_DAY_MS = "day_ms"

        fun newInstance(dayMs: Long) = MapsDayMapFragment().apply {
            arguments = Bundle().apply { putLong(ARG_DAY_MS, dayMs) }
        }
    }
}
