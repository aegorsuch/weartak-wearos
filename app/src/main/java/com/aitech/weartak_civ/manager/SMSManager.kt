/*
 * Copyright (C) 2024-2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.manager

import android.telephony.SmsManager
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SMSManager {

    companion object {
        val TAG = "WearTAK-" + SMSManager::class.java.simpleName
        private val ioScope = CoroutineScope(Dispatchers.IO)
        val smsManager = SmsManager.getDefault()

        fun sendSMSMessage(numbers: List<DataRepository.SmsNumber>, description: String) {
            ioScope.launch(Dispatchers.IO) {
                val loc = dataRepository.latestLocation.value
                var locMsg = "Location: Not Available"
                var altMsg = "Altitude: Not Available"

                if (null != loc) {
                    locMsg = "Location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                    altMsg = "Altitude: ${loc.altitude}"
                }

                val messageBody = buildString {
                    appendLine("Emergency Alert!")
                    appendLine("Description: $description")
                    appendLine("Callsign: $callsign")
                    appendLine("$locMsg")
                    append("$altMsg")
                }
                Log.d(TAG, "Sending alert: $messageBody")

                for (number in numbers.map { it.telephoneNumber.trim() }.filter { it.isNotEmpty() }) {
                    try {
                        Log.d(TAG, "Message sent to: $number")
                        val messageParts = smsManager.divideMessage(messageBody)
                        Log.d(TAG, "messageParts.size: ${messageParts.size}")
                        smsManager.sendMultipartTextMessage(number, null, messageParts, null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "ERROR: Failed to send message to: $number w/ error: ${e.message}", e)
                    }
                }
            }
        }
    }
}