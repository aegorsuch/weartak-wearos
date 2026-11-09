package com.aitech.weartak_civ.service

import com.aitech.weartak_civ.cot.BatdokPatient
import com.aitech.weartak_civ.cot.BatdokPatientEncoder
import com.aitech.weartak_civ.cot.BatdokPatientProfile
import com.aitech.weartak_civ.cot.EmergencyLocationPolicy
import com.aitech.weartak_civ.data.BatdokSharingConsent
import com.aitech.weartak_civ.data.DataRepository.LocationSnapshot
import com.aitech.weartak_civ.data.SelfStatusInputs

enum class BatdokPatientStatus {
    DISABLED, CONFIRMATION_REQUIRED, NOT_PROVISIONED, WAITING_TAK, LOCATION_UNAVAILABLE,
    CALLSIGN_REQUIRED, SENT, FAILED
}

/** Sanitized failure stage; the short code is safe to show and log (no payload, identity or configuration). */
enum class BatdokFailureStage(val code: String) {
    INPUT("S"), CONFIGURATION("C"), IDENTITY("I"), PAYLOAD("P"), ENCRYPTION("E"), TRANSPORT("T")
}

class BatdokPublishException(val stage: BatdokFailureStage, cause: Throwable) : Exception(stage.name, cause)

/** One fixed-format diagnostic line: status enum plus sanitized detail code, no payload, identity, location or endpoint. */
fun batdokOutcomeLog(status: BatdokPatientStatus, detail: String): String =
    "BATTAK patient outcome: ${status.name}" + if (detail.isBlank()) "" else " $detail"

/** Stage code plus innermost exception class name only; messages may contain payload or configuration and are dropped. */
fun batdokFailureCode(error: Throwable): String {
    var root: Throwable = error
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val stage = (error as? BatdokPublishException)?.stage?.code ?: "U"
    return "$stage:${root.javaClass.simpleName.filter(Char::isLetterOrDigit).take(40).ifBlank { "Exception" }}"
}

data class BatdokPublishingInput(
    val consent: BatdokSharingConsent,
    val allowed: Boolean,
    val profile: BatdokPatientProfile,
    val location: LocationSnapshot?,
    val physiology: SelfStatusInputs,
    /** Fixed reason code when [allowed] is false; never endpoint, payload or identity data. */
    val waitReason: String? = null
)

/** Direct TAK write outcome: stream counts plus an optional fixed gate code. */
data class BatdokTakWrite(val written: Int, val eligibleStreams: Int, val blockedBy: String? = null)

/** Fresh snapshots only. No pending-patient queue, replay, multicast, plugin or Companion route. */
class BatdokPatientPublisher(
    private val input: suspend () -> BatdokPublishingInput,
    private val patientId: suspend () -> String,
    private val encoder: () -> BatdokPatientEncoder?,
    private val sendToTak: suspend (String, suspend () -> Boolean) -> BatdokTakWrite,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    /** Sanitized detail for the last SENT/WAITING_TAK result (e.g. `streams=2/3`, `no_connected_stream`); empty otherwise. */
    var lastDetail: String = ""
        private set

    suspend fun publish(): BatdokPatientStatus {
        lastDetail = ""
        val snapshot = stage(BatdokFailureStage.INPUT) { input() }
        if (!snapshot.consent.enabled) return BatdokPatientStatus.DISABLED
        if (!snapshot.consent.confirmed) return BatdokPatientStatus.CONFIRMATION_REQUIRED
        val codec = stage(BatdokFailureStage.CONFIGURATION) { encoder() } ?: return BatdokPatientStatus.NOT_PROVISIONED
        if (!snapshot.allowed) {
            lastDetail = snapshot.waitReason ?: "not_allowed"
            return BatdokPatientStatus.WAITING_TAK
        }
        if (snapshot.profile.callsign.isBlank()) return BatdokPatientStatus.CALLSIGN_REQUIRED
        val now = nowMillis()
        // Last valid fix of any age; its original time/accuracy are sent unchanged. Future-dated fixes are invalid.
        val location = snapshot.location?.takeIf {
            EmergencyLocationPolicy.usable(it) && it.timeMillis <= now + MAX_FUTURE_LOCATION_MS
        } ?: return BatdokPatientStatus.LOCATION_UNAVAILABLE
        val id = stage(BatdokFailureStage.IDENTITY) { patientId() }
        val json = stage(BatdokFailureStage.PAYLOAD) {
            BatdokPatient.json(id, snapshot.profile, location, snapshot.physiology, now)
        }
        val encoded = stage(BatdokFailureStage.ENCRYPTION) { codec.encode(json) }
        val xml = stage(BatdokFailureStage.PAYLOAD) {
            BatdokPatient.cot(id, snapshot.profile.callsign, location, encoded, now)
        }
        var revalidationRejected = 0
        val write = stage(BatdokFailureStage.TRANSPORT) { sendToTak(xml) {
            val current = input()
            val atWrite = nowMillis()
            // Revalidate under each connection's output serializer, including removal of stale vitals.
            (current.allowed && current.consent.confirmed && current.consent == snapshot.consent &&
                encoder() === codec && current.profile == snapshot.profile &&
                current.location == location && atWrite >= now && atWrite - now < 10_000L &&
                location.timeMillis <= atWrite + MAX_FUTURE_LOCATION_MS &&
                BatdokPatient.json(id, current.profile, location, current.physiology,
                    nowMillis = atWrite, reportTimeMillis = now) == json).also { if (!it) revalidationRejected++ }
        } }
        if (write.written > 0) {
            lastDetail = "streams=${write.written}/${write.eligibleStreams}"
            return BatdokPatientStatus.SENT
        }
        lastDetail = write.blockedBy ?: when {
            write.eligibleStreams == 0 -> "no_connected_stream"
            revalidationRejected > 0 -> "revalidation_rejected"
            else -> "write_failed"
        }
        return BatdokPatientStatus.WAITING_TAK
    }

    companion object {
        const val MAX_FUTURE_LOCATION_MS = 30_000L
    }

    private inline fun <T> stage(stage: BatdokFailureStage, block: () -> T): T = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: BatdokPublishException) {
        throw error
    } catch (error: Exception) {
        throw BatdokPublishException(stage, error)
    }
}
