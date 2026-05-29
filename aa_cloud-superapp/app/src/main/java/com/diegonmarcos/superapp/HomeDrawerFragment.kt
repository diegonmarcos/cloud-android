package com.diegonmarcos.superapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView

/**
 * "Home" drawer page — the all-sections index. Hosts the existing
 * NavigationView + drawer_menu.xml; clicks bubble to the host Activity via
 * the [NavigationItemListener] interface.
 */
class HomeDrawerFragment : Fragment() {

    interface NavigationItemListener {
        fun onDrawerItemSelected(item: MenuItem): Boolean
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_home_drawer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val nav = view.findViewById<NavigationView>(R.id.navigation_view)
        // Set drawer header build identifier (same as before).
        nav.getHeaderView(0)
            ?.findViewById<android.widget.TextView>(R.id.nav_header_build)?.text =
            "${BuildConfig.VERSION_NAME}  vc:${BuildConfig.VERSION_CODE}"
        nav.setNavigationItemSelectedListener {
            (activity as? NavigationItemListener)?.onDrawerItemSelected(it) ?: false
        }
    }

    companion object { fun newInstance() = HomeDrawerFragment() }
}
