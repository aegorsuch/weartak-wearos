/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */
package com.aitech.weartak_civ.command

data class ChatCommandRequest(
    val senderUid: String,
    val senderCallsign: String,
    val text: String,
    val messageId: String?,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val sourceServerId: String,
    val replyRoomUid: String? = null,
    val replyRoomTitle: String? = null,
    val replyIsGroup: Boolean = false,
    val replyParticipantUids: List<String> = emptyList()
) {
    override fun toString() = "ChatCommandRequest(senderUid=$senderUid, messageId=$messageId)"
}

data class ChatCommandResult(
    val ok: Boolean,
    val message: String,
    val afterReply: (suspend () -> Unit)? = null,
    /** Null means applied; a nonnull message reports an apply-time rejection. */
    val afterSuccessfulReply: (suspend () -> String?)? = null
)

fun interface ChatCommandHandler {
    suspend fun execute(command: ChatCommand, request: ChatCommandRequest): ChatCommandResult
}

/**
 * Runs `#weartakcommand...` chat messages. Only messages from allow-listed callsigns are
 * acted on, and only when the Commands setting is on. Direct, group and broadcast
 * replies retain the originating conversation. Messages from other senders are swallowed
 * silently so the feature cannot be probed. Argument values are never logged or echoed.
 */
class ChatCommandProcessor(
    private val isEnabled: suspend () -> Boolean,
    private val allowedCallsigns: suspend () -> List<String>,
    private val handlers: Map<String, ChatCommandHandler>,
    private val reply: suspend (ChatCommandRequest, String) -> Unit,
    private val audit: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val authorizer: ChatCommandAuthorizer = ChatCommandAuthorizer(),
    private val confirmedReply: suspend (ChatCommandRequest, String) -> Boolean = { _, _ -> false }
) {
    /** Returns true when the message was a command and must not be shown as ordinary chat. */
    suspend fun handle(request: ChatCommandRequest): Boolean {
        val parsed = ChatCommandParser.parse(request.text)
        if (parsed is ChatCommandParse.NotACommand) return false
        com.aitech.weartak_civ.service.AppDiagnostics.record(com.aitech.weartak_civ.service.DiagnosticEvent.COMMAND)
        if (!isEnabled()) return SensitiveEnrollment.isSensitiveText(request.text) || request.text.trimStart()
            .substring(ChatCommandParser.PREFIX.length).trimStart().let {
                it.startsWith("usep12", true) || it.startsWith("setpdl", true)
            }

        if (request.messageId.isNullOrBlank() || request.senderUid.isBlank()) {
            audit("ignored command without message ID")
            return true
        }
        val messageKey = "${request.senderUid.length}:${request.senderUid}:${request.messageId}"
        val decision = authorizer.authorize(
            request.senderCallsign, allowedCallsigns(), messageKey, request.sentAtMs, now()
        )
        if (decision != ChatCommandDecision.ALLOWED) {
            audit("ignored command from ${request.senderCallsign}: $decision")
            return true
        }

        when (parsed) {
            is ChatCommandParse.Malformed -> {
                audit("malformed command from ${request.senderCallsign}: ${parsed.reason}")
                reply(request, "Command rejected: ${parsed.reason}")
            }
            is ChatCommandParse.Parsed -> run(parsed.command, request)
            ChatCommandParse.NotACommand -> Unit
        }
        return true
    }

    private suspend fun run(command: ChatCommand, request: ChatCommandRequest) {
        val handler = handlers[command.name]
        if (handler == null) {
            audit("unknown command ${command.name} from ${request.senderCallsign}")
            reply(request, "Unknown command: ${command.name}")
            return
        }
        val result = try {
            handler.execute(command, request)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            audit("command ${command.name} execution failed: ${error.javaClass.simpleName}")
            ChatCommandResult(false, "Command failed")
        }
        val deferred = result.afterSuccessfulReply
        if (deferred != null) {
            try {
                if (!confirmedReply(request, result.message)) {
                    audit("command ${command.name} refused: originating warning write not accepted")
                    recordOutcome(command, request, false)
                    return
                }
                val rejection = deferred()
                recordOutcome(command, request, rejection == null)
                if (rejection != null) {
                    audit("command ${command.name} apply rejected: $rejection")
                    reply(request, rejection)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                audit("command ${command.name} deferred execution failed: ${error.javaClass.simpleName}")
                recordOutcome(command, request, false)
            }
            return
        }
        recordOutcome(command, request, result.ok)
        try {
            reply(request, result.message)
        } finally {
            result.afterReply?.invoke()
        }
    }

    private fun recordOutcome(command: ChatCommand, request: ChatCommandRequest, ok: Boolean) {
        audit("command ${command.name} from ${request.senderCallsign}: ${if (ok) "ok" else "failed"}")
        com.aitech.weartak_civ.service.AppDiagnostics.record(com.aitech.weartak_civ.service.DiagnosticEvent.COMMAND,
            mapOf(com.aitech.weartak_civ.service.DiagnosticMetric.RESULT to if (ok) 1L else 0L))
    }
}