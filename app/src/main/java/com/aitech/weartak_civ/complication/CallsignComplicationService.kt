/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.complication

import android.util.Log
import androidx.core.graphics.drawable.IconCompat
import androidx.core.widget.TextViewCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.MainActivity.Constants.callsign
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

/**
 * Skeleton for complication data source that returns short text.
 */
class CallsignComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + CallsignComplicationService::class.java.simpleName
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) {
            return null
        }
        return createComplicationData(callsign, getString(R.string.watch_callsign))
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        if (null == mainActivityIsInit) {
            return createComplicationData(
                getString(R.string.loading),
                getString(R.string.callsign_loading)
            )
        }
        return when (isLicenseValid()) {
            true -> createComplicationData(callsign, getString(R.string.watch_callsign))
            false -> createComplicationData(
                getString(R.string.invalid_license),
                getString(R.string.invalid_license_key)
            )

            else -> createComplicationData(
                getString(R.string.loading),
                getString(R.string.callsign_loading)
            )
        }
    }

    private fun createComplicationData(text: String, contentDescription: String) =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()
}
