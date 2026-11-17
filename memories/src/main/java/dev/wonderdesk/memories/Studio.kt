package dev.wonderdesk.memories

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One layer of the picture: its pixels plus where it sits on the canvas. */
class Layer(
    val id: Int, var name: String, var bmp: Bitmap,
    var cx: Float, var cy: Float, var scale: Float = 1f, var rot: Float = 0f, var alpha: Float = 1f, var visible: Boolean = true, var blend: Int = 0,
) {
    /** Layer pixels to canvas pixels. */
    fun matrix(): Matrix = Matrix().apply {
        postTranslate(-bmp.width / 2f, -bmp.height / 2f); postScale(scale, scale); postRotate(rot); postTranslate(cx, cy)
    }
    fun copyShallow() = Layer(id, name, bmp, cx, cy, scale, rot, alpha, visible, blend)
}

/** Ways a layer can mix with what is under it. */
private val BLENDS = listOf("Normal" to android.graphics.BlendMode.SRC_OVER, "Multiply" to android.graphics.BlendMode.MULTIPLY, "Screen" to android.graphics.BlendMode.SCREEN,
    "Overlay" to android.graphics.BlendMode.OVERLAY, "Darken" to android.graphics.BlendMode.DARKEN, "Lighten" to android.graphics.BlendMode.LIGHTEN,
    "Colour dodge" to android.graphics.BlendMode.COLOR_DODGE, "Colour burn" to android.graphics.BlendMode.COLOR_BURN, "Soft light" to android.graphics.BlendMode.SOFT_LIGHT,
    "Hard light" to android.graphics.BlendMode.HARD_LIGHT, "Difference" to android.graphics.BlendMode.DIFFERENCE, "Hue" to android.graphics.BlendMode.HUE, "Colour" to android.graphics.BlendMode.COLOR)

private class Snap(val layers: List<Layer>, val selected: Int, val pixelLayer: Int, val pixels: Bitmap?)

/** The whole document: a canvas size, the layers (bottom first) and the undo history. */
private class Doc(val w: Int, val h: Int) {
    val layers = mutableStateListOf<Layer>()
    var version by mutableIntStateOf(0)
    var selected by mutableIntStateOf(-1)
    var nextId = 1
    val undo = ArrayDeque<Snap>()
    // Selection: a mask over the pixels of one layer (1 = selected).
    var mask: ByteArray? = null
    var maskLayer = -1
    var maskPreview: Bitmap? = null

    fun sel(): Layer? = layers.firstOrNull { it.id == selected }
    fun touch() { version++ }

    fun add(name: String, bmp: Bitmap, at: Int = layers.size): Layer {
        val l = Layer(nextId++, name, bmp, w / 2f, h / 2f)
        layers.add(at.coerceIn(0, layers.size), l); selected = l.id; touch(); return l
    }

    /** Remember the state before a change. Pass the layer whose pixels are about to change. */
    fun snapshot(pixelsOf: Layer? = null) {
        undo.addLast(Snap(layers.map { it.copyShallow() }, selected, pixelsOf?.id ?: -1, pixelsOf?.bmp?.copy(Bitmap.Config.ARGB_8888, false)))
        while (undo.size > 24) undo.removeFirst().pixels?.recycle()
    }

    fun undo(): Boolean {
        val s = undo.removeLastOrNull() ?: return false
        layers.clear(); layers.addAll(s.layers.map { it.copyShallow() })
        selected = s.selected
        if (s.pixels != null) layers.firstOrNull { it.id == s.pixelLayer }?.let { l ->
            val c = AndroidCanvas(l.bmp); c.drawColor(0, PorterDuff.Mode.CLEAR); c.drawBitmap(s.pixels, 0f, 0f, null)
            s.pixels.recycle()
        }
        clearMask(); touch(); return true
    }

    fun clearMask() { mask = null; maskLayer = -1; maskPreview?.recycle(); maskPreview = null }

    fun setMask(layer: Layer, m: ByteArray) {
        mask = m; maskLayer = layer.id
        val w = layer.bmp.width; val h = layer.bmp.height
        val px = IntArray(w * h)
        for (i in px.indices) if (m[i].toInt() != 0) px[i] = 0x662F8CFF
        maskPreview?.recycle()
        maskPreview = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        touch()
    }

    /** Flattens every visible layer into one picture. */
    fun render(): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        layers.forEach { l -> if (l.visible) { p.alpha = (l.alpha * 255).toInt(); p.blendMode = BLENDS[l.blend].second; c.drawBitmap(l.bmp, l.matrix(), p) } }
        return out
    }
}

/** Colour-similar region around a point (magic wand). Returns a mask the size of the bitmap. */
private fun floodMask(bmp: Bitmap, sx: Int, sy: Int, tolerance: Int): ByteArray {
    val w = bmp.width; val h = bmp.height
    val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
    val mask = ByteArray(w * h)
    val seed = px[sy * w + sx]
    fun close(c: Int): Boolean {
        val da = abs((c ushr 24) - (seed ushr 24)); val dr = abs(((c shr 16) and 255) - ((seed shr 16) and 255))
        val dg = abs(((c shr 8) and 255) - ((seed shr 8) and 255)); val db = abs((c and 255) - (seed and 255))
        return max(max(da, dr), max(dg, db)) <= tolerance
    }
    val stack = IntArray(w * h)
    var sp = 0
    stack[sp++] = sy * w + sx
    while (sp > 0) {
        val i = stack[--sp]
        if (mask[i].toInt() != 0) continue
        if (!close(px[i])) continue
        // Fill the whole run to the left and right, then queue the rows above and below.
        val y = i / w
        var l = i % w; var r = l
        while (l > 0 && mask[y * w + l - 1].toInt() == 0 && close(px[y * w + l - 1])) l--
        while (r < w - 1 && mask[y * w + r + 1].toInt() == 0 && close(px[y * w + r + 1])) r++
        for (x in l..r) {
            mask[y * w + x] = 1
            if (y > 0 && mask[(y - 1) * w + x].toInt() == 0 && sp < stack.size - 1) stack[sp++] = (y - 1) * w + x
            if (y < h - 1 && mask[(y + 1) * w + x].toInt() == 0 && sp < stack.size - 1) stack[sp++] = (y + 1) * w + x
        }
    }
    return mask
}

/** Grows (+) or shrinks (-) a selection by [r] pixels. */
private fun resizeMask(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
    var cur = m
    repeat(kotlin.math.abs(r)) {
        val grow = r > 0; val nx = cur.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val any = (x > 0 && (cur[i - 1].toInt() != 0) == grow) || (x < w - 1 && (cur[i + 1].toInt() != 0) == grow) || (y > 0 && (cur[i - w].toInt() != 0) == grow) || (y < h - 1 && (cur[i + w].toInt() != 0) == grow)
            if (any) nx[i] = if (grow) 1 else 0
        }
        cur = nx
    }
    return cur
}

/** A box blur, run a few times to look smooth. */
private fun blurBitmap(src: Bitmap, radius: Int): Bitmap {
    val w = src.width; val h = src.height; var a = IntArray(w * h); src.getPixels(a, 0, w, 0, 0, w, h)
    var b = IntArray(w * h)
    repeat(3) {
        for (y in 0 until h) { var sa = 0; var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (x in -radius..radius) if (x in 0 until w) { val c = a[y * w + x]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++ }
            for (x in 0 until w) {
                b[y * w + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val out = x - radius; val inn = x + radius + 1
                if (out >= 0) { val c = a[y * w + out]; sa -= c ushr 24; sr -= (c shr 16) and 255; sg -= (c shr 8) and 255; sb -= c and 255; n-- }
                if (inn < w) { val c = a[y * w + inn]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++ }
            } }
        for (x in 0 until w) { var sa = 0; var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (y in -radius..radius) if (y in 0 until h) { val c = b[y * w + x]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++ }
            for (y in 0 until h) {
                a[y * w + x] = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                val out = y - radius; val inn = y + radius + 1
                if (out >= 0) { val c = b[out * w + x]; sa -= c ushr 24; sr -= (c shr 16) and 255; sg -= (c shr 8) and 255; sb -= c and 255; n-- }
                if (inn < h) { val c = b[inn * w + x]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255; n++ }
            } }
    }
    return Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888)
}

/** Brightness (-1..1), contrast (-1..1), saturation (-1..1) and warmth (-1..1) as one colour matrix. */
private fun adjustMatrix(bright: Float, contrast: Float, sat: Float, warm: Float): android.graphics.ColorMatrix {
    val m = android.graphics.ColorMatrix()
    m.setSaturation(1f + sat)
    val c = 1f + contrast; val t = (1f - c) * 128f + bright * 255f
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f + .2f * warm, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - .2f * warm, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    return m
}

private fun textLayerBitmap(text: String, color: Int, sizePx: Float): Bitmap {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; textSize = sizePx; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    val lines = text.split('\n')
    val width = (lines.maxOf { paint.measureText(it) } + sizePx * .4f).toInt().coerceAtLeast(8)
    val lineH = (paint.fontMetrics.descent - paint.fontMetrics.ascent)
    val height = (lineH * lines.size + sizePx * .3f).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    lines.forEachIndexed { i, s -> c.drawText(s, sizePx * .2f, -paint.fontMetrics.ascent + i * lineH + sizePx * .1f, paint) }
    return bmp
}

private enum class Tool(val label: String) { Move("Move"), Brush("Brush"), Eraser("Eraser"), Wand("Magic wand"), Box("Box select"), Lasso("Lasso"), Bucket("Paint bucket"), Pick("Colour picker"), Shape("Shapes"), Adjust("Adjust") }

/** The layered editor: layers, brush, eraser, magic wand, box select, text, added photos, transparency and PNG export. */
@Composable
fun StudioScreen(uri: android.net.Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    LaunchedEffect(uri) {
        val bmp = withContext(Dispatchers.IO) { Gallery.load(context, uri, 2048)?.copy(Bitmap.Config.ARGB_8888, true) }
        if (bmp == null) { toastRef.value("Couldn't open that picture"); return@LaunchedEffect }
        doc = Doc(bmp.width, bmp.height).also { it.add("Photo", bmp) }
    }
    val d = doc
    var tool by remember { mutableStateOf(Tool.Move) }
    var brush by remember { mutableFloatStateOf(36f) }
    var tol by remember { mutableFloatStateOf(28f) }
    var color by remember { mutableIntStateOf(0xFFE5483C.toInt()) }
    var brushAlpha by remember { mutableFloatStateOf(1f) }
    var shape by remember { mutableStateOf("Rectangle") }
    var filled by remember { mutableStateOf(true) }
    var aBright by remember { mutableFloatStateOf(0f) }; var aContrast by remember { mutableFloatStateOf(0f) }; var aSat by remember { mutableFloatStateOf(0f) }; var aWarm by remember { mutableFloatStateOf(0f) }
    var aBlur by remember { mutableFloatStateOf(4f) }
    var lassoPath by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var working by remember { mutableStateOf<String?>(null) }
    var png by remember { mutableStateOf(context.getSharedPreferences("memories", android.content.Context.MODE_PRIVATE).getBoolean("studioPng", true)) }
    var saving by remember { mutableStateOf(false) }
    var textAsk by remember { mutableStateOf(false) }
    var replaceAsk by remember { mutableStateOf(false) }
    val addPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null && d != null) scope.launch {
            val b = withContext(Dispatchers.IO) { Gallery.load(context, u, max(d.w, d.h) / 2 + 400)?.copy(Bitmap.Config.ARGB_8888, true) }
            if (b != null) { d.snapshot(); val n = d.add(context.displayName(u).substringBeforeLast('.').take(16), b); n.scale = min(1f, d.w * .7f / b.width) } else toastRef.value("Couldn't open that picture")
        }
    }
    val writeGrant = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK && d != null) {
            saving = true
            scope.launch {
                val res = withContext(Dispatchers.Default) { runCatching {
                    val out = d.render()
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { o ->
                        if (png) out.compress(Bitmap.CompressFormat.PNG, 100, o) else {
                            val flat = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888); AndroidCanvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(out, 0f, 0f, null) }
                            flat.compress(Bitmap.CompressFormat.JPEG, 95, o)
                        }
                    }
                    true
                } }
                saving = false
                res.onSuccess { toastRef.value("Replaced the original"); onClose() }.onFailure { toastRef.value("Couldn't replace it: ${it.message}") }
            }
        }
    }
    if (textAsk && d != null) InputDialog("Add text", "What should it say?", confirm = "Add", onDismiss = { textAsk = false }) { t ->
        textAsk = false
        if (t.isNotBlank()) { d.snapshot(); val l = d.add(t.take(20).replace('\n', ' '), textLayerBitmap(t, color, d.h / 9f)); l.name = "Text: " + t.take(14) }
    }
    if (replaceAsk && d != null) AlertDialog(onDismissRequest = { replaceAsk = false }, title = { Text("Replace the original?") },
        text = { Text("The original picture is overwritten with this edit. Android asks once more before it does. A copy is safer.") },
        confirmButton = { TextButton(onClick = {
            replaceAsk = false
            runCatching { writeGrant.launch(androidx.activity.result.IntentSenderRequest.Builder(Gallery.writeRequest(context, listOf(uri)).intentSender).build()) }
                .onFailure { toastRef.value("This picture can't be replaced, save a copy instead") }
        }) { Text("Replace") } }, dismissButton = { TextButton(onClick = { replaceAsk = false }) { Text("Keep original") } })

    WonderApp(appName = "Memories", about = "Layers, cut-outs and transparency.", title = "Layers", onBack = onClose, actions = {
        ToolbarButton(WonderIcons.Undo, "Undo", { d?.undo() }, enabled = d != null && d.version >= 0 && d.undo.isNotEmpty())
        TextButton(enabled = d != null && !saving, onClick = {
            val dd = d ?: return@TextButton
            saving = true
            scope.launch {
                val r = withContext(Dispatchers.Default) { runCatching {
                    val out = dd.render()
                    val base = context.displayName(uri).substringBeforeLast('.') + " (layers)"
                    Gallery.saveImage(context, out, base + if (png) ".png" else ".jpg", png)
                } }
                saving = false
                r.onSuccess { toastRef.value("Saved a copy in Pictures/Memories"); onClose() }.onFailure { toastRef.value(it.message ?: "Couldn't save") }
            }
        }) { Text(if (saving) "Saving…" else "Save copy", color = p.toolbarText, style = MaterialTheme.typography.titleMedium) }
        TextButton(enabled = d != null && !saving, onClick = { replaceAsk = true }) { Text("Replace", color = p.toolbarText, style = MaterialTheme.typography.titleMedium) }
    }) {
        toastRef.value = LocalToast.current
        if (d == null) { LoadingPane("Opening your picture", detail = "Preparing it for layers"); return@WonderApp }
        val ver = d.version
        Column(Modifier.fillMaxSize()) {
            // ---- canvas
            var box by remember { mutableStateOf(IntSize(1, 1)) }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF2B2F36)).padding(10.dp), contentAlignment = Alignment.Center) {
                val ratio = d.w.toFloat() / d.h
                androidx.compose.foundation.Canvas(Modifier.aspectRatio(ratio).onSizeChanged { box = it }
                    .pointerInput(tool, d.selected, tol, brush, color) {
                        val vs = { box.width.toFloat() / d.w }
                        when (tool) {
                            Tool.Move -> awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val l = d.sel()
                                var first = true
                                do {
                                    val ev = awaitPointerEvent()
                                    if (l != null) {
                                        val pan = ev.calculatePan(); val zoom = ev.calculateZoom(); val rot = ev.calculateRotation()
                                        if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                                            if (first) { d.snapshot(); first = false }
                                            l.cx += pan.x / vs(); l.cy += pan.y / vs(); l.scale = (l.scale * zoom).coerceIn(.05f, 20f); l.rot += rot
                                            d.touch()
                                        }
                                    }
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            Tool.Brush, Tool.Eraser -> awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val l = d.sel() ?: return@awaitEachGesture
                                val inv = Matrix(); if (!l.matrix().invert(inv)) return@awaitEachGesture
                                d.snapshot(l)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = brush / l.scale
                                    if (tool == Tool.Eraser) xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) else { this.color = color; this.alpha = (brushAlpha * 255).toInt() }
                                }
                                val cv = AndroidCanvas(l.bmp)
                                fun local(o: Offset): FloatArray { val pt = floatArrayOf(o.x / vs(), o.y / vs()); inv.mapPoints(pt); return pt }
                                var last = local(down.position)
                                cv.drawPoint(last[0], last[1], paint.apply { style = Paint.Style.FILL_AND_STROKE })
                                paint.style = Paint.Style.STROKE
                                d.touch()
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { ch ->
                                        val now = local(ch.position)
                                        cv.drawLine(last[0], last[1], now[0], now[1], paint); last = now; ch.consume()
                                    }
                                    d.touch()
                                } while (ev.changes.any { it.pressed })
                            }
                            Tool.Wand -> detectTapGestures { o ->
                                val l = d.sel() ?: return@detectTapGestures
                                val inv = Matrix(); if (!l.matrix().invert(inv)) return@detectTapGestures
                                val pt = floatArrayOf(o.x / vs(), o.y / vs()); inv.mapPoints(pt)
                                val x = pt[0].toInt(); val y = pt[1].toInt()
                                if (x !in 0 until l.bmp.width || y !in 0 until l.bmp.height) { toastRef.value("Tap on the layer's picture"); return@detectTapGestures }
                                d.setMask(l, floodMask(l.bmp, x, y, tol.toInt()))
                            }
                            Tool.Box -> awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val l = d.sel() ?: return@awaitEachGesture
                                val inv = Matrix(); if (!l.matrix().invert(inv)) return@awaitEachGesture
                                fun local(o: Offset): FloatArray { val pt = floatArrayOf(o.x / vs(), o.y / vs()); inv.mapPoints(pt); return pt }
                                val a = local(down.position)
                                do {
                                    val ev = awaitPointerEvent()
                                    val b = local(ev.changes.first().position)
                                    val r = RectF(min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1]))
                                    val w = l.bmp.width; val h = l.bmp.height
                                    val m = ByteArray(w * h)
                                    val x0 = r.left.toInt().coerceIn(0, w); val x1 = r.right.toInt().coerceIn(0, w)
                                    val y0 = r.top.toInt().coerceIn(0, h); val y1 = r.bottom.toInt().coerceIn(0, h)
                                    for (y in y0 until y1) for (x in x0 until x1) m[y * w + x] = 1
                                    d.setMask(l, m)
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            Tool.Lasso -> awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val l = d.sel() ?: return@awaitEachGesture
                                val inv = Matrix(); if (!l.matrix().invert(inv)) return@awaitEachGesture
                                val pts = mutableListOf(down.position)
                                do {
                                    val ev = awaitPointerEvent(); ev.changes.forEach { pts.add(it.position); it.consume() }
                                    lassoPath = pts.toList()
                                } while (ev.changes.any { it.pressed })
                                lassoPath = emptyList()
                                if (pts.size < 3) return@awaitEachGesture
                                val w = l.bmp.width; val h = l.bmp.height
                                val path = android.graphics.Path(); val first = floatArrayOf(pts[0].x / vs(), pts[0].y / vs()); inv.mapPoints(first); path.moveTo(first[0], first[1])
                                pts.drop(1).forEach { o -> val q = floatArrayOf(o.x / vs(), o.y / vs()); inv.mapPoints(q); path.lineTo(q[0], q[1]) }
                                path.close()
                                val mb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
                                AndroidCanvas(mb).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = android.graphics.Color.BLACK })
                                val buf = java.nio.ByteBuffer.allocate(w * h); mb.copyPixelsToBuffer(buf); mb.recycle()
                                val raw = buf.array(); d.setMask(l, ByteArray(w * h) { if ((raw[it].toInt() and 255) > 127) 1 else 0 })
                            }
                            Tool.Bucket -> detectTapGestures { o ->
                                val l = d.sel() ?: return@detectTapGestures
                                val inv = Matrix(); if (!l.matrix().invert(inv)) return@detectTapGestures
                                val pt = floatArrayOf(o.x / vs(), o.y / vs()); inv.mapPoints(pt)
                                val x = pt[0].toInt(); val y = pt[1].toInt()
                                if (x !in 0 until l.bmp.width || y !in 0 until l.bmp.height) return@detectTapGestures
                                d.snapshot(l)
                                val m = floodMask(l.bmp, x, y, tol.toInt()); val w = l.bmp.width; val h = l.bmp.height
                                val px = IntArray(w * h); l.bmp.getPixels(px, 0, w, 0, 0, w, h)
                                val fill = (color and 0x00FFFFFF) or ((brushAlpha * 255).toInt() shl 24)
                                for (i in px.indices) if (m[i].toInt() != 0) px[i] = fill
                                l.bmp.setPixels(px, 0, w, 0, 0, w, h); d.touch()
                            }
                            Tool.Pick -> detectTapGestures { o ->
                                val flat = d.render(); val x = (o.x / vs()).toInt().coerceIn(0, d.w - 1); val y = (o.y / vs()).toInt().coerceIn(0, d.h - 1)
                                val c = flat.getPixel(x, y); flat.recycle()
                                if ((c ushr 24) > 0) { color = c or (0xFF shl 24); toastRef.value("Picked that colour") } else toastRef.value("That spot is see-through")
                            }
                            Tool.Shape -> awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val l = d.sel() ?: return@awaitEachGesture
                                val inv = Matrix(); if (!l.matrix().invert(inv)) return@awaitEachGesture
                                fun local(o: Offset): FloatArray { val pt = floatArrayOf(o.x / vs(), o.y / vs()); inv.mapPoints(pt); return pt }
                                val a = local(down.position); var b = a
                                var last = down.position
                                do { val ev = awaitPointerEvent(); last = ev.changes.first().position; ev.changes.forEach { it.consume() } } while (ev.changes.any { it.pressed })
                                b = local(last)
                                d.snapshot(l)
                                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = (brushAlpha * 255).toInt(); strokeWidth = brush / l.scale / 4f; strokeCap = Paint.Cap.ROUND
                                    style = if (filled && shape != "Line") Paint.Style.FILL else Paint.Style.STROKE }
                                val cv = AndroidCanvas(l.bmp)
                                val r = RectF(min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1]))
                                when (shape) { "Rectangle" -> cv.drawRect(r, paint); "Circle" -> cv.drawOval(r, paint); else -> cv.drawLine(a[0], a[1], b[0], b[1], paint) }
                                d.touch()
                            }
                            Tool.Adjust -> {}
                        }
                    }) {
                    d.version
                    val s = size.width / d.w
                    // transparency checkerboard
                    val sq = 24f
                    var yy = 0f; var row = 0
                    while (yy < size.height) { var xx = 0f; var col = row % 2; while (xx < size.width) {
                        drawRect(if (col % 2 == 0) Color(0xFFCFD3DA) else Color(0xFFF4F5F7), Offset(xx, yy), androidx.compose.ui.geometry.Size(min(sq, size.width - xx), min(sq, size.height - yy))); xx += sq; col++ }
                        yy += sq; row++ }
                    drawIntoCanvas { cv ->
                        val nc = cv.nativeCanvas
                        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                        nc.saveLayer(null, null); nc.scale(s, s)
                        d.layers.forEach { l ->
                            if (!l.visible) return@forEach
                            paint.alpha = (l.alpha * 255).toInt(); paint.blendMode = BLENDS[l.blend].second
                            nc.drawBitmap(l.bmp, l.matrix(), paint)
                            if (l.id == d.maskLayer) d.maskPreview?.let { nc.drawBitmap(it, l.matrix(), null) }
                        }
                        nc.restore()
                    }
                    if (lassoPath.size > 1) { val pth = androidx.compose.ui.graphics.Path(); pth.moveTo(lassoPath[0].x, lassoPath[0].y); lassoPath.drop(1).forEach { pth.lineTo(it.x, it.y) }
                        drawPath(pth, Color(0xFF2F8CFF), style = androidx.compose.ui.graphics.drawscope.Stroke(4f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))) }
                }
            }
            // ---- tools
            CompositionLocalProvider(LocalContentColor provides p.text) {
            Column(Modifier.heightIn(max = 330.dp).background(p.panel).verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tool.entries.forEach { t -> Chip(t.label, tool == t, { tool = t }) }
                }
                when (tool) {
                    Tool.Brush, Tool.Eraser -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Size", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
                            Slider(brush, { brush = it }, Modifier.weight(1f), valueRange = 4f..160f)
                            Text("${brush.toInt()}", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        if (tool == Tool.Brush) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0xFFE5483C, 0xFFF2A516, 0xFFFFE14D, 0xFF2E9E44, 0xFF1E7FE8, 0xFF8E44AD, 0xFFFFFFFF, 0xFF1B1D21).forEach { c ->
                                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (color == c.toInt()) 3.dp else 1.dp, if (color == c.toInt()) p.accent else p.panelBorder, CircleShape).clickable { color = c.toInt() })
                            }
                        } else Text("Rubs the selected layer out to see-through.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    }
                    Tool.Wand -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reach", Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge)
                            Slider(tol, { tol = it }, Modifier.weight(1f), valueRange = 2f..120f)
                            Text("${tol.toInt()}", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        Text("Tap a colour to select everything like it. Raise Reach to take in similar shades.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    }
                    Tool.Lasso -> Text("Draw around what you want. Lift your finger to close the loop and select it.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    Tool.Bucket, Tool.Shape -> {
                        if (tool == Tool.Bucket) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reach", Modifier.width(56.dp), style = MaterialTheme.typography.labelLarge); Slider(tol, { tol = it }, Modifier.weight(1f), valueRange = 2f..120f); Text("${tol.toInt()}", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall) }
                        else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Rectangle", "Circle", "Line").forEach { n -> Chip(n, shape == n, { shape = n }) }; if (shape != "Line") Chip(if (filled) "Filled" else "Outline", filled, { filled = !filled }) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Line", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge); Slider(brush, { brush = it }, Modifier.weight(1f), valueRange = 4f..160f) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Opacity", Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge); Slider(brushAlpha, { brushAlpha = it }, Modifier.weight(1f), valueRange = .05f..1f) }
                        ColorRow(color, { color = it })
                        Text(if (tool == Tool.Bucket) "Tap to fill the area of similar colour." else "Drag on the layer to draw the shape.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    }
                    Tool.Pick -> { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(34.dp).clip(CircleShape).background(Color(color)).border(2.dp, p.panelBorder, CircleShape)); Spacer(Modifier.width(10.dp)); Text("Tap anywhere on the picture to use its colour for the brush, bucket, shapes and text.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText) } }
                    Tool.Adjust -> {
                        val sel = d.sel()
                        Text(if (d.mask != null) "Changes only the selected part of the layer." else "Changes the whole selected layer.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Brightness", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge); Slider(aBright, { aBright = it }, Modifier.weight(1f), valueRange = -.5f..0.5f) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Contrast", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge); Slider(aContrast, { aContrast = it }, Modifier.weight(1f), valueRange = -.6f..1f) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Colour", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge); Slider(aSat, { aSat = it }, Modifier.weight(1f), valueRange = -1f..1f) }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Warmth", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge); Slider(aWarm, { aWarm = it }, Modifier.weight(1f), valueRange = -1f..1f) }
                        fun applyTo(l: Layer, f: (Bitmap) -> Bitmap) {
                            d.snapshot(l)
                            val w = l.bmp.width; val h = l.bmp.height
                            val out = f(l.bmp); val m = if (d.maskLayer == l.id) d.mask else null
                            val a = IntArray(w * h); out.getPixels(a, 0, w, 0, 0, w, h)
                            if (m != null) { val o = IntArray(w * h); l.bmp.getPixels(o, 0, w, 0, 0, w, h); for (i in a.indices) if (m[i].toInt() == 0) a[i] = o[i] }
                            l.bmp.setPixels(a, 0, w, 0, 0, w, h); if (out !== l.bmp) out.recycle(); d.touch()
                        }
                        fun colourFx(l: Layer, cm: android.graphics.ColorMatrix) = applyTo(l) { src -> Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { o -> AndroidCanvas(o).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(cm) }) } }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WButton("Apply", { sel?.let { colourFx(it, adjustMatrix(aBright, aContrast, aSat, aWarm)); aBright = 0f; aContrast = 0f; aSat = 0f; aWarm = 0f } }, kind = ButtonKind.Primary, icon = WonderIcons.Check, enabled = sel != null)
                            WButton("Black and white", { sel?.let { colourFx(it, android.graphics.ColorMatrix().apply { setSaturation(0f) }) } }, enabled = sel != null)
                            WButton("Sepia", { sel?.let { colourFx(it, android.graphics.ColorMatrix().apply { setSaturation(0f); postConcat(android.graphics.ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, 20f, 0f, .95f, 0f, 0f, 8f, 0f, 0f, .75f, 0f, -10f, 0f, 0f, 0f, 1f, 0f))) }) } }, enabled = sel != null)
                            WButton("Invert colours", { sel?.let { colourFx(it, android.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))) } }, enabled = sel != null)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Blur", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge); Slider(aBlur, { aBlur = it }, Modifier.weight(1f), valueRange = 1f..40f); Text("${aBlur.toInt()}", Modifier.width(32.dp), style = MaterialTheme.typography.labelSmall) }
                        WButton(if (working != null) "Blurring…" else "Blur", { sel?.let { l -> scope.launch { working = "Blurring"; val r = aBlur.toInt()
                            val out = withContext(Dispatchers.Default) { blurBitmap(l.bmp, r) }; d.snapshot(l)
                            val w = l.bmp.width; val h = l.bmp.height; val a = IntArray(w * h); out.getPixels(a, 0, w, 0, 0, w, h); val m = if (d.maskLayer == l.id) d.mask else null
                            if (m != null) { val o = IntArray(w * h); l.bmp.getPixels(o, 0, w, 0, 0, w, h); for (i in a.indices) if (m[i].toInt() == 0) a[i] = o[i] }
                            l.bmp.setPixels(a, 0, w, 0, 0, w, h); out.recycle(); d.touch(); working = null } } }, enabled = sel != null && working == null)
                        Text("Blend with the layers under it", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { BLENDS.forEachIndexed { i, (n, _) -> Chip(n, sel?.blend == i, { sel?.let { d.snapshot(); it.blend = i; d.touch() } }) } }
                    }
                    Tool.Box -> Text("Drag over the layer to select a rectangle.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    Tool.Move -> Text("Drag to move the selected layer. Pinch to resize, twist to turn.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                }
                val l = d.sel()
                if (d.mask != null && l != null && l.id == d.maskLayer) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WButton("Delete selection", {
                            val m = d.mask ?: return@WButton
                            d.snapshot(l)
                            val w = l.bmp.width; val h = l.bmp.height
                            val px = IntArray(w * h); l.bmp.getPixels(px, 0, w, 0, 0, w, h)
                            for (i in px.indices) if (m[i].toInt() != 0) px[i] = 0
                            l.bmp.setPixels(px, 0, w, 0, 0, w, h); d.clearMask(); d.touch()
                        }, icon = WonderIcons.Delete, kind = ButtonKind.Danger)
                        WButton("Cut out to a new layer", {
                            val m = d.mask ?: return@WButton
                            d.snapshot(l)
                            val w = l.bmp.width; val h = l.bmp.height
                            val px = IntArray(w * h); l.bmp.getPixels(px, 0, w, 0, 0, w, h)
                            val cut = IntArray(w * h)
                            for (i in px.indices) if (m[i].toInt() != 0) { cut[i] = px[i]; px[i] = 0 }
                            l.bmp.setPixels(px, 0, w, 0, 0, w, h)
                            val nb = Bitmap.createBitmap(cut, w, h, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
                            val n = d.add(l.name + " cut-out", nb, d.layers.indexOf(l) + 1)
                            n.cx = l.cx; n.cy = l.cy; n.scale = l.scale; n.rot = l.rot
                            d.clearMask(); d.touch()
                        }, icon = WonderIcons.ContentCut)
                        WButton("Keep only selection", {
                            val m = d.mask ?: return@WButton
                            d.snapshot(l)
                            val w = l.bmp.width; val h = l.bmp.height
                            val px = IntArray(w * h); l.bmp.getPixels(px, 0, w, 0, 0, w, h)
                            for (i in px.indices) if (m[i].toInt() == 0) px[i] = 0
                            l.bmp.setPixels(px, 0, w, 0, 0, w, h); d.clearMask(); d.touch()
                        }, icon = WonderIcons.Check)
                        WButton("Invert", { d.mask?.let { m -> d.setMask(l, ByteArray(m.size) { if (m[it].toInt() == 0) 1 else 0 }) } }, icon = WonderIcons.SwapHoriz)
                        WButton("Grow", { d.mask?.let { m -> d.setMask(l, resizeMask(m, l.bmp.width, l.bmp.height, 3)) } }, kind = ButtonKind.Secondary)
                        WButton("Shrink", { d.mask?.let { m -> d.setMask(l, resizeMask(m, l.bmp.width, l.bmp.height, -3)) } }, kind = ButtonKind.Secondary)
                        WButton("Fill with colour", {
                            val m = d.mask ?: return@WButton
                            d.snapshot(l)
                            val w = l.bmp.width; val h = l.bmp.height
                            val px = IntArray(w * h); l.bmp.getPixels(px, 0, w, 0, 0, w, h)
                            for (i in px.indices) if (m[i].toInt() != 0) px[i] = color
                            l.bmp.setPixels(px, 0, w, 0, 0, w, h); d.clearMask(); d.touch()
                        }, icon = WonderIcons.Edit)
                        WButton("Deselect", { d.clearMask(); d.touch() }, kind = ButtonKind.Plain)
                    }
                }
                // ---- layers
                Text("Layers", style = MaterialTheme.typography.titleSmall)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(d.layers.reversed(), key = { _, it -> it.id }) { _, layer ->
                        val on = layer.id == d.selected
                        Column(Modifier.width(76.dp).clickable { d.selected = layer.id; d.clearMask(); d.touch() }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFD8DCE2)).border(if (on) 3.dp else 1.dp, if (on) p.accent else p.panelBorder, RoundedCornerShape(8.dp))) {
                                key(ver) { Image(layer.bmp.asImageBitmap(), layer.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = if (layer.visible) 1f else .3f) }
                            }
                            Text(layer.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = if (on) p.accent else p.text)
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WButton("Add photo", { addPhoto.launch(arrayOf("image/*")) }, icon = WonderIcons.Image)
                    WButton("Add text", { textAsk = true }, icon = WonderIcons.Edit)
                    WButton("New layer", { d.snapshot(); d.add("Layer ${d.nextId}", Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888)) }, icon = WonderIcons.Layers)
                    if (l != null) {
                        WButton(if (l.visible) "Hide" else "Show", { d.snapshot(); l.visible = !l.visible; d.touch() }, icon = WonderIcons.Visibility)
                        WButton("Duplicate", { d.snapshot(); val n = d.add(l.name + " copy", l.bmp.copy(Bitmap.Config.ARGB_8888, true), d.layers.indexOf(l) + 1); n.cx = l.cx + 30; n.cy = l.cy + 30; n.scale = l.scale; n.rot = l.rot; n.alpha = l.alpha }, icon = WonderIcons.ContentCopy)
                        WButton("Up", { val i = d.layers.indexOf(l); if (i < d.layers.size - 1) { d.snapshot(); d.layers.removeAt(i); d.layers.add(i + 1, l); d.touch() } }, icon = WonderIcons.ArrowUpward)
                        WButton("Down", { val i = d.layers.indexOf(l); if (i > 0) { d.snapshot(); d.layers.removeAt(i); d.layers.add(i - 1, l); d.touch() } }, icon = WonderIcons.ArrowDownward)
                        WButton("Merge down", { val i = d.layers.indexOf(l); if (i > 0) { val below = d.layers[i - 1]; d.snapshot(below)
                            val flat = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888); val cv = AndroidCanvas(flat); val pp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                            pp.alpha = (below.alpha * 255).toInt(); pp.blendMode = BLENDS[below.blend].second; cv.drawBitmap(below.bmp, below.matrix(), pp)
                            pp.alpha = (l.alpha * 255).toInt(); pp.blendMode = BLENDS[l.blend].second; cv.drawBitmap(l.bmp, l.matrix(), pp)
                            val n = Layer(below.id, below.name, flat, d.w / 2f, d.h / 2f); d.layers.removeAt(i); d.layers[i - 1] = n; d.selected = n.id; d.clearMask(); d.touch() } else toastRef.value("There is no layer under this one") }, icon = WonderIcons.Layers)
                        WButton("Flip", { d.snapshot(l); val m = Matrix().apply { postScale(-1f, 1f) }; l.bmp = Bitmap.createBitmap(l.bmp, 0, 0, l.bmp.width, l.bmp.height, m, true).copy(Bitmap.Config.ARGB_8888, true); d.clearMask(); d.touch() }, icon = WonderIcons.SwapHoriz)
                        WButton("Delete layer", { if (d.layers.size > 1) { d.snapshot(); d.layers.remove(l); d.selected = d.layers.last().id; d.clearMask(); d.touch() } else toastRef.value("Keep at least one layer") }, icon = WonderIcons.Delete, kind = ButtonKind.Danger)
                    }
                }
                if (l != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Opacity", Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                    Slider(l.alpha, { l.alpha = it; d.touch() }, Modifier.weight(1f), valueRange = 0f..1f, onValueChangeFinished = { })
                    Text("${(l.alpha * 100).toInt()}%", Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Save as", style = MaterialTheme.typography.labelLarge)
                    Chip("PNG (keeps see-through)", png, { png = true; context.getSharedPreferences("memories", android.content.Context.MODE_PRIVATE).edit().putBoolean("studioPng", true).apply() })
                    Chip("JPEG (smaller)", !png, { png = false; context.getSharedPreferences("memories", android.content.Context.MODE_PRIVATE).edit().putBoolean("studioPng", false).apply() })
                }
            }
            }
        }
    }
}

/** A row of swatches plus a hue strip for any colour. */
@Composable
private fun ColorRow(color: Int, onPick: (Int) -> Unit) {
    val p = wonderPalette()
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(0xFFE5483C, 0xFFF2A516, 0xFFFFE14D, 0xFF2E9E44, 0xFF1E7FE8, 0xFF8E44AD, 0xFFFFFFFF, 0xFF8A8F98, 0xFF1B1D21).forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (color == c.toInt()) 3.dp else 1.dp, if (color == c.toInt()) p.accent else p.panelBorder, CircleShape).clickable { onPick(c.toInt()) })
        }
        listOf(0f, 30f, 60f, 120f, 180f, 210f, 240f, 270f, 300f, 330f).forEach { hue ->
            val c = android.graphics.Color.HSVToColor(floatArrayOf(hue, .85f, .95f))
            Box(Modifier.size(24.dp).clip(CircleShape).background(Color(c)).clickable { onPick(c) })
        }
    }
}
