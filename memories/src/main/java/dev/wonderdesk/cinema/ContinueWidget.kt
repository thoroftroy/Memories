package dev.wonderdesk.cinema

import dev.wonderdesk.memories.R

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/** Continue watching: the video you were last part-way through (or your newest one), with how far you got. */
class ContinueWidget : AppWidgetProvider() {
    companion object {
        val IDS = CardIds(R.layout.widget_card, R.layout.widget_card_small, R.id.w_root, R.id.w_img, R.id.w_label, R.id.w_title, R.id.w_text, R.id.w_bar, R.id.w_btn,
            Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear))
        fun build(c: Context, id: Int): android.widget.RemoteViews {
            val s = WidgetPrefs(c, id)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            val resume = Resume.recent(c).firstOrNull()
            val newest = if (resume == null && s.bool("newest", true)) runCatching { Videos.all(c).firstOrNull() }.getOrNull() else null
            val uri = resume?.first ?: newest?.uri
            val thumb = if (uri != null && s.bool("thumb", true)) runCatching { c.contentResolver.loadThumbnail(uri, android.util.Size(320, 320), null) }.getOrNull() else null
            val play = uri?.let { u -> widgetOpen(c, MainActivity::class.java, 8100 + id % 100) { it.setAction(Intent.ACTION_VIEW).setData(u) } }
            val card = when {
                resume != null -> Card("Continue watching", c.displayName(resume.first).substringBeforeLast('.'), "Stopped at " + timeLabel(resume.second) + " · " + relativeTime(resume.third),
                    progress = (Resume.progress(c, resume.first) * 100).toInt(), button = "Resume", image = thumb, open = play, action = play)
                newest != null -> Card("Newest video", newest.title.substringBeforeLast('.'), timeLabel(newest.duration), button = "Play", image = thumb, open = play, action = play)
                else -> Card("Memories", "No videos yet", "Videos you record or save show up here", open = widgetOpen(c, MainActivity::class.java, 8100 + id % 100))
            }
            return cardWidget(c, IDS, look, card)
        }
        private fun timeLabel(ms: Long): String { val t = ms / 1000; return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val r = goAsync()
        Thread { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } }; r.finish() }.start()
    }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class ContinueWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { val m = AppWidgetManager.getInstance(this); Thread { runCatching { m.updateAppWidget(widgetId, ContinueWidget.build(this, widgetId)) } }.start() }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        var thumb by remember { mutableStateOf(s.bool("thumb", true)) }
        var newest by remember { mutableStateOf(s.bool("newest", true)) }
        WidgetSetup("Memories", "Continue watching", onCancel = { finish() }, onSave = { s.put("look" to look.name, "thumb" to thumb, "newest" to newest); save() }) {
            Panel(title = "Show") {
                SwitchRow("Picture from the video", thumb, { thumb = it }, icon = WonderIcons.Image)
                PanelDivider()
                SwitchRow("Newest video when nothing is half-watched", newest, { newest = it }, icon = WonderIcons.Movie)
            }
            WidgetLookPicker(look) { look = it }
        }
    }
}
