package com.aitech.weartak_civ.cep.plugin

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * v1 trust gate: the broadcaster's UID must own the claimed [pluginId] (sender-package
 * binding), and the SHA-256 of the package's signing cert must match the first-handshake
 * pinned value on subsequent submissions (cert pinning). A single TAK Product Center
 * signer is trusted even when replacing an older per-package pin.
 *
 * Per WEAR-508/509 design §477 the trust contract is "this UID claims this pluginId — does
 * the pinned cert match the package that owns this UID?" — NOT "does the pinned cert match
 * whatever package happens to have this name?". The receiver layer is responsible for
 * resolving the caller's UID and package set (via `Binder.getCallingUid()` +
 * `PackageManager.getPackagesForUid`) BEFORE invoking [enforce].
 */
class PluginTrust(
    private val context: Context,
    private val repo: DataRepository,
) {
    private val certMutex = Mutex()

    /**
     * Bind the claimed [pluginId] to the resolved sender identity, then run cert pinning.
     *
     * @param pluginId the pluginId claimed by the broadcast.
     * @param senderUid uid of the calling process, resolved by the receiver and checked
     *   against the installed package's uid.
     * @param senderPackages the packages owned by the caller's UID. If [pluginId] is not in
     *   this set, the broadcast is rejected as a sender/package mismatch — closing the
     *   first-handshake spoofability gap called out in the 2026-05-11 review.
     *
     * Throws [ParserException] with [ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH] or
     * [ReasonCodes.PLUGIN_CERT_CHANGED] on failure.
     */
    suspend fun enforce(pluginId: String, senderUid: Int, senderPackages: Set<String>) {
        // Sender-package binding: the calling UID must actually own the claimed pluginId.
        if (pluginId !in senderPackages) {
            throw ParserException(
                ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
                "sender uid=$senderUid pkgs=${senderPackages.joinToString()} does not own pluginId=$pluginId"
            )
        }

        certMutex.withLock {
            val certMap = repo.getCepPluginCertLogOnce()
            val (packageUid, digests) = packageSigners(pluginId)
            if (packageUid != senderUid) {
                throw ParserException(
                    ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
                    "sender uid=$senderUid does not match package uid for $pluginId"
                )
            }
            val pinned = certMap[pluginId]
            val digest = resolveCertificatePin(pluginId, digests, pinned)
            if (pinned != digest) {
                repo.storeCepPluginCertLog(certMap + (pluginId to digest))
            }
        }
    }

    private fun packageSigners(packageName: String): Pair<Int?, List<String>> {
        val pm = context.packageManager
        val pkg: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signatures = pkg.signingInfo?.apkContentsSigners
            ?: throw ParserException(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH, "package not signed: $packageName")
        val digests = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        return pkg.applicationInfo?.uid to digests
    }

    internal companion object {
        private const val PRODUCT_CENTER_SIGNER =
            "1579b600cd84e619e76a91c2db6b031938c999bc2aad926b9a51ec2b166d348d"

        fun resolveCertificatePin(pluginId: String, digests: List<String>, pinned: String?): String {
            val digest = digests.firstOrNull()
                ?: throw ParserException(ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH, "no cert for package $pluginId")
            if (PRODUCT_CENTER_SIGNER in digests) {
                if (digests.size != 1) {
                    throw ParserException(ReasonCodes.PLUGIN_CERT_CHANGED, "multiple signers for $pluginId")
                }
                return PRODUCT_CENTER_SIGNER
            }
            if (pinned != null && pinned != digest) {
                throw ParserException(ReasonCodes.PLUGIN_CERT_CHANGED, "plugin cert changed for $pluginId")
            }
            return digest
        }
    }
}
