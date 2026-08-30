package com.diegonmarcos.superapp.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Transparent bouncer activity. An [android.inputmethodservice.InputMethodService]
 * has no Activity context and so cannot request runtime permissions; the VOICE
 * key launches this to obtain RECORD_AUDIO, then it finishes. The user taps the
 * mic key again afterwards to start dictation.
 */
class VoicePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) "Microphone enabled — tap the mic key to dictate" else "Microphone denied",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private companion object {
        const val REQ = 4201
    }
}
