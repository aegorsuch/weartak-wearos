package com.aitech.weartak_civ.service

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import android.util.Log
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Operation {
        data class Fix(val uid: String, val location: Location) : Operation
        data class Barrier(val done: CompletableDeferred<Unit>) : Operation
    }
    private val queue = Channel<Operation>(256)

    init {
        scope.launch {
            for (operation in queue) {
                when (operation) {
                    is Operation.Fix -> try {
                        if (journal.append(operation.uid, operation.location))
                            fail(IOException("Position log retention limit reached; older fixes discarded"))
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
        if (queue.trySend(Operation.Fix(uid, Location(location))).isFailure)
            fail(IOException("Position log queue overflow"))
    }

    fun setEnabled(value: Boolean) {
        if (!preferences.edit().putBoolean("enabled", value).commit())
            throw IOException("Cannot save position log preference")
        mutableEnabled.value = value
    }

    suspend fun export(writer: Writer) {
        val barrier = CompletableDeferred<Unit>()
        queue.send(Operation.Barrier(barrier))
        barrier.await()
        try {
            journal.export(writer)
        } catch (error: SQLiteException) {
            fail(error)
            throw IOException("Cannot read position log", error)
        }
    }
}

internal class PositionJournal(context: Context, private val maxRows: Int = 50_000) :
    SQLiteOpenHelper(context, "position-log.db", null, 1) {
    init {
        require(maxRows > 0)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE fixes (
            id INTEGER PRIMARY KEY,
            uid TEXT NOT NULL, provider TEXT NOT NULL, fix_ms INTEGER NOT NULL,
            elapsed_ns INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL,
            accuracy_m REAL,
            UNIQUE(uid, provider, fix_ms, elapsed_ns, latitude, longitude)
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported position log upgrade")
    }

    @Synchronized
    fun append(uid: String, location: Location): Boolean {
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
    fun export(writer: Writer) {
        writer.write("device_id,fix_timestamp,latitude_wgs84_deg,longitude_wgs84_deg,horizontal_accuracy_m,bearer,disposition,relayed\r\n")
        readableDatabase.rawQuery(
            "SELECT uid, fix_ms, latitude, longitude, accuracy_m FROM fixes ORDER BY id", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val fields = listOf(
                    cursor.getString(0), Instant.ofEpochMilli(cursor.getLong(1)).toString(),
                    cursor.getDouble(2).toString(), cursor.getDouble(3).toString(),
                    if (cursor.isNull(4)) "" else cursor.getDouble(4).toString(),
                    "", "unknown", ""
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
