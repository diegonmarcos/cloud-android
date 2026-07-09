package com.diegonmarcos.cloudwallet

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.wallet.WalletFragment
import com.diegonmarcos.superapp.wallet.WalletHost

/**
 * Single-activity shell for Cloud Wallet. Hosts [WalletFragment] full-screen
 * and wires the self-updater (Updater) so the app can silently update itself
 * from GHCR — same engine the SuperApp uses for each constellation member.
 *
 * Implements [WalletHost] for the one cross-surface callback the wallet lib
 * needs: [onOpenVcard]. In the standalone wallet there is no BusinessCard
 * surface, so we no-op it (the user stays in the deck).
 */
class MainActivity : AppCompatActivity(), WalletHost {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, WalletFragment.newInstance())
            }
        }

        // Self-update: start the periodic GHCR check + one-shot 30s after
        // launch so the first update fires promptly (periodic alone defers by
        // a full interval on first install).
        Updater.start(this)

        // Drive the update overlay: listen to UpdateProgress and show / hide
        // the overlay fragment accordingly — same pattern as SuperApp.
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

    // WalletHost: vCard tap. Standalone wallet has no BusinessCardFragment,
    // so no-op — user stays in the deck. (ponytail: add a BusinessCardFragment
    // here if a full vCard surface is ever needed in the standalone app.)
    override fun onOpenVcard() = Unit

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        when (state) {
            is UpdateProgress.State.Idle -> {
                frag?.let { supportFragmentManager.commit { remove(it) } }
            }
            else -> {
                if (frag == null) {
                    supportFragmentManager.commit {
                        add(android.R.id.content,
                            com.diegonmarcos.superapp.updater.UpdateOverlayFragment.newInstance(),
                            tag)
                    }
                } else {
                    (frag as? com.diegonmarcos.superapp.updater.UpdateOverlayFragment)
                        ?.applyState(state)
                }
            }
        }
    }
}
