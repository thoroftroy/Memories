package dev.wonderdesk.cinema

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import org.json.JSONObject
import java.io.File

data class Video(val id: Long, val uri: Uri, val title: String, val duration: Long, val size: Long, val width: Int, val height: Int,
                 val folder: String, val added: Long, val path: String)

object Videos {
    fun all(c: Context): List<Video> {
        val out = mutableListOf<Video>()
        val proj = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.RELATIVE_PATH)
        runCatching {
            c.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    out += Video(id, ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id), cur.getString(1) ?: "Video", cur.getLong(2), cur.getLong(3),
                        cur.getInt(4), cur.getInt(5), cur.getString(6) ?: "Videos", cur.getLong(7) * 1000, cur.getString(8) ?: "")
                }
            }
        }
        return out
    }

    private val thumbs = object : LruCache<Long, Bitmap>(40 * 1024 * 1024) { override fun sizeOf(key: Long, value: Bitmap) = value.byteCount }
    fun thumb(c: Context, v: Video): Bitmap? = thumbs.get(v.id) ?: runCatching { c.contentResolver.loadThumbnail(v.uri, Size(384, 216), null) }.getOrNull()?.also { thumbs.put(v.id, it) }

    /** Length, width and height of a video, or null if it can't be read. */
    fun probe(c: Context, uri: Uri): Triple<Long, Int, Int>? = runCatching {
        val r = android.media.MediaMetadataRetriever(); r.setDataSource(c, uri)
        val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
        val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        r.release(); if (rot % 180 != 0) Triple(d, h, w) else Triple(d, w, h)
    }.getOrNull()

    fun frame(c: Context, uri: Uri, atMs: Long): Bitmap? = runCatching {
        MediaMetadataRetriever().use { r -> r.setDataSource(c, uri); r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) }
    }.getOrNull()

    /** Saves into Movies/Cinema or Pictures/Cinema through MediaStore and returns the new item's uri. */
    fun publish(c: Context, src: File, name: String, image: Boolean): Uri {
        val coll = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, if (image) "image/png" else "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/Cinema")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(coll, values) ?: error("Couldn't save to your library")
        c.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}

/** Deletes a video from the phone. Android asks the person to confirm for files Cinema did not create. */
object VideoDeleter {
    fun delete(c: Context, uri: Uri, ask: (android.content.IntentSender) -> Unit, done: () -> Unit, fail: () -> Unit) {
        try {
            if (c.contentResolver.delete(uri, null, null) > 0) done() else fail()
        } catch (e: SecurityException) {
            val sender = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(c.contentResolver, listOf(uri)).intentSender
                else (e as? android.app.RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }.getOrNull()
            if (sender != null) ask(sender) else fail()
        } catch (e: Exception) { fail() }
    }
}

/** Remembers where you stopped each video. */
object Resume {
    private fun prefs(c: Context) = c.getSharedPreferences("cinema-resume", Context.MODE_PRIVATE)
    fun get(c: Context, uri: Uri): Long = prefs(c).getString(uri.toString(), null)?.let { runCatching { JSONObject(it).getLong("pos") }.getOrNull() } ?: 0L
    fun set(c: Context, uri: Uri, pos: Long, duration: Long) {
        val e = prefs(c).edit()
        if (duration > 0 && (pos < 5_000 || pos > duration - 10_000)) e.remove(uri.toString())
        else e.putString(uri.toString(), JSONObject().put("pos", pos).put("dur", duration).put("at", System.currentTimeMillis()).toString())
        e.apply()
        // The widget only needs to catch up now and then, not every few seconds of playback.
        if (System.currentTimeMillis() - lastWidget > 60_000) { lastWidget = System.currentTimeMillis(); dev.wonderdesk.shared.refreshWidgets(c, ContinueWidget::class.java) }
    }
    private var lastWidget = 0L
    fun recent(c: Context): List<Triple<Uri, Long, Long>> = prefs(c).all.mapNotNull { (k, v) ->
        runCatching { val o = JSONObject(v as String); Triple(Uri.parse(k), o.getLong("pos"), o.getLong("at")) to o.getLong("dur") }.getOrNull()
    }.sortedByDescending { it.first.third }.map { it.first }
    fun progress(c: Context, uri: Uri): Float = prefs(c).getString(uri.toString(), null)?.let { runCatching { JSONObject(it).let { o -> o.getLong("pos").toFloat() / o.getLong("dur").coerceAtLeast(1) } }.getOrNull() } ?: 0f
}

/**
 * [keep] are the parts of the video to keep, in order; everything between them is cut out.
 * Brightness and contrast run -1 to 1, saturation and warmth -1 to 1, zoom 1 to 4 with pan -1 to 1.
 */
data class Part(val uri: Uri, val start: Long, val end: Long)

data class EditSpec(
    val keep: List<Part>, val mute: Boolean, val rotate: Int, val height: Int?,
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val warmth: Float = 0f,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f,
    val volume: Float = 1f, val speed: Float = 1f,
    val caption: String = "", val music: Uri? = null, val musicVolume: Float = 0.8f,
    /** Width and height every clip is fitted into when the movie joins clips of different shapes. */
    val canvas: Pair<Int, Int>? = null,
)

/** Video edits with Media3 Transformer. Must be started on the main thread. */
@UnstableApi
object Editor {
    private var transformer: Transformer? = null

    /** The picture effects for [spec]. Preview skips rotation and resizing, which the preview does itself. */
    fun videoEffects(spec: EditSpec, preview: Boolean): List<androidx.media3.common.Effect> = buildList {
        if (spec.zoom > 1.001f) {
            val half = 1f / spec.zoom
            val cx = spec.panX.coerceIn(-1f, 1f) * (1f - half)
            val cy = spec.panY.coerceIn(-1f, 1f) * (1f - half)
            add(androidx.media3.effect.Crop(cx - half, cx + half, cy - half, cy + half))
        }
        if (spec.brightness != 0f) add(androidx.media3.effect.Brightness(spec.brightness.coerceIn(-1f, 1f)))
        if (spec.contrast != 0f) add(androidx.media3.effect.Contrast(spec.contrast.coerceIn(-1f, 1f)))
        if (spec.saturation != 0f) add(androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(spec.saturation.coerceIn(-1f, 1f) * 100f).build())
        if (spec.warmth != 0f) add(androidx.media3.effect.RgbAdjustment.Builder().setRedScale(1f + .18f * spec.warmth).setBlueScale(1f - .18f * spec.warmth).build())
        if (spec.caption.isNotBlank()) {
            val text = android.text.SpannableString(spec.caption.trim())
            text.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.AbsoluteSizeSpan(56), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(android.text.style.BackgroundColorSpan(0x99000000.toInt()), 0, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val settings = androidx.media3.effect.OverlaySettings.Builder().setBackgroundFrameAnchor(0f, -.78f).build()
            add(androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.of<androidx.media3.effect.TextureOverlay>(androidx.media3.effect.TextOverlay.createStaticTextOverlay(text, settings))))
        }
        if (!preview) {
            if (spec.rotate % 360 != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(spec.rotate.toFloat()).build())
            if (spec.canvas != null) add(Presentation.createForWidthAndHeight(spec.canvas.first, spec.canvas.second, Presentation.LAYOUT_SCALE_TO_FIT))
            else spec.height?.let { add(Presentation.createForHeight(it)) }
        }
    }

    /** A gain applied to every channel layout the clip might have. */
    private fun volumeProcessor(v: Float): androidx.media3.common.audio.AudioProcessor =
        androidx.media3.common.audio.ChannelMixingAudioProcessor().apply {
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(1, 1).scaleBy(v))
            putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(2, 2).scaleBy(v))
        }

    fun export(c: Context, spec: EditSpec, out: File, onDone: (Result<File>) -> Unit): () -> Int {
        val video = videoEffects(spec, preview = false).toMutableList()
        val audio = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        if (kotlin.math.abs(spec.volume - 1f) > .01f) audio += volumeProcessor(spec.volume)
        if (kotlin.math.abs(spec.speed - 1f) > .01f) {
            val speed = spec.speed
            val provider = object : androidx.media3.common.audio.SpeedProvider {
                override fun getSpeed(timeUs: Long) = speed
                override fun getNextSpeedChangeTimeUs(timeUs: Long) = androidx.media3.common.C.TIME_UNSET
            }
            val pair = Effects.createExperimentalSpeedChangingEffect(provider)
            audio += pair.first; video += pair.second
        }
        // Each kept part becomes a clip; the clips play back to back in one new video.
        val clips = spec.keep.filter { it.end - it.start >= 100 }.map { (u, a, b) ->
            val item = MediaItem.Builder().setUri(u).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a).setEndPositionMs(b).build()).build()
            EditedMediaItem.Builder(item).setRemoveAudio(spec.mute).setEffects(Effects(audio, video)).build()
        }
        require(clips.isNotEmpty()) { "Keep at least one part of the video" }
        val sequences = mutableListOf(androidx.media3.transformer.EditedMediaItemSequence(clips))
        spec.music?.let { m ->
            val total = (spec.keep.sumOf { it.end - it.start } / spec.speed.coerceAtLeast(.1f)).toLong()
            val track = MediaItem.Builder().setUri(m).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs(total).build()).build()
            val item = EditedMediaItem.Builder(track).setRemoveVideo(true).setEffects(Effects(listOf(volumeProcessor(spec.musicVolume)), emptyList())).build()
            sequences += androidx.media3.transformer.EditedMediaItemSequence(listOf(item))
        }
        val composition = Composition.Builder(sequences).build()
        val t = Transformer.Builder(c).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { transformer = null; onDone(Result.success(out)) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { transformer = null; onDone(Result.failure(exportException)) }
        }).build()
        transformer = t
        out.delete()
        t.start(composition, out.path)
        val holder = ProgressHolder()
        return { if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1 }
    }

    fun cancel() { transformer?.cancel(); transformer = null }
}
