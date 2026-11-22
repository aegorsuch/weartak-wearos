package com.aitech.weartak_civ.service

import android.content.Context
import com.aitech.weartak_civ.NetworkUtils
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.command.ChatCommandRequest
import com.aitech.weartak_civ.cot.CotMessageBuilder
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.integration.plugin.PluginTransportHostV1Impl
import com.aitech.weartak_civ.integration.plugin.TrustedCommunityPlugin
import com.aitech.weartak_civ.service.sitx.SitxServiceCommand
import com.aitech.weartak_civ.service.sitx.SitxServiceCommandBus

internal object ChatCommandReplies {
    suspend fun send(context: Context, repo: DataRepository, request: ChatCommandRequest, text: String): Boolean =
        send(context, repo, request, text, requireWrite = false)

    /** Write/store acceptance on the originating transport, not end-to-end delivery. */
    suspend fun sendConfirmedWrite(
        context: Context, repo: DataRepository, request: ChatCommandRequest, text: String
    ): Boolean = send(context, repo, request, text, requireWrite = true)

    private suspend fun send(
        context: Context, repo: DataRepository, request: ChatCommandRequest, text: String, requireWrite: Boolean
    ): Boolean {
        val source = request.sourceServerId
        if (requireWrite && (source.startsWith("sitx:") || source.startsWith("companion-"))) {
            android.util.Log.w("WearTAK-ChatCommands", "Disable refused: originating route supports queue acceptance only")
            return false
        }
        val snapshot = repo.snapshotChatEvent(
            roomUid = if (request.replyIsGroup) requireNotNull(request.replyRoomUid) else request.senderUid,
            roomTitle = if (request.replyIsGroup) requireNotNull(request.replyRoomTitle) else request.senderCallsign,
            message = text, isGroup = request.replyIsGroup, participantUids = request.replyParticipantUids
        )
        val xml = CotMessageBuilder.chat(snapshot, MainActivity.callsign, MainActivity.device_uid)
        return when {
            source == "plugin:${TrustedCommunityPlugin.PACKAGE}" -> {
                val connection = (context.applicationContext as WearTakApplication).pluginManager
                    .connectionFor(TrustedCommunityPlugin.PACKAGE)
                connection?.submitDittoCoT(xml, MainActivity.device_uid, MainActivity.callsign,
                    java.util.UUID.randomUUID().toString()) == PluginTransportHostV1Impl.Submission.SUBMITTED
            }
            source.startsWith("ditto:") -> {
                android.util.Log.w("WearTAK-ChatCommands", "Bundled Ditto reply route is unavailable")
                false
            }
            source.startsWith("multicast:") -> {
                val target = source.removePrefix("multicast:").split(':')
                target.size == 2 && NetworkUtils.MulticastPublisher.sendCommandReply(
                    xml, target[0], target[1].toIntOrNull() ?: return false)
            }
            source.startsWith("sitx:") -> SitxServiceCommandBus.trySend(
                SitxServiceCommand.SendCot(xml, source.removePrefix("sitx:")))
            source.startsWith("companion-") -> {
                if (!repo.isCompanionConnected.value) return false
                val capRepo = (context.applicationContext as WearTakApplication).capRepo
                if (source == "companion-gateway") capRepo.offerGatewayCot(xml)
                else {
                    if (!capRepo.companionAppReady.value) return false
                    capRepo.enqueueChatSnapshotForBle(snapshot)
                    true
                }
            }
            else -> if (requireWrite) TAKConnectService.sendCommandReplyWritten(source, xml)
                else TAKConnectService.sendCommandReply(source, xml)
        }
    }
}
