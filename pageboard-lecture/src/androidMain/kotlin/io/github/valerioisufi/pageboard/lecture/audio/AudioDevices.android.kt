package io.github.valerioisufi.pageboard.lecture.audio

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.github.valerioisufi.pageboard.lecture.R
import io.github.valerioisufi.pageboard.platform.DrawViewAndroid

internal actual fun createAudioCapture(): AudioCapture? = AndroidAudioCapture(DrawViewAndroid.context)

internal actual fun createAudioOutput(): AudioOutput? = AndroidAudioOutput(DrawViewAndroid.context)

/**
 * The microphone of Android ([AudioRecord], 16 kHz mono). While it records, a foreground service
 * of the microphone ([LectureRecordingService]) keeps the lecture going with the application in
 * the background or the screen off.
 *
 * During a call the system silences it (the recording goes on with silence, told by [CaptureEvent]);
 * when its device changes or disappears it goes on with the microphone there is.
 */
internal class AndroidAudioCapture(private val context: Context) : AudioCapture {
    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // The permission is asked before (see rememberMicrophoneAccess)
    override fun start(onSamples: (ShortArray) -> Unit, onEvent: (CaptureEvent) -> Unit) {
        check(!running) { "The capture is already running" }
        val opened = open(onEvent)
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
        thread = Thread({ capture(opened, onSamples, onEvent) }, "lecture-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    /** Reads the microphone; lost (its device gone, the audio server restarted), opened again. */
    private fun capture(first: AudioRecord, onSamples: (ShortArray) -> Unit, onEvent: (CaptureEvent) -> Unit) {
        // ~100 ms blocks
        val block = ShortArray(LECTURE_SAMPLE_RATE / 10)
        var current = first
        while (running) {
            val read = current.read(block, 0, block.size)
            if (read > 0) {
                onSamples(block.copyOf(read))
            } else if (read < 0 && running) {
                onEvent(CaptureEvent.INTERRUPTED)
                current.release()
                current = reopen(onEvent) ?: return
                onEvent(CaptureEvent.RESUMED)
            }
        }
    }

    private fun reopen(onEvent: (CaptureEvent) -> Unit): AudioRecord? {
        while (running) {
            try {
                Thread.sleep(REOPEN_MS)
                val opened = open(onEvent)
                opened.startRecording()
                record = opened
                return opened
            } catch (_: InterruptedException) {
                return null
            } catch (_: Exception) {
                // Not yet: tried again
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun open(onEvent: (CaptureEvent) -> Unit): AudioRecord {
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
        // Silenced by the system (a call, the assistant), then given back
        var silenced = false
        opened.registerAudioRecordingCallback(context.mainExecutor, object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val ours = configs.firstOrNull { it.clientAudioSessionId == opened.audioSessionId } ?: return
                if (ours.isClientSilenced == silenced || !running) return
                silenced = ours.isClientSilenced
                onEvent(if (silenced) CaptureEvent.INTERRUPTED else CaptureEvent.RESUMED)
            }
        })
        opened.addOnRoutingChangedListener({ if (running) onEvent(CaptureEvent.DEVICE_CHANGED) }, mainHandler)
        return opened
    }

    override fun stop() {
        running = false
        record?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
        }
        thread?.interrupt()
        thread?.join(2000)
        record?.release()
        record = null
        thread = null
        context.stopService(Intent(context, LectureRecordingService::class.java))
    }

    private companion object {
        const val REOPEN_MS = 1_000L
    }
}

/**
 * Keeps a lecture recording with the application in the background: a foreground service of the
 * microphone, with its notification (which opens the application).
 */
internal class LectureRecordingService : Service() {
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
internal class AndroidAudioOutput(private val context: Context) : AudioOutput {
    @Volatile
    private var track: AudioTrack? = null
    private var written = 0L
    private var focus: AudioFocusRequest? = null
    private var noisy: BroadcastReceiver? = null

    override fun open(onStopped: () -> Unit): Boolean = try {
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
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
        follow(onStopped)
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

    /** The focus of the audio (lost to a call, another player) and the headphones unplugged: stopped. */
    private fun follow(onStopped: () -> Unit) {
        val manager = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener { change -> if (change < 0) onStopped() }
            .build()
        manager.requestAudioFocus(request)
        focus = request
        noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onStopped()
        }.also { context.registerReceiver(it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED) }
    }

    override fun close() {
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
        noisy?.let { context.unregisterReceiver(it) }
        noisy = null
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
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        const val BUFFER_BYTES = LECTURE_BYTES_PER_SECOND / 4
        const val WAIT_MS = 10L
    }
}
