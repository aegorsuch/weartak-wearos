/*
 * Copyright (C) 2026, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.AudioCaptureRepository
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureService : Service() {


    companion object {
        val isRunning = AtomicBoolean(false)
    }
    // --- Config ---
    private val SAMPLE_RATE_HZ = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val READ_SIZE = 2048
    private val MIN_INTERNAL_BUFFER_MULT = 2

    // --- Runtime ---
    private val NOTIFICATION_ID = 201
    private val TAG = "WearTAK-AudioCapSrvc"
    private val isCollecting = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var currentFile: File? = null
    private var currentTempFile: File? = null

    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    private val audioRepo by lazy {
        try {
            (applicationContext as WearTakApplication).audioRepo
        } catch (_: Throwable) {
            AudioCaptureRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(NOTIFICATION_ID, importance = NotificationManager.IMPORTANCE_LOW)

        handlerThread = HandlerThread("AudioCaptureService-Thread").also {
            try {
                it.start()
                handler = Handler(it.looper)
            } catch (t: Throwable) {
                Log.w(TAG, "handler thread start failed: ${t.message}")
            }
        }

        collectAudioFlows()

        if (!hasRecordAudioPermission()) {
            updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
        } else {
            updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
        }
    }

    private fun collectAudioFlows() {
        serviceScope.launch(Dispatchers.IO) {
            audioRepo.activeConsumers
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { hasConsumers ->
                    if (hasConsumers) {
                        startAudioCollection()
                    } else {
                        stopAudioCollection()
                    }
                }
        }

        serviceScope.launch(Dispatchers.IO) {
            audioRepo.getRecordingState
                .collect { state ->
                    when (state) {
                        AudioCaptureRepository.RecordingState.RECORDING -> Log.i(TAG, "RecordingState --> RECORDING")
                        AudioCaptureRepository.RecordingState.AVAILABLE -> Log.i(TAG, "RecordingState --> AVAILABLE")
                        AudioCaptureRepository.RecordingState.UNAVAILABLE -> Log.i(TAG, "RecordingState --> UNAVAILABLE")
                        AudioCaptureRepository.RecordingState.PAUSED -> Log.i(TAG, "RecordingState --> PAUSED")
                        AudioCaptureRepository.RecordingState.PLAYING -> Log.i(TAG, "RecordingState --> PLAYING")
                    }
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            audioRepo.getRecordingSessionState
                .collect { state ->
                    when (state) {
                        true -> Log.i(TAG, "Recording Session IN PROGRESS")
                        false -> Log.i(TAG, "Recording Session NOT IN PROGRESS")
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() action=${intent?.action}")
        return START_NOT_STICKY
    }

    private suspend fun destroyCleanly() {
        try { stopAudioCollection() } catch (_: Throwable) {}
        audioRepo.updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        serviceScope.launch {
            destroyCleanly()
        }
        isRunning.set(false)
        recordingJob?.cancel()
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
//TODO: Use isCollecting as a state holder for "paused" or not, use the state in repo later for is a session happening or not.
    private fun updateRecordingSessionState(isSessionRunning: Boolean) {
        if (!isSessionRunning) {
            if (hasRecordAudioPermission()) {
                updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
            } else {
                updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
            }
        }
        serviceScope.launch {
            isCollecting.set(isSessionRunning)
            audioRepo.updateRecordingSessionState(isSessionRunning)
        }
    }

    private fun updateRecordingState(state: AudioCaptureRepository.RecordingState) {
        serviceScope.launch {
            audioRepo.updateRecordingState(state)
        }
    }
/*
    private fun startAudioCollection() {
        if (!isRunning.get()) {
            Log.w(TAG, "Service not running; cannot start collection")
            return
        }
        if (isCollecting.getAndSet(true)) {
            Log.w(TAG, "Already collecting")
            return
        }

        // Permission guard
        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted; aborting")
            updateRecordingSessionState(false)
            updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
            return
        }

        try {
            prepareOutputFiles()
            initAudioRecord()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init recording: ${t.message}", t)
            updateRecordingSessionState(false)
            return
        }

        recordingJob = serviceScope.launch {
            try {
                // start may throw SecurityException if permission revoked mid-flight
                audioRecord?.startRecording()
                updateRecordingSessionState(true)
                updateRecordingState(AudioCaptureRepository.RecordingState.RECORDING)
            } catch (se: SecurityException) {
                Log.e(TAG, "startRecording() SecurityException: ${se.message}", se)
                updateRecordingSessionState(false)
                return@launch
            } catch (t: Throwable) {
                Log.e(TAG, "startRecording() error: ${t.message}", t)
                updateRecordingSessionState(false)
                return@launch
            }

            FileOutputStream(currentTempFile).use { out ->
                // READ_SIZE is bytes; AudioRecord(short[]) is samples
                val shortBuf = ShortArray(READ_SIZE / 2)

                // For saving to .pcm file (little-endian)
                val byteBuf = ByteArray(shortBuf.size * 2)

                var seq = 0L
                val sessionId = currentTempFile?.name ?: "session" // or generate a UUID if you prefer

                while (isActive && isCollecting.get()) {
                    val readShorts = try {
                        audioRecord?.read(shortBuf, 0, shortBuf.size) ?: AudioRecord.ERROR_INVALID_OPERATION
                    } catch (t: Throwable) {
                        Log.e(TAG, "AudioRecord.read(short[]) error: ${t.message}", t)
                        AudioRecord.ERROR
                    }

                    if (readShorts > 0) {
                        // 1) Emit to repo as samples (copy only the valid portion)
                        val nowEl = SystemClock.elapsedRealtime()
                        val now = System.currentTimeMillis()
                        val chunk = AudioCaptureRepository.AudioPcmChunk(
                            sessionId = sessionId,
                            seq = seq++,
                            timestampMs = now,
                            timestampElMs = nowEl,
                            sampleRateHz = SAMPLE_RATE_HZ,
                            channels = 1,
                            samples = shortBuf.copyOf(readShorts)
                        )

                        val ok = audioRepo.tryEmitChunk(chunk)

                        if (!ok) {
                            Log.w(TAG, "PCM chunk dropped seq=$seq readShorts=$readShorts")
                        }

                        // 2) Also write to disk as PCM16 LE (optional, but keeps your current WAV finalize flow)
//                        shortsToPcm16LeBytes(shortBuf, readShorts, byteBuf)
//                        out.write(byteBuf, 0, readShorts * 2)
                    } else if (readShorts == AudioRecord.ERROR_INVALID_OPERATION || readShorts == AudioRecord.ERROR_BAD_VALUE) {
                        yield()
                    } else if (readShorts == AudioRecord.ERROR) {
                        delay(5)
                    }
                }

                try { out.fd.sync() } catch (_: Throwable) {}
            }
        }

        Log.d(TAG, "startAudioCollection(): writing to ${currentTempFile?.absolutePath}")
    }

    fun stopAudioCollection() {
        if (!isCollecting.getAndSet(false)) {
            Log.w(TAG, "Not currently collecting")
            return
        }

        try {
            recordingJob?.cancel()
            try { audioRecord?.stop() } catch (_: Throwable) {}
            audioRecord?.release()
            audioRecord = null
            updateRecordingSessionState(false)
            updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
        } catch (t: Throwable) {
            Log.w(TAG, "stopAudioCollection cleanup error: ${t.message}")
        }

        try {
            finalizeWavFile(currentTempFile, currentFile)
            Log.d(TAG, "stopAudioCollection(): finalized ${currentFile?.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to finalize WAV: ${t.message}", t)
        } finally {
            currentTempFile = null
            currentFile = null
            recordingJob = null
        }
    }
*/

    private fun startAudioCollection() {
        if (!isRunning.get()) {
            Log.w(TAG, "Service not running; cannot start collection")
            return
        }
        if (isCollecting.getAndSet(true)) {
            Log.w(TAG, "Already collecting")
            return
        }

        if (!hasRecordAudioPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted; aborting")
            updateRecordingSessionState(false)
            updateRecordingState(AudioCaptureRepository.RecordingState.UNAVAILABLE)
            isCollecting.set(false)
            return
        }

        try {
            // If stream-only, we don't need to prepare output files:
            // prepareOutputFiles()
            initAudioRecord()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init recording: ${t.message}", t)
            updateRecordingSessionState(false)
            isCollecting.set(false)
            return
        }

        recordingJob = serviceScope.launch {
            try {
                audioRecord?.startRecording()
                updateRecordingSessionState(true)
                updateRecordingState(AudioCaptureRepository.RecordingState.RECORDING)
            } catch (se: SecurityException) {
                Log.e(TAG, "startRecording() SecurityException: ${se.message}", se)
                updateRecordingSessionState(false)
                isCollecting.set(false)
                return@launch
            } catch (t: Throwable) {
                Log.e(TAG, "startRecording() error: ${t.message}", t)
                updateRecordingSessionState(false)
                isCollecting.set(false)
                return@launch
            }

            val shortBuf = ShortArray(READ_SIZE / 2) // READ_SIZE is bytes; short[] is samples

            var seq = 0L
            val sessionId = "audio_${System.currentTimeMillis()}" // or UUID.randomUUID().toString()

            while (isActive && isCollecting.get()) {
                val readShorts = try {
                    audioRecord?.read(shortBuf, 0, shortBuf.size)
                        ?: AudioRecord.ERROR_INVALID_OPERATION
                } catch (t: Throwable) {
                    Log.e(TAG, "AudioRecord.read(short[]) error: ${t.message}", t)
                    AudioRecord.ERROR
                }

                when {
                    readShorts > 0 -> {
                        val nowEl = SystemClock.elapsedRealtime()
                        val now = System.currentTimeMillis()
                        val chunk = AudioCaptureRepository.AudioPcmChunk(
                            sessionId = sessionId,
                            seq = seq++,
                            timestampMs = now,
                            timestampElMs = nowEl,
                            sampleRateHz = SAMPLE_RATE_HZ,
                            channels = 1,
                            samples = shortBuf.copyOf(readShorts)
                        )

                        val ok = audioRepo.tryEmitChunk(chunk)
                        if (!ok) {
                            Log.w(TAG, "PCM chunk dropped seq=${seq - 1} readShorts=$readShorts")
                        }
                    }

                    readShorts == AudioRecord.ERROR_INVALID_OPERATION ||
                            readShorts == AudioRecord.ERROR_BAD_VALUE -> {
                        yield()
                    }

                    readShorts == AudioRecord.ERROR -> {
                        delay(5)
                    }
                }
            }
        }

        Log.d(TAG, "startAudioCollection(): streaming chunks only")
    }

    fun stopAudioCollection() {
        if (!isCollecting.getAndSet(false)) {
            Log.w(TAG, "Not currently collecting")
            return
        }

        try {
            recordingJob?.cancel()
            try { audioRecord?.stop() } catch (_: Throwable) {}
            audioRecord?.release()
            audioRecord = null
        } catch (t: Throwable) {
            Log.w(TAG, "stopAudioCollection cleanup error: ${t.message}")
        } finally {
            recordingJob = null
            updateRecordingSessionState(false)
            updateRecordingState(AudioCaptureRepository.RecordingState.AVAILABLE)
        }
    }

    fun pauseAudioCollection() {
        //TODO: Pause Audio Collection
        serviceScope.launch {
            val state = audioRepo.getRecordingState.first()
            if (state == AudioCaptureRepository.RecordingState.PAUSED) {
                resumeAudioCollection()
            }
            if (state == AudioCaptureRepository.RecordingState.RECORDING) {
                updateRecordingState(AudioCaptureRepository.RecordingState.PAUSED)
            }
        }
    }

    fun resumeAudioCollection() {
        updateRecordingState(AudioCaptureRepository.RecordingState.RECORDING)
    }

    // ---------------- Internals ----------------

    private fun hasRecordAudioPermission(): Boolean {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun prepareOutputFiles() {
        val recordingsDir = File(filesDir, "recordings")
        if (!recordingsDir.exists()) recordingsDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentFile = File(recordingsDir, "audio_$ts.wav")
        currentTempFile = File(recordingsDir, "audio_$ts.pcm")
        if (currentTempFile!!.exists()) currentTempFile!!.delete()
        currentTempFile!!.createNewFile()
    }

    private fun shortsToPcm16LeBytes(src: ShortArray, count: Int, dst: ByteArray) {
        // dst must be at least count*2
        var i = 0
        var j = 0
        while (i < count) {
            val s = src[i].toInt()
            dst[j] = (s and 0xFF).toByte()         // low byte
            dst[j + 1] = ((s shr 8) and 0xFF).toByte() // high byte
            i++
            j += 2
        }
    }

    private fun initAudioRecord() {
        if (audioRecord != null) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_CONFIG, AUDIO_FORMAT)
        require(minBuf != AudioRecord.ERROR && minBuf != AudioRecord.ERROR_BAD_VALUE) {
            "Invalid min buffer size"
        }
        val bufferSize = maxOf(minBuf * MIN_INTERNAL_BUFFER_MULT, READ_SIZE)

        try {
            audioRecord = AudioRecord(
                audioRepo.audioSource,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "AudioRecord ctor SecurityException: ${se.message}", se)
            throw se
        }

        val st = audioRecord?.state
        require(st == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed (state=$st)" }
    }

    private fun finalizeWavFile(tempPcm: File?, outWav: File?) {
        if (tempPcm == null || outWav == null) throw IllegalArgumentException("Files missing")
        val pcmSize = tempPcm.length()
        val byteRate = SAMPLE_RATE_HZ * 2 /* bytesPerSample */ * 1 /* channels */

        // Write header + PCM
        RandomAccessFile(outWav, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt((pcmSize + 36).toInt())
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)                // PCM subchunk size
            header.putShort(1.toShort())     // PCM format
            header.putShort(1.toShort())     // channels = 1
            header.putInt(SAMPLE_RATE_HZ)
            header.putInt(byteRate)
            header.putShort(2.toShort())     // block align = channels * bytes/sample
            header.putShort(16.toShort())    // bits per sample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcmSize.toInt())
            raf.write(header.array())

            FileInputStream(tempPcm).use { fis ->
                val buf = ByteArray(8192)
                var read: Int
                while (fis.read(buf).also { read = it } > 0) {
                    raf.write(buf, 0, read)
                }
            }
        }
        tempPcm.delete()
    }
}
