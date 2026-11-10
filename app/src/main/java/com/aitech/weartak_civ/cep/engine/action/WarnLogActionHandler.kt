/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.cep.engine.action

import android.util.Log
import com.aitech.weartak_civ.WearTakApplication
import kotlinx.coroutines.CoroutineScope

/**
 * Handles CEP rules with action type "warn_log": on main fire, performs both
 * warn-style behavior (vibration, UserWarnActivity) and log-style behavior
 * (AlertRegistry.tryActivate, TAK/physio enqueue). Exactly one registry
 * activation and one addPhysioAndAutoGenAlertRequest per main fire.
 * Pre-warn is dispatched as type "warn" with isPreWarn=true and never reaches this handler.
 */
class WarnLogActionHandler(
    appScope: CoroutineScope
) : BaseCepActionHandler(appScope) {

    override val type: String = "warn_log"

    override suspend fun handle(ctx: TriggerContext) {
        val expandedMessage = expandMessage(ctx.action.message.ifEmpty { "Triggered" }, ctx.snapshot)
        Log.w("CEP", "[CEP][${ctx.action.severity}][${ctx.ruleId}] $expandedMessage (warn_log)")

        if (!ctx.action.isTak) return

        val tak = resolveTakContext(ctx)

        val isFirstActivation = AlertRegistry.tryActivate(
            ruleId         = ctx.ruleId,
            priority       = tak.priority,
            uid            = tak.rawUid,
            exitConditions = tak.exitConditions,
            exitLogic      = tak.exitLogic,
            exitSustainSec = tak.sustainSec
        )
        if (!isFirstActivation) {
            Log.i("CEP", "[AlertRegistry] Suppressing duplicate warn_log for rule=${ctx.ruleId}")
            return
        }

        val uid      = tak.rawUid
        val category = ctx.action.category ?: "CAT_HEALTH_GENERIC"

        val repo = (WearTakApplication.AppCtx.app as WearTakApplication).repo
        repo.addPhysioAndAutoGenAlertRequest(
            uid         = uid,
            category    = category,
            description = expandedMessage.ifEmpty { ctx.ruleId },
            priority    = tak.priority,
            sendSms     = ctx.action.sendSms,
            timeMillis  = System.currentTimeMillis()
        )
    }
}
