package com.diegonmarcos.cloudme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.wallet.WalletMeTab

/**
 * Me — one social card per profile, drawn by libs:wallet's Me tab.
 *
 * This is the same composable Cloud Wallet shows, reading the same
 * `ui.socials` list, deliberately shared rather than reimplemented: the app is
 * called Cloud Me and the wallet's Me tab is the public half of the same
 * identity, so two copies would be two things to keep true.
 */
class MeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0414))) {
                    WalletMeTab()
                }
            }
        }
}
