package io.github.valerioisufi.drawview.lecture.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.IBinder
import io.github.valerioisufi.drawview.core.platform.DrawViewAndroid
import io.github.valerioisufi.drawview.lecture.R

actual fun createAudioCapture(): AudioCapture? = AndroidAudioCapture(DrawViewAndroid.context)

actual fun createAudioOutput(): AudioOutput? = AndroidAudioOutput()

/**
 * The microphone of Android ([AudioRecord], 16 kHz mono). While it records, a foreground service
 * of the microphone ([LectureRecordingService]) keeps the lecture going with the application in
 * the background or the screen off.
 */
class AndroidAudioCapture(private val context: Context) : AudioCapture {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    // The permission is asked before (see rememberMicrophoneAccess)
    @SuppressLint("MissingPermission")
    override fun start(onSamples: (ShortArray) -> Unit) {
        check(!running) { "The capture is already running" }
        val minBuffer = AudioRecord.getMinBufferSize(LECTURE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw AudioCaptureException("No microphone available")
        val opened = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                LECTURE_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, LECTURE_BYTES_PER_SECOND / 2)
            )
        } catch (e: SecurityException) {
            throw AudioCaptureException("The microphone is not allowed", e)
        }
        if (opened.state != AudioRecord.STATE_INITIALIZED) {
            opened.release()
            throw AudioCaptureException("No microphone available")
        }
        // Started while the application is shown: then it may go on in the background
        context.startForegroundService(Intent(context, LectureRecordingService::class.java))
        try {
            opened.startRecording()
        } catch (e: IllegalStateException) {
            opened.release()
            context.stopService(Intent(context, LectureRecordingService::class.java))
            throw AudioCaptureException("The microphone cannot be started", e)
        }
        record = opened
        running = true
        thread = Thread({
            // ~100 ms blocks
            val block = ShortArray(LECTURE_SAMPLE_RATE / 10)
            while (running) {
                val read = opened.read(block, 0, block.size)
                if (read > 0) onSamples(block.copyOf(read)) else if (read < 0) break
            }
        }, "lecture-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
        }
        thread?.join(2000)
        record?.release()
        record = null
        thread = null
        context.stopService(Intent(context, LectureRecordingService::class.java))
    }
}

/**
 * Keeps a lecture recording with the application in the background: a foreground service of the
 * microphone, with its notification (which opens the application).
 */
class LectureRecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.lecture_recording_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.lecture_recording_notification))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "lecture-recording"
        const val NOTIFICATION_ID = 4_211
    }
}

/**
 * The speakers of Android ([AudioTrack], 16 kHz mono): [write] waits while a quarter of a second
 * is waiting to be played (a pause or a seek is heard right away).
 */
class AndroidAudioOutput : AudioOutput {
    @Volatile
    private var track: AudioTrack? = null
    private var written = 0L

    override fun open(): Boolean = try {
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(LECTURE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(BUFFER_BYTES, AudioTrack.getMinBufferSize(LECTURE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        newTrack.play()
        written = 0L
        track = newTrack
        true
    } catch (e: Exception) {
        println("DrawView: cannot play the recording: ${e.message}")
        false
    }

    override fun write(samples: ShortArray, count: Int) {
        val current = track ?: return
        var offset = 0
        // Not blocking: close() stops it from another thread
        while (offset < count && track === current) {
            val done = current.write(samples, offset, count - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (done < 0) break
            if (done == 0) Thread.sleep(WAIT_MS) else offset += done
        }
        written += offset
    }

    override val pending: Int
        get() = track?.let { (written - (it.playbackHeadPosition.toLong() and 0xFFFFFFFFL)).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() } ?: 0

    override fun close() {
        val current = track ?: return
        track = null
        try {
            current.pause()
            current.flush()
            current.stop()
        } catch (_: IllegalStateException) {
        }
        current.release()
    }

    private companion object {
        const val BUFFER_BYTES = LECTURE_BYTES_PER_SECOND / 4
        const val WAIT_MS = 10L
    }
}
