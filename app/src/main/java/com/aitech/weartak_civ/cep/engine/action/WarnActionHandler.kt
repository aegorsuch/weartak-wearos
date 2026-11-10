/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.cep.engine.action

import android.content.Intent
import android.util.Log
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.presentation.UserWarnActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first

/**
 * Handles CEP rules with action type "warn": same pipeline as log (AlertRegistry + physio request)
 * plus shows UserWarnActivity so the user can Acknowledge or Cancel.
 */
class WarnActionHandler(
    appScope: CoroutineScope
) : BaseCepActionHandler(appScope) {

    override val type: String = "warn"

    override suspend fun handle(ctx: TriggerContext) {
        val expandedMessage = expandMessage(ctx.action.message.ifEmpty { "Triggered" }, ctx.snapshot)
        Log.w("CEP", "[CEP][${ctx.action.severity}][${ctx.ruleId}] $expandedMessage (warn)")

        if (!ctx.action.isTak) return

        val tak = resolveTakContext(ctx)

        if (!ctx.isPreWarn) {
            val isFirstActivation = AlertRegistry.tryActivate(
                ruleId         = ctx.ruleId,
                priority       = tak.priority,
                uid            = tak.rawUid,
                exitConditions = tak.exitConditions,
                exitLogic      = tak.exitLogic,
                exitSustainSec = tak.sustainSec
            )
            if (!isFirstActivation) {
                Log.i("CEP", "[AlertRegistry] Suppressing duplicate warn for rule=${ctx.ruleId} priority=${tak.priority}")
                return
            }
        }

        val uid      = tak.rawUid
        val category = ctx.action.category ?: "CAT_HEALTH_GENERIC"

        // Show UserWarnActivity only if user warning is enabled.
        // Use the Application-level repo so this works when MainActivity has never started
        // (e.g. service launched headlessly during simulation or ADB testing).
        val repo = (WearTakApplication.AppCtx.app as WearTakApplication).repo
        val enableUserWarning = repo.getEnableUserWarning.first()
        if (enableUserWarning) {
            val appContext = WearTakApplication.AppCtx.app.applicationContext
            Utils.vibrateWatch(vibDurationMs = 200L, numberOfVibes = 5, timeBtwnVibesMs = 120L)
            val intent = Intent(appContext, UserWarnActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(UserWarnActivity.EXTRA_UID, uid)
                putExtra(UserWarnActivity.EXTRA_CATEGORY, category)
                putExtra(UserWarnActivity.EXTRA_DESCRIPTION, expandedMessage.ifEmpty { ctx.ruleId })
                putExtra(UserWarnActivity.EXTRA_SOURCE, UserWarnActivity.SOURCE_PHYSIO)
            }
            appContext.startActivity(intent)
        }
    }
}
