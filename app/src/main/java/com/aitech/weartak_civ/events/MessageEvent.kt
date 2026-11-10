/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.events

// Use this event type for infrequent events
class MessageEvent(val message: String, val addlIntInfo: Int) {

    companion object {
        //val DROP_MARKER = "DropMarker"
        val SERVER_CHANGED = "ServerChanged"
        val SERVER_ADDED = "ServerAdded"
        val SERVER_ADDED_VIA_UI = "ServerAddedViaUI"
        val SERVER_REMOVED = "ServerRemoved"
        val SERVER_TOGGLED = "ServerToggled"
        val UPDATE_INTERVAL_CHANGED = "UpdateIntervalChanged"
        val CALLSIGN_TEAM_ROLE_CHANGED = "CallsignTeamRoleChanged"
        val OPTIMIZE_BATTERY_ON_WIFI_DETAILS_CHANGED = "OptimizeBatteryOnWifiDetailsChanged"
        val LOCALIZATION_BEACONS_CHANGED = "LocalizationBeaconsChanged"
        val CUSTOM_DEVELOPER_CONFIG_JSON_CHANGED = "CustomDeveloperConfigJsonChanged"
        val PHYSIOLOGICAL_ALERTS_ENABLED_CHANGED = "PhysiologicalAlertsEnabledChanged"

        val HR_ALERT_THRESHOLDS_CHANGED = "HrAlertThresholdsChanged"
    }
}
