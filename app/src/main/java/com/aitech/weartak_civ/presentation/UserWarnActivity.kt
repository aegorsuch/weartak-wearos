/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.presentation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.MutableLiveData
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.events.AlertClearedEvent
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class UserWarnActivity : ComponentActivity() {

    companion object {
        const val TAG = "WearTAK-" + "UserWarnActivity"

        const val EXTRA_UID = "extra_uid"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_DESCRIPTION = "extra_description"
        const val EXTRA_SOURCE = "extra_source"

        const val SOURCE_PHYSIO = "source_physio"

        val showWarnDialogLiveData = MutableLiveData(true)
    }

    /** UID of the alert we're showing; used to dismiss when threshold is exited (AlertClearedEvent). */
    private val currentUid: String
        get() = intent.getStringExtra(EXTRA_UID).orEmpty()

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty()
        val uid = intent.getStringExtra(EXTRA_UID).orEmpty()
        val category = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()

        Log.w(
            TAG,
            "Opened UserWarnActivity uid=$uid category=$category source=$source description=$description"
        )

        setContent {
            WearTAKCIVTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    UserWarnApp(
                        description = description,
                        onAcknowledge = {
                            Log.w(TAG, "User acknowledged alert uid=$uid")
                            finishAndRemoveTask()
                        }
                    )
                }
            }
        }
    }

    @Preview(device = Devices.WEAR_OS_SMALL_ROUND, showBackground = true)
    @Composable
    fun PreviewWarnScreen() {
        WearTAKCIVTheme {
            Surface {
                UserWarnDialogPage(
                    shouldShowDialog = remember { mutableStateOf(true) },
                    description = "High Heart Rate",
                    onAcknowledge = {}
                )
            }
        }
    }
}

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
fun UserWarnApp(
    description: String,
    onAcknowledge: () -> Unit
) {
    val shouldShowDialog = remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        Utils.vibrateWatch(
            vibDurationMs = 200L,
            numberOfVibes = 2,
            timeBtwnVibesMs = 120L
        )
    }

    UserWarnDialogPage(
        shouldShowDialog = shouldShowDialog,
        description = description,
        onAcknowledge = {
            shouldShowDialog.value = false
            onAcknowledge()
        }
    )
}

@Composable
fun UserWarnDialogPage(
    shouldShowDialog: MutableState<Boolean>,
    description: String,
    onAcknowledge: () -> Unit
) {
    if (shouldShowDialog.value) {
        AlertDialog(
            containerColor = Color.Red,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Red)
                .padding(0.dp),
            onDismissRequest = {
                // Do nothing so the user must explicitly acknowledge
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.tak_alert_icon),
                    modifier = Modifier.size(35.dp),
                    contentDescription = null,
                    tint = Color.White
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (description.isBlank()) "Acknowledge Alert" else description,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onAcknowledge,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black
                    ),
                ) {
                    androidx.compose.material3.Text(
                        text = "Acknowledge Alert",
                        color = Color.White
                    )
                }
            },
            dismissButton = {}
        )
    }
}