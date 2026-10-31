package com.aitech.weartak_civ.presentation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat
import com.aitech.weartak_civ.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntegrationIconsTest {
    private val expected = linkedMapOf(
        BuiltInPlugin.BATDOK to R.drawable.batdok,
        BuiltInPlugin.COMMANDS to R.drawable.ic_commands_megaphone,
        BuiltInPlugin.COMPANION to R.drawable.weartak_companion_plugin,
        BuiltInPlugin.CRM to R.drawable.ic_plugin_gauge,
        BuiltInPlugin.SENSOR_MANAGER to R.drawable.ic_sensor_manager,
        BuiltInPlugin.SOMEWEAR_LABS to R.drawable.ic_integration_somewear,
        BuiltInPlugin.EVERYWHERE_COMMS to R.drawable.ic_integration_everywhere,
        BuiltInPlugin.DART to R.drawable.ic_integration_certus,
        BuiltInPlugin.FALCON to R.drawable.ic_integration_certus,
        BuiltInPlugin.DITTO to R.drawable.ic_integration_ditto,
        BuiltInPlugin.DATA_SYNC to R.drawable.ic_data_sync,
    )

    @Test
    fun allIconEntriesUseTheirArtwork() {
        expected.forEach { (plugin, resource) -> assertEquals(resource, plugin.brandIconRes) }
        assertEquals(expected.keys, BuiltInPlugin.entries.filter { it.brandIconRes != null }.toSet())
    }

    @Test
    fun artworkRendersAtWatchSizesWithoutLosingVendorColors() {
        val resources = RuntimeEnvironment.getApplication().resources
        val preview = Bitmap.createBitmap(320, expected.size * 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.DKGRAY)
        val label = Paint().apply { color = Color.WHITE; textSize = 12f }
        expected.entries.forEachIndexed { index, (plugin, resource) ->
            val drawable = ResourcesCompat.getDrawable(resources, resource, null)
            assertNotNull(drawable)
            requireNotNull(drawable)
            val icon = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            val ratio = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
            val width = if (ratio > 1) 24 else (24 * ratio).toInt()
            val height = if (ratio > 1) (24 / ratio).toInt() else 24
            val left = (24 - width) / 2
            val top = (24 - height) / 2
            drawable.setBounds(left, top, left + width, top + height)
            drawable.draw(Canvas(icon))
            val pixels = IntArray(24 * 24)
            icon.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            assertTrue("$plugin must remain visible at 24px", pixels.count { Color.alpha(it) > 128 } > 60)
            if (plugin == BuiltInPlugin.DART || plugin == BuiltInPlugin.FALCON) {
                assertTrue("Certus teal must survive rendering", pixels.any {
                    Color.alpha(it) > 128 && Color.green(it) > Color.red(it) + 40 &&
                        Color.blue(it) > Color.red(it) + 40
                })
            }
            canvas.drawText(plugin.name, 8f, index * 64f + 16f, label)
            canvas.drawBitmap(icon, 8f, index * 64f + 28f, null)
            drawable.setBounds(64, index * 64 + 24, 64 + width * 4 / 3, index * 64 + 24 + height * 4 / 3)
            drawable.draw(canvas)
        }
        val output = File(File(System.getProperty("user.home")).parentFile, "reports/integration-icons.png")
        output.parentFile.mkdirs()
        output.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
