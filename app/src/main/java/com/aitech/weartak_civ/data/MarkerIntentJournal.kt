package com.aitech.weartak_civ.data

import android.content.SharedPreferences
import com.aitech.weartak_civ.cot.CotMessageParser
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.WeakHashMap

/**
 * Durable desired marker state and ordered intents. In-flight effects are never
 * automatically retried after recovery: their outcome requires reconciliation.
 */
internal class MarkerIntentJournal(private val preferences: SharedPreferences) {
    enum class Phase { NEVER_DISPATCHED, DISPATCH_STARTED, UNCERTAIN, ACKNOWLEDGED }
    enum class Outcome { LOCAL_ONLY, NATIVE_COMMITTED, DELETION_STAGED, PLUGIN_SUBMITTED, RETRY_LATER }
    enum class OtherRoutes { PENDING, STARTED, OFFERED }

    data class Intent(
        val sequence: Long,
        val dispatchId: String,
        val uid: String,
        val marker: DataRepository.DroppedMarker?,
        val payload: String,
        val digest: String,
        val phase: Phase,
        val dispatchPayload: String? = null,
        val dispatchDigest: String? = null,
        val outcome: Outcome? = null,
        val refreshSelf: Boolean = false,
        val otherRoutes: OtherRoutes = OtherRoutes.PENDING,
    )

    data class State(
        val closed: Boolean,
        val nextSequence: Long,
        val effective: Map<String, DataRepository.DroppedMarker?>,
        val intents: List<Intent>,
    )

    fun read(): State = synchronized(lock) { load() }

    fun update(
        uid: String,
        fallback: DataRepository.DroppedMarker?,
        refreshSelf: Boolean = false,
        transform: (DataRepository.DroppedMarker) -> DataRepository.DroppedMarker?,
    ): Intent? = synchronized(lock) {
        val state = load()
        val current = if (state.effective.containsKey(uid)) state.effective[uid] else fallback
        if (current == null) return@synchronized null
        val marker = transform(current)
        admitAll(listOf(uid to marker), if (refreshSelf && marker != null) setOf(uid) else emptySet()).single()
    }

    fun admit(uid: String, marker: DataRepository.DroppedMarker?): Intent =
        admitAll(listOf(uid to marker)).single()

    fun recordRemoteDeletion(uid: String, token: String): Intent? = synchronized(lock) {
        val loaded = load()
        val state = if (loaded.intents.size >= MAX_INTENTS) compactAcknowledged(loaded) else loaded
        if (state.closed || state.intents.any { it.uid == uid && it.phase != Phase.ACKNOWLEDGED }) {
            return@synchronized null
        }
        require(uid.isNotBlank() && uid.length <= 512 && uid.none(Char::isISOControl))
        check(state.intents.size < MAX_INTENTS && state.nextSequence < Long.MAX_VALUE) {
            "Marker intent journal capacity exhausted"
        }
        val id = UUID.randomUUID().toString()
        val payload = "null"
        val evidence = "companion-deletion:${sha256(token)}"
        val intent = Intent(state.nextSequence, id, uid, null, payload, digest(id, uid, payload),
            Phase.ACKNOWLEDGED, evidence, sha256(evidence), Outcome.LOCAL_ONLY)
        save(state.copy(nextSequence = state.nextSequence + 1,
            effective = state.effective + (uid to null), intents = state.intents + intent))
        intent
    }

    fun admitNew(uid: String, marker: DataRepository.DroppedMarker, refreshSelf: Boolean = false): Intent? = synchronized(lock) {
        if (load().effective.containsKey(uid)) return@synchronized null
        admitAll(listOf(uid to marker), if (refreshSelf) setOf(uid) else emptySet()).single()
    }

    fun dispatchable(): List<Intent> = synchronized(lock) {
        val state = load()
        if (state.closed) return@synchronized emptyList()
        val blocked = mutableSetOf<String>()
        state.intents.filter { intent ->
            if (intent.phase == Phase.ACKNOWLEDGED) false
            else {
                val first = blocked.add(intent.uid)
                first && intent.phase == Phase.NEVER_DISPATCHED
            }
        }
    }

    fun admitAll(changes: List<Pair<String, DataRepository.DroppedMarker?>>,
        selfRefreshFor: Set<String> = emptySet()): List<Intent> = synchronized(lock) {
        require(changes.isNotEmpty())
        require(selfRefreshFor.all { uid -> changes.any { it.first == uid && it.second != null } })
        val loaded = load()
        val state = if (changes.size > MAX_INTENTS - loaded.intents.size) {
            compactAcknowledged(loaded)
        } else loaded
        check(!state.closed) { "Marker admission is closed" }
        check(changes.size <= MAX_INTENTS - state.intents.size &&
            state.nextSequence <= Long.MAX_VALUE - changes.size) {
            "Marker intent journal capacity exhausted"
        }
        val entries = changes.mapIndexed { index, (uid, marker) ->
            require(uid.isNotBlank() && uid.length <= 512 && uid.none(Char::isISOControl))
            require(marker == null || marker.uid == uid)
            val payload = marker?.let {
                val encoded = encodeMarker(it)
                require(decodeMarker(encoded) == it) { "Invalid marker payload" }
                encoded.toString()
            } ?: "null"
            val id = UUID.randomUUID().toString()
            Intent(state.nextSequence + index, id, uid, marker, payload,
                digest(id, uid, payload), Phase.NEVER_DISPATCHED, refreshSelf = uid in selfRefreshFor)
        }
        save(state.copy(
            nextSequence = state.nextSequence + entries.size,
            effective = state.effective + changes,
            intents = state.intents + entries,
        ))
        entries
    }

    private fun compactAcknowledged(state: State): State {
        val retained = mutableSetOf<Long>()
        state.intents.groupBy { it.uid }.values.forEach { entries ->
            // Preserve first-seen UI order and the latest effective state.
            retained.add(entries.first().sequence)
            var last: Intent? = null
            var lastMarker: Intent? = null
            var highestTime: Pair<Long, Long>? = null
            fun retainAnchors() {
                last?.let { retained.add(it.sequence) }
                lastMarker?.let { retained.add(it.sequence) }
                highestTime?.let { retained.add(it.second) }
            }
            entries.forEach { entry ->
                if (entry.phase != Phase.ACKNOWLEDGED) {
                    retainAnchors()
                } else {
                    last = entry
                    if (entry.marker != null) {
                        lastMarker = entry
                        val time = entry.dispatchPayload?.let(CotMessageParser::parseMarkerOrNull)?.time
                        if (time != null && (highestTime == null || time > highestTime!!.first)) {
                            highestTime = time to entry.sequence
                        }
                    }
                }
            }
            retainAnchors()
        }
        return state.copy(intents = state.intents.filter {
            it.phase != Phase.ACKNOWLEDGED || it.sequence in retained
        })
    }

    fun closeAdmission(): State = synchronized(lock) {
        val state = load()
        if (!state.closed) save(state.copy(closed = true)) else state
    }

    fun begin(sequence: Long, dispatchPayload: String): Intent = synchronized(lock) {
        val state = load()
        val index = state.intents.indexOfFirst { it.sequence == sequence }
        require(index >= 0) { "Unknown marker intent" }
        val intent = state.intents[index]
        check(!state.closed) { "Marker admission is closed" }
        check(intent.phase == Phase.NEVER_DISPATCHED) { "Marker intent is not pending" }
        check(state.intents.take(index).none {
            it.uid == intent.uid && it.phase != Phase.ACKNOWLEDGED
        }) { "Earlier marker intent is unresolved" }
        require(dispatchPayload.isNotEmpty())
        require(intent.dispatchPayload == null || intent.dispatchPayload == dispatchPayload)
        val updated = intent.copy(phase = Phase.DISPATCH_STARTED, dispatchPayload = dispatchPayload,
            dispatchDigest = sha256(dispatchPayload))
        save(state.copy(intents = state.intents.toMutableList().apply { set(index, updated) }))
        updated
    }

    fun markUncertain(sequence: Long) = transition(sequence, Phase.DISPATCH_STARTED, Phase.UNCERTAIN)

    fun deferNotSent(sequence: Long) = transition(sequence, Phase.DISPATCH_STARTED, Phase.NEVER_DISPATCHED)

    fun beginOtherRoutes(sequence: Long, dispatchPayload: String) = synchronized(lock) {
        val state = load()
        val index = state.intents.indexOfFirst { it.sequence == sequence }
        require(index >= 0)
        val intent = state.intents[index]
        check(!state.closed && intent.otherRoutes == OtherRoutes.PENDING)
        require(dispatchPayload.isNotEmpty())
        require(intent.dispatchPayload == null || intent.dispatchPayload == dispatchPayload)
        save(state.copy(intents = state.intents.toMutableList().apply {
            set(index, intent.copy(dispatchPayload = dispatchPayload, dispatchDigest = sha256(dispatchPayload),
                otherRoutes = OtherRoutes.STARTED))
        }))
    }

    fun finishOtherRoutes(sequence: Long, offered: Boolean) = synchronized(lock) {
        val state = load()
        val index = state.intents.indexOfFirst { it.sequence == sequence }
        require(index >= 0 && state.intents[index].otherRoutes == OtherRoutes.STARTED)
        save(state.copy(intents = state.intents.toMutableList().apply {
            set(index, get(index).copy(otherRoutes = if (offered) OtherRoutes.OFFERED else OtherRoutes.PENDING))
        }))
    }

    /** Caller must have independently proven a durable effect and outcome. */
    fun markCommitted(sequence: Long, outcome: Outcome): State {
        require(outcome != Outcome.RETRY_LATER)
        return transition(sequence, Phase.DISPATCH_STARTED, Phase.ACKNOWLEDGED, outcome)
    }

    fun recoverInterrupted(): State = synchronized(lock) {
        val state = load()
        val recovered = state.intents.map {
            if (it.phase == Phase.DISPATCH_STARTED) it.copy(phase = Phase.UNCERTAIN) else it
        }
        if (recovered == state.intents) state else save(state.copy(intents = recovered))
    }

    private fun transition(sequence: Long, from: Phase, to: Phase, outcome: Outcome? = null): State = synchronized(lock) {
        val state = load()
        val index = state.intents.indexOfFirst { it.sequence == sequence }
        require(index >= 0) { "Unknown marker intent" }
        check(state.intents[index].phase == from) { "Unexpected marker intent phase" }
        save(state.copy(intents = state.intents.toMutableList().apply {
            set(index, get(index).copy(phase = to, outcome = outcome))
        }))
    }

    private fun load(): State {
        check(!uncertain.containsKey(preferences)) { "Marker journal write outcome uncertain" }
        val raw = preferences.all[KEY] ?: return State(false, 1, emptyMap(), emptyList())
        require(raw is String && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Invalid marker journal"
        }
        try {
            val root = JSONObject(raw)
            require(root.keys().asSequence().toSet() == setOf("version", "closed", "next", "intents"))
            require(root.get("version") == 1 && root.get("closed") is Boolean)
            val next = number(root, "next")
            val array = root.getJSONArray("intents")
            require(next >= 1 && array.length() <= MAX_INTENTS)
            val entries = (0 until array.length()).map { index ->
                val value = array.getJSONObject(index)
                val oldKeys = setOf("sequence", "id", "uid", "payload", "digest", "phase")
                val keys = value.keys().asSequence().toSet()
                val dispatchKeys = oldKeys + setOf("dispatchPayload", "dispatchDigest", "outcome")
                require(keys == oldKeys || keys == dispatchKeys || keys == dispatchKeys + "refreshSelf" ||
                    keys == dispatchKeys + setOf("refreshSelf", "otherRoutes"))
                val sequence = number(value, "sequence")
                val id = value.getString("id")
                require(UUID.fromString(id).toString() == id)
                val uid = value.get("uid") as? String ?: error("Invalid marker UID")
                require(sequence > 0 && uid.isNotBlank() && uid.length <= 512 && uid.none(Char::isISOControl))
                val payload = value.getString("payload")
                val payloadDigest = value.getString("digest")
                require(payloadDigest == digest(id, uid, payload)) { "Marker payload digest mismatch" }
                val marker = if (payload == "null") null else decodeMarker(JSONObject(payload))
                require(marker == null || marker.uid == uid)
                val storedPhase = Phase.valueOf(value.getString("phase"))
                val phase = if (keys == oldKeys && storedPhase == Phase.ACKNOWLEDGED) Phase.UNCERTAIN else storedPhase
                val dispatchPayload = if (!value.has("dispatchPayload") || value.isNull("dispatchPayload")) null else value.getString("dispatchPayload")
                val dispatchDigest = if (!value.has("dispatchDigest") || value.isNull("dispatchDigest")) null else value.getString("dispatchDigest")
                val outcome = if (!value.has("outcome") || value.isNull("outcome")) null else Outcome.valueOf(value.getString("outcome"))
                require((dispatchPayload == null && dispatchDigest == null) ||
                    (dispatchPayload != null && dispatchDigest == sha256(dispatchPayload)))
                if (keys != oldKeys) {
                    if (phase == Phase.DISPATCH_STARTED || phase == Phase.ACKNOWLEDGED) require(dispatchPayload != null)
                    require((phase == Phase.ACKNOWLEDGED) == (outcome != null))
                    require(outcome != Outcome.RETRY_LATER)
                }
                if (value.has("refreshSelf")) require(value.get("refreshSelf") is Boolean)
                val otherRoutes = if (value.has("otherRoutes")) OtherRoutes.valueOf(value.getString("otherRoutes"))
                    else OtherRoutes.PENDING
                if (otherRoutes != OtherRoutes.PENDING) require(dispatchPayload != null)
                Intent(sequence, id, uid, marker, payload, payloadDigest, phase, dispatchPayload, dispatchDigest,
                    outcome, value.optBoolean("refreshSelf", false), otherRoutes)
            }
            require(entries.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
            require(entries.map { it.dispatchId }.toSet().size == entries.size)
            require(entries.lastOrNull()?.sequence?.let { it < next } != false)
            val effective = linkedMapOf<String, DataRepository.DroppedMarker?>()
            entries.forEach { effective[it.uid] = it.marker }
            return State(root.getBoolean("closed"), next, effective, entries)
        } catch (error: org.json.JSONException) {
            throw IllegalArgumentException("Invalid marker journal", error)
        }
    }

    private fun encode(state: State): String {
        val array = JSONArray()
        state.intents.forEach { intent ->
            array.put(JSONObject().put("sequence", intent.sequence).put("id", intent.dispatchId)
                .put("uid", intent.uid).put("payload", intent.payload).put("digest", intent.digest)
                .put("phase", intent.phase.name)
                .put("dispatchPayload", intent.dispatchPayload ?: JSONObject.NULL)
                .put("dispatchDigest", intent.dispatchDigest ?: JSONObject.NULL)
                .put("outcome", intent.outcome?.name ?: JSONObject.NULL)
                .put("refreshSelf", intent.refreshSelf)
                .put("otherRoutes", intent.otherRoutes.name))
        }
        return JSONObject().put("version", 1).put("closed", state.closed)
            .put("next", state.nextSequence).put("intents", array).toString()
    }

    private fun save(state: State): State {
        var stored = state
        var raw = encode(stored)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            stored = compactAcknowledged(state)
            raw = encode(stored)
        }
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Marker journal capacity exhausted" }
        uncertain[preferences] = true
        if (!preferences.edit().putString(KEY, raw).commit()) {
            throw IllegalStateException("Marker journal write outcome uncertain")
        }
        uncertain.remove(preferences)
        return stored
    }

    private fun encodeMarker(marker: DataRepository.DroppedMarker): JSONObject {
        val location = marker.location?.let {
            JSONObject().put("time", it.timeMillis).put("lat", it.lat).put("lon", it.lon)
                .put("hae", it.hae ?: JSONObject.NULL).put("ce", it.ce ?: JSONObject.NULL)
                .put("le", it.le ?: JSONObject.NULL).put("course", it.course ?: JSONObject.NULL)
                .put("speed", it.speed ?: JSONObject.NULL).put("provider", it.provider ?: JSONObject.NULL)
                .put("elapsed", it.elapsedRealtimeNanos ?: JSONObject.NULL)
                .put("source", it.coordinateSource ?: JSONObject.NULL)
        }
        return JSONObject().put("uid", marker.uid).put("cotType", marker.cotType.name)
            .put("markerType", marker.markerType.name).put("title", marker.title).put("remark", marker.remark)
            .put("time", marker.timeDropped).put("location", location ?: JSONObject.NULL)
            .put("queued", marker.enqueuedToBeSent).put("waiting", marker.waitingForUpdatedLocation)
    }

    private fun decodeMarker(json: JSONObject): DataRepository.DroppedMarker {
        require(json.keys().asSequence().toSet() ==
            setOf("uid", "cotType", "markerType", "title", "remark", "time", "location", "queued", "waiting"))
        val uid = json.get("uid") as? String ?: error("Invalid marker UID")
        val title = json.get("title") as? String ?: error("Invalid marker title")
        val remark = json.get("remark") as? String ?: error("Invalid marker remark")
        require(title.length <= MAX_TEXT && remark.length <= MAX_TEXT)
        require(json.get("queued") is Boolean && json.get("waiting") is Boolean)
        val location = if (json.isNull("location")) null else {
            val value = json.getJSONObject("location")
            require(value.keys().asSequence().toSet() ==
                setOf("time", "lat", "lon", "hae", "ce", "le", "course", "speed", "provider", "elapsed", "source"))
            fun optionalDouble(key: String): Double? =
                if (value.isNull(key)) null else decimal(value, key)
            fun optionalFloat(key: String): Float? = optionalDouble(key)?.toFloat()?.also { require(it.isFinite()) }
            fun optionalString(key: String): String? =
                if (value.isNull(key)) null else value.get(key) as? String ?: error("Invalid location field")
            val lat = decimal(value, "lat")
            val lon = decimal(value, "lon")
            require(lat in -90.0..90.0 && lon in -180.0..180.0)
            DataRepository.LocationSnapshot(
                number(value, "time"), lat, lon, optionalDouble("hae"), optionalFloat("ce"),
                optionalFloat("le"), optionalFloat("course"), optionalFloat("speed"), optionalString("provider"),
                if (value.isNull("elapsed")) null else number(value, "elapsed"), optionalString("source"),
            )
        }
        return DataRepository.DroppedMarker(
            DataRepository.MarkerCotType.valueOf(json.getString("cotType")), uid,
            DataRepository.MarkerType.valueOf(json.getString("markerType")), title, remark, number(json, "time"),
            location, json.getBoolean("queued"), json.getBoolean("waiting"),
        )
    }

    private fun number(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "Invalid integer field" }
        return (value as Number).toLong()
    }

    private fun decimal(json: JSONObject, key: String): Double {
        val value = json.get(key) as? Number ?: error("Invalid numeric field")
        return value.toDouble().also { require(it.isFinite()) }
    }

    private fun digest(id: String, uid: String, payload: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        listOf(id, uid, payload).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun sha256(payload: String): String = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val KEY = "marker-intent-journal-v1"
        const val MAX_INTENTS = 4096
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MAX_TEXT = 256 * 1024
        val lock = Any()
        val uncertain = WeakHashMap<SharedPreferences, Boolean>()
    }
}
