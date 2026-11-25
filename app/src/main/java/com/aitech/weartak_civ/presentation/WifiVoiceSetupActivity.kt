package com.aitech.weartak_civ.presentation

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.integration.plugin.VoicePackageBroker
import com.aitech.weartak_civ.integration.plugin.VoicePackageStageException
import com.weartak.voice.api.VoicePackageContract as Voice
import org.json.JSONObject
import org.json.JSONTokener
import se.takcampus.voice.WifiVoiceAdvertiser
import se.takcampus.voice.WifiVoiceTransfer.Receipt
import java.util.UUID

/** The foreground pairing window shares the host's existing, authenticated Voice plugin broker. */
class WifiVoiceSetupActivity : Activity() {
    private var pairing: WifiVoiceAdvertiser? = null
    private var prompt: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!broker.canDeliver()) {
            showResult("Enable the WearTAK Voice plugin, then open Wi-Fi pairing again.")
            return
        }
        val origin = "wifi:" + UUID.randomUUID()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Pair over Wi-Fi").setMessage("Connecting the watch to Wi-Fi…")
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        prompt = dialog
        pairing = WifiVoiceAdvertiser(this, { receive(it, origin) }, object : WifiVoiceAdvertiser.Listener {
            override fun ready(name: String, code: String) {
                dialog.setMessage("$name\n\n${code.take(4)} ${code.takeLast(4)}\n\nEnter this code in ATAK Companion.")
            }

            override fun finished(message: String, staged: Boolean) {
                dialog.dismiss()
                if (!isFinishing && !isDestroyed) showResult(
                    if (staged) "Voice channels received. Open Voice to review them." else message
                )
            }
        })
        dialog.show()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        pairing?.start()
    }

    private val broker get() = (application as WearTakApplication).voicePackages

    private fun receive(raw: String, origin: String): Receipt {
        return try {
            if (!broker.canDeliver()) return Receipt.RECEIVER_UNAVAILABLE
            // The envelope has a flat schema. The mission JSON is a string and is parsed by Voice.
            requireFlatObject(raw)
            val tokens = JSONTokener(raw)
            val envelope = tokens.nextValue() as JSONObject
            require(tokens.nextClean() == 0.toChar() && envelope.keys().asSequence().toSet() == HANDOFF_KEYS)
            require(envelope.get("version") == 1 && envelope.get("type") == "voice_channels_v1")
            val transferId = envelope.get("transferId") as String
            require(UUID.fromString(transferId).toString() == transferId)
            val mission = (envelope.get("missionJson") as String).toByteArray(Charsets.UTF_8)
            require(mission.size in 1..Voice.MAX_JSON &&
                VoicePackageBroker.digest(mission) == envelope.get("sha256"))
            val first = broker.stage(Voice.JSON, mission, transferId, origin)
            val canonicalId = first.getString("transferId", transferId)
            var state = first.getInt("state")
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (state == Voice.DOWNLOADED && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
                state = broker.statusForOrigin(canonicalId, origin).getInt("state")
            }
            when (state) {
                Voice.STAGED, Voice.APPLIED -> Receipt.STAGED
                Voice.DOWNLOADED -> Receipt.QUEUED
                else -> Receipt.REJECTED
            }
        } catch (failure: VoicePackageStageException) {
            when (failure.code) {
                Voice.BUSY -> Receipt.BUSY
                Voice.UNAVAILABLE -> Receipt.RECEIVER_UNAVAILABLE
                else -> Receipt.REJECTED
            }
        } catch (_: Exception) {
            Receipt.REJECTED
        }
    }

    private fun showResult(message: String) {
        prompt = AlertDialog.Builder(this).setTitle("Pair over Wi-Fi").setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    override fun onPause() {
        pairing?.close()
        super.onPause()
        finish()
    }

    override fun onDestroy() {
        prompt?.dismiss()
        super.onDestroy()
    }

    private fun requireFlatObject(raw: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{' -> require(++depth == 1)
                    '}' -> require(--depth == 0)
                    '[', ']', '\'' -> error("Expected a flat voice handoff")
                }
            }
        }
        require(depth == 0 && !quoted)
    }

    private companion object {
        private val HANDOFF_KEYS = setOf("type", "version", "transferId", "sha256", "missionJson")
    }
}
