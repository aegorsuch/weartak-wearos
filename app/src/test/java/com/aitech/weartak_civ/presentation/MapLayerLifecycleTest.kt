package com.aitech.weartak_civ.presentation

import android.app.Application
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.eq
import org.maplibre.compose.expressions.dsl.Feature.get
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.collator
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.rememberStyleState
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import kotlin.coroutines.EmptyCoroutineContext
import org.maplibre.android.style.layers.SymbolLayer as NativeSymbolLayer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34],
    qualifiers = "w227dp-h227dp-round",
    shadows = [RecordingSymbolLayer::class, RecordingLayer::class, RecordingBackgroundLayer::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(InternalComposeApi::class)
class MapLayerLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Before fun resetRecording() {
        RecordingSymbolLayer.detachedFilterCalled = false
        RecordingSymbolLayer.filterCalls = 0
    }

    private fun markers(generation: Int): List<MapMarkerItem> = List(74) { index ->
        MapMarkerItem(
            "synthetic-$index",
            if (index < 70) {
                if (generation % 2 == 0) "a-n-A-C-F" else "a-n-A-C"
            } else {
                if (generation % 2 == 0) "a-f-G-U-C" else "a-f-G-U-C-I"
            },
            "Marker $index", 38.0 + generation * 0.0001, -77.0 + index * 0.0001,
            false, DataRepository.MarkerCotType.DROP
        )
    }

    @Composable
    private fun MarkerGroups(fixture: LayerTestComposition, markers: List<MapMarkerItem>) {
        val features = rememberMarkerFeatures(markers)
        check(features.features.size == 74)
        markers.groupBy { it.markerType }.keys.forEachIndexed { index, type ->
            SymbolLayer(
                "group-$index", fixture.source,
                filter = eq(
                    get("markerType").asString(), const(type),
                    collator(caseSensitive = true, diacriticSensitive = true, locale = "en")
                )
            )
        }
    }

    @Test fun stopResumeAndDestroyUseThePinnedMapLifecycleObserverDuringMarkerUpdates() {
        val fixture = LayerTestComposition()
        val generation = mutableStateOf(0)
        val showMap = mutableStateOf(true)
        val view = mockk<org.maplibre.android.maps.MapView>(relaxed = true)
        every { view.onDestroy() } answers { fixture.detachNativeLayers() }
        val observer = fixture.lifecycleObserver(view)
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        compose.setContent {
            if (showMap.value) {
                fixture.rememberLibraryComposition { MarkerGroups(fixture, markers(generation.value)) }
            }
        }
        try {
            compose.runOnIdle {
                owner.lifecycle.addObserver(observer)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            val originalLayers = fixture.layers.values.toList()
            repeat(10) {
                val before = RecordingSymbolLayer.filterCalls
                compose.runOnIdle {
                    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    generation.value++
                }
                compose.waitForIdle()
                assertTrue("The test must actually apply changed filters", RecordingSymbolLayer.filterCalls > before)
                compose.runOnIdle {
                    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    generation.value++
                }
                compose.waitForIdle()
                assertEquals(originalLayers, fixture.layers.values.toList())
                assertFalse(RecordingSymbolLayer.detachedFilterCalled)
            }
            verify(exactly = 10) { view.onStop() }
            verify(exactly = 0) { view.onDestroy() }
            val callsBeforeDestroy = RecordingSymbolLayer.filterCalls
            compose.runOnIdle {
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                generation.value++
            }
            compose.waitForIdle()
            verify(exactly = 1) { view.onDestroy() }
            assertEquals(callsBeforeDestroy, RecordingSymbolLayer.filterCalls)
            assertFalse(RecordingSymbolLayer.detachedFilterCalled)
        } finally {
            compose.runOnIdle { showMap.value = false }
            compose.waitForIdle()
            fixture.close()
        }
    }

    @Test fun wearUserNavigationPreservesLiveStyleWhileMarkerGroupsChange() {
        MainActivity.device_uid = "watch"
        val user = DataRepository.ReceivedUser(
            "synthetic-user", "a-f-G-U-C", "Blue", "Synthetic user", "Team Member", 1, 100,
            DataRepository.LocationSnapshot(1, 38.0, -77.0, null, null, null, null, null),
            "synthetic-server"
        )
        val generation = mutableStateOf(0)
        val fixtures = mutableListOf<LayerTestComposition>()
        var returned = 0
        compose.setContent {
            MapUserNavigation(
                listOf(user), LocalContext.current,
                onBloodhoundUserSelected = { returned++; true }
            ) { select ->
                val fixture = remember { LayerTestComposition().also { fixtures += it } }
                DisposableEffect(fixture) { onDispose { fixture.unload() } }
                fixture.rememberLibraryComposition { MarkerGroups(fixture, markers(generation.value)) }
                Chip(onClick = { select(user.uid) }, label = { Text("Select synthetic marker") })
            }
        }
        try {
            repeat(10) { index ->
                compose.runOnIdle { generation.value++ }
                compose.onNodeWithText("Select synthetic marker").performClick()
                compose.runOnIdle { generation.value++ }
                compose.onNodeWithContentDescription("Start Bloodhound").performClick()
                compose.waitForIdle()
                assertEquals(index + 1, returned)
                assertFalse(RecordingSymbolLayer.detachedFilterCalled)
                assertEquals(2, fixtures.last().layers.size)
            }
        } finally {
            compose.runOnIdle { fixtures.forEach { it.close() } }
        }
    }

    @Test fun disposingAndRecreatingLibraryStyleCompositionsKeepsDynamicMarkerGroupsSeparate() {
        val showMap = mutableStateOf(true)
        val generation = mutableStateOf(0)
        val current = mutableStateOf(LayerTestComposition())
        val fixtures = mutableListOf(current.value)
        compose.setContent {
            if (showMap.value) {
                val fixture = current.value
                DisposableEffect(fixture) {
                    onDispose { fixture.unload() }
                }
                fixture.rememberLibraryComposition { MarkerGroups(fixture, markers(generation.value)) }
            }
        }
        try {
            compose.waitForIdle()
            repeat(20) {
                val old = current.value
                val oldLayers = old.layers.values.toSet()
                assertEquals(2, oldLayers.size)
                compose.runOnIdle {
                    generation.value++
                    showMap.value = false
                }
                compose.waitForIdle()
                assertFalse("A disposed map must not apply a queued filter", RecordingSymbolLayer.detachedFilterCalled)
                val next = LayerTestComposition()
                fixtures += next
                compose.runOnIdle {
                    current.value = next
                    showMap.value = true
                    generation.value++
                }
                compose.waitForIdle()
                assertEquals(2, next.layers.size)
                assertTrue("A recreated map must own fresh wrappers", next.layers.values.none { it in oldLayers })
                assertFalse(RecordingSymbolLayer.detachedFilterCalled)
            }
        } finally {
            compose.runOnIdle { showMap.value = false }
            compose.waitForIdle()
            fixtures.forEach { it.close() }
        }
    }

    @Test fun pinnedLibraryCanApplyAQueuedFilterAfterItsStyleUnloads() {
        val harness = LayerTestComposition()
        val type = mutableStateOf("a-f-G")
        try {
            harness.setContent {
                SymbolLayer("test-layer", harness.source, filter = eq(
                    get("type").asString(), const(type.value),
                    collator(caseSensitive = true, diacriticSensitive = true, locale = "en")
                ))
            }
            type.value = "a-h-G"
            harness.recompose(type)
            harness.unload()
            harness.applyChanges()
            assertTrue(RecordingSymbolLayer.detachedFilterCalled)
        } finally { harness.close() }
    }

    @Test fun rapidVisibilityChangesPreserveStyleAndOverlayThenRestoreOriginalLayers() {
        val app = RuntimeEnvironment.getApplication()
        val hidden = mutableStateOf(emptySet<String>())
        val filter = mutableStateOf("a-f-G")
        val baseIds = setOf("roads", "labels")
        val harness = LayerTestComposition(baseIds)
        val styles = mutableListOf<BaseStyle.Json>()
        try {
            harness.setContent {
                styles += rememberBaseMapStyle(app)
                HiddenBaseMapLayers(baseIds, hidden.value)
                SymbolLayer("test-layer", harness.source, filter = eq(
                    get("type").asString(), const(filter.value),
                    collator(caseSensitive = true, diacriticSensitive = true, locale = "en")
                ))
            }
            val overlay = harness.layers.getValue("test-layer")
            val originalBaseLayers = baseIds.associateWith { harness.layers.getValue(it) }
            val choices = listOf(baseIds, setOf("roads"), setOf("labels"), setOf("old-unknown-id"), emptySet())
            repeat(30) { index ->
                hidden.value = choices[index % choices.size]
                filter.value = "type-$index"
                harness.recompose(hidden, filter)
                harness.applyChanges()
                assertSame(overlay, harness.layers.getValue("test-layer"))
                assertSame(styles.first(), styles.last())
                baseIds.forEach { id ->
                    if (id in hidden.value) {
                        assertFalse(harness.layers.containsKey(id))
                        assertTrue(harness.layers.containsKey("weartak-hidden-base-$id"))
                    } else {
                        assertSame(originalBaseLayers.getValue(id), harness.layers.getValue(id))
                        assertFalse(harness.layers.containsKey("weartak-hidden-base-$id"))
                    }
                }
                assertFalse(RecordingSymbolLayer.detachedFilterCalled)
            }
            assertEquals(baseIds + "test-layer", harness.layers.keys)
            val changes = harness.mutations.size
            harness.unload()
            harness.close()
            assertEquals("Disposal must not touch an unloaded native style", changes, harness.mutations.size)
            assertFalse(RecordingSymbolLayer.detachedFilterCalled)
        } finally { harness.close() }
    }

    @Test fun baseStyleRetainsEveryAssetLayerForRestoration() {
        val app = RuntimeEnvironment.getApplication()
        val style = BaseStyle.Json(app.assets.open("style.json").bufferedReader().use { it.readText() })
        val ids = baseMapLayerIds(style)
        assertTrue(ids.isNotEmpty())
        assertEquals(org.json.JSONObject(style.json).getJSONArray("layers").length(), ids.size)
        assertFalse(ids.any { it.startsWith("weartak-hidden-base-") })
    }

    private class LayerTestComposition(baseIds: Set<String> = emptySet()) : AutoCloseable {
        init {
            org.maplibre.android.utils.ThreadUtils.init(RuntimeEnvironment.getApplication())
        }
        private val ownedLayers = mutableSetOf<org.maplibre.android.style.layers.Layer>()
        private fun own(layer: Any): Any {
            val native = layer.javaClass.methods.first {
                it.name.startsWith("getImpl") && it.parameterCount == 0
            }.invoke(layer) as org.maplibre.android.style.layers.Layer
            ownedLayers += native
            return layer
        }
        val layers = linkedMapOf<String, Any>().apply {
            val constructor = Class.forName("org.maplibre.compose.layers.UnknownLayer")
                .declaredConstructors.single()
            baseIds.forEach { put(it, own(constructor.newInstance(NativeSymbolLayer(it, "base-source")))) }
        }
        val mutations = mutableListOf<String>()
        private val styleType = Class.forName("org.maplibre.compose.style.Style")
        private val style = Proxy.newProxyInstance(styleType.classLoader, arrayOf(styleType)) { proxy, method, args ->
            when (method.name) {
                "getLayers" -> layers.values.toList()
                "getSources" -> emptyList<Any>()
                "getLayer" -> layers[args!![0]]
                "addLayer", "addLayerAbove", "addLayerBelow", "addLayerAt" -> {
                    val layer = args!!.last()
                    val id = layer.javaClass.getMethod("getId").invoke(layer) as String
                    check(layers.put(id, own(layer)) == null) { "Duplicate layer $id" }
                    mutations += "add:$id"
                    null
                }
                "removeLayer" -> {
                    val id = args!![0].javaClass.getMethod("getId").invoke(args[0]) as String
                    check(layers.remove(id) != null) { "Missing layer $id" }
                    mutations += "remove:$id"
                    null
                }
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "RecordingStyle"
                else -> null
            }
        }
        private val safeStyleType = Class.forName("org.maplibre.compose.style.SafeStyle")
        private val safeStyle = safeStyleType.declaredConstructors.single().newInstance(style)
        private val nodeType = Class.forName("org.maplibre.compose.style.StyleNode")
        private val node = nodeType.declaredConstructors.single().newInstance(safeStyle, null)
        @Suppress("UNCHECKED_CAST")
        private val applier = Class.forName("org.maplibre.compose.style.MapNodeApplier")
            .declaredConstructors.single().newInstance(node) as AbstractApplier<Any>
        @Suppress("UNCHECKED_CAST")
        private val local = Class.forName("org.maplibre.compose.style.RememberStyleCompositionKt")
            .getMethod("getLocalStyleNode").invoke(null) as ProvidableCompositionLocal<Any>
        val source = mockk<GeoJsonSource>(relaxed = true)
        private val recomposer = Recomposer(EmptyCoroutineContext)
        private val composition = Composition(applier, recomposer) as ControlledComposition
        private var closed = false
        private var pendingChanges = false

        @Composable
        fun rememberLibraryComposition(content: @Composable () -> Unit) {
            val state = rememberStyleState()
            val method = Class.forName("org.maplibre.compose.style.RememberStyleCompositionKt")
                .methods.single { it.name == "rememberStyleComposition" }
            method.invoke(null, state, safeStyle, null, content, currentComposer, 0)
        }

        fun setContent(content: @Composable () -> Unit) {
            composition.setContent {
                CompositionLocalProvider(
                    local provides node, LocalDensity provides Density(1f),
                    LocalLayoutDirection provides LayoutDirection.Ltr
                ) { content() }
            }
        }

        fun recompose(vararg changed: Any) {
            composition.recordModificationsOf(changed.toSet())
            val snapshot = Snapshot.takeMutableSnapshot(
                readObserver = composition::recordReadOf,
                writeObserver = composition::recordWriteOf
            )
            try {
                pendingChanges = snapshot.enter { composition.recompose() }
                snapshot.apply().check()
            } finally { snapshot.dispose() }
        }

        fun applyChanges() {
            if (!pendingChanges) return
            composition.applyChanges()
            composition.applyLateChanges()
            composition.changesApplied()
            pendingChanges = false
        }

        fun unload() {
            safeStyleType.methods.single { it.name.startsWith("unload") }.invoke(safeStyle)
            detachNativeLayers()
        }

        fun detachNativeLayers() {
            ownedLayers.forEach { it.setDetached() }
        }

        fun lifecycleObserver(view: org.maplibre.android.maps.MapView): LifecycleEventObserver =
            Class.forName("org.maplibre.compose.map.MapViewLifecycleObserver")
                .declaredConstructors.single().newInstance(view, safeStyle) as LifecycleEventObserver

        override fun close() {
            if (closed) return
            closed = true
            unload()
            composition.dispose()
            recomposer.cancel()
        }
    }
}

@Implements(org.maplibre.android.style.layers.Layer::class, isInAndroidSdk = false)
open class RecordingLayer {
    protected var layerId = ""
    @Implementation fun getId(): String = layerId
    @Implementation fun setMinZoom(value: Float) {}
    @Implementation fun setMaxZoom(value: Float) {}
    @Implementation fun setProperties(vararg values: PropertyValue<*>) {}
}

@Implements(NativeSymbolLayer::class, isInAndroidSdk = false)
class RecordingSymbolLayer : RecordingLayer() {
    @RealObject private lateinit var layer: NativeSymbolLayer
    @Implementation fun __constructor__(layerId: String, sourceId: String) {
        this.layerId = layerId
    }

    @Implementation fun setSourceLayer(value: String) {}
    @Implementation fun setFilter(value: Expression) {
        filterCalls++
        if (layer.isDetached) detachedFilterCalled = true
    }

    companion object {
        var detachedFilterCalled = false
        var filterCalls = 0
    }
}

@Implements(org.maplibre.android.style.layers.BackgroundLayer::class, isInAndroidSdk = false)
class RecordingBackgroundLayer : RecordingLayer() {
    @Implementation fun __constructor__(layerId: String) {
        this.layerId = layerId
    }
}
