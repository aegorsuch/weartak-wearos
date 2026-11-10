/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.complication

import android.util.Log
import androidx.core.graphics.drawable.IconCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.presentation.MainActivity.Constants.dataRepository
import com.aitech.weartak_civ.presentation.MainActivity.Constants.enablePhysiologicalServices
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricIconComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricStringComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.physioMetricTitleComplication
import com.aitech.weartak_civ.presentation.alertChipState
import com.aitech.weartak_civ.presentation.alertText
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class PhysioStatusAndAlertingComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + PhysioStatusAndAlertingComplicationService::class.java.simpleName
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.LONG_TEXT) {
            return null
        }
        return createComplicationData(
            physioMetricStringComplication,
            getString(R.string.heart_rate),
            icon = physioMetricIconComplication,
            image = R.drawable.alert_warning,
            ""
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        //Log.d(TAG, "PhysioStatusandAlerting Complication onComplicationRequest called")
        if (null == mainActivityIsInit) {
            return null
        }
        return when (isLicenseValid()) {
            true -> return when (enablePhysiologicalServices) {
                true -> return when (dataRepository.physioAndAutoGenAlertList.value.alertChipState(true)) {
                    Utils.AlertChipState.None -> createComplicationData(
                        physioMetricStringComplication,
                        getString(R.string.heart_rate),
                        icon = physioMetricIconComplication,
                        image = R.drawable.blank,
                        physioMetricTitleComplication
                    )
                    Utils.AlertChipState.Warning -> createComplicationData(
                        alertText(dataRepository.physioAndAutoGenAlertList.value),
                        getString(R.string.heart_rate),
                        icon = R.drawable.blank,
                        image = R.drawable.alert_warning,
                        ""
                    )
                    Utils.AlertChipState.Emergency -> createComplicationData(
                        alertText(dataRepository.physioAndAutoGenAlertList.value),
                        getString(R.string.heart_rate),
                        icon = R.drawable.blank,
                        image = R.drawable.alert_emergency,
                        ""
                    )
                }

                false -> createComplicationData(
                    physioMetricStringComplication,
                    getString(R.string.heart_rate),
                    icon = physioMetricIconComplication,
                    image = R.drawable.blank,
                    physioMetricTitleComplication
                )
            }

            false -> createComplicationData(
                physioMetricStringComplication,
                getString(R.string.heart_rate),
                icon = physioMetricIconComplication,
                image = R.drawable.blank,
                physioMetricTitleComplication
            )
        }
    }

    private fun createComplicationData(text: String, contentDescription: String, icon: Int, image: Int, titleText: String): LongTextComplicationData {

        //Physio Metric Text
        val complicationData = LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        )

        //Physio Alert Image
        val image = IconCompat.createWithResource(this, image).toIcon(this.baseContext)
        complicationData.setSmallImage(SmallImage.Builder(
            image,
            SmallImageType.ICON
        ).build())

        //Physio Metric Icon
        val icon = IconCompat.createWithResource(this, icon).toIcon(this.baseContext)
        complicationData.setMonochromaticImage(MonochromaticImage.Builder(icon).build())

        //Made up title
        val title: ComplicationText? = PlainComplicationText.Builder(titleText).build()
        complicationData.setTitle(title)

        return complicationData.build()
    }
}
