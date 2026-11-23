package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.aitech.weartak_civ.service.DittoCommunityLicenseHelper
import com.weartak.plugin.sdk.community.v1.CommunityIdentityContract as Contract
import com.weartak.plugin.sdk.community.v1.ICommunityIdentityControlV1
import java.security.MessageDigest

internal class HostCommunityIdentityControl(
    private val expectedUid: Int,
    private val bindingActive: () -> Boolean,
    private val authorize: () -> Boolean,
    private val ownershipConfirmed: () -> Boolean,
    private val communityIdentity: () -> String,
    private val callingUid: () -> Int = Binder::getCallingUid,
) : ICommunityIdentityControlV1.Stub() {
    @Volatile private var active = true
    @Volatile private var ready = false

    fun ready() { if (active) ready = true }
    fun close() { active = false; ready = false }

    override fun getVersion(): Int = Contract.VERSION

    override fun readCommunityIdentity(): Bundle {
        if (callingUid() != expectedUid) return rejected(Contract.UNAUTHORIZED)
        if (!active || !ready || !bindingActive()) return rejected(Contract.INACTIVE)
        if (!authorize()) {
            close()
            return rejected(Contract.UNAUTHORIZED)
        }
        if (!ownershipConfirmed()) return rejected(Contract.UNAVAILABLE)
        val identity = communityIdentity()
        val valid = try {
            identity.length <= Contract.MAX_IDENTITY_LENGTH &&
                !DittoCommunityLicenseHelper.parse(identity).isExpired
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!valid) return rejected(Contract.UNAVAILABLE)
        if (!active || !ready || !bindingActive() || !ownershipConfirmed()) {
            return rejected(Contract.INACTIVE)
        }
        if (!authorize()) {
            close()
            return rejected(Contract.UNAUTHORIZED)
        }
        return Bundle().apply {
            putString(Contract.KEY_STATUS, Contract.OK)
            putString(Contract.KEY_IDENTITY, identity)
        }
    }

    private fun rejected(status: String) = Bundle().apply {
        Log.w("WearTAK-Community", "Community identity request rejected: $status")
        putString(Contract.KEY_STATUS, status)
    }
}

internal object TrustedCommunityPlugin {
    const val PACKAGE = "com.weartak.plugin.ditto"
    const val GATE_VERSION_NAME = "5.8.4-p62+b5ab0265"
    private val releaseDigest = "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun verify(context: Context, uid: Int): Boolean {
        val pm = context.packageManager
        return try {
            val packages = pm.getPackagesForUid(uid)?.toSet() ?: return false
            if (packages != setOf(PACKAGE)) return false
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            val digests = signers.map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
            }
            matches(uid, packages, info.applicationInfo?.uid, digests)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun supportsQuiescentReplacement(context: Context, uid: Int): Boolean {
        val pm = context.packageManager
        return try {
            val packages = pm.getPackagesForUid(uid)?.toSet() ?: return false
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val signers = info.signingInfo?.apkContentsSigners ?: return false
            val digests = signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
            matchesQuiescentReplacement(uid, packages, info.applicationInfo?.uid, digests,
                info.longVersionCode, info.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun matches(uid: Int, packages: Set<String>, packageUid: Int?, digests: List<ByteArray>): Boolean =
        packages == setOf(PACKAGE) && packageUid == uid && digests.size == 1 &&
            MessageDigest.isEqual(releaseDigest, digests.single())

    internal fun matchesQuiescentReplacement(
        uid: Int, packages: Set<String>, packageUid: Int?, digests: List<ByteArray>,
        versionCode: Long, versionName: String?,
    ): Boolean = matches(uid, packages, packageUid, digests) &&
        versionCode == 5840062L && versionName == GATE_VERSION_NAME
}
