package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.weartak.plugin.sdk.MainUiActionContract as C
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
        assertEquals(C.TEXT, action.actionPresentation)
        assertEquals(C.BODY, action.actionPlacement)
        assertTrue(action.showTitle)
    }

    @Test fun parsesAccessibleIconGroupHeaderAndTitleFreeTextWithoutChangingArgs() {
        val cards = listOf(
            presentedCard("back", C.BACK, placement = C.HEADER_END),
            presentedCard("note", C.NOTE, group = "incident.actions"),
            presentedCard("bloodhound", C.BLOODHOUND, group = "incident.actions"),
            presentedCard("target", C.TARGET, group = "incident.actions", enabled = false),
            MainUiCardParcel(ACTION_CARD_TYPE, "server", "Server choice", content =
                hostedActionContent("server.select", "Server Alpha").apply {
                    putBoolean(C.SHOW_TITLE, false)
                }),
        )
        val parsed = HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(cards = cards))
        assertEquals(C.HEADER_END, parsed.first().action!!.actionPlacement)
        assertEquals(listOf(C.NOTE, C.BLOODHOUND, C.TARGET),
            parsed.subList(1, 4).map { it.action!!.actionIcon })
        assertTrue(parsed.all { it.action!!.actionLabel.isNotBlank() && !it.action.showTitle })
        assertEquals("incident-17", parsed[2].action!!.args.getString("incidentId"))
        assertFalse(parsed[3].action!!.enabled)
        assertEquals(C.TEXT, parsed.last().action!!.actionPresentation)
        assertEquals(C.STOP, HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(
            cards = listOf(presentedCard("stop", C.STOP)),
        )).single().action!!.actionIcon)
    }

    @Test fun rejectsUnsafePresentationAndDuplicateHeaders() {
        val valid = presentedCard("action", C.NOTE)
        val invalidContents = listOf(
            Bundle(valid.content).apply { putString(C.ACTION_ICON, "CUSTOM") },
            Bundle(valid.content).apply { remove(C.ACTION_LABEL) },
            Bundle(valid.content).apply { putString(C.ACTION_LABEL, "") },
            Bundle(valid.content).apply { putString(C.SHOW_TITLE, "false") },
            Bundle(valid.content).apply { putBoolean(C.SHOW_TITLE, true) },
            Bundle(valid.content).apply { putString(C.ACTION_PLACEMENT, C.HEADER_END) },
            Bundle(valid.content).apply { putString(C.ACTION_ARGS_JSON, "{\"access_token\":\"secret\"}") },
            Bundle(valid.content).apply { putString("unknown", "value") },
        )
        invalidContents.forEach { content ->
            assertThrows(IllegalArgumentException::class.java) {
                HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(
                    cards = listOf(valid.copy(content = content)),
                ))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            HostedPluginMainUiContract.parse(PluginMainUiSchemaParcel(cards = listOf(
                presentedCard("back-1", C.BACK, placement = C.HEADER_END),
                presentedCard("back-2", C.BACK, placement = C.HEADER_END),
            )))
        }
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

internal fun presentedCard(
    id: String,
    icon: String,
    placement: String = C.BODY,
    group: String? = null,
    enabled: Boolean = true,
) = MainUiCardParcel(ACTION_CARD_TYPE, id, "$id title", content = C.createPresentedContent(
    actionId = id,
    actionLabel = "$id label",
    actionPresentation = C.ICON,
    actionIcon = icon,
    actionPlacement = placement,
    actionGroup = group,
    showTitle = false,
    enabled = enabled,
    args = if (placement == C.BODY) mapOf("incidentId" to "incident-17") else emptyMap(),
))
