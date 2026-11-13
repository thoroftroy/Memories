package dev.wonderdesk.memories

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*
import java.time.LocalDate

/** A photo frame on the home screen: On this day, favourites, recent shots or one album, changing on your schedule. */
class PhotoWidget : AppWidgetProvider() {
    companion object {
        const val NEXT = "dev.wonderdesk.memories.WIDGET_NEXT"
        val SOURCES = listOf("memories" to "On this day (then favourites)", "favorites" to "Favourites", "recent" to "Recent photos", "album" to "One album")
        val EVERY = listOf(30 to "Every 30 minutes", 60 to "Every hour", 180 to "Every 3 hours", 1440 to "Once a day")

        private fun pool(c: Context, s: WidgetPrefs): List<Photo> {
            val all = Gallery.all(c).filter { !it.video }
            val fav = all.filter { it.favorite }
            return when (s.str("source", "memories")) {
                "favorites" -> fav
                "recent" -> all.take(60)
                "album" -> all.filter { it.album == s.str("album", "Camera") }
                else -> { val t = LocalDate.now(); all.filter { it.day.year < t.year && it.day.monthValue == t.monthValue && it.day.dayOfMonth == t.dayOfMonth }.ifEmpty { fav } }
            }.ifEmpty { all.take(60) }
        }

        fun build(c: Context, id: Int): RemoteViews {
            val s = WidgetPrefs(c, id)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "CLEAR")) }.getOrDefault(WidgetLook.CLEAR)
            val v = RemoteViews(c.packageName, R.layout.widget_photo)
            v.setInt(R.id.w_root, "setBackgroundResource", when (look) { WidgetLook.GLASS -> R.drawable.wd_widget_bg; WidgetLook.DARK -> R.drawable.wd_widget_bg_dark; WidgetLook.CLEAR -> R.drawable.wd_widget_bg_clear })
            val list = runCatching { pool(c, s) }.getOrDefault(emptyList())
            if (list.isEmpty()) {
                v.setTextViewText(R.id.w_title, "Memories"); v.setTextViewText(R.id.w_text, "Your photos will appear here")
                v.setOnClickPendingIntent(R.id.w_root, widgetOpen(c, MainActivity::class.java, 9100 + id % 100)); return v
            }
            val every = s.int("every", 60).coerceAtLeast(30) * 60_000L
            val step = s.int("step", 0)
            val ph = list[((System.currentTimeMillis() / every + step) % list.size).toInt()]
            runCatching { c.contentResolver.loadThumbnail(ph.uri, android.util.Size(640, 640), null) }.getOrNull()?.let { v.setImageViewBitmap(R.id.w_img, it) }
            val caption = s.bool("caption", true)
            v.setViewVisibility(R.id.w_caption, if (caption) View.VISIBLE else View.GONE)
            val years = LocalDate.now().year - ph.day.year
            v.setTextViewText(R.id.w_title, if (years > 0 && ph.day.dayOfYear == LocalDate.now().dayOfYear) (if (years == 1) "1 year ago" else "$years years ago") else ph.album)
            v.setTextViewText(R.id.w_text, ph.day.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy")))
            val tap = if (s.str("tap", "open") == "next") widgetBroadcast(c, PhotoWidget::class.java, 9200 + id % 100, NEXT) { it.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) }
                else widgetOpen(c, MainActivity::class.java, 9300 + id % 100) { it.setAction(Intent.ACTION_VIEW).setDataAndType(ph.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            v.setOnClickPendingIntent(R.id.w_root, tap)
            return v
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val r = goAsync()
        Thread { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } }; r.finish() }.start()
    }
    override fun onReceive(c: Context, i: Intent) {
        super.onReceive(c, i)
        if (i.action == NEXT) {
            val id = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1); if (id < 0) return
            val p = WidgetPrefs(c, id); p.put("step" to p.int("step", 0) + 1)
            val r = goAsync(); Thread { runCatching { AppWidgetManager.getInstance(c).updateAppWidget(id, build(c, id)) }; r.finish() }.start()
        }
    }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class PhotoWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { val m = AppWidgetManager.getInstance(this); Thread { runCatching { m.updateAppWidget(widgetId, PhotoWidget.build(this, widgetId)) } }.start() }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var source by remember { mutableStateOf(s.str("source", "memories")) }
        var album by remember { mutableStateOf(s.str("album", "Camera")) }
        var every by remember { mutableIntStateOf(s.int("every", 60)) }
        var caption by remember { mutableStateOf(s.bool("caption", true)) }
        var tap by remember { mutableStateOf(s.str("tap", "open")) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "CLEAR")) }.getOrDefault(WidgetLook.CLEAR)) }
        var albums by remember { mutableStateOf<List<String>>(emptyList()) }
        LaunchedEffect(Unit) { albums = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Gallery.all(c).map { it.album }.distinct().sorted() }.getOrDefault(emptyList()) } }
        WidgetSetup("Memories", "Photo frame", onCancel = { finish() }, onSave = { s.put("source" to source, "album" to album, "every" to every, "caption" to caption, "tap" to tap, "look" to look.name); save() }) {
            Panel(title = "Photos from") { PhotoWidget.SOURCES.forEachIndexed { i, (k, l) -> if (i > 0) PanelDivider(); ListRow(l, selected = source == k, onClick = { source = k }) } }
            if (source == "album") Panel(title = "Album") { albums.forEachIndexed { i, a -> if (i > 0) PanelDivider(); ListRow(a, selected = album == a, onClick = { album = a }) } }
            Panel(title = "Change the photo") { PhotoWidget.EVERY.forEachIndexed { i, (m, l) -> if (i > 0) PanelDivider(); ListRow(l, selected = every == m, onClick = { every = m }) } }
            Panel(title = "Tapping the photo") {
                ListRow("Opens it in Memories", selected = tap == "open", onClick = { tap = "open" }); PanelDivider()
                ListRow("Shows the next photo", selected = tap == "next", onClick = { tap = "next" })
            }
            Panel { SwitchRow("Caption with album and date", caption, { caption = it }, icon = WonderIcons.Label) }
            WidgetLookPicker(look) { look = it }
        }
    }
}
