/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service.sitx
import com.aitech.weartak_civ.service.SitxTakService
import kotlinx.coroutines.flow.MutableSharedFlow

sealed class SitxServiceCommand {
    data class BeginAuth(val baseUrl: String, val clientId: String, val callsign: String, val deviceName: String, val deviceId: String): SitxServiceCommand()
    object PollDeviceToken: SitxServiceCommand()
    object ConnectSockets: SitxServiceCommand()
    object FetchAvailableGroups : SitxServiceCommand()

    data class SwitchGroup(
        val flowTag: String
    ) : SitxServiceCommand()
    data class ConnectSelectedGroups(
        val groups: List<SitxTakService.TakServerItem>
    ) : SitxServiceCommand()
    data class SendCot(val xml: String, val flowTag: String? = null): SitxServiceCommand()
    object Stop: SitxServiceCommand()
}

object SitxServiceCommandBus {
    val commands = MutableSharedFlow<SitxServiceCommand>(extraBufferCapacity = 64)
    fun trySend(cmd: SitxServiceCommand) = commands.tryEmit(cmd)
}