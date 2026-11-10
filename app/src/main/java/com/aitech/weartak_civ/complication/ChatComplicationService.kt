/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.complication

import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.presentation.chatComplicationString
import com.aitech.weartak_civ.presentation.MainActivity.Constants.chatComplication
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivityIsInit
import com.aitech.weartak_civ.service.LicenseManager.isLicenseValid

class ChatComplicationService : SuspendingComplicationDataSourceService() {
    companion object {
        val TAG = "WearTAK-" + ChatComplicationService::class.java.simpleName
        var disabledComplicationData: SmallImageComplicationData? = null
        var enabledNoMessageComplicationData: SmallImageComplicationData? = null
        var enabledNewMessageComplicationData: SmallImageComplicationData? = null
        const val CHAT_DISABLED = "disabled"
        const val CHAT_ENABLED_NEW_MESSAGE = "enabledNewMessage"
        const val CHAT_ENABLED_NO_NEW_MESSAGE = "enabledNoMessage"
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) {
            return null
        }
        return createComplicationData(
            Icon.createWithResource(
                applicationContext,
                R.drawable.takchat_disabled
            ), getString(R.string.chat_disabled)
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (null == mainActivityIsInit) {
            return null
        }
        //Log.d(TAG, "onComplicationRequest called")
        chatComplicationString()

        return when (isLicenseValid()) {
            true -> return when (chatComplication) {
                CHAT_DISABLED -> when (null != disabledComplicationData) {
                    true -> disabledComplicationData
                    false -> {
                        Log.d(TAG, "Creating new connectedComplicationData")
                        disabledComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.takchat_disabled
                            ), getString(R.string.chat_disabled)
                        )
                        return disabledComplicationData
                    }
                }

                CHAT_ENABLED_NO_NEW_MESSAGE -> when (null != enabledNoMessageComplicationData) {
                    true -> enabledNoMessageComplicationData
                    false -> {
                        Log.d(TAG, "Creating new enabledNoMessageComplicationData")
                        enabledNoMessageComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.takchat_enablednomessage
                            ), getString(R.string.chat_enabled_no_new_messages)
                        )
                        return enabledNoMessageComplicationData
                    }
                }

                else -> when (null != enabledNewMessageComplicationData) {
                    true -> enabledNewMessageComplicationData
                    false -> {
                        Log.d(TAG, "Creating new enabledNewMessageComplicationData")
                        enabledNewMessageComplicationData = createComplicationData(
                            Icon.createWithResource(
                                applicationContext,
                                R.drawable.takchat_enablednewmessage
                            ), getString(R.string.chat_enabled_new_messages)
                        )
                        return enabledNewMessageComplicationData
                    }
                }
            }

            false -> when (null != enabledNewMessageComplicationData) {
                true -> enabledNewMessageComplicationData
                false -> {
                    Log.d(TAG, "Creating new disabledComplicationData")
                    disabledComplicationData = createComplicationData(
                        Icon.createWithResource(
                            applicationContext,
                            R.drawable.takchat_disabled
                        ), getString(R.string.chat_disabled)
                    )
                    return disabledComplicationData
                }
            }
        }

    }

    private fun createComplicationData(icon: Icon, contentDescription: String) =
        SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(icon, SmallImageType.ICON).build(),
            contentDescription = PlainComplicationText.Builder(contentDescription).build()
        ).build()
}