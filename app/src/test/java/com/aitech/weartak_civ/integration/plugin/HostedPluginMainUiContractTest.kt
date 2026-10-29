package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.model.MainUiCardParcel
import com.weartak.plugin.sdk.model.MainUiCardTypes
import com.weartak.plugin.sdk.model.PluginMainUiSchemaParcel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val ACTION_CARD_TYPE = "ACTION"

@RunWith(RobolectricTestRunner::class)
class HostedPluginMainUiContractTest {
    @Test
    fun parsesStatusAndActionCardsForAnyPlugin() {
        val schema = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = MainUiCardTypes.STATUS,
                    cardId = "status",
                    title = "Service unavailable",
                    subtitle = "The service is not connected",
                ),
                MainUiCardParcel(
                    cardType = ACTION_CARD_TYPE,
                    cardId = "refresh",
                    title = "Refresh status",
                    content = hostedActionContent(
                        actionId = "telemetry.refresh",
                        actionLabel = "Refresh",
                    ),
                ),
            ),
        )

        val cards = HostedPluginMainUiContract.parse(schema)

        assertEquals(2, cards.size)
        assertEquals("The service is not connected", cards.first().subtitle)
        val action = cards.last().action!!
        assertEquals("telemetry.refresh", action.actionId)
        assertEquals("Refresh", action.actionLabel)
        assertTrue(action.enabled)
        assertEquals(0, action.args.size())
    }

    @Test
    fun parsesPluginDefinedActionArguments() {
        val schema = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = ACTION_CARD_TYPE,
                    cardId = "open-record",
                    title = "Open record",
                    content = hostedActionContent(
                        actionId = "fieldtools.record.open",
                        actionLabel = "Open",
                        args = mapOf("recordId" to "record-17"),
                    ),
                ),
            ),
        )

        val action = HostedPluginMainUiContract.parse(schema).single().action!!

        assertEquals("fieldtools.record.open", action.actionId)
        assertEquals("record-17", action.args.getString("recordId"))
    }

    @Test
    fun respectsDisabledActionCards() {
        val schema = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = ACTION_CARD_TYPE,
                    cardId = "disabled",
                    title = "Disabled action",
                    content = hostedActionContent(
                        actionId = "example.refresh",
                        actionLabel = "Refresh",
                        enabled = false,
                    ),
                ),
            ),
        )

        assertFalse(
            HostedPluginMainUiContract.parse(schema)
                .single().action!!.enabled
        )
    }

    @Test
    fun rejectsMalformedActionAndUnknownCardTypes() {
        val missingActionLabel = Bundle().apply {
            putString("actionId", "example.invalid")
        }
        val malformedSchema = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = ACTION_CARD_TYPE,
                    cardId = "bad",
                    title = "Bad action",
                    content = missingActionLabel,
                ),
            ),
        )
        val unknownCardSchema = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = "CUSTOM_WIDGET",
                    cardId = "unknown",
                    title = "Unsupported",
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            HostedPluginMainUiContract.parse(malformedSchema)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostedPluginMainUiContract.parse(unknownCardSchema)
        }
    }

    @Test
    fun rejectsCredentialArgumentKeysForAnyPlugin() {
        val secretArgument = PluginMainUiSchemaParcel(
            cards = listOf(
                MainUiCardParcel(
                    cardType = ACTION_CARD_TYPE,
                    cardId = "refresh",
                    title = "Refresh",
                    content = hostedActionContent(
                        actionId = "fieldtools.refresh",
                        actionLabel = "Refresh",
                        args = mapOf("access_token" to "never-forward"),
                    ),
                ),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            HostedPluginMainUiContract.parse(secretArgument)
        }
    }

}

internal fun hostedActionContent(
    actionId: String,
    actionLabel: String,
    enabled: Boolean = true,
    args: Map<String, String> = emptyMap(),
): Bundle = Bundle().apply {
    putString("actionId", actionId)
    putString("actionLabel", actionLabel)
    putBoolean("enabled", enabled)
    putString("actionArgsJson", JSONObject(args).toString())
}
