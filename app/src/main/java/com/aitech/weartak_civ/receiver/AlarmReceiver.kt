/*
 * Copyright (C) 2024-2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.service.LocationService

class AlarmReceiver : BroadcastReceiver() {
    private val TAG = "WearTAK-AlarmReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        //Log.d(TAG, "onReceive broadcast")

        // TODO: Check request code or send some data if possible intent. For now, we assume that we just need to call LocationService
        LocationService.getInstance()?.acquireWakelockAndRequestLocation()
    }

}
