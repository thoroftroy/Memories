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

    /**
     * For a picture that is not in the library (one coming from a computer through Ripple, a chat, a share): the data is read once, with progress,
     * then shown in quick passes, a rough one almost at once and sharper ones after it, so something is on screen as early as possible.
     */
    fun loadProgressive(c: Context, uri: Uri, max: Int = 2560, onProgress: (read: Long, total: Long) -> Unit, onPass: (Bitmap) -> Unit): Bitmap? = runCatching {
        // A picture on a computer (through Ripple): the computer makes a tiny preview, then a medium one, then a large one (each a few kilobytes
        // to a few hundred), so something is on screen almost at once and gets sharper, while the full picture downloads after.
        if (uri.authority == "dev.wonderdesk.ripple.link") {
            for (px in intArrayOf(64, 320, 1280)) {
                val b = runCatching {
                    c.contentResolver.openInputStream(uri.buildUpon().appendQueryParameter("thumb", px.toString()).build())?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
                if (b != null) onPass(b) else if (px == 64) break   // this computer can't make previews yet: fetch the whole picture as before
            }
        }
        val total = runCatching { c.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1L } }.getOrNull() ?: -1L
        val out = java.io.ByteArrayOutputStream(if (total in 1..64_000_000) total.toInt() else 1 shl 20)
        c.contentResolver.openInputStream(uri)!!.use { i ->
            val buf = ByteArray(64 * 1024); var read = 0L
            while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n); read += n; onProgress(read, total) }
        }
        val bytes = out.toByteArray()
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        val deg = runCatching { ExifInterface(java.io.ByteArrayInputStream(bytes)).rotationDegrees }.getOrDefault(0)
        fun rotate(b: Bitmap) = if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
        // Rough first, then sharper, then the full size: each step is worth showing only if it is clearly better than the last.
        var pass = maxOf(s * 8, 8)
        while (pass > s * 2) { runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = pass }) }.getOrNull()?.let { onPass(rotate(it)) }; pass /= 2 }
        rotate(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })!!)
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

    /**
     * The library entry for a photo or video that was handed over by another app (Wayfinder shows files by their own address).
     * Favouriting, deleting and moving only work on library entries, so a file is matched to its entry by name and size; null if it isn't in the library.
     */
    fun libraryUri(c: Context, ph: Photo): Uri? {
        if (ph.uri.authority == "media") return ph.uri
        return runCatching {
            var name = ph.name; var size = 0L
            c.contentResolver.query(ph.uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) { cur.getString(0)?.let { name = it }; size = cur.getLong(1) }
            }
            val q = MediaStore.Files.getContentUri("external")
            c.contentResolver.query(q, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE), "${MediaStore.MediaColumns.DISPLAY_NAME}=?" + if (size > 0) " AND ${MediaStore.MediaColumns.SIZE}=?" else "",
                if (size > 0) arrayOf(name, size.toString()) else arrayOf(name), null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val base = if (cur.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    android.content.ContentUris.withAppendedId(base, cur.getLong(0))
                } else null
            }
        }.getOrNull()
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

    /** Items in Android's trash (deleted in the last 30 days), newest first; [Photo.taken] holds when each is removed for good. */
    fun trashed(c: Context): List<Photo> {
        val out = mutableListOf<Photo>()
        val args = android.os.Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})")
        }
        runCatching {
            c.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_EXPIRES,
                MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), args, null)?.use { cur ->
                while (cur.moveToNext()) { val id = cur.getLong(0); val video = cur.getInt(3) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    out += Photo(id, ContentUris.withAppendedId(base, id), cur.getString(1) ?: "", cur.getLong(2) * 1000, cur.getLong(6), 0, 0, "Trash", "", video, cur.getLong(4), false, cur.getString(5) ?: "") }
            }
        }
        return out.sortedBy { it.taken }
    }
    fun deleteRequest(c: Context, uris: List<Uri>): PendingIntent = MediaStore.createDeleteRequest(c.contentResolver, uris)

    /** Saves a PNG (keeps see-through areas) or a JPEG into Pictures/<album>. */
    fun saveImage(c: Context, bmp: Bitmap, name: String, png: Boolean, album: String = "Memories"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album"); put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: error("Couldn't save the picture")
        c.contentResolver.openOutputStream(uri)!!.use { o ->
            if (png) bmp.compress(Bitmap.CompressFormat.PNG, 100, o)
            else {
                val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                flat.compress(Bitmap.CompressFormat.JPEG, 95, o)
            }
        }
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
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
