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

/** Stage code plus innermost exception class name only; messages may contain payload or configuration and are dropped. */
fun batdokFailureCode(error: Throwable): String {
    var root: Throwable = error
    while (root.cause != null && root.cause !== root) root = root.cause!!
    val stage = (error as? BatdokPublishException)?.stage?.code ?: "U"
    return "$stage:${root.javaClass.simpleName.ifBlank { "Exception" }}"
}

data class BatdokPublishingInput(
    val consent: BatdokSharingConsent,
    val allowed: Boolean,
    val profile: BatdokPatientProfile,
    val location: LocationSnapshot?,
    val physiology: SelfStatusInputs
)

/** Fresh snapshots only. No pending-patient queue, replay, multicast, plugin or Companion route. */
class BatdokPatientPublisher(
    private val input: suspend () -> BatdokPublishingInput,
    private val patientId: suspend () -> String,
    private val encoder: () -> BatdokPatientEncoder?,
    private val sendToTak: suspend (String, suspend () -> Boolean) -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun publish(): BatdokPatientStatus {
        val snapshot = stage(BatdokFailureStage.INPUT) { input() }
        if (!snapshot.consent.enabled) return BatdokPatientStatus.DISABLED
        if (!snapshot.consent.confirmed) return BatdokPatientStatus.CONFIRMATION_REQUIRED
        val codec = stage(BatdokFailureStage.CONFIGURATION) { encoder() } ?: return BatdokPatientStatus.NOT_PROVISIONED
        if (!snapshot.allowed) return BatdokPatientStatus.WAITING_TAK
        if (snapshot.profile.callsign.isBlank()) return BatdokPatientStatus.CALLSIGN_REQUIRED
        val now = nowMillis()
        val location = snapshot.location?.takeIf {
            EmergencyLocationPolicy.usable(it) && it.timeMillis in (now - 120_000L)..(now + 30_000L)
        } ?: return BatdokPatientStatus.LOCATION_UNAVAILABLE
        val id = stage(BatdokFailureStage.IDENTITY) { patientId() }
        val json = stage(BatdokFailureStage.PAYLOAD) {
            BatdokPatient.json(id, snapshot.profile, location, snapshot.physiology, now)
        }
        val encoded = stage(BatdokFailureStage.ENCRYPTION) { codec.encode(json) }
        val xml = stage(BatdokFailureStage.PAYLOAD) {
            BatdokPatient.cot(id, snapshot.profile.callsign, location, encoded, now)
        }
        val written = stage(BatdokFailureStage.TRANSPORT) { sendToTak(xml) {
            val current = input()
            val atWrite = nowMillis()
            // Revalidate under each connection's output serializer, including removal of stale vitals.
            current.allowed && current.consent.confirmed && current.consent == snapshot.consent &&
                encoder() === codec && current.profile == snapshot.profile &&
                current.location == location && atWrite >= now && atWrite - now < 10_000L &&
                location.timeMillis in (atWrite - 120_000L)..(atWrite + 30_000L) &&
                BatdokPatient.json(id, current.profile, location, current.physiology,
                    nowMillis = atWrite, reportTimeMillis = now) == json
        } }
        return if (written) BatdokPatientStatus.SENT else BatdokPatientStatus.WAITING_TAK
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
