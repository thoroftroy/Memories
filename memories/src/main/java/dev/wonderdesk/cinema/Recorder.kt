package dev.wonderdesk.cinema

import dev.wonderdesk.memories.R

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How a recording is made. Saved by the Record tab, read by the service and the quick tile. */
data class CaptureConfig(
    val scalePct: Int = 80,
    val fps: Int = 30,
    val mbps: Int = 6,
    /** 0 none, 1 microphone, 2 sound playing on the phone */
    val audio: Int = 0,
    /** Seconds kept in the silent buffer in clip mode, and how far back a clip reaches. */
    val bufferSec: Int = 15,
    val clipMode: Boolean = false,
) {
    fun save(c: Context) {
        c.getSharedPreferences("cinema", Context.MODE_PRIVATE).edit().putInt("rec_scale", scalePct).putInt("rec_fps", fps).putInt("rec_mbps", mbps)
            .putInt("rec_audio", audio).putInt("rec_buffer", bufferSec).putBoolean("rec_clip", clipMode).apply()
    }
    companion object {
        fun load(c: Context): CaptureConfig {
            val p = c.getSharedPreferences("cinema", Context.MODE_PRIVATE)
            return CaptureConfig(p.getInt("rec_scale", 80), p.getInt("rec_fps", 30), p.getInt("rec_mbps", 6), p.getInt("rec_audio", 0),
                p.getInt("rec_buffer", 15).coerceIn(3, 60), p.getBoolean("rec_clip", false))
        }
    }
}

/** What the Record tab and the quick tile show. */
object RecorderState {
    var running by mutableStateOf(false)
    var clipMode by mutableStateOf(false)
    /** True while a real recording is being written (always in normal mode, only after Clip in clip mode). */
    var writing by mutableStateOf(false)
    var saved by mutableIntStateOf(0)
    var lastSaved by mutableStateOf<Uri?>(null)
    var message by mutableStateOf("")
    var startedAt by mutableStateOf(0L)
    var savingNow by mutableStateOf(false)
}

private class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val key: Boolean)

/**
 * Screen capture pipeline. The screen goes into an H.264 encoder, sound into an AAC encoder, and the
 * encoded pieces go either into a rolling buffer (clip mode, nothing is kept beyond the last few seconds)
 * or into an MP4 file. Pressing Clip copies the buffer from a few seconds ago into a new file and keeps
 * writing until you stop, then goes back to the silent buffer.
 */
class ScreenCapture(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val cfg: CaptureConfig,
) {
    private val lock = Any()
    private val ring = ArrayDeque<Sample>()
    private var muxer: MediaMuxer? = null
    private var outFile: File? = null
    private var vTrack = -1
    private var aTrack = -1
    private var vFormat: MediaFormat? = null
    private var aFormat: MediaFormat? = null
    private var wantRecording = false
    private var recording = false
    private var needKey = true
    private var base = -1L
    private var preUs = 0L
    @Volatile private var running = false

    private var venc: MediaCodec? = null
    private var aenc: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var surface: Surface? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null

    fun start() {
        val real = android.graphics.Point()
        @Suppress("DEPRECATION") ctx.getSystemService(WindowManager::class.java).defaultDisplay.getRealSize(real)
        val w = ((real.x * cfg.scalePct / 100) and 1.inv()).coerceAtLeast(144)
        val h = ((real.y * cfg.scalePct / 100) and 1.inv()).coerceAtLeast(144)
        val dpi = ctx.resources.displayMetrics.densityDpi
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.mbps * 1_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            // Clips need a clean frame every second to start from; a normal recording can use fewer.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (cfg.clipMode) 1 else 2)
            // Steady quality at a lower cost, and let the phone encode at its own relaxed pace instead of racing.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_PRIORITY, 1)
            // A still screen produces no new frames; repeat the last one a few times a second so the video never stalls,
            // instead of re-encoding it at the full frame rate (which keeps the phone busy and warm for nothing).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L)
            // Say plainly what the picture is (normal SDR colour, the usual video range) so no phone or player guesses wrong.
            // Without this, recordings from phones with wide-colour or HDR screens could come out far too dark.
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // If this phone's encoder refuses the extra colour and profile hints, fall back to the plain settings rather than not recording.
        try { enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) } catch (e: Exception) {
            enc.reset()
            enc.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, cfg.mbps * 1_000_000); setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, if (cfg.clipMode) 1 else 2)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        surface = enc.createInputSurface()
        enc.start()
        venc = enc
        running = true
        display = projection.createVirtualDisplay("WonderdeskCinemaRecorder", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        vThread = Thread({ drainLoop(enc, true) }, "cinema-rec-video").also { it.start() }
        if (cfg.audio != 0) runCatching { startAudio() }.onFailure { RecorderState.message = "Sound could not be captured, recording video only" ; cfgAudioFailed() }
        if (!cfg.clipMode) beginRecording(0)
    }

    /** If audio failed to start the muxer must not wait for an audio track forever. */
    private var audioFailed = false
    private fun cfgAudioFailed() { synchronized(lock) { audioFailed = true; startMuxerIfReady() } }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val rate = 44100
        val ch = AudioFormat.CHANNEL_IN_STEREO
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(rate, ch, enc).coerceAtLeast(16384)
        val format = AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).setChannelMask(ch).build()
        val rec = if (cfg.audio == 2) {
            val pc = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(minBuf * 2).setAudioPlaybackCaptureConfig(pc).build()
        } else {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(format).setBufferSizeInBytes(minBuf * 2).build()
        }
        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        aenc = codec
        rec.startRecording()
        aThread = Thread({ audioLoop(rec, codec) }, "cinema-rec-audio").also { it.start() }
    }

    private fun audioLoop(rec: AudioRecord, codec: MediaCodec) {
        val buf = ByteArray(8192)
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { try { Thread.sleep(10) } catch (_: InterruptedException) { break }; continue }
                val ptsUs = System.nanoTime() / 1000 - (n / 4) * 1_000_000L / 44100
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    ib.clear(); ib.put(buf, 0, n)
                    codec.queueInputBuffer(ii, 0, n, ptsUs, 0)
                }
                drainOnce(codec, false, info, 0)
            }
        } catch (_: Exception) {
        } finally {
            runCatching { rec.stop() }; runCatching { rec.release() }
        }
    }

    private fun drainLoop(codec: MediaCodec, video: Boolean) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                if (drainOnce(codec, video, info, 10_000)) break
            }
        } catch (_: Exception) { }
    }

    /** Pulls every finished piece out of a codec. Returns true when the stream has ended. */
    private fun drainOnce(codec: MediaCodec, video: Boolean, info: MediaCodec.BufferInfo, timeoutUs: Long): Boolean {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    if (video) vFormat = codec.outputFormat else aFormat = codec.outputFormat
                    startMuxerIfReady()
                }
                i >= 0 -> {
                    val out = codec.getOutputBuffer(i)
                    if (out != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val bytes = ByteArray(info.size)
                        out.position(info.offset); out.limit(info.offset + info.size); out.get(bytes)
                        onSample(Sample(video, bytes, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0))
                    }
                    codec.releaseOutputBuffer(i, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
                }
            }
        }
    }

    private fun onSample(s: Sample) = synchronized(lock) {
        if (recording) writeSample(s) else { ring.addLast(s); if (!wantRecording) prune(s.ptsUs) }
    }

    /** Drops old pieces but always keeps a whole picture group, so a saved clip starts on a clean frame. */
    private fun prune(newest: Long) {
        val keepFrom = newest - cfg.bufferSec * 1_000_000L
        val k = ring.indexOfLast { it.video && it.key && it.ptsUs <= keepFrom }
        if (k > 0) repeat(k) { ring.removeFirst() }
    }

    /** Starts writing a file. [preSec] seconds before now come from the silent buffer. */
    fun beginRecording(preSec: Int) {
        synchronized(lock) {
            if (recording || wantRecording) return
            wantRecording = true
            preUs = preSec * 1_000_000L
            startMuxerIfReady()
        }
    }

    private fun startMuxerIfReady() {
        if (!wantRecording || recording) return
        val vf = vFormat ?: return
        if (cfg.audio != 0 && aFormat == null && !audioFailed) return
        val f = File(ctx.cacheDir, "rec-${System.currentTimeMillis()}.mp4")
        val m = MediaMuxer(f.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        vTrack = m.addTrack(vf)
        aTrack = aFormat?.let { m.addTrack(it) } ?: -1
        m.start()
        muxer = m; outFile = f; base = -1; needKey = true
        val newest = ring.lastOrNull()?.ptsUs ?: 0L
        val want = newest - preUs
        var start = ring.indexOfLast { it.video && it.key && it.ptsUs <= want }
        if (start < 0) start = ring.indexOfFirst { it.video && it.key }
        recording = true
        if (start >= 0) for (i in start until ring.size) writeSample(ring[i])
        ring.clear()
        RecorderState.writing = true
    }

    private fun writeSample(s: Sample) {
        val m = muxer ?: return
        if (s.video) {
            if (needKey) {
                if (!s.key) return
                needKey = false
                if (base < 0 || s.ptsUs < base) base = s.ptsUs
            }
        } else if (base < 0 || s.ptsUs < base) return
        val track = if (s.video) vTrack else aTrack
        if (track < 0) return
        val info = MediaCodec.BufferInfo()
        info.set(0, s.data.size, s.ptsUs - base, if (s.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        runCatching { m.writeSampleData(track, ByteBuffer.wrap(s.data), info) }
    }

    /** Closes the current file and returns it. In clip mode the buffer starts filling again. */
    fun endRecording(): File? = synchronized(lock) {
        wantRecording = false
        val m = muxer ?: return null
        runCatching { m.stop() }
        runCatching { m.release() }
        muxer = null; recording = false
        val f = outFile; outFile = null
        RecorderState.writing = false
        f
    }

    fun stop(): File? {
        running = false
        runCatching { venc?.signalEndOfInputStream() }
        runCatching { vThread?.join(1500) }
        runCatching { aThread?.join(1500) }
        val f = endRecording()
        runCatching { display?.release() }
        runCatching { surface?.release() }
        runCatching { venc?.stop() }; runCatching { venc?.release() }
        runCatching { aenc?.stop() }; runCatching { aenc?.release() }
        runCatching { projection.stop() }
        ring.clear()
        return f
    }
}

/** Foreground service that owns the capture. Controlled from the Record tab, the notification and the quick tile. */
class RecorderService : Service() {
    private var capture: ScreenCapture? = null
    private var projection: MediaProjection? = null
    private var cfg = CaptureConfig()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_CLIP -> capture?.let { c -> if (!RecorderState.writing) { c.beginRecording(cfg.bufferSec); refreshNotice() } }
            ACTION_END_CLIP -> endClip()
            ACTION_STOP -> shutDown()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        if (capture != null) return
        cfg = CaptureConfig.load(this)
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (cfg.audio == 1) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        ensureChannel()
        ServiceCompat.startForeground(this, NOTE_ID, notice(), types)
        @Suppress("DEPRECATION") val data = intent.getParcelableExtra<Intent>("data")
        val code = intent.getIntExtra("code", 0)
        if (data == null) { fail("Recording was not allowed"); return }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = runCatching { mpm.getMediaProjection(code, data) }.getOrNull() ?: run { fail("Android did not hand over the screen"); return }
        proj.registerCallback(object : MediaProjection.Callback() { override fun onStop() { main.post { shutDown() } } }, main)
        projection = proj
        try {
            val cap = ScreenCapture(this, proj, cfg)
            capture = cap
            cap.start()
        } catch (e: Exception) {
            capture = null
            fail("Could not start recording: ${e.message ?: e.javaClass.simpleName}"); return
        }
        RecorderState.running = true; RecorderState.clipMode = cfg.clipMode; RecorderState.startedAt = System.currentTimeMillis(); RecorderState.message = ""
        refreshNotice()
        announce()
    }

    private fun fail(msg: String) {
        RecorderState.message = msg; RecorderState.running = false; tellLauncher()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        runCatching { projection?.stop() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun endClip() {
        val cap = capture ?: return
        if (!cfg.clipMode) { shutDown(); return }
        val f = cap.endRecording() ?: return
        save(f, "Clip", then = { refreshNotice() })
    }

    private fun shutDown() {
        val cap = capture
        capture = null
        RecorderState.running = false; tellLauncher()
        if (cap == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        RecorderState.savingNow = true
        Thread {
            val f = runCatching { cap.stop() }.getOrNull()
            if (f != null) save(f, if (cfg.clipMode) "Clip" else "Screen recording", then = { finish() }) else main.post { RecorderState.savingNow = false; finish() }
        }.start()
    }

    private fun finish() { RecorderState.savingNow = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun save(file: File, kind: String, then: () -> Unit) {
        Thread {
            val name = "$kind ${SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date())}.mp4"
            val uri = runCatching { Videos.publish(this, file, name, false) }.getOrNull()
            file.delete()
            main.post {
                if (uri != null) { RecorderState.saved++; RecorderState.lastSaved = uri; Toast.makeText(this, "$kind saved to Movies/Cinema", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "Couldn't save the recording", Toast.LENGTH_LONG).show()
                then()
            }
        }.start()
    }

    private fun ensureChannel() = ensureChannels(this)

    private fun action(label: String, act: String, req: Int): NotificationCompat.Action {
        val pi = PendingIntent.getService(this, req, Intent(this, RecorderService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    private fun openRecordTab() = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java).putExtra("tab", "record").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A short heads-up when recording begins, so it is obvious that something is capturing the screen and how to manage it. */
    private fun announce() {
        val clip = CaptureConfig.load(this).clipMode
        val b = NotificationCompat.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_tile_record).setAutoCancel(true).setTimeoutAfter(7000)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentTitle(if (clip) "Clip mode is on" else "Memories is recording your screen")
            .setContentText(if (clip) "Tap Clip to keep the last few seconds. Nothing else is saved." else "Pull down the notification shade any time to stop and save.")
            .setContentIntent(openRecordTab())
        if (clip) b.addAction(action("Clip", ACTION_CLIP, 11)).addAction(action("Turn off", ACTION_STOP, 12)) else b.addAction(action("Stop and save", ACTION_STOP, 12))
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_ID, b.build()) }
    }

    private fun refreshNotice() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTE_ID, notice()) }
        tellLauncher()
    }

    /** The recording controls as a card on the floating button, the same buttons as the notification. */
    private fun card() {
        if (!RecorderState.running) { dev.wonderdesk.shared.WonderAssist.clear(this, "memories", "rec"); return }
        fun pi(act: String, req: Int) = PendingIntent.getService(this, req, Intent(this, RecorderService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val clip = RecorderState.clipMode
        val acts = if (clip) { if (RecorderState.writing) listOf("Save clip" to pi(ACTION_END_CLIP, 31), "Turn off" to pi(ACTION_STOP, 32)) else listOf("Start clip" to pi(ACTION_CLIP, 33), "Turn off" to pi(ACTION_STOP, 32)) } else listOf("Stop and save" to pi(ACTION_STOP, 32))
        dev.wonderdesk.shared.WonderAssist.post(this, "memories", "rec", if (clip) (if (RecorderState.writing) "Saving a clip" else "Clip mode is on") else "Recording your screen",
            if (clip) (if (RecorderState.writing) "Recording this clip. Tap Save clip when you have what you want" else "Tap Start clip to keep the last few seconds") else "Stop and save when you are done", acts, openRecordTab(), ongoing = true)
    }

    /** Lets the Wonderdesk Launcher's floating button know what the recorder is doing, so it can hide itself from the recording and offer Clip and Stop. */
    private fun tellLauncher() {
        runCatching { card() }
        runCatching { sendBroadcast(Intent(RecorderControl.STATE).setPackage("dev.wonderdesk.launcher").putExtra("running", RecorderState.running).putExtra("clip", RecorderState.clipMode).putExtra("writing", RecorderState.writing)) }
    }

    private fun notice(): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile_record).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openRecordTab())
        val c = CaptureConfig.load(this)
        if (c.clipMode && !RecorderState.writing) {
            b.setContentTitle("Clip mode is on").setContentText("Nothing is kept except the last ${c.bufferSec} seconds. Tap Clip to save them.")
            b.addAction(action("Clip", ACTION_CLIP, 1)); b.addAction(action("Turn off", ACTION_STOP, 2))
        } else if (c.clipMode) {
            b.setContentTitle("Recording a clip").setContentText("Started ${c.bufferSec} seconds back. Stop to save it.").setUsesChronometer(true)
            b.addAction(action("Stop and save", ACTION_END_CLIP, 3)); b.addAction(action("Turn off", ACTION_STOP, 2))
        } else {
            b.setContentTitle("Recording the screen").setUsesChronometer(true).setWhen(RecorderState.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            b.addAction(action("Stop and save", ACTION_STOP, 2))
        }
        return b.build()
    }

    override fun onDestroy() {
        capture?.let { c -> Thread { runCatching { c.stop() } }.start() }
        capture = null
        RecorderState.running = false; RecorderState.writing = false; tellLauncher()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.wonderdesk.memories.REC_START"
        const val ACTION_STOP = "dev.wonderdesk.memories.REC_STOP"
        const val ACTION_CLIP = "dev.wonderdesk.memories.REC_CLIP"
        const val ACTION_END_CLIP = "dev.wonderdesk.memories.REC_END_CLIP"
        private const val CHANNEL = "recorder_live"
        private const val ALERT_CHANNEL = "recorder_alert"
        private const val NOTE_ID = 4711
        private const val ALERT_ID = 4712

        fun ensureChannels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            // The old low-importance channel cannot be raised, so the live one has a new id.
            runCatching { nm.deleteNotificationChannel("recorder") }
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows while Memories is recording the screen, with buttons to stop or clip"
                setSound(null, null); enableVibration(false); setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            if (nm.getNotificationChannel(ALERT_CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Recording started", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A short pop-up when a screen recording or clip mode starts"
                setSound(null, null); enableVibration(false); setShowBadge(false)
            })
        }

        /** True when Android would actually show the recording notification. */
        fun notificationsWork(c: Context): Boolean {
            if (!androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled()) return false
            ensureChannels(c)
            val nm = c.getSystemService(NotificationManager::class.java)
            return nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        fun start(c: Context, resultCode: Int, data: Intent) =
            ContextCompat.startForegroundService(c, Intent(c, RecorderService::class.java).setAction(ACTION_START).putExtra("code", resultCode).putExtra("data", data))
        fun send(c: Context, action: String) = c.startService(Intent(c, RecorderService::class.java).setAction(action))
    }
}

/** The recorder's state broadcast and the commands other Wonderdesk apps may send it (same-signature apps only). */
object RecorderControl {
    const val STATE = "dev.wonderdesk.memories.REC_STATE"
    const val CLIP = "dev.wonderdesk.memories.CTL_CLIP"
    const val SAVE_CLIP = "dev.wonderdesk.memories.CTL_SAVE_CLIP"
    const val STOP = "dev.wonderdesk.memories.CTL_STOP"
}

class RecorderControlReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (!RecorderState.running) return
        when (i.action) {
            RecorderControl.CLIP -> RecorderService.send(c, RecorderService.ACTION_CLIP)
            RecorderControl.SAVE_CLIP -> RecorderService.send(c, RecorderService.ACTION_END_CLIP)
            RecorderControl.STOP -> RecorderService.send(c, RecorderService.ACTION_STOP)
        }
    }
}
