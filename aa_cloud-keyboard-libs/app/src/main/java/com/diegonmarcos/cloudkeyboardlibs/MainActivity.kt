package com.diegonmarcos.cloudkeyboardlibs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity

/**
 * Minimal launcher Activity.
 *
 * Displays a brief description of the feature pack (stickers/GIF, voice,
 * translate) and an Update button that opens the latest APK on GitHub Releases.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Cloud Keyboard — Feature Pack"
            textSize = 22f
        })

        root.addView(TextView(this).apply {
            text = "• Stickers / GIF (libs:media)\n• Voice input (libs:voice)\n• On-device translate (libs:translate)"
            textSize = 16f
            setPadding(0, 32, 0, 48)
        })

        root.addView(Button(this).apply {
            text = "Update"
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/diegonmarcos/cloud-unix/releases/latest/download/Cloud-Keyboard-Libs.apk"))
                )
            }
        })

        setContentView(root)
    }
}
