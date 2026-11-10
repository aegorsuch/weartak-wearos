/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.cep.engine.action

import com.aitech.weartak_civ.cep.engine.ExitCondition
import com.aitech.weartak_civ.cep.engine.MessageTemplate
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Shared base for all CEP action handlers.
 *
 * Provides:
 *  - [expandMessage]      — placeholder substitution from the feature snapshot
 *  - [sevToPriority]      — maps Severity enum to TAK priority int
 *  - [resolveTakContext]  — resolves common TAK fields (priority, uid, exit conditions)
 *                           from a [TriggerContext] so each handler avoids repeating the block
 */
abstract class BaseCepActionHandler(
    protected val appScope: CoroutineScope
) : ActionHandler {

    protected fun expandMessage(template: String, snapshot: Map<String, Any?>): String {
        return MessageTemplate.expand(template, snapshot)
    }

    protected fun sevToPriority(sev: Severity): Int = when (sev) {
        Severity.EMERGENCY -> 1
        Severity.ALERT     -> 2
        Severity.WARN      -> 2
        Severity.INFO      -> 3
    }

    /** Resolved TAK fields shared across the isTak path in every handler. */
    protected data class TakContext(
        val priority: Int,
        val rawUid: String,
        val exitConditions: List<ExitCondition>,
        val exitLogic: String,
        val sustainSec: Long
    )

    protected fun resolveTakContext(ctx: TriggerContext): TakContext {
        val priority = when (ctx.action.priorityFrom) {
            "severity" -> sevToPriority(ctx.action.severity)
            else       -> ctx.action.priority ?: 0
        }
        return TakContext(
            priority       = priority,
            rawUid         = ctx.action.uid?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            exitConditions = ctx.action.monitor,
            exitLogic      = (ctx.action.exitLogic ?: "AND").uppercase(),
            sustainSec     = ctx.action.exitSustainSec ?: 0L
        )
    }
}
