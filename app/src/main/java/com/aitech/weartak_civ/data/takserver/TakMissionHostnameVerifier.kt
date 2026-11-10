/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.data.takserver

import android.util.Log
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import okhttp3.OkHttpClient

/**
 * Hostname verifier used specifically for TAK Server Mission/Data Sync REST requests.
 *
 * Standard hostname verification is attempted first. Some TAK Server deployments
 * use certificates whose SAN does not match the hostname used to reach the server.
 * Mission API requests allow that mismatch while certificate trust is still handled
 * by the configured SSLContext/TrustManager.
 */
internal class TakMissionHostnameVerifier(
    private val strictVerifier: HostnameVerifier = OkHttpClient().hostnameVerifier,
) : HostnameVerifier {

    override fun verify(
        hostname: String,
        session: SSLSession,
    ): Boolean {
        if (strictVerifier.verify(hostname, session)) {
            return true
        }

        Log.w(
            TAG,
            "Mission API hostname mismatch allowed for host=$hostname"
        )

        return true
    }

    private companion object {
        const val TAG = "TakMissionHostnameVerifier"
    }
}