package com.aitech.weartak_civ.presentation

import android.app.Application
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.service.DittoPowerSuppression
import com.aitech.weartak_civ.presentation.theme.WearTAKCIVTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w192dp-h192dp-round")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkConnectivityIconsTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var renderedView: View

    @Test fun builtInArtworkMapsToEveryChecklistRoute() {
        val expected = mapOf(
            "tak" to R.drawable.tak_server_connected,
            "multicast" to R.drawable.multicast_connected,
            "sitx" to R.drawable.ic_connection_sitx_logo,
            "ditto" to R.drawable.ic_integration_ditto,
            "companion" to R.drawable.weartak_companion_plugin
        )
        expected.forEach { (key, icon) ->
            assertEquals(icon, NetworkConnectivityRow(key, key, "", false, {}).iconRes)
        }
        assertNull(networkConnectivityIcon("plugin:example"))
        assertEquals(R.drawable.tak_server_disconnected,
            NetworkConnectivityRow("tak", "TAK", "", false, {},
                iconRes = TakServerIndicator.NO_SERVER.drawable).iconRes)
    }

    @Test fun checklistIconsLabelsAndSwitchesFit192dpRound() = verifyRound(192)

    @Test fun powerSuppressedDittoKeepsItsToggleOnAndRecoversWithoutAnErrorState() {
        var suppressed by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        compose.setContent {
            WearTAKCIVTheme {
                NetworkConnectivityChip(
                    NetworkConnectivityRow("ditto", "Ditto", "Community", enabled,
                        { enabled = it }, suppressed = suppressed),
                    false, false, {}, {}, {})
            }
        }
        val icon = compose.onNode(hasContentDescription("Ditto") and !isToggleable(),
            useUnmergedTree = true)
        icon.assertIsDisplayed().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "Suppressed"))
        compose.onNode(isToggleable()).assertIsOn()
        compose.runOnIdle { suppressed = false }
        icon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { suppressed = true; enabled = false }
        compose.onNode(isToggleable()).assertIsOff()
        icon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun dittoOffWristSubtitleAndAccessibilityFollowReasonAndEnablement() {
        var reason by mutableStateOf<DittoPowerSuppression?>(DittoPowerSuppression.OFF_WRIST)
        var enabled by mutableStateOf(true)
        compose.setContent {
            val label = dittoSuppressionLabel(enabled, reason)
            val subtitle = label?.let { stringResource(it) } ?: "Community"
            WearTAKCIVTheme {
                NetworkConnectivityChip(
                    NetworkConnectivityRow("ditto", "Ditto", subtitle, enabled, { enabled = it },
                        statusDescription = if (label != null) "Community, $subtitle" else subtitle,
                        suppressed = label != null,
                        suppressionLabel = label ?: R.string.connection_route_suppressed),
                    false, false, {}, {}, {})
            }
        }
        val icon = compose.onNode(hasContentDescription("Ditto") and !isToggleable(),
            useUnmergedTree = true)
        compose.onNodeWithText("Off-Wrist").assertIsDisplayed()
        icon.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off-Wrist"))
        compose.onNode(isToggleable()).assertIsOn().assert(SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription, "On, Community, Off-Wrist"))
        compose.runOnIdle { reason = DittoPowerSuppression.POWER }
        compose.onNodeWithText("Suppressed").assertIsDisplayed()
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
        icon.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Suppressed"))
        compose.runOnIdle { reason = null }
        compose.onNodeWithText("Community").assertIsDisplayed()
        icon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { reason = DittoPowerSuppression.OFF_WRIST; enabled = false }
        compose.onNodeWithText("Off-Wrist").assertDoesNotExist()
        compose.onNodeWithText("Community").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff()
        icon.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun checklistIconsLabelsAndSwitchesFit227dpRound() = verifyRound(227)

    @Test fun sitxAssetIsTransparentWideAndSmall() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.ic_connection_sitx_logo)
        assertEquals(128, bitmap.width)
        assertEquals(48, bitmap.height)
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        assertTrue(bitmap.hasAlpha())
    }

    @Test fun sitxSettingsWordmarkFits192dpRound() = verifySitxSettings(192)

    @Test
    @Config(qualifiers = "w227dp-h227dp-round")
    fun sitxSettingsWordmarkFits227dpRound() = verifySitxSettings(227)

    private fun verifySitxSettings(size: Int) {
        compose.setContent {
            renderedView = LocalView.current
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(size.dp).padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center) {
                    WearTAKTitleChipWithState(
                        route = "sitx_tak_screen", title = "SITX TAK", state = true,
                        icon = { SitxBrandIcon("Sit(x) logo") },
                        navController = rememberNavController(),
                        formatState = { "Enabled" }
                    )
                }
            }
        }
        compose.onNodeWithText("SITX TAK").assertIsDisplayed()
        verifyCyanWordmark(compose.onNodeWithContentDescription("Sit(x) logo", useUnmergedTree = true),
            "sitx-settings-${size}dp")
    }

    private fun verifyCyanWordmark(icon: SemanticsNodeInteraction, name: String) {
        val bounds = icon.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888)
            renderedView.draw(Canvas(bitmap))
        }
        val cyanPixels = (bounds.top.toInt() until bounds.bottom.toInt()).sumOf { y ->
            (bounds.left.toInt() until bounds.right.toInt()).count { x ->
                val color = bitmap.getPixel(x, y)
                Color.red(color) < 80 && Color.green(color) > 120 && Color.blue(color) > 120
            }
        }
        assertTrue("Sit(x) must retain its cyan pixels at row size: $name", cyanPixels > 20)
        System.getenv("WEARTAK_SCREENSHOT_DIR")?.let { directory ->
            val output = File(directory).apply { mkdirs() }
            File(output, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun verifyRound(size: Int) {
        val labels = mapOf("tak" to ("TAK Servers" to "1/6"),
            "multicast" to ("TAK SA Multicast" to ""),
            "sitx" to ("SITX TAK" to "No group"),
            "ditto" to ("Ditto Edge Sync" to "Community"),
            "companion" to ("WearTAK Companion" to ""))
        var key by mutableStateOf("tak")
        var checked by mutableStateOf(true)
        var bonded by mutableStateOf(false)
        var density = 1f
        compose.setContent {
            renderedView = LocalView.current
            density = LocalDensity.current.density
            WearTAKCIVTheme {
                Box(Modifier.requiredSize(size.dp).padding(horizontal = 7.dp),
                    contentAlignment = Alignment.Center) {
                    val (title, subtitle) = labels.getValue(key)
                    NetworkConnectivityChip(
                        NetworkConnectivityRow(key, title, subtitle, checked, { checked = it }),
                        false, bonded, {}, {}, {})
                }
            }
        }
        labels.forEach { (route, label) ->
            compose.runOnIdle { key = route; checked = true }
            val icon = compose.onNode(hasContentDescription(label.first) and !isToggleable(),
                useUnmergedTree = true)
            icon.assertIsDisplayed()
            if (route == "sitx") verifyCyanWordmark(icon, "sitx-checklist-${size}dp-on")
            val text = compose.onNodeWithText(label.first, useUnmergedTree = true)
            text.assertIsDisplayed()
            val textLayouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
            val layout = textLayouts.single()
            assertFalse("${label.first} width overflow at $size", layout.didOverflowWidth)
            assertFalse("${label.first} height overflow at $size", layout.didOverflowHeight)
            assertFalse("${label.first} truncated at $size", layout.isLineEllipsized(layout.lineCount - 1))
            val toggle = compose.onNode(isToggleable()).assertIsOn()
            val iconBounds = icon.fetchSemanticsNode().boundsInRoot
            val textBounds = text.fetchSemanticsNode().boundsInRoot
            val switchBounds = toggle.fetchSemanticsNode().boundsInRoot
            assertTrue(iconBounds.right < textBounds.left)
            assertTrue(textBounds.right <= switchBounds.left)
            val radius = size * density / 2
            for (bounds in listOf(iconBounds, textBounds)) {
                for (corner in listOf(bounds.topLeft, bounds.topRight, bounds.bottomLeft, bounds.bottomRight)) {
                    val x = corner.x - radius
                    val y = corner.y - radius
                    assertTrue("${label.first} clipped by round face at $size: $bounds",
                        x * x + y * y <= radius * radius + 1)
                }
            }
            toggle.performClick().assertIsOff()
            icon.assertIsDisplayed()
            if (route == "sitx") verifyCyanWordmark(icon, "sitx-checklist-${size}dp-off")
            compose.runOnIdle { bonded = true }
            if (route == "companion") {
                compose.onNodeWithText("Bonded").assertDoesNotExist()
                compose.onNode(isToggleable()).assertIsEnabled()
                compose.runOnIdle { bonded = false }
                return@forEach
            }
            val bondedText = compose.onNodeWithText("Bonded", useUnmergedTree = true)
            bondedText.assertIsDisplayed()
            if (label.second.isNotEmpty()) compose.onNodeWithText(label.second).assertDoesNotExist()
            val bondedLayouts = mutableListOf<TextLayoutResult>()
            bondedText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(bondedLayouts) }
            assertFalse(bondedLayouts.single().didOverflowWidth)
            assertFalse(bondedLayouts.single().didOverflowHeight)
            val bondedBounds = bondedText.fetchSemanticsNode().boundsInRoot
            for (corner in listOf(bondedBounds.topLeft, bondedBounds.topRight, bondedBounds.bottomLeft, bondedBounds.bottomRight)) {
                val x = corner.x - radius
                val y = corner.y - radius
                assertTrue("Bonded clipped at $size: $bondedBounds", x * x + y * y <= radius * radius + 1)
            }
            compose.onNode(isToggleable()).assertIsNotEnabled()
            if (route == "sitx") verifyCyanWordmark(icon, "sitx-checklist-${size}dp-bonded")
            compose.runOnIdle { bonded = false }
        }
    }

    @Test fun suppliedPluginIconIsRenderedAndUpdatedWithoutASettingsDestination() {
        val first = ColorDrawable(Color.RED)
        val second = ColorDrawable(Color.BLUE)
        var drawable by mutableStateOf(first)
        lateinit var root: View
        compose.setContent {
            root = LocalView.current.rootView
            WearTAKCIVTheme {
                NetworkConnectivityChip(
                    NetworkConnectivityRow("plugin:mesh", "Mesh Plugin", "", true, {}, pluginIcon = drawable),
                    false, false, {}, {}, {})
            }
        }
        compose.onNodeWithText("Mesh Plugin").assertIsDisplayed()
        fun findIcon(view: View): ImageView? {
            if (view is ImageView && view.contentDescription == "Mesh Plugin") return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findIcon(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        compose.runOnIdle { assertSame(first, requireNotNull(findIcon(root)).drawable) }
        compose.runOnIdle { drawable = second }
        compose.runOnIdle { assertSame(second, requireNotNull(findIcon(root)).drawable) }
    }
}
