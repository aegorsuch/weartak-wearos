package com.aitech.weartak_civ.market

import android.app.Application
import android.content.Context
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.aitech.weartak_civ.BuildConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MarketSecurityPolicyTest {
    private val hostPackage = "com.aitech.weartak_civ"
    private val signerBytes = byteArrayOf(1, 2, 3)
    private val signer = sha256(signerBytes)

    @Test
    fun comparesInstalledVersionAndHostCompatibility() {
        assertEquals(
            MarketInstallState.INSTALL,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, null, null, false),
        )
        assertEquals(
            MarketInstallState.UPDATE,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 4, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.UP_TO_DATE,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 5, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.NEWER_VERSION_INSTALLED,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 6, setOf(signer), false),
        )
        assertEquals(
            MarketInstallState.INCOMPATIBLE,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 201, null, null, false),
        )
    }

    @Test
    fun blocksPlayManagedHostUpdatesAndSignerChanges() {
        val hostUpdate = plugin(packageName = hostPackage, hostUpdate = true)
        assertEquals(
            MarketInstallState.PLAY_MANAGED_HOST,
            MarketSecurityPolicy.installState(hostUpdate, hostPackage, 100, 4, setOf(signer), true),
        )
        assertEquals(
            MarketInstallState.SIGNER_MISMATCH,
            MarketSecurityPolicy.installState(plugin(), hostPackage, 100, 4, setOf("c".repeat(64)), false),
        )
    }

    @Test
    fun uninstalledPluginRemainsInCatalogWithoutTriggeringUpdateWarning() {
        val release = plugin().copy(minHostVersionCode = 1, maxHostVersionCode = null)
        val snapshot = refreshSnapshot(listOf(release), emptyMap())

        assertEquals(listOf(release), snapshot.catalog?.plugins)
        assertTrue(snapshot.availableUpdates.isEmpty())
        assertFalse(snapshot.showUpdateWarning)
    }

    @Test
    fun installedPluginUpdateTriggersWarningButNewPluginDoesNotCount() {
        val installed = plugin().copy(minHostVersionCode = 1, maxHostVersionCode = null)
        val newPlugin = installed.copy(id = "new", packageName = "org.example.new")
        val snapshot = refreshSnapshot(listOf(installed, newPlugin), mapOf(installed.packageName to 4L))

        assertEquals(listOf(installed, newPlugin), snapshot.catalog?.plugins)
        assertEquals(listOf(installed), snapshot.availableUpdates)
        assertTrue(snapshot.showUpdateWarning)
    }

    @Test
    fun newerCompatibleOwnHostTriggersUpdateWarning() {
        val installedVersion = BuildConfig.VERSION_CODE.toLong()
        val host = plugin(packageName = hostPackage, hostUpdate = true)
            .copy(versionCode = installedVersion + 1, minHostVersionCode = 1, maxHostVersionCode = null)
        val snapshot = refreshSnapshot(listOf(host), mapOf(hostPackage to installedVersion))

        assertEquals(listOf(host), snapshot.availableUpdates)
        assertTrue(snapshot.showUpdateWarning)
    }

    @Test
    fun reviewedUpdatesStayHiddenThroughCatalogChangesUntilProcessRestart() {
        val first = plugin()
        val second = plugin(id = "another", packageName = "org.example.another")
        val updates = listOf(first, second)
        val review = MarketUpdateReview()

        assertTrue(review.shouldShowWarning(updates))
        review.markReviewed(updates)
        assertFalse(review.shouldShowWarning(updates.reversed()))
        assertFalse(review.shouldShowWarning(listOf(first.copy(versionCode = 6), second)))
        assertFalse(review.shouldShowWarning(listOf(second)))
        assertFalse(review.shouldShowWarning(emptyList()))
        assertFalse(review.shouldShowWarning(updates))

        val restartedProcessReview = MarketUpdateReview()
        assertTrue(restartedProcessReview.shouldShowWarning(updates))
    }

    @Test
    fun openingMarketWithoutUpdatesDoesNotSuppressLaterWarning() {
        val review = MarketUpdateReview()
        assertFalse(review.shouldShowWarning(emptyList()))
        review.markReviewed(emptyList())
        assertTrue(review.shouldShowWarning(listOf(plugin())))
    }

    @Test
    fun archiveMustMatchCatalogPackageVersionAndSigner() {
        val release = plugin()
        assertTrue(MarketSecurityPolicy.archiveMatches(release, release.packageName, 5, setOf(signer)))
        assertFalse(MarketSecurityPolicy.archiveMatches(release, "org.example.other", 5, setOf(signer)))
        assertFalse(MarketSecurityPolicy.archiveMatches(release, release.packageName, 6, setOf(signer)))
        assertFalse(MarketSecurityPolicy.archiveMatches(release, release.packageName, 5, setOf("c".repeat(64))))
    }

    @Test
    fun computesStandardSha256Hex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".toByteArray()),
        )
    }

    private fun refreshSnapshot(
        releases: List<MarketPlugin>,
        installedVersions: Map<String, Long>,
    ): MarketUpdateSnapshot = runBlocking {
        val context = mockk<Context>()
        val packages = mockk<PackageManager>()
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { context.applicationContext } returns context
        every { context.packageName } returns hostPackage
        every { context.packageManager } returns packages
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns true
        every { packages.getPackageInfo(any<String>(), any<PackageManager.PackageInfoFlags>()) } answers {
            val name = firstArg<String>()
            val version = installedVersions[name] ?: throw PackageManager.NameNotFoundException(name)
            PackageInfo().apply {
                setLongVersionCode(version)
                signingInfo = mockk<SigningInfo> {
                    every { apkContentsSigners } returns arrayOf(Signature(signerBytes))
                }
            }
        }
        every { packages.getInstallSourceInfo(hostPackage) } returns mockk<InstallSourceInfo>(relaxed = true)
        val repository = mockk<MarketCatalogRepository>()
        coEvery { repository.fetchCatalog(hostPackage) } returns MarketCatalog(releases)
        mockkObject(MarketConfiguration.Companion)
        try {
            every { MarketConfiguration.fromBuildConfig() } returns MarketConfiguration("https://example.org/catalog", "key")
            MarketUpdateMonitor(context, repository).use { monitor ->
                monitor.start()
                withTimeout(5_000) {
                    monitor.snapshot.first { it.status == MarketRefreshStatus.READY }
                }
            }
        } finally {
            unmockkObject(MarketConfiguration.Companion)
        }
    }

    private fun plugin(
        id: String = "weather",
        packageName: String = "org.example.weather",
        hostUpdate: Boolean = false,
    ) = MarketPlugin(
        id = id,
        name = "Weather",
        description = "Field weather",
        publisher = "Community",
        publisherType = if (hostUpdate) MarketPublisherType.OFFICIAL else MarketPublisherType.COMMUNITY,
        packageName = packageName,
        versionName = "1.0",
        versionCode = 5,
        minHostVersionCode = 90,
        maxHostVersionCode = 200,
        apkUrl = "https://releases.example/weather.apk",
        apkSha256 = "b".repeat(64),
        signerSha256 = signer,
        sourceUrl = null,
        hostUpdate = hostUpdate,
    )
}
