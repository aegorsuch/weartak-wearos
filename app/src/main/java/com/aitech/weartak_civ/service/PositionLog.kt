package com.aitech.weartak_civ.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.Writer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Separate opt-in journal: unlike AppDiagnostics, these records contain location and identity.
 * The bounded queue and database report failures instead of treating an incomplete log as complete.
 */
internal class PositionLog(context: Context) {
    private val preferences = context.getSharedPreferences("position_log", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableFailure = MutableStateFlow(preferences.getBoolean("incomplete", false))
    val storageFailed = mutableFailure.asStateFlow()
    private val journal = PositionJournal(context.applicationContext)
    @Volatile private var captureStartNanos = SystemClock.elapsedRealtimeNanos()
    private data class Tick(val id: String, val deadlineElapsedMs: Long)
    private val currentAttempt = AtomicReference<Tick?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Operation {
        data class Fix(val uid: String, val location: Location, val generatedDuringCapture: Boolean) : Operation
        data class Selection(val key: PositionFixKey, val selected: Boolean, val expected: Boolean) : Operation
        data class Attempt(val key: PositionFixKey, val id: String, val withinInterval: Boolean, val expected: Boolean) : Operation
        data class Untracked(val key: PositionFixKey, val route: PositionUntrackedRoute, val expected: Boolean) : Operation
        data class RouteStarted(val key: PositionFixKey, val bearer: PositionBearer, val id: String, val expected: Boolean) : Operation
        data class RouteResult(val key: PositionFixKey, val bearer: PositionBearer, val accepted: Boolean,
            val atMs: Long, val attemptId: String?, val sameTick: Boolean, val routeId: String?) : Operation
        data class Barrier(val done: CompletableDeferred<Unit>) : Operation
    }
    private val queue = Channel<Operation>(256)

    init {
        scope.launch {
            for (operation in queue) {
                when (operation) {
                    is Operation.Fix -> try {
                        if (journal.append(operation.uid, operation.location, operation.generatedDuringCapture))
                            fail(IOException("Position log retention limit reached; older fixes discarded"))
                    } catch (error: SQLiteException) {
                        fail(error)
                    } catch (error: SecurityException) {
                        fail(error)
                    }
                    is Operation.Selection -> try {
                        if (!journal.recordSelection(operation.key, operation.selected) && operation.expected)
                            fail(IOException("Position selection could not be correlated"))
                    } catch (error: SQLiteException) {
                        fail(error)
                    } catch (error: SecurityException) {
                        fail(error)
                    }
                    is Operation.Attempt -> try {
                        if (!journal.recordAttempt(operation.key, operation.id, operation.withinInterval) && operation.expected)
                            fail(IOException("Position dispatch could not be correlated"))
                    } catch (error: SQLiteException) {
                        fail(error)
                    } catch (error: SecurityException) {
                        fail(error)
                    }
                    is Operation.Untracked -> try {
                        if (!journal.recordUntracked(operation.key, operation.route) && operation.expected)
                            fail(IOException("Position route offer could not be correlated"))
                    } catch (error: SQLiteException) {
                        fail(error)
                    } catch (error: SecurityException) {
                        fail(error)
                    }
                    is Operation.RouteStarted -> try {
                        if (!journal.startRoute(operation.key, operation.bearer, operation.id, operation.expected))
                            fail(IOException("Position route accounting limit reached or fix unavailable"))
                    } catch (error: SQLiteException) {
                        fail(error)
                    } catch (error: SecurityException) {
                        fail(error)
                    }
                    is Operation.RouteResult -> try {
                        journal.recordRouteResult(operation.key, operation.bearer, operation.accepted, operation.atMs,
                            operation.attemptId, operation.sameTick, operation.routeId)
                    } catch (error: SQLiteException) {
                        fail(error)
                    } catch (error: SecurityException) {
                        fail(error)
                    }
                    is Operation.Barrier -> operation.done.complete(Unit)
                }
            }
        }
    }

    private fun fail(error: Exception) {
        if (!mutableFailure.compareAndSet(false, true)) return
        Log.e("WearTAK-PositionLog", "Position log incomplete", error)
        preferences.edit().putBoolean("incomplete", true).apply()
    }

    fun record(uid: String, location: Location) {
        if (!enabled.value) return
        if (uid.isBlank() || location.time <= 0 ||
            !location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
            !location.longitude.isFinite() || location.longitude !in -180.0..180.0) {
            fail(IOException("Position callback has no valid identity, timestamp, or coordinates"))
            return
        }
        if (queue.trySend(Operation.Fix(uid, Location(location),
                location.elapsedRealtimeNanos >= captureStartNanos)).isFailure)
            fail(IOException("Position log queue overflow"))
    }

    fun setEnabled(value: Boolean) {
        if (!preferences.edit().putBoolean("enabled", value).commit())
            throw IOException("Cannot save position log preference")
        if (value && !mutableEnabled.value) captureStartNanos = SystemClock.elapsedRealtimeNanos()
        mutableEnabled.value = value
    }

    private fun key(uid: String, location: Location) =
        PositionFixKey(uid, location.provider.orEmpty(), location.time, location.elapsedRealtimeNanos,
            location.latitude, location.longitude)

    private fun expectedFix(key: PositionFixKey) = key.elapsedNanos >= captureStartNanos

    fun recordSelection(uid: String, location: Location, selected: Boolean) {
        if (!enabled.value) return
        val fix = key(uid, location)
        if (queue.trySend(Operation.Selection(fix, selected, expectedFix(fix))).isFailure)
            fail(IOException("Position log queue overflow"))
    }

    private fun key(uid: String, location: DataRepository.LocationSnapshot): PositionFixKey? {
        val provider = location.provider ?: return null
        val elapsed = location.elapsedRealtimeNanos ?: return null
        if (location.coordinateSource != provider) return null
        return PositionFixKey(uid, provider, location.timeMillis, elapsed, location.lat, location.lon)
    }

    fun beginAttempt(uid: String, location: DataRepository.LocationSnapshot, tickDurationMs: Long): String? {
        if (!enabled.value) return null
        val id = UUID.randomUUID().toString()
        val nowMs = SystemClock.elapsedRealtime()
        val fix = key(uid, location)
        val fixElapsedMs = fix?.elapsedNanos?.div(1_000_000L) ?: nowMs
        currentAttempt.set(Tick(id, fixElapsedMs + tickDurationMs.coerceAtLeast(1)))
        if (fix == null) return id
        val ageMs = nowMs - fixElapsedMs
        if (queue.trySend(Operation.Attempt(fix, id, ageMs in 0..tickDurationMs.coerceAtLeast(1), expectedFix(fix))).isFailure)
            fail(IOException("Position log queue overflow"))
        return id
    }

    fun recordUntracked(uid: String, location: DataRepository.LocationSnapshot, route: PositionUntrackedRoute) {
        if (!enabled.value) return
        val fix = key(uid, location) ?: return
        if (queue.trySend(Operation.Untracked(fix, route, expectedFix(fix))).isFailure)
            fail(IOException("Position log queue overflow"))
    }

    fun startRoute(uid: String, location: DataRepository.LocationSnapshot, bearer: PositionBearer): String? {
        if (!enabled.value) return null
        val fix = key(uid, location) ?: return null
        val id = UUID.randomUUID().toString()
        if (queue.trySend(Operation.RouteStarted(fix, bearer, id, expectedFix(fix))).isFailure) {
            fail(IOException("Position log queue overflow"))
            return null
        }
        return id
    }

    fun recordRouteResult(uid: String, location: DataRepository.LocationSnapshot, bearer: PositionBearer,
        accepted: Boolean, attemptId: String?, routeId: String? = null) {
        if (!enabled.value) return
        val fix = key(uid, location) ?: return
        val tick = currentAttempt.get()
        if (queue.trySend(Operation.RouteResult(fix, bearer, accepted, System.currentTimeMillis(),
                attemptId, attemptId != null && tick != null && tick.id == attemptId &&
                    SystemClock.elapsedRealtime() <= tick.deadlineElapsedMs, routeId)).isFailure)
            fail(IOException("Position log queue overflow"))
    }

    suspend fun export(writer: Writer) {
        val barrier = CompletableDeferred<Unit>()
        queue.send(Operation.Barrier(barrier))
        barrier.await()
        try {
            journal.export(writer, allowNeverSent = !storageFailed.value)
        } catch (error: SQLiteException) {
            fail(error)
            throw IOException("Cannot read position log", error)
        }
    }
}

internal data class PositionFixKey(
    val uid: String, val provider: String, val timeMs: Long, val elapsedNanos: Long,
    val latitude: Double, val longitude: Double
)

internal enum class PositionBearer(val column: String, val success: String, val failure: String) {
    TAK_SERVER("tak", "socket_written", "write_unconfirmed"),
    MULTICAST("multicast", "socket_written", "write_unconfirmed"),
    SITX("sitx", "api_accepted", "not_accepted"),
}

internal enum class PositionUntrackedRoute(val column: String) {
    PLUGIN("plugin"), COMPANION("companion")
}

internal class PositionJournal(context: Context, private val maxRows: Int = 50_000,
    private val maxPendingRoutes: Int = 10_000) :
    SQLiteOpenHelper(context, "position-log.db", null, 7) {
    init {
        require(maxRows > 0 && maxPendingRoutes > 0)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE fixes (
            id INTEGER PRIMARY KEY,
            uid TEXT NOT NULL, provider TEXT NOT NULL, fix_ms INTEGER NOT NULL,
            elapsed_ns INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
            accuracy_m REAL, tak_status TEXT, tak_written_ms INTEGER,
            multicast_status TEXT, multicast_written_ms INTEGER,
            sitx_status TEXT, sitx_written_ms INTEGER,
            capture_new INTEGER NOT NULL DEFAULT 0, selected INTEGER NOT NULL DEFAULT 0,
            rejected INTEGER NOT NULL DEFAULT 0, first_attempt_id TEXT, first_attempt_within_interval INTEGER NOT NULL DEFAULT 0,
            disposition TEXT, first_handoff_ms INTEGER, first_handoff_bearer TEXT,
            untracked INTEGER NOT NULL DEFAULT 0, plugin_offered INTEGER NOT NULL DEFAULT 0,
            companion_offered INTEGER NOT NULL DEFAULT 0,
            tak_attempts INTEGER NOT NULL DEFAULT 0, multicast_attempts INTEGER NOT NULL DEFAULT 0,
            sitx_attempts INTEGER NOT NULL DEFAULT 0,
            tak_unresolved INTEGER NOT NULL DEFAULT 0, multicast_unresolved INTEGER NOT NULL DEFAULT 0,
            sitx_unresolved INTEGER NOT NULL DEFAULT 0,
            UNIQUE(uid, provider, fix_ms, elapsed_ns, latitude, longitude)
        )""")
        createPendingRoutes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1 || newVersion != 7) throw SQLiteException("Unsupported position log upgrade")
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN tak_status TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN tak_written_ms INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN multicast_status TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN multicast_written_ms INTEGER")
            db.execSQL("ALTER TABLE fixes ADD COLUMN sitx_status TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN sitx_written_ms INTEGER")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN capture_new INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE fixes ADD COLUMN selected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE fixes ADD COLUMN rejected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE fixes ADD COLUMN first_attempt_id TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN disposition TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN first_handoff_ms INTEGER")
            db.execSQL("ALTER TABLE fixes ADD COLUMN first_handoff_bearer TEXT")
            db.execSQL("ALTER TABLE fixes ADD COLUMN untracked INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5)
            db.execSQL("ALTER TABLE fixes ADD COLUMN first_attempt_within_interval INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE fixes ADD COLUMN plugin_offered INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE fixes ADD COLUMN companion_offered INTEGER NOT NULL DEFAULT 0")
        }
        db.execSQL("ALTER TABLE fixes ADD COLUMN tak_attempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fixes ADD COLUMN multicast_attempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fixes ADD COLUMN sitx_attempts INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fixes ADD COLUMN tak_unresolved INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fixes ADD COLUMN multicast_unresolved INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE fixes ADD COLUMN sitx_unresolved INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE fixes SET tak_status = 'write_unconfirmed' WHERE tak_status = 'not_written'")
        db.execSQL("UPDATE fixes SET multicast_status = 'write_unconfirmed' WHERE multicast_status = 'not_written'")
        createPendingRoutes(db)
    }

    private fun createPendingRoutes(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pending_routes (id TEXT PRIMARY KEY, fix_id INTEGER NOT NULL, bearer TEXT NOT NULL)")
        db.execSQL("CREATE INDEX pending_routes_fix_bearer ON pending_routes(fix_id, bearer)")
        db.execSQL("""CREATE TRIGGER delete_pending_routes AFTER DELETE ON fixes
            BEGIN DELETE FROM pending_routes WHERE fix_id = OLD.id; END""")
    }

    @Synchronized
    fun append(uid: String, location: Location, generatedDuringCapture: Boolean = false): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("uid", uid)
                put("provider", location.provider.orEmpty())
                put("fix_ms", location.time)
                put("elapsed_ns", location.elapsedRealtimeNanos)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("capture_new", if (generatedDuringCapture) 1 else 0)
                if (location.hasAccuracy() && location.accuracy.isFinite() && location.accuracy >= 0)
                    put("accuracy_m", location.accuracy)
            }
            val id = db.insertWithOnConflict("fixes", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val pruned = id > maxRows && db.delete("fixes", "id <= ?", arrayOf((id - maxRows).toString())) > 0
            db.setTransactionSuccessful()
            return pruned
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun recordSelection(key: PositionFixKey, selected: Boolean): Boolean {
        writableDatabase.execSQL(
            """UPDATE fixes SET selected = MAX(selected, ?), rejected = MAX(rejected, ?)
               WHERE uid = ? AND provider = ? AND fix_ms = ? AND elapsed_ns = ? AND latitude = ? AND longitude = ?""",
            arrayOf(if (selected) 1 else 0, if (selected) 0 else 1,
                key.uid, key.provider, key.timeMs, key.elapsedNanos, key.latitude, key.longitude)
        )
        return changes(writableDatabase) == 1
    }

    @Synchronized
    fun recordAttempt(key: PositionFixKey, id: String, withinInterval: Boolean = true): Boolean {
        writableDatabase.execSQL(
            """UPDATE fixes SET first_attempt_within_interval = CASE WHEN first_attempt_id IS NULL THEN ?
               ELSE first_attempt_within_interval END, first_attempt_id = COALESCE(first_attempt_id, ?), selected = 1
               WHERE uid = ? AND provider = ? AND fix_ms = ? AND elapsed_ns = ? AND latitude = ? AND longitude = ?""",
            arrayOf(if (withinInterval) 1 else 0, id, key.uid, key.provider, key.timeMs, key.elapsedNanos, key.latitude, key.longitude)
        )
        return changes(writableDatabase) == 1
    }

    @Synchronized
    fun recordUntracked(key: PositionFixKey, route: PositionUntrackedRoute): Boolean {
        writableDatabase.execSQL(
            """UPDATE fixes SET selected = 1, untracked = 1, ${route.column}_offered = 1
               WHERE uid = ? AND provider = ? AND fix_ms = ? AND elapsed_ns = ? AND latitude = ? AND longitude = ?""",
            arrayOf(key.uid, key.provider, key.timeMs, key.elapsedNanos, key.latitude, key.longitude)
        )
        return changes(writableDatabase) == 1
    }

    @Synchronized
    fun startRoute(key: PositionFixKey, bearer: PositionBearer, id: String, expected: Boolean = true): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val fixId = db.rawQuery(
                """SELECT id FROM fixes WHERE uid = ? AND provider = ? AND fix_ms = ?
                   AND elapsed_ns = ? AND latitude = ? AND longitude = ?""",
                arrayOf(key.uid, key.provider, key.timeMs.toString(), key.elapsedNanos.toString(),
                    key.latitude.toString(), key.longitude.toString())
            ).use { if (it.moveToFirst()) it.getLong(0) else null }
            if (fixId == null) {
                db.setTransactionSuccessful()
                return !expected
            }
            val count = db.rawQuery("SELECT COUNT(*) FROM pending_routes", null).use {
                it.moveToFirst()
                it.getLong(0)
            }
            val evicted = count >= maxPendingRoutes
            if (evicted) {
                val oldest = db.rawQuery("SELECT id, fix_id, bearer FROM pending_routes ORDER BY rowid LIMIT 1", null).use {
                    if (!it.moveToFirst()) throw SQLiteException("Missing oldest pending route")
                    Triple(it.getString(0), it.getLong(1), it.getString(2))
                }
                val oldestBearer = PositionBearer.values().firstOrNull { it.name == oldest.third }
                    ?: throw SQLiteException("Invalid pending route bearer")
                db.execSQL("UPDATE fixes SET ${oldestBearer.column}_unresolved = ${oldestBearer.column}_unresolved + 1 WHERE id = ?", arrayOf(oldest.second))
                db.execSQL("DELETE FROM pending_routes WHERE id = ?", arrayOf(oldest.first))
            }
            db.execSQL("INSERT INTO pending_routes(id, fix_id, bearer) VALUES(?, ?, ?)", arrayOf(id, fixId, bearer.name))
            db.execSQL("UPDATE fixes SET selected = 1, ${bearer.column}_attempts = ${bearer.column}_attempts + 1 WHERE id = ?", arrayOf(fixId))
            db.setTransactionSuccessful()
            return !evicted
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun recordRouteResult(key: PositionFixKey, bearer: PositionBearer, accepted: Boolean, atMs: Long,
        attemptId: String? = null, sameTick: Boolean = false, routeId: String? = null) {
        val status = "${bearer.column}_status"
        val timestamp = "${bearer.column}_written_ms"
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (routeId != null) {
                db.execSQL("""DELETE FROM pending_routes WHERE id = ? AND bearer = ? AND fix_id IN
                    (SELECT id FROM fixes WHERE uid = ? AND provider = ? AND fix_ms = ? AND elapsed_ns = ? AND latitude = ? AND longitude = ?)""",
                    arrayOf(routeId, bearer.name, key.uid, key.provider, key.timeMs, key.elapsedNanos, key.latitude, key.longitude))
                if (changes(db) != 1) return
            }
            db.execSQL(
                """UPDATE fixes SET
                   selected = 1,
                   $status = CASE WHEN $status = ? OR ? = 1 THEN ? ELSE ? END,
                   $timestamp = CASE WHEN ? = 1 THEN COALESCE($timestamp, ?) ELSE $timestamp END,
                   disposition = CASE WHEN ? = 1 AND first_handoff_ms IS NULL AND capture_new = 1 AND first_attempt_id IS NOT NULL AND ? IS NOT NULL
                       THEN CASE WHEN first_attempt_id = ? AND first_attempt_within_interval = 1 AND ? = 1 THEN 'sent_immediately'
                                 ELSE 'buffered_sent_later' END ELSE disposition END,
                   first_handoff_bearer = CASE WHEN ? = 1 AND first_handoff_ms IS NULL AND ? IS NOT NULL
                       THEN ? ELSE first_handoff_bearer END,
                   first_handoff_ms = CASE WHEN ? = 1 AND ? IS NOT NULL
                       THEN COALESCE(first_handoff_ms, ?) ELSE first_handoff_ms END
                   WHERE uid = ? AND provider = ? AND fix_ms = ? AND elapsed_ns = ? AND latitude = ? AND longitude = ?""",
                arrayOf(bearer.success, if (accepted) 1 else 0, bearer.success, bearer.failure,
                    if (accepted) 1 else 0, atMs, if (accepted) 1 else 0, attemptId, attemptId,
                    if (sameTick) 1 else 0, if (accepted) 1 else 0, attemptId, bearer.name,
                    if (accepted) 1 else 0, attemptId, atMs,
                    key.uid, key.provider, key.timeMs, key.elapsedNanos, key.latitude, key.longitude)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun changes(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized
    fun export(writer: Writer) = export(writer, allowNeverSent = true)

    @Synchronized
    fun export(writer: Writer, allowNeverSent: Boolean) {
        writer.write("device_id,fix_timestamp,latitude_wgs84_deg,longitude_wgs84_deg,horizontal_accuracy_m,bearer,disposition,relayed,tak_status,tak_first_write_timestamp,multicast_status,multicast_first_write_timestamp,sitx_status,sitx_first_accept_timestamp,first_handoff_timestamp,first_handoff_bearer,untracked_offers,tak_attempts,tak_pending,multicast_attempts,multicast_pending,sitx_attempts,sitx_pending\r\n")
        readableDatabase.rawQuery(
            """SELECT uid, fix_ms, latitude, longitude, accuracy_m, tak_status, tak_written_ms,
               multicast_status, multicast_written_ms, sitx_status, sitx_written_ms, disposition,
               capture_new, selected, rejected, first_handoff_ms, first_handoff_bearer, untracked,
               plugin_offered, companion_offered, tak_attempts,
               tak_unresolved + (SELECT COUNT(*) FROM pending_routes p WHERE p.fix_id = fixes.id AND p.bearer = 'TAK_SERVER'),
               multicast_attempts,
               multicast_unresolved + (SELECT COUNT(*) FROM pending_routes p WHERE p.fix_id = fixes.id AND p.bearer = 'MULTICAST'),
               sitx_attempts,
               sitx_unresolved + (SELECT COUNT(*) FROM pending_routes p WHERE p.fix_id = fixes.id AND p.bearer = 'SITX')
               FROM fixes ORDER BY id""", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val fields = listOf(
                    cursor.getString(0), Instant.ofEpochMilli(cursor.getLong(1)).toString(),
                    cursor.getDouble(2).toString(), cursor.getDouble(3).toString(),
                    if (cursor.isNull(4)) "" else cursor.getDouble(4).toString(),
                    buildList {
                        if (cursor.getString(5) == PositionBearer.TAK_SERVER.success) add("TAK_SERVER")
                        if (cursor.getString(7) == PositionBearer.MULTICAST.success) add("MULTICAST")
                        if (cursor.getString(9) == PositionBearer.SITX.success) add("SITX")
                    }.joinToString(";"),
                    if (cursor.getInt(17) == 1) "unknown" else cursor.getString(11)
                        ?: if (allowNeverSent && cursor.getInt(12) == 1 && cursor.getInt(13) == 0 &&
                            cursor.getInt(14) == 1) "never_sent" else "unknown", "",
                    cursor.getString(5) ?: "", if (cursor.isNull(6)) "" else Instant.ofEpochMilli(cursor.getLong(6)).toString(),
                    cursor.getString(7) ?: "", if (cursor.isNull(8)) "" else Instant.ofEpochMilli(cursor.getLong(8)).toString(),
                    cursor.getString(9) ?: "", if (cursor.isNull(10)) "" else Instant.ofEpochMilli(cursor.getLong(10)).toString(),
                    if (cursor.isNull(15)) "" else Instant.ofEpochMilli(cursor.getLong(15)).toString(),
                    cursor.getString(16) ?: "",
                    buildList {
                        if (cursor.getInt(18) == 1) add("PLUGIN")
                        if (cursor.getInt(19) == 1) add("COMPANION")
                    }.joinToString(";"),
                    cursor.getLong(20).toString(), cursor.getLong(21).toString(),
                    cursor.getLong(22).toString(), cursor.getLong(23).toString(),
                    cursor.getLong(24).toString(), cursor.getLong(25).toString()
                )
                writer.write(fields.joinToString(",") { csvField(it) })
                writer.write("\r\n")
            }
        }
        writer.flush()
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' })
            "\"${value.replace("\"", "\"\"")}\"" else value
}
