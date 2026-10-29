package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.validation.BundleContractValidator
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class HostedPluginMainUiCard(
    val cardId: String,
    val cardType: String,
    val title: String,
    val subtitle: String?,
    val action: HostedPluginMainUiAction? = null,
)

internal data class HostedPluginMainUiAction(
    val actionId: String,
    val actionLabel: String,
    val enabled: Boolean,
    val args: Bundle,
)

internal sealed interface HostedPluginMainUiResult {
    data class Ready(val cards: List<HostedPluginMainUiCard>) : HostedPluginMainUiResult
    data object NotConnected : HostedPluginMainUiResult
    data object Unsupported : HostedPluginMainUiResult
    data class Failed(val message: String) : HostedPluginMainUiResult
}

internal object HostedPluginMainUiContract {
    private const val ACTION_CARD_TYPE = "ACTION"
    private const val ACTION_ID = "actionId"
    private const val ACTION_LABEL = "actionLabel"
    private const val ACTION_ENABLED = "enabled"
    private const val ACTION_ARGS_JSON = "actionArgsJson"
    private const val MAX_ACTION_ID_BYTES = 128
    private const val MAX_ACTION_LABEL_BYTES = 256
    private val actionContentKeys = setOf(ACTION_ID, ACTION_LABEL, ACTION_ENABLED, ACTION_ARGS_JSON)

    fun parse(schema: PluginMainUiSchemaParcel): List<HostedPluginMainUiCard> {
        val validated = when (
            val result = MainUiSchemaContractValidator.validate(schema)
        ) {
            is ContractValidationResult.Ok -> result.value
            is ContractValidationResult.Err -> throw IllegalArgumentException("Invalid hosted UI schema")
        }

        return validated.cards.map { card ->
            val type = card.cardType.uppercase(Locale.ROOT)
            when (type) {
                MainUiCardTypes.STATUS, MainUiCardTypes.KEY_VALUE_LIST ->
                    HostedPluginMainUiCard(card.cardId, type, card.title, card.subtitle)
                ACTION_CARD_TYPE -> {
                    val action = parseAction(card.content)
                    require(action.args.keySet().none(::isSensitiveArgumentKey)) {
                        "Hosted UI action arguments cannot contain credentials"
                    }
                    HostedPluginMainUiCard(
                        cardId = card.cardId,
                        cardType = type,
                        title = card.title,
                        subtitle = card.subtitle,
                        action = action,
                    )
                }
                else -> throw IllegalArgumentException("Unsupported hosted UI card type")
            }
        }
    }

    private fun isSensitiveArgumentKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        return listOf("token", "secret", "password", "credential", "apikey", "authorization")
            .any(normalized::contains)
    }

    @Suppress("DEPRECATION")
    private fun parseAction(content: Bundle): HostedPluginMainUiAction {
        require(content.keySet().all { it in actionContentKeys }) {
            "Unsupported hosted UI action content"
        }
        when (val result = BundleContractValidator.validateContentBundle(content)) {
            is ContractValidationResult.Err -> throw IllegalArgumentException("Invalid hosted UI action content")
            is ContractValidationResult.Ok -> Unit
        }

        val actionId = content.get(ACTION_ID)
        val actionLabel = content.get(ACTION_LABEL)
        val enabled = if (content.containsKey(ACTION_ENABLED)) content.get(ACTION_ENABLED) else true
        val argsJson = if (content.containsKey(ACTION_ARGS_JSON)) content.get(ACTION_ARGS_JSON) else ""
        require(
            actionId is String && actionId.isNotBlank() &&
                actionId.toByteArray(StandardCharsets.UTF_8).size <= MAX_ACTION_ID_BYTES
        ) { "Invalid hosted UI action id" }
        require(
            actionLabel is String && actionLabel.isNotBlank() &&
                actionLabel.toByteArray(StandardCharsets.UTF_8).size <= MAX_ACTION_LABEL_BYTES
        ) { "Invalid hosted UI action label" }
        require(enabled is Boolean) { "Invalid hosted UI action enabled flag" }
        require(argsJson is String) { "Invalid hosted UI action arguments" }

        val args = parseArgs(argsJson)
        return HostedPluginMainUiAction(actionId, actionLabel, enabled, args)
    }

    private fun parseArgs(json: String): Bundle {
        val args = Bundle()
        if (json.isNotEmpty()) {
            val parsed = try {
                val tokener = JSONTokener(json)
                val value = tokener.nextValue()
                require(value is JSONObject && tokener.nextClean() == '\u0000') {
                    "Hosted UI arguments must be a JSON object"
                }
                value
            } catch (_: JSONException) {
                throw IllegalArgumentException("Invalid hosted UI action JSON")
            }
            val keys = parsed.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = parsed.opt(key)
                require(value is String) { "Hosted UI action arguments must be strings" }
                args.putString(key, value)
            }
        }
        when (val result = BundleContractValidator.validateContentBundle(args)) {
            is ContractValidationResult.Err -> throw IllegalArgumentException("Invalid hosted UI action arguments")
            is ContractValidationResult.Ok -> Unit
        }
        return args
    }
}
