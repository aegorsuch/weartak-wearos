/*
 * Copyright (C) 2025, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.content.Context
import android.util.Log
import com.aitech.weartak_civ.manager.NetworkManager
import com.aitech.weartak_civ.presentation.MainActivity
import com.rain.hail.sdk.HailDetail
import com.rain.hail.sdk.licensing.ILicenseServiceCallbacks
import com.rain.hail.sdk.licensing.IPairingCodeCallbacks
import com.rain.hail.sdk.licensing.LicensingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.UUID

object HailService {
    val TAG = "WearTAK-HailService"
    val hailScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val initialized = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun repo() = MainActivity.dataRepository
    private var checkCtr = 0
    private fun logCheck(phase: String) = Log.d(TAG, "[check#${++checkCtr}] $phase")

    @Volatile var hailLicenseInfo: HailLicenseInfo = HailLicenseInfo()
    @Volatile var lastError: String? = null
        private set

    /**
     * Initialize licensing.
     *
     * appId: package application context com.aitech.weartak_civ
     * context: WearOS app context
     * deviceUid: a stable per-device identifier you choose
     *
     *  e.g. --> initialize(
     *          appId="com.rain.hail",
     *          ctx=MainActivity.applicationContext,
     *          deviceUid="WEAROS-abeb9c116e41b389"
     *          )
     */
    fun initialize(appId: String, context: Context, deviceUid: String) {
        if (!initialized.compareAndSet(false, true)) {
            Log.i(TAG, "initialized() ignored: already initialized")
        }

        LicensingManager.initialize(appId, context, deviceUid)  // per SDK
        Log.i(TAG, "initialize(appId=$appId, ctx=${context.applicationContext.javaClass.simpleName}, deviceUid=$deviceUid)")
        hailScope.launch {
            getCombatId(deviceUid)
        }
        logCheck("initialize")
        checkOnInit()
    }

    /**
     * Everytime we initialize hail, either on boot or toggling the service,
     * we should check the license. Check with the Hail Cloud License server
     * if we have network, otherwise, use the locally store authenticator.
    **/
    fun checkOnInit() {
        if (NetworkManager.isNetworkConnected) {
            logCheck("Checking License since Network is connected ...")
            runBlocking {
                checkLicense {
                    checkLocalHailLicense()
                }  // First check network call if net available
            }
        } else {
            if (LicensingManager.isLicenseChecked()) {
                checkLocalHailLicense()
            } else {
                logCheck("License is Not Checked ... check and fetch code")
                checkAndFetchCode()
            }
        }
    }

    fun checkLocalHailLicense() {
        if (LicensingManager.isLicensed()) {
            if (LicensingManager.isLicenseSoftExpired()) {
                logCheck( "License is Soft Expired") // Grace period - app still works
                updateHailLicenseState(HailLicenseStates.SOFT_EXPIRED)
            } else {
                logCheck( "License is Valid")
                updateHailLicenseState(HailLicenseStates.VALID)
            }
        } else {
            // Check if token is valid but expired
            if (LicensingManager.isTokenValid()) {
                if (LicensingManager.isLicenseExpired()) {
                    logCheck("License is Hard Expired")
                    updateHailLicenseState(HailLicenseStates.EXPIRED)
                } else {
                    logCheck("License is NOT Licensed... Token is VALID ... License is NOT Expired ... Check error on cloud end.")
                    updateHailLicenseState(HailLicenseStates.INVALID)
                    fetchPairingCode {}
                }
            } else {
                logCheck("Token is Invalid.")
                updateHailLicenseState(HailLicenseStates.INVALID)
                fetchPairingCode {}
            }
        }
    }

    /**
     * Called when the License has not been checked. Makes network call
     * to Hail Server to check the license. If we aren't registered,
     * request a pairing code from HAIL cloud license server.
     */
    fun checkAndFetchCode() {
        checkLicense {
            if (LicensingManager.isLicensed()) {
                logCheck("License is not Licensed exiting checkAndFetchCode()")
                return@checkLicense
            } else {
                logCheck("License is not valid ... checking for token validity ...")
                if (!LicensingManager.isTokenValid()) {
                    logCheck("Token is not valid or missing ... fetching pairing code.")
                    fetchPairingCode {}
                } else {
                    Log.w(TAG, "Token is valid, but License is NOT. License is Invalid.")
                }
            }
        }
    }


    /**
     * Represents the possible states of the Hail license.
     *
     * - VALID: License is active and fully valid.
     * - CHECKING: License is being checked on the first time you are pairing with Rain cloud. Will go to VALID or INVALID afterwards.
     * - SOFT_EXPIRED: License is still valid, but will expire soon; UI should indicate this.
     * - EXPIRED: License is no longer valid; stop adding Hail detail to CoT messages in the dispatcher.
     * - INVALID: Default or catch-all state (e.g., on first app launch before pairing, or after certain errors);
     *            requires obtaining a pairing code or other recovery action.
     */
    enum class HailLicenseStates {
        VALID,
        CHECKING,
        SOFT_EXPIRED,
        EXPIRED,
        INVALID
    }

    /**
     * License info displayed in UI, populated from network calls
     * to the HAIL cloud license server.
     */
    data class HailLicenseInfo(
        var pairingCode: String? = null,
        var expirationDate: String? = null,
        var softExpirationDate: String? = null,
        var accountNumber: String? = null,
        var licenseId: String? = null,
        var deviceId: String? = null
    )

    /**
     * Runs everytime we build a Hail CotDetail, which is every CotDispatch.
     * Checks the state of the license, locally, without network.
     */
    fun isHailLicenseValid(): Boolean {
        if (LicensingManager.isLicenseChecked()) {
            if (LicensingManager.isLicensed()) {
                updateHailLicenseState(HailLicenseStates.VALID)
                return true
            } else {
                // Check if token is valid but expired
                if (LicensingManager.isTokenValid()) {
                    if (LicensingManager.isLicenseExpired()) {
                        Log.i(TAG, "Licensed Hard Expired")
                        updateHailLicenseState(HailLicenseStates.EXPIRED)
                    }
                } else {
                    //Log.i(TAG, "Other Issue. We Probably are not paired. Show License Dialog")
                    updateHailLicenseState(HailLicenseStates.INVALID)
                }
                return false
            }
        } else {
            return false
        }
    }

    /**
     * Trigger an async license check and update state for UI. Makes a
     * network call to the Hail Cloud Licensing Server.
     */
    fun checkLicense(onChanged: (() -> Unit)? = null) {
        var state = HailLicenseStates.CHECKING
        updateHailLicenseState(state)

        Log.i(TAG, "===> Checking License ... ")

        LicensingManager.checkLicense(object : ILicenseServiceCallbacks {
            override fun onLicenseValid() {
                Log.i(TAG, "Hail Service License =======> VALID | (licensed=${LicensingManager.isLicensed()}, soft=${LicensingManager.isLicenseSoftExpired()}, hard=${LicensingManager.isLicenseExpired()})")
                state = if (LicensingManager.isLicenseSoftExpired()) {
                    HailLicenseStates.SOFT_EXPIRED
                } else {
                    HailLicenseStates.VALID
                }

                updateHailLicenseState(state)
                updateHailLicenseInfo { info ->
                    info.expirationDate     = LicensingManager.expirationDate.toString()
                    info.softExpirationDate = LicensingManager.softExpirationDate.toString()
                    info.accountNumber      = LicensingManager.accountNumber
                    info.licenseId          = LicensingManager.licenseId
                    info.deviceId           = LicensingManager.deviceId
                }
                onChanged?.invoke()
            }
            override fun onLicenseInvalid() {
                Log.w(TAG, "Hail Service License =======> INVALID")
                updateHailLicenseState(HailLicenseStates.INVALID)
                onChanged?.invoke()
            }
        })
    }

    /**
     * Helper function for updating LicenseInfo in data repo.
     * Protects against overwriting existing data with null.
     **/
    fun updateHailLicenseInfo(info: (HailLicenseInfo) -> Unit) {
        hailScope.launch {
            Log.d(TAG, "updateHailLicenseInfo called")
            repo().updateHailLicenseInfo({ current ->
                val base = current ?: HailLicenseInfo()
                info(base)
                base
            })
        }
    }

    /**
     * Helper function for updating License State in data repo.
     * Protects against rewriting the current state.
     **/
    fun updateHailLicenseState(state: HailLicenseStates) {
        hailScope.launch {
            if (state.name == repo().getHailLicenseState.first()) {
                return@launch
            }
            repo().storeHailLicenseState(state)
        }
    }

    /**
     * Fetch pairing code (device registration). Makes a network call
     * to the Hail Cloud Licensing Server. Returns a pairing code or
     * error.
     */
    fun fetchPairingCode(onChanged: (() -> Unit)? = null) {
        lastError = null
        LicensingManager.fetchPairingCode(object : IPairingCodeCallbacks {
            override fun onPairingCodeSuccess(pairingCode: String) {
                logCheck( "Successfully retrieved a Pairing code: $pairingCode ... storing it ... waiting for user to press the button.")
                    updateHailLicenseInfo { info ->
                        info.pairingCode = pairingCode
                    }
                onChanged?.invoke()
            }
            override fun onPairingCodeFailure(error: String) {
                lastError = "Failed to fetch a pairing code: $error"
                Log.w(TAG, lastError!!)
                onChanged?.invoke()
            }
        })
    }

    /**
     * Encode a HAIL payload to a CoT <detail> body string.
     * Returns Result<String> so callers can surface license errors cleanly.  [oai_citation:9‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     *
     * IMPORTANT: Pass a **user-stable** combatId you generate/store once per user.  [oai_citation:10‡readme.md](file-service://file-WQM5E7xCmEkr2xQetVahG4)
     */
    fun encodeHailDetail(
        combatId: Int,
        callsign: String,
        builder: ( HailDetail) -> Unit
    ): Result<String> {
        return try {
            val hail = HailDetail(combatId, callsign)
            builder(hail)
            val encoded: String = hail.toCotDetailBody()  // throws HailEncodingException on license issues
            Result.success(encoded)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * CombatId is a unique identifier per USER, not device. If we don't have
     * one from the user in settings, we generate one from the device UUID (e.g. WEAROS-34aeoh77ff34...)
     */
    suspend fun getCombatId(deviceId: String): Int = withContext(Dispatchers.IO) {
        val existing = repo().getCombatId.firstOrNull()
        if (existing != null) return@withContext existing

        val newId = combatIdFromDeviceId(deviceId)
        repo().storeCombatId(newId)
        newId
    }

    /**
     * Hash our Device UUID to generate a unique combat ID if needed.
     */
    fun combatIdFromDeviceId(deviceId: String): Int {
        val bytes = deviceId.trim().lowercase().toByteArray(Charsets.UTF_8)
        val uuid = UUID.nameUUIDFromBytes(bytes)
        val folded = uuid.mostSignificantBits xor uuid.leastSignificantBits
        val positive = (folded and 0x7fff_ffffL).toInt()
        return if (positive == 0) 1 else positive
    }
}