package dev.wonderdesk.memories

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class Photo(val id: Long, val uri: Uri, val name: String, val taken: Long, val size: Long, val width: Int, val height: Int,
                 val album: String, val relPath: String, val video: Boolean, val duration: Long, val favorite: Boolean, val mime: String) {
    val day: LocalDate get() = Instant.ofEpochMilli(taken).atZone(ZoneId.systemDefault()).toLocalDate()
    val path get() = "/storage/emulated/0/$relPath$name"
}

object Gallery {
    fun all(c: Context): List<Photo> {
        val out = mutableListOf<Photo>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.MIME_TYPE)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        runCatching {
            c.contentResolver.query(files, proj, sel, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0); val video = cur.getInt(9) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val taken = cur.getLong(2).takeIf { it > 0 } ?: cur.getLong(3) * 1000
                    out += Photo(id, ContentUris.withAppendedId(base, id), cur.getString(1) ?: "Photo", taken, cur.getLong(4), cur.getInt(5), cur.getInt(6),
                        cur.getString(7) ?: "Pictures", cur.getString(8) ?: "", video, cur.getLong(10), cur.getInt(11) == 1, cur.getString(12) ?: "")
                }
            }
        }
        return out.sortedByDescending { it.taken }
    }

    private val thumbs = object : LruCache<String, Bitmap>(64 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    fun cached(p: Photo, px: Int): Bitmap? = thumbs.get("${p.id}@$px")
    /** A few decodes at a time: a fast fling doesn't pile up hundreds of reads. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val loader = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(4)

    fun thumb(c: Context, p: Photo, px: Int = 256): Bitmap? {
        val k = "${p.id}@$px"
        return thumbs.get(k) ?: runCatching { c.contentResolver.loadThumbnail(p.uri, Size(px, px), null) }.getOrNull()?.also { thumbs.put(k, it) }
    }

    /** Full image downsampled to fit [max] pixels on its long edge, rotated by EXIF. */
    fun load(c: Context, uri: Uri, max: Int = 2560): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        val b = c.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
        val deg = runCatching { c.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    }.getOrNull()

    data class Info(val rows: List<Pair<String, String>>, val lat: Double?, val lon: Double?)

    fun info(c: Context, p: Photo): Info {
        val rows = mutableListOf<Pair<String, String>>()
        rows += "Name" to p.name
        rows += "Taken" to java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT).format(java.util.Date(p.taken))
        if (p.width > 0) rows += "Size" to "${p.width} × ${p.height} · ${"%.1f".format(p.width * p.height / 1_000_000f)} MP · ${dev.wonderdesk.shared.formatBytes(p.size)}"
        rows += "Folder" to p.relPath.ifBlank { p.album }
        var lat: Double? = null; var lon: Double? = null
        if (!p.video) runCatching {
            c.contentResolver.openInputStream(MediaStore.setRequireOriginal(p.uri))!!.use { s ->
                val e = ExifInterface(s)
                listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)).joinToString(" ").takeIf { it.isNotBlank() }?.let { rows += "Camera" to it }
                val exposure = listOfNotNull(e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                    e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { if (it < 1) "1/${(1 / it).toInt()} s" else "$it s" },
                    e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
                    e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "${it.toInt()} mm" }).joinToString(" · ")
                if (exposure.isNotBlank()) rows += "Exposure" to exposure
                e.latLong?.let { lat = it[0]; lon = it[1]; rows += "Location" to "%.5f, %.5f".format(it[0], it[1]) }
            }
        }
        return Info(rows, lat, lon)
    }

    fun favoriteRequest(c: Context, uris: List<Uri>, fav: Boolean): PendingIntent = MediaStore.createFavoriteRequest(c.contentResolver, uris, fav)
    fun trashRequest(c: Context, uris: List<Uri>, trash: Boolean = true): PendingIntent = MediaStore.createTrashRequest(c.contentResolver, uris, trash)
    fun writeRequest(c: Context, uris: List<Uri>): PendingIntent = MediaStore.createWriteRequest(c.contentResolver, uris)

    /** Moves items into Pictures/<album> (after the user approved a write request). */
    fun move(c: Context, uris: List<Uri>, album: String) {
        uris.forEach { u ->
            val isVideo = u.toString().contains("/video/")
            val root = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            c.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$root/$album/") }, null, null)
        }
    }

    fun trashed(c: Context): List<Photo> {
        val out = mutableListOf<Photo>()
        val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        runCatching {
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_EXPIRES), args, null)?.use { cur ->
                while (cur.moveToNext()) { val id = cur.getLong(0)
                    out += Photo(id, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), cur.getString(1) ?: "", cur.getLong(2) * 1000, 0, 0, 0, "Trash", "", false, 0, false, "image/*") }
            }
        }
        return out
    }

    fun saveCopy(c: Context, bmp: Bitmap, name: String, album: String = "Memories"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Couldn't save the picture")
        c.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }
}

/* -------------------------------------------------------- edits --------------------------------------------- */

data class Adjust(val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f, val warmth: Float = 0f)

enum class Filter(val label: String) { None("Original"), Vivid("Vivid"), Mono("Mono"), Noir("Noir"), Sepia("Sepia"), Cool("Cool"), Warm("Warm"), Fade("Fade") }

object Edits {
    fun matrix(a: Adjust, f: Filter): ColorMatrix {
        val m = ColorMatrix()
        // filter first
        when (f) {
            Filter.Mono -> m.setSaturation(0f)
            Filter.Noir -> { m.setSaturation(0f); m.postConcat(contrast(.35f)) }
            Filter.Vivid -> { m.setSaturation(1.45f); m.postConcat(contrast(.12f)) }
            Filter.Sepia -> { m.setSaturation(0f); m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 40f, 0f, 1f, 0f, 0f, 20f, 0f, 0f, 1f, 0f, -20f, 0f, 0f, 0f, 1f, 0f))) }
            Filter.Cool -> m.postConcat(ColorMatrix(floatArrayOf(.92f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.12f, 0f, 12f, 0f, 0f, 0f, 1f, 0f)))
            Filter.Warm -> m.postConcat(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 14f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, .88f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            Filter.Fade -> { m.setSaturation(.7f); m.postConcat(ColorMatrix(floatArrayOf(.85f, 0f, 0f, 0f, 30f, 0f, .85f, 0f, 0f, 30f, 0f, 0f, .85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f))) }
            Filter.None -> {}
        }
        if (a.saturation != 0f) m.postConcat(ColorMatrix().apply { setSaturation(1f + a.saturation) })
        if (a.contrast != 0f) m.postConcat(contrast(a.contrast))
        if (a.brightness != 0f) { val b = a.brightness * 80f; m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))) }
        if (a.warmth != 0f) { val w = a.warmth * 30f; m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, -w, 0f, 0f, 0f, 1f, 0f))) }
        return m
    }

    private fun contrast(c: Float): ColorMatrix { val s = 1f + c; val t = (-.5f * s + .5f) * 255f
        return ColorMatrix(floatArrayOf(s, 0f, 0f, 0f, t, 0f, s, 0f, 0f, t, 0f, 0f, s, 0f, t, 0f, 0f, 0f, 1f, 0f)) }

    /** Applies everything to a full-size bitmap. Crop is a fraction rect of the rotated image. */
    fun render(src: Bitmap, rotate: Int, flipH: Boolean, crop: android.graphics.RectF, a: Adjust, f: Filter, strokes: List<Markup>): Bitmap {
        val m = Matrix().apply { postRotate(rotate.toFloat()); if (flipH) postScale(-1f, 1f) }
        val turned = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        val x = (crop.left * turned.width).toInt().coerceIn(0, turned.width - 1); val y = (crop.top * turned.height).toInt().coerceIn(0, turned.height - 1)
        val w = (crop.width() * turned.width).toInt().coerceIn(1, turned.width - x); val h = (crop.height() * turned.height).toInt().coerceIn(1, turned.height - y)
        val cropped = Bitmap.createBitmap(turned, x, y, w, h)
        val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(cropped, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix(a, f)) })
        strokes.forEach { s ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; style = Paint.Style.STROKE; strokeWidth = s.width * out.width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val path = android.graphics.Path(); s.points.forEachIndexed { i, (px, py) -> if (i == 0) path.moveTo(px * out.width, py * out.height) else path.lineTo(px * out.width, py * out.height) }
            canvas.drawPath(path, p)
        }
        return out
    }
}

/** A markup stroke in fractions of the final (cropped) image. */
data class Markup(val color: Int, val width: Float, val points: List<Pair<Float, Float>>)
