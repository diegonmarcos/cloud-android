package com.diegonmarcos.cloudbrowser

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.browser.BrowserHostFragment
import com.diegonmarcos.superapp.updater.UpdateOverlayFragment
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * Single-activity shell for Cloud Browser. Hosts [BrowserHostFragment] full-screen.
 * Handles VIEW intents (http/https) so other apps can open links here.
 * Wires the self-updater (Updater) so the app can silently update itself from GHCR.
 *
 * [BrowserHostFragment] has no host interface — it is fully self-contained.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (savedInstanceState == null) {
            val openUrl = intent?.dataString?.takeIf { it.isNotBlank() }
            supportFragmentManager.commit {
                replace(R.id.fragment_container, BrowserHostFragment.newInstance(openUrl))
            }
        }

        Updater.start(this)
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // VIEW intent from another app: replace fragment with the new URL.
        // BrowserHostFragment takes its URL via newInstance(openUrl) args only.
        val url = intent.dataString?.takeIf { it.isNotBlank() } ?: return
        supportFragmentManager.commit {
            replace(R.id.fragment_container, BrowserHostFragment.newInstance(url))
        }
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
