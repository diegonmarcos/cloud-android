package com.diegonmarcos.cloudvault

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.browser.BrowserHostFragment
import com.diegonmarcos.superapp.updater.UpdateOverlayFragment
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * Single-activity shell for Cloud Vault. Hosts [BrowserHostFragment] full-screen,
 * always locked to [BuildConfig.VAULT_URL] (vault.diegonmarcos.com — self-hosted Vaultwarden).
 * Wires the self-updater (Updater) so the app can silently update itself from GHCR.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, BrowserHostFragment.newInstance(BuildConfig.VAULT_URL))
            }
        }

        Updater.start(this)
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Cloud Vault is always locked to vault.diegonmarcos.com — external URL intents ignored.
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateProgress.setListener(null)
    }

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
                        add(android.R.id.content, UpdateOverlayFragment.newInstance(), tag)
                    }
                } else {
                    (frag as? UpdateOverlayFragment)?.applyState(state)
                }
            }
        }
    }
}
