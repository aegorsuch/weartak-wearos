package com.aitech.weartak_civ.debug

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import com.aitech.weartak_civ.WearTakApplication

class VoiceStandaloneConsentActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        AlertDialog.Builder(this).setTitle("Link standalone Voice?")
            .setMessage("Allow WearTAK Dev to send verified voice packages to the standalone Voice test app for review? Both apps must use the same development signing key.")
            .setPositiveButton("Allow") { _, _ ->
                try {
                    (application as WearTakApplication).voiceStandalone.setEnabled(true)
                    android.widget.Toast.makeText(this, "Voice link enabled", android.widget.Toast.LENGTH_LONG).show()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, "Install the matching standalone Voice development app first", android.widget.Toast.LENGTH_LONG).show()
                }
                finish()
            }.setNeutralButton("Disconnect") { _, _ ->
                (application as WearTakApplication).voiceStandalone.setEnabled(false); finish()
            }.setNegativeButton("Cancel") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}
