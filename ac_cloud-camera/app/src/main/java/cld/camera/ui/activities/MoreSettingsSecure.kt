package cld.camera.ui.activities

import android.os.Bundle
import cld.camera.AutoFinishOnSleep

class MoreSettingsSecure : MoreSettings() {

    private val autoFinisher = AutoFinishOnSleep(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoFinisher.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        autoFinisher.stop()
    }
}
