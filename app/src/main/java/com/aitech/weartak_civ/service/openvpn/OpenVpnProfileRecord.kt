/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service.openvpn

data class OpenVpnProfileRecord(
    val id: String,
    val name: String,
    val config: String,
    val requiresCredentials: Boolean,
    val username: String? = null,
    val password: String? = null,
)
