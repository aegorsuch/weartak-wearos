package com.aitech.weartak_civ.market

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarketAdminLockTest {
    @Test
    fun lockPersistsAndBlocksInstallerBeforePermissionOrDownload() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("market_admin", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val lock = MarketAdminLock(context)
            assertFalse(lock.locked.value)
            assertTrue(lock.setLocked(true))
            val restored = MarketAdminLock(context)
            assertTrue(restored.locked.value)
            assertNull(restored.ifUnlocked { "submitted" })
            val repository = mockk<MarketCatalogRepository>()
            val installer = MarketInstallCoordinator(context, repository, restored)
            assertEquals(MarketInstallResult.Locked, installer.install(plugin()))
            coVerify(exactly = 0) { repository.downloadVerified(any(), any()) }
            assertTrue(restored.setLocked(false))
            assertEquals("submitted", restored.ifUnlocked { "submitted" })
            assertFalse(MarketAdminLock(context).locked.value)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun lockSuppressesChecksAndStaleResultsAndUnlockRefreshes() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("market_monitor_lock_test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val context = mockk<Context>()
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { context.applicationContext } returns context
        every { context.packageName } returns "com.aitech.weartak_civ"
        val packages = mockk<PackageManager>()
        every { context.packageManager } returns packages
        every { context.getSharedPreferences("market_admin", Context.MODE_PRIVATE) } returns preferences
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val repository = mockk<MarketCatalogRepository>()
        val signerBytes = byteArrayOf(1, 2, 3)
        val release = plugin().copy(signerSha256 = sha256(signerBytes))
        every { packages.getPackageInfo(release.packageName, any<PackageManager.PackageInfoFlags>()) } returns
            PackageInfo().apply {
                setLongVersionCode(0)
                signingInfo = mockk<SigningInfo> {
                    every { apkContentsSigners } returns arrayOf(Signature(signerBytes))
                }
            }
        var fetches = 0
        coEvery { repository.fetchCatalog(any()) } coAnswers {
            if (++fetches == 1) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { releaseFirst.await() }
            }
            MarketCatalog(listOf(release))
        }
        mockkObject(MarketConfiguration.Companion)
        try {
            every { MarketConfiguration.fromBuildConfig() } returns MarketConfiguration("https://example.org/catalog", "key")
            MarketUpdateMonitor(context, repository).use { monitor ->
                assertTrue(monitor.setMarketLocked(true))
                monitor.start()
                monitor.refresh()
                assertEquals(MarketRefreshStatus.LOCKED, monitor.snapshot.value.status)
                coVerify(exactly = 0) { repository.fetchCatalog(any()) }

                assertTrue(monitor.setMarketLocked(false))
                withTimeout(5_000) { firstStarted.await() }
                assertTrue(monitor.setMarketLocked(true))
                releaseFirst.complete(Unit)
                assertEquals(MarketUpdateSnapshot(MarketRefreshStatus.LOCKED), monitor.snapshot.value)

                assertTrue(monitor.setMarketLocked(false))
                withTimeout(5_000) {
                    monitor.snapshot.first { it.status == MarketRefreshStatus.READY }
                }
                assertEquals(2, fetches)
                assertTrue(monitor.snapshot.value.showUpdateWarning)
                assertTrue(monitor.setMarketLocked(true))
                assertEquals(MarketUpdateSnapshot(MarketRefreshStatus.LOCKED), monitor.snapshot.value)
                monitor.refresh()
                coVerify(exactly = 2) { repository.fetchCatalog(any()) }
            }
        } finally {
            releaseFirst.complete(Unit)
            unmockkObject(MarketConfiguration.Companion)
            preferences.edit().clear().commit()
        }
    }

    private fun plugin() = MarketPlugin(
        id = "weather",
        name = "Weather",
        description = "",
        publisher = "Example",
        publisherType = MarketPublisherType.COMMUNITY,
        packageName = "org.example.weather",
        versionName = "1",
        versionCode = 1,
        minHostVersionCode = 1,
        maxHostVersionCode = null,
        apkUrl = "https://example.org/weather.apk",
        apkSha256 = "a".repeat(64),
        signerSha256 = "b".repeat(64),
        sourceUrl = null,
        hostUpdate = false,
    )
}
