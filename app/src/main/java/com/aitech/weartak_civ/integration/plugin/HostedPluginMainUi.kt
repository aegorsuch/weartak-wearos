package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.MainUiActionContract
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import com.weartak.plugin.sdk.validation.ContractValidationResult
import com.weartak.plugin.sdk.validation.MainUiSchemaContractValidator
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
    val actionPresentation: String = MainUiActionContract.TEXT,
    val actionIcon: String? = null,
    val actionPlacement: String = MainUiActionContract.BODY,
    val actionGroup: String? = null,
    val showTitle: Boolean = true,
)

internal sealed interface HostedPluginMainUiResult {
    data class Ready(val cards: List<HostedPluginMainUiCard>) : HostedPluginMainUiResult
    data object NotConnected : HostedPluginMainUiResult
    data object Unsupported : HostedPluginMainUiResult
    data class Failed(val message: String) : HostedPluginMainUiResult
}

internal object HostedPluginMainUiContract {
    private const val ACTION_CARD_TYPE = "ACTION"

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

    private fun parseAction(content: Bundle): HostedPluginMainUiAction {
        return when (val result = MainUiActionContract.parseContent(content)) {
            is ContractValidationResult.Err ->
                throw IllegalArgumentException("Invalid hosted UI action content")
            is ContractValidationResult.Ok -> result.value.let {
                HostedPluginMainUiAction(
                    it.actionId, it.actionLabel, it.enabled, it.args,
                    it.actionPresentation, it.actionIcon, it.actionPlacement, it.actionGroup, it.showTitle,
                )
            }
        }
    }
}
