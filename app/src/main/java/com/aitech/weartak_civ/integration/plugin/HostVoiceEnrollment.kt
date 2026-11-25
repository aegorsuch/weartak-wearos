package com.aitech.weartak_civ.integration.plugin

import android.os.Bundle
import com.aitech.weartak_civ.service.TAKConnectService
import com.aitech.weartak_civ.data.takserver.TakEnrollmentException
import com.weartak.voice.api.IVoiceEnrollment
import com.weartak.voice.api.IVoiceRequestSigner
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.pkcs.CertificationRequest
import org.bouncycastle.asn1.pkcs.CertificationRequestInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** Only the authenticated Voice plugin gets this binder. Credentials and destinations come from core. */
internal class HostVoiceEnrollment(private val authenticate: () -> Unit) : IVoiceEnrollment.Stub() {
    override fun profiles(): Bundle {
        authenticate()
        val eligible = TAKConnectService.takServerRuntimeDetails.value.filter {
            it.isEnabled && !it.isP12Cert && !it.duplicateEndpointSuppressed
        }
        return Bundle().apply {
            putStringArray("ids", eligible.map { it.serverId }.toTypedArray())
            putStringArray("labels", eligible.map { "${it.name} (${it.address}:${it.port})" }.toTypedArray())
        }
    }

    @Synchronized override fun enroll(
        serverId: String, installationId: String, publicKey: ByteArray, signer: IVoiceRequestSigner,
    ): Bundle {
        authenticate()
        require(UUID.fromString(installationId).toString() == installationId && publicKey.size in 256..1024)
        val detail = TAKConnectService.getTakServerDetail(serverId)
            ?: return error("The selected WearTAK server profile is unavailable.")
        if (!detail.isEnabled || detail.isP12Cert || detail.duplicateEndpointSuppressed)
            return error("Choose an enabled auto-enrollment profile.")
        if (detail.username.isBlank() || detail.password.isEmpty())
            return error("Complete this TAK profile’s enrollment login in Companion, then retry.")
        return try {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey)) as RSAPublicKey
            require(key.modulus.bitLength() in 2048..4096)
            val session = TAKConnectService.voiceEnrollmentSession(detail)
            val entries = requireNotNull(TAKConnectService.fetchAndParseCertificateConfig(detail, session))
            val subject = buildString {
                append("CN=").append(TAKConnectService.escapeDnValue(detail.username))
                for (entry in entries) append(", ").append(entry.name).append('=').append(TAKConnectService.escapeDnValue(entry.value))
            }
            val info = CertificationRequestInfo(X500Name(subject), SubjectPublicKeyInfo.getInstance(publicKey), DERSet())
            val signature = requireNotNull(signer.sign(info.encoded))
            require(Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update(info.encoded)
                verify(signature)
            })
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val csr = CertificationRequest(info, algorithm, DERBitString(signature))
            authenticate()
            check(TAKConnectService.getTakServerDetail(serverId) === detail)
            val signed = requireNotNull(TAKConnectService.postCsrAndParse(detail,
                Base64.getEncoder().encodeToString(csr.encoded), session, "Voice-$installationId"))
            authenticate()
            signed.signedCert.checkValidity()
            require(signed.signedCert.publicKey.encoded.contentEquals(publicKey))
            Bundle().apply {
                val chain = listOf(signed.signedCert) + signed.authorities.map { it.second }
                require(chain.size <= 16)
                putInt("count", chain.size)
                chain.forEachIndexed { i, certificate -> putByteArray("cert$i", certificate.encoded) }
            }
        } catch (failure: TakEnrollmentException) {
            error(failure.failure.displayMessage())
        } catch (_: Exception) {
            error("Voice enrollment failed. Check the selected profile, server trust and connection.")
        }
    }

    private fun error(message: String) = Bundle().apply { putString("error", message) }
}
