/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.RestrictionsManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.command.ChatCommandProcessor
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.command.EnrollServerHandler
import com.aitech.weartak_civ.command.LoadProfileHandler
import com.aitech.weartak_civ.command.PresetServerSink
import com.aitech.weartak_civ.command.ProfilePresetParser
import com.aitech.weartak_civ.command.ProfileHandler
import com.aitech.weartak_civ.command.ProfileOptions
import com.aitech.weartak_civ.command.ProfileStore
import com.aitech.weartak_civ.command.ServerEnrollment
import com.aitech.weartak_civ.command.SetCallsignHandler
import com.aitech.weartak_civ.command.SetLocationInputHandler
import com.aitech.weartak_civ.command.SetDittoPdlHandler
import com.aitech.weartak_civ.command.UseP12Handler
import com.aitech.weartak_civ.command.ToggleDittoHandler
import com.aitech.weartak_civ.command.NetworkMasterCommandHandler
import com.aitech.weartak_civ.command.NetworkCommandPolicy
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.takserver.TakServerId
import com.aitech.weartak_civ.data.takserver.TakServerRegistration
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity
import com.aitech.weartak_civ.presentation.WearTAKSettingsActivity.Constants.sexOptionsList
import com.aitech.weartak_civ.data.ProfileUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/** Connects the chat command processor to settings storage, chat replies and the credential prompt. */
object ChatCommandRuntime {
    private const val TAG = "WearTAK-ChatCommands"
    private const val CHANNEL_ID = "chat_commands"
    private const val NOTIFICATION_ID = 7130

    fun create(context: Context, repo: DataRepository): suspend (ChatCommandRequest) -> Boolean {
        return repo.chatCommandHandler
    }

    internal fun build(context: Context, repo: DataRepository): suspend (ChatCommandRequest) -> Boolean {
        val appContext = context.applicationContext
        val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identityLocked: suspend () -> Boolean = { repo.phoneManagedSettingsLocked.first() }
        val options = ProfileOptions(
            teams = { MainActivity.teamEntriesValuesList.ltEntries },
            roles = { MainActivity.categoryRolesEntryLists.flatMap { c -> c.roles.map { c.category to it } } },
            sexes = { sexOptionsList.map(appContext::getString) },
            userTypes = { ProfileUnit.entries.map { appContext.getString(it.labelRes) } }
        )
        val store = object : ProfileStore {
            override suspend fun setCallsign(value: String) = repo.storeCallsign(value)
            override suspend fun setTeam(value: String) = repo.storeTeam(value)
            override suspend fun setRole(category: String, role: String) {
                repo.storeRoleCategory(category)
                repo.storeRole(role)
            }
            override suspend fun setHeight(feet: Int, inches: Int) {
                repo.storePhysiologicalMetricHeightFeet(feet)
                repo.storePhysiologicalMetricHeightInches(inches)
            }
            override suspend fun setWeight(pounds: Int) = repo.storePhysiologicalMetricWeight(pounds)
            override suspend fun setBirthYear(year: Int) = repo.storeYearOfBirth(year)
            override suspend fun setSex(value: String) = repo.storePhysiologicalMetricSex(value)
            override suspend fun setUserType(value: String) = repo.storePhysiologicalMetricUserType(
                ProfileUnit.entries.first { appContext.getString(it.labelRes) == value }.storageValue
            )
            override suspend fun setPantsLength(value: Int) = repo.storePhysiologicalMetricUniformPantsLength(value)
            override suspend fun setWaistSize(value: Int) = repo.storePhysiologicalMetricUniformWaistSize(value)
        }
        val profileHandlers = ProfileHandler.COMMAND_KEYS.mapValues { (_, keys) ->
            ProfileHandler(keys, identityLocked, options, store)
        }
        val policyRejection: (Boolean) -> String? = { tak ->
            val manager = appContext.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
            NetworkCommandPolicy.rejection(manager.applicationRestrictions, tak)?.also {
                Log.w(TAG, "Network command rejected: $it")
            }
        }
        val networkHandler = NetworkMasterCommandHandler(
            rejection = { tak ->
                if (repo.networkSettingsLocked.first()) "network settings locked or phone-managed"
                else policyRejection(tak)
            },
            apply = { tak, enabled ->
                val reason = repo.storeCommandNetworkToggle(tak, enabled) { policyRejection(tak) }
                if (reason == null) {
                    if (tak) MainActivity.enableTakEndpoint = repo.getEnableTakEndpoint.first()
                    else MainActivity.enableMulticastOnWifi = repo.getEnableMulticast.first()
                }
                reason
            }
        )
        val processor = ChatCommandProcessor(
            isEnabled = { repo.getEnableChatCommands.first() },
            allowedCallsigns = { repo.getChatCommandCallsigns.first() },
            handlers = profileHandlers + NetworkMasterCommandHandler.COMMANDS.associateWith { networkHandler } + mapOf(
                "usep12" to UseP12Handler(
                    isLocked = { repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first() },
                    serverExists = { id -> repo.awaitTakServerList().any { it.serverId == id } },
                    install = { id, p12 ->
                        repo.installChatP12(id, p12) { TAKConnectService.getInstance().registerServer(id) }
                    }
                ),
                "toggleditto" to ToggleDittoHandler(
                    isLocked = { repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first() },
                    apply = { enabled -> DittoService.applyEnabled(appContext, repo, enabled) }
                ),
                "setpdl" to SetDittoPdlHandler(
                    isLocked = { repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first() },
                    communityPdl = repo::communityDittoPdl,
                    save = repo::storeDittoCommandSettings,
                    apply = { DittoService.applySelectedIdentity(appContext, repo) }
                ),
                "loadprofile" to LoadProfileHandler(
                    source = { name -> readPreset(appContext, name) },
                    profileHandler = profileHandlers.getValue("setprofile"),
                    serverSink = serverSink(repo),
                    networkLocked = { repo.phoneManagedSettingsLocked.first() || repo.getNetworkPreferencesLocked.first() },
                    unitPrefsSink = { repo.storeUnitPrefs(it) },
                    customAlertsSink = { alerts ->
                        val store = (appContext as WearTakApplication).savedCustomAlerts
                        val before = store.alerts.first().size
                        if (store.merge(alerts)) store.alerts.first().size - before else 0
                    }
                ),
                "setcallsign" to SetCallsignHandler(
                    isLocked = { repo.phoneManagedSettingsLocked.first() },
                    store = { repo.storeCallsign(it) }
                ),
                "setlocationinput" to SetLocationInputHandler(
                    isLocked = { repo.phoneManagedSettingsLocked.first() },
                    store = repo::storeLocationInput
                ),
                "enrollserver" to EnrollServerHandler(
                    isLocked = repo::chatEnrollmentBlocked,
                    prompt = { promptForCredentials(appContext, it) },
                    enroll = { name, address, port, username, password ->
                        enrollServer(repo, name, address, port, username, password) { id ->
                            TAKConnectService.getInstance().registerServer(id)
                        }
                    }
                )
            ),
            reply = { request, text ->
                check(ChatCommandReplies.send(appContext, repo, request, text)) {
                    "Command acknowledgement route unavailable"
                }
            },
            audit = { Log.i(TAG, it) },
            confirmedReply = { request, text ->
                ChatCommandReplies.sendConfirmedWrite(appContext, repo, request, text)
            }
        )
        val mutex = kotlinx.coroutines.sync.Mutex()
        return { request ->
            val execution = commandScope.async {
                mutex.lock()
                try { processor.handle(request) } finally {
                    try {
                        if (com.aitech.weartak_civ.command.SensitiveEnrollment.isSensitiveText(request.text)) {
                            ChatService.getInstance()?.purgeSensitiveEnrollments()
                            repo.purgeSensitiveChatRooms()
                            com.aitech.weartak_civ.command.SensitiveEnrollment.purgeNotifications(appContext)
                            org.greenrobot.eventbus.EventBus.getDefault().post(
                                com.aitech.weartak_civ.command.PurgeSensitiveEnrollmentCopies())
                        }
                    } finally { mutex.unlock() }
                }
            }
            try { execution.await() } finally { execution.cancel() }
        }
    }

    internal suspend fun enrollServer(
        repo: DataRepository, name: String, address: String, port: Int, username: String, password: String,
        register: suspend (String) -> Unit
    ): com.aitech.weartak_civ.command.ChatCommandResult {
        val saved = repo.saveChatEnrollment(name, address, port, username, password)
        return when (saved.status) {
            DataRepository.ChatEnrollmentStatus.LOCKED, DataRepository.ChatEnrollmentStatus.MANAGED ->
                com.aitech.weartak_civ.command.ChatCommandResult(false,
                    "Network settings are managed or locked; server not changed")
            DataRepository.ChatEnrollmentStatus.CONFLICT ->
                com.aitech.weartak_civ.command.ChatCommandResult(false,
                    "Server name and endpoint match different servers; server not changed")
            DataRepository.ChatEnrollmentStatus.ADDED, DataRepository.ChatEnrollmentStatus.UPDATED -> {
                val id = requireNotNull(saved.serverId)
                TAKConnectService.serverRegistration.saveAndConnect(id,
                    save = { TakServerRegistration.SaveResult.SAVED }, connect = { register(id) })
                val verb = if (saved.status == DataRepository.ChatEnrollmentStatus.UPDATED) "updated" else "added"
                com.aitech.weartak_civ.command.ChatCommandResult(true, "Server $name $verb")
            }
        }
    }

    /** Presets are JSON files in the app's external files folder, pushed to the watch with adb. */
    private suspend fun readPreset(context: Context, name: String): String? = withContext(Dispatchers.IO) {
        val dir = context.getExternalFilesDir("presets") ?: return@withContext null
        File(dir, "$name.json").takeIf { it.isFile && it.length() <= 64 * 1024 }?.readText()
    }

    private fun serverSink(repo: DataRepository) = PresetServerSink { name, address, port, username, password ->
        val serverId = TakServerId.ensure(null)
        val item = DataRepository.TakServerItem(true, name, address, port, false, username, password, "", "", serverId)
        var added = false
        TAKConnectService.serverRegistration.saveAndConnect(
            serverId,
            save = {
                when (repo.addTakServerItem(item)) {
                    DataRepository.TakServerAddResult.ADDED -> {
                        added = true
                        TakServerRegistration.SaveResult.SAVED
                    }
                    DataRepository.TakServerAddResult.DUPLICATE -> TakServerRegistration.SaveResult.DUPLICATE
                }
            },
            connect = { TAKConnectService.getInstance().registerServer(serverId) }
        )
        added
    }

    private fun promptForCredentials(context: Context, enrollment: ServerEnrollment) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Chat commands", NotificationManager.IMPORTANCE_HIGH)
        )
        val intent = Intent(context, WearTAKSettingsActivity::class.java)
            .putExtra(WearTAKSettingsActivity.EXTRA_ENROLLMENT_TOKEN, enrollment.token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.weartak_logo)
            .setContentTitle(context.getString(R.string.chat_command_enroll_title))
            .setContentText(context.getString(R.string.chat_command_enroll_text, enrollment.name))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}