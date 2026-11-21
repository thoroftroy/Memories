@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.wonderdesk.cinema

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : WonderActivity() {
    @Composable override fun Content() = CinemaApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (playing.value && autoPip.value) enterPip(this) }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig); pip.value = isInPictureInPictureMode
    }
    companion object {
        val incoming = mutableStateOf<Intent?>(null)
        val pip = mutableStateOf(false)
        val playing = mutableStateOf(false)
        val autoPip = mutableStateOf(true)
        /** A tab the recording notification asks to open. */
        val wantTab = mutableStateOf<String?>(null)
        fun enterPip(a: Activity) { runCatching { a.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) } }
    }
}

/** What Android must allow for this app to work fully; shown as a banner when off and in Settings. */
private val APP_NEEDS by lazy { listOf(
    WonderNeed("Videos", "Finds every video on your phone", permissions = if (android.os.Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.READ_MEDIA_VIDEO) else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)),
) }

private const val ABOUT = "Memories plays every video on your phone: resume where you left off, swipe to seek or change brightness and volume, pick audio and subtitle tracks, " +
    "load your own subtitles, change speed, and keep watching in picture-in-picture. Its editor trims clips, removes sound, rotates, shrinks for sharing and saves still frames."

private fun Context.cInt(k: String, d: Int) = prefs("cinema").getInt(k, d)
private fun Context.cBool(k: String, d: Boolean) = prefs("cinema").getBoolean(k, d)

private fun timeText(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

@Composable
fun CinemaApp() {
    val context = LocalContext.current
    var playingItem by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var editing by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val scope = rememberCoroutineScope()
    val incoming by MainActivity.incoming
    LaunchedEffect(incoming) {
        val i = incoming ?: (context as ComponentActivity).intent
        MainActivity.incoming.value = null; (context as ComponentActivity).intent = Intent()
        i?.getStringExtra("tab")?.let { MainActivity.wantTab.value = it }
        val u = i?.data ?: return@LaunchedEffect
        if (i.action == Intent.ACTION_EDIT || i.action == "dev.wonderdesk.memories.EDIT") editing = u to context.displayName(u) else playingItem = u to context.displayName(u)
    }
    val p = playingItem; val e = editing
    when {
        e != null -> EditorScreen(e.first, e.second, onClose = { editing = null; (context as? android.app.Activity)?.finish() })
        p != null -> key(p.first) {
            // Next or previous video in the same folder, by name: episodes play in order.
            fun step(delta: Int, wholeLibrary: Boolean = false) {
                scope.launch {
                    val next = withContext(Dispatchers.IO) { runCatching {
                        val all = Videos.all(context); val cur = all.find { it.uri == p.first } ?: return@runCatching null
                        (if (wholeLibrary) all else all.filter { it.path == cur.path }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })).let { l -> l.getOrNull(l.indexOf(cur) + delta) }
                    }.getOrNull() }
                    if (next != null && playingItem?.first == p.first) playingItem = next.uri to next.title
                    else if (delta != 0) android.widget.Toast.makeText(context, if (delta > 0) "That is the last video" else "That is the first video", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            PlayerScreen(p.first, p.second, onClose = { playingItem = null; (context as? android.app.Activity)?.finish() }, onEdit = { editing = p; playingItem = null }, onEnded = { step(1) }, onStep = { step(it, wholeLibrary = true) }, onDeleted = { playingItem = null; (context as? android.app.Activity)?.finish() })
        }
        else -> StandaloneLibrary(onPlay = { playingItem = it.uri to it.title }, onEdit = { editing = it.uri to it.title })
    }
}

/* ------------------------------------------------------- library -------------------------------------------- */

private val VIDEO_PERMISSION = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun CinemaLibrary(section: String, onPlay: (Video) -> Unit, onEdit: (Video) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, VIDEO_PERMISSION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> onPlay(Video(-1, u, context.displayName(u), 0, 0, 0, 0, "", 0, "")) } }
    val bus = CinemaBus
    LaunchedEffect(bus.pick.intValue) { if (bus.pick.intValue > 0) pick.launch(arrayOf("video/*")) }
    var streamAsk by remember { mutableStateOf(false) }
    // Network streams: any http(s), HLS (.m3u8), DASH (.mpd) or RTSP link, like VLC's "Open network stream".
    if (streamAsk) InputDialog("Open a network stream", "https://… or rtsp://…", initial = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { it.startsWith("http") || it.startsWith("rtsp") }.orEmpty(),
        confirm = "Play", onDismiss = { streamAsk = false }) { url ->
        val u = android.net.Uri.parse(url.trim()); streamAsk = false
        if (u.scheme in setOf("http", "https", "rtsp")) onPlay(Video(-1, u, u.lastPathSegment ?: u.host ?: "Stream", 0, 0, 0, 0, "", 0, ""))
    }
    var videos by remember { mutableStateOf<List<Video>?>(null) }
    var sub by rememberSaveable { mutableStateOf("all") }
    val tab = if (section == "record") "record" else sub
    val wantTab by MainActivity.wantTab
    LaunchedEffect(wantTab) { wantTab?.let { sub = if (it == "folders") "folders" else "all"; MainActivity.wantTab.value = null } }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(bus.stream.intValue) { if (bus.stream.intValue > 0) streamAsk = true }
    LaunchedEffect(bus.refresh.intValue) { if (bus.refresh.intValue > 0) tick++ }
    // Screen recordings and clips saved while this screen is open show up straight away, newest first.
    val savedCount = RecorderState.saved
    LaunchedEffect(savedCount) { if (savedCount > 0) tick++ }
    var askDelete by remember { mutableStateOf<Video?>(null) }
    val deleteAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { tick++ }
    askDelete?.let { v -> ConfirmDialog("Delete this video?", "“${v.title}” is removed from your phone.", "Delete", {
        askDelete = null
        VideoDeleter.delete(context, v.uri, ask = { deleteAsk.launch(androidx.activity.result.IntentSenderRequest.Builder(it).build()) }, done = { tick++ }, fail = { android.widget.Toast.makeText(context, "Couldn't delete it", android.widget.Toast.LENGTH_SHORT).show() })
    }, { askDelete = null }, danger = true) }
    LaunchedEffect(granted, tick) { if (granted) videos = withContext(Dispatchers.IO) { Videos.all(context).filter { it.duration >= context.cInt("minLen", 0) * 1000L } } }
    Column(Modifier.fillMaxSize()) {
    if (section != "record" && folder == null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("All videos", sub == "all", { sub = "all" }); Chip("Folders", sub == "folders", { sub = "folders" })
    }
    if (folder != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { folder = null }) { Text("‹ All folders") }; Text(folder ?: "", style = MaterialTheme.typography.titleSmall) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
    if (tab == "record") { RecorderScreen(); return }
    if (!granted) {
        EmptyState("See all your videos", "Allow Memories to read the videos on this phone to build your library. You can also open single files without it.",
            action = { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WButton("Allow access", { ask.launch(VIDEO_PERMISSION) }, kind = ButtonKind.Primary, icon = WonderIcons.VideoLibrary)
                WButton("Open a file", { pick.launch(arrayOf("video/*")) })
            } })
        return
    }
    val all = videos
    if (all == null) { LoadingPane("Reading your video library", detail = "Looking through every folder on this phone for videos"); return }
    if (all.isEmpty()) { EmptyState("No videos yet", "Videos you record or download appear here automatically.", action = { WButton("Open a file", { pick.launch(arrayOf("video/*")) }) }); return }
    if (tab == "folders" && folder == null) {
        val folders = all.groupBy { it.folder }.toList().sortedByDescending { it.second.maxOf { v -> v.added } }
        LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), state = rememberTabTopGrid(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(folders, key = { it.first }) { (name, list) ->
                Column(Modifier.clickable { folder = name }) {
                    Thumb(list.first(), Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text("${list.size} video${if (list.size == 1) "" else "s"} · ${formatBytes(list.sumOf { it.size })}", style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
                }
            }
        }
        return
    }
    val base = all.filter { (folder == null || it.folder == folder) && (q.isBlank() || it.title.contains(q, true)) }
    val shown = when (sort) { 1 -> base.sortedBy { it.title.lowercase() }; 2 -> base.sortedByDescending { it.duration }; 3 -> base.sortedByDescending { it.size }; else -> base }
    val recent = remember(tick, all) { Resume.recent(context).mapNotNull { (u, _, _) -> all.find { it.uri == u } }.take(10) }
    LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize(), state = rememberTabTopGrid(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(q, { q = it }, "Search ${all.size} videos")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Newest", "Name", "Longest", "Largest").forEachIndexed { i, s -> Chip(s, sort == i, { sort = i }) }
                }
            }
        }
        if (recent.isNotEmpty() && folder == null && q.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    SectionLabel("Continue watching", Modifier.padding(start = 6.dp, bottom = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recent, key = { "r" + it.id }) { v -> Column(Modifier.width(200.dp).clickable { onPlay(v) }) {
                            Thumb(v, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
                            Text(v.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        } }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel(if (folder != null) folder!! else "All videos", Modifier.padding(start = 6.dp)) }
        }
        items(shown, key = { it.id }) { v -> VideoCard(v, onPlay = { onPlay(v) }, onEdit = { onEdit(v) }, onDelete = { askDelete = v }) }
    }

    }
    }
}

/** Cinema's playback, touch, subtitle and editing settings, now part of Memories' Settings. */
@Composable
fun androidx.compose.foundation.layout.ColumnScope.CinemaSettingsPanels() {
    val context = LocalContext.current
            var auto by remember { mutableStateOf(context.prefs("cinema").getBoolean("autoPip", true)) }
            Panel(title = "Playback") {
                SwitchRow("Picture-in-picture when leaving", auto, { auto = it; MainActivity.autoPip.value = it; context.prefs("cinema").edit().putBoolean("autoPip", it).apply() },
                    subtitle = "Keep watching in a small window", icon = WonderIcons.Layers, tint = Color(0xFF1E7FE8), help = "Keeps the video playing in a small window when you leave the app.")
                PanelDivider()
                var next by remember { mutableStateOf(context.prefs("cinema").getBoolean("autoNext", true)) }
                SwitchRow("Play the next video automatically", next, { next = it; context.prefs("cinema").edit().putBoolean("autoNext", it).apply() },
                    subtitle = "When one ends, the next in the same folder starts (great for episodes)", icon = WonderIcons.SkipNext, tint = Color(0xFF2E9E44), help = "When a video ends, the next one in the list starts by itself.")
                PanelDivider()
                var resume by remember { mutableStateOf(context.prefs("cinema").getBoolean("resume", true)) }
                SwitchRow("Resume where you left off", resume, { resume = it; context.prefs("cinema").edit().putBoolean("resume", it).apply() },
                    icon = WonderIcons.History, tint = Color(0xFFE39A12), help = "Starts a video from where you stopped last time.")
                PanelDivider()
                var awake by remember { mutableStateOf(context.cBool("keepAwake", true)) }
                SwitchRow("Keep the screen on while playing", awake, { awake = it; context.prefs("cinema").edit().putBoolean("keepAwake", it).apply() }, icon = WonderIcons.Visibility, tint = Color(0xFF8E44AD), help = "Stops the screen sleeping during a video.")
                PanelDivider()
                var loopD by remember { mutableStateOf(context.cBool("defaultLoop", false)) }
                SwitchRow("Repeat each video by default", loopD, { loopD = it; context.prefs("cinema").edit().putBoolean("defaultLoop", it).apply() }, icon = WonderIcons.Repeat, tint = Color(0xFFE39A12))
                PanelDivider()
                var step by remember { mutableIntStateOf(context.cInt("seekStep", 10)) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Skip button and double-tap jump", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 15, 30, 60).forEach { v -> Chip("$v s", step == v, { step = v; context.prefs("cinema").edit().putInt("seekStep", v).apply() }) } }
                    Text("Applies the next time you open a video.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                }
                PanelDivider()
                var spd by remember { mutableFloatStateOf(context.prefs("cinema").getFloat("defaultSpeed", 1f)) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Starting speed", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { v -> Chip(if (v == 1f) "Normal" else "${v}x", spd == v, { spd = v; context.prefs("cinema").edit().putFloat("defaultSpeed", v).apply() }) } }
                }
                PanelDivider()
                var fit by remember { mutableIntStateOf(context.cInt("defaultFit", 0)) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Picture fit", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Fit", "Fill", "Stretch").forEachIndexed { i, n -> Chip(n, fit == i, { fit = i; context.prefs("cinema").edit().putInt("defaultFit", i).apply() }) } }
                }
                PanelDivider()
                var hide by remember { mutableIntStateOf(context.cInt("hideDelay", 35)) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Hide the controls after", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(20 to "2 s", 35 to "3.5 s", 50 to "5 s", 80 to "8 s", 0 to "Never").forEach { (v, n) -> Chip(n, hide == v, { hide = v; context.prefs("cinema").edit().putInt("hideDelay", v).apply() }) } }
                }
            }
            Panel(title = "Touch controls", footer = "Turn off any gesture you keep triggering by accident.") {
                var al by remember { mutableStateOf(context.cBool("autoLandscape", false)) }
                SwitchRow("Turn sideways for wide videos", al, { al = it; context.prefs("cinema").edit().putBoolean("autoLandscape", it).apply() }, subtitle = "Rotate the screen by itself when a video is wider than it is tall", icon = WonderIcons.RotateRight, tint = Color(0xFF8E44AD))
                PanelDivider()
                var gd by remember { mutableStateOf(context.cBool("gDouble", true)) }
                SwitchRow("Double-tap to skip or pause", gd, { gd = it; context.prefs("cinema").edit().putBoolean("gDouble", it).apply() }, icon = WonderIcons.SkipNext, tint = Color(0xFF2E9E44), help = "Double-tap the sides to jump ahead or back, and the middle to pause.")
                PanelDivider()
                var sv by remember { mutableStateOf(context.cBool("gSwipeVideo", true)) }
                SwitchRow("Swipe sideways to change video", sv, { sv = it; context.prefs("cinema").edit().putBoolean("gSwipeVideo", it).apply() }, subtitle = "Swipe left for the next video in the folder, right for the previous one. Turn off to swipe to seek instead", icon = WonderIcons.SkipNext, tint = Color(0xFF1E7FE8))
                PanelDivider()
                var gs by remember { mutableStateOf(context.cBool("gSeek", true)) }
                SwitchRow("Swipe sideways to seek", gs, { gs = it; context.prefs("cinema").edit().putBoolean("gSeek", it).apply() }, icon = WonderIcons.SwapHoriz, tint = Color(0xFF1E7FE8))
                PanelDivider()
                var gb by remember { mutableStateOf(context.cBool("gBright", true)) }
                SwitchRow("Swipe the left side for brightness", gb, { gb = it; context.prefs("cinema").edit().putBoolean("gBright", it).apply() }, icon = WonderIcons.Visibility, tint = Color(0xFFE39A12))
                PanelDivider()
                var gv by remember { mutableStateOf(context.cBool("gVolume", true)) }
                SwitchRow("Swipe the right side for volume", gv, { gv = it; context.prefs("cinema").edit().putBoolean("gVolume", it).apply() }, icon = WonderIcons.VolumeUp, tint = Color(0xFFE5483C))
                PanelDivider()
                var sens by remember { mutableIntStateOf(context.cInt("seekSens", 120)) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("A full swipe across the screen seeks", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min").forEach { (v, n) -> Chip(n, sens == v, { sens = v; context.prefs("cinema").edit().putInt("seekSens", v).apply() }) } }
                }
            }
            Panel(title = "Subtitles", footer = "Applies the next time you open a video.") {
                var ss by remember { mutableIntStateOf(context.cInt("subSize", 100)) }
                Column(Modifier.padding(16.dp, 10.dp)) { Text("Subtitle size: $ss%", style = MaterialTheme.typography.labelLarge); Slider(ss.toFloat(), { ss = it.toInt() }, valueRange = 60f..250f, onValueChangeFinished = { context.prefs("cinema").edit().putInt("subSize", ss).apply() }) }
                PanelDivider()
                var sb by remember { mutableStateOf(context.cBool("subBox", false)) }
                SwitchRow("Dark box behind subtitles", sb, { sb = it; context.prefs("cinema").edit().putBoolean("subBox", it).apply() }, subtitle = "Off shows white text with an outline", icon = WonderIcons.Translate, tint = Color(0xFF1E7FE8))
            }
            Panel(title = "Editing", footer = "What the editor starts with. You can still change it for each video.") {
                var os by remember { mutableIntStateOf(context.cInt("outSize", 0)) }
                Column(Modifier.padding(16.dp, 10.dp)) { Text("Save videos at", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Original", "1080p", "720p", "480p").forEachIndexed { i, n -> Chip(n, os == i, { os = i; context.prefs("cinema").edit().putInt("outSize", i).apply() }) } } }
            }
            Panel(title = "Library") {
                var minLen by remember { mutableIntStateOf(context.cInt("minLen", 0)) }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Hide videos shorter than", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(0 to "Show all", 5 to "5 s", 15 to "15 s", 60 to "1 min").forEach { (v, n) -> Chip(n, minLen == v, { minLen = v; context.prefs("cinema").edit().putInt("minLen", v).apply() }) } }
                    Text("Pull to refresh the library after changing this.", style = MaterialTheme.typography.bodySmall, color = wonderPalette().secondaryText)
                }
            }
}

@Composable
private fun Thumb(v: Video, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(v.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(v.id) { bmp = withContext(Dispatchers.IO) { Videos.thumb(context, v) } }
    val progress = remember(v.uri) { Resume.progress(context, v.uri) }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1B1D21))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (v.duration > 0) Text(timeText(v.duration), Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = .7f)).padding(horizontal = 5.dp, vertical = 1.dp),
            color = Color.White, style = MaterialTheme.typography.labelSmall)
        if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .3f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Color(0xFFE5483C))) }
    }
}

@Composable
private fun VideoCard(v: Video, onPlay: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clickable(onClick = onPlay)) {
        Thumb(v, Modifier.fillMaxWidth().aspectRatio(16 / 9f))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(v.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(if (v.height > 0) "${minOf(v.width, v.height)}p" else null, formatBytes(v.size), relativeTime(v.added)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
            }
            Box {
                Icon(WonderIcons.MoreVert, "Options", Modifier.size(20.dp).clickable { menu = true }, tint = p.secondaryText)
                DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
                    DropdownMenuItem({ Text("Play") }, { menu = false; onPlay() }, leadingIcon = { Icon(WonderIcons.PlayArrow, null) })
                    DropdownMenuItem({ Text("Edit or trim") }, { menu = false; onEdit() }, leadingIcon = { Icon(WonderIcons.ContentCut, null) })
                    DropdownMenuItem({ Text("Share") }, { menu = false; context.share(v.uri, "video/*") {} }, leadingIcon = { Icon(WonderIcons.Share, null) })
                    DropdownMenuItem({ Text("Delete", color = p.danger) }, { menu = false; onDelete() }, leadingIcon = { Icon(WonderIcons.Delete, null, tint = p.danger) })
                    DropdownMenuItem({ Text("Show in Wayfinder") }, { menu = false
                        val path = "/storage/emulated/0/" + v.path + v.title
                        context.launchSafely(Intent("dev.wonderdesk.wayfinder.SHOW").setPackage("dev.wonderdesk.wayfinder").putExtra("path", path)) {} }, leadingIcon = { Icon(WonderIcons.FolderOpen, null) })
                }
            }
        }
    }
}

/* -------------------------------------------------------- player -------------------------------------------- */

@Composable
private fun PlayerScreen(uri: Uri, title: String, onClose: () -> Unit, onEdit: () -> Unit, onEnded: () -> Unit = {}, onStep: (Int) -> Unit = {}, onDeleted: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as Activity
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var subtitle by remember { mutableStateOf<Uri?>(null) }
    val player = remember {
        Streaming.builder(context, uri).setSeekBackIncrementMs(context.cInt("seekStep", 10) * 1000L).setSeekForwardIncrementMs(context.cInt("seekStep", 10) * 1000L).build().apply {
            setPlaybackSpeed(context.prefs("cinema").getFloat("defaultSpeed", 1f)); repeatMode = if (context.cBool("defaultLoop", false)) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            setMediaItem(Streaming.item(uri)); prepare(); playWhenReady = true
            val resume = if (context.prefs("cinema").getBoolean("resume", true)) Resume.get(context, uri) else 0L; if (resume > 0) seekTo(resume)
        }
    }
    val deleteAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { r -> if (r.resultCode == Activity.RESULT_OK) onDeleted() }
    var isPlaying by remember { mutableStateOf(true) }
    // What the player is waiting for, so it can be said out loud instead of leaving a black screen.
    val remote = remember(uri) { Streaming.isRemote(uri) }
    var firstFrame by remember { mutableStateOf(false) }
    var stalled by remember { mutableStateOf(true) }          // buffering right now
    var aheadMs by remember { mutableLongStateOf(0L) }       // video already fetched beyond the picture
    var bufferedFrac by remember { mutableFloatStateOf(0f) }
    var bps by remember { mutableLongStateOf(0L) }           // measured speed of the source
    var openedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var waited by remember { mutableLongStateOf(0L) }
    var streamError by remember { mutableStateOf<String?>(null) }
    var lastBuf by remember { mutableLongStateOf(-1L) }
    var lastBufAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idleMs by remember { mutableLongStateOf(0L) }          // how long no new video has arrived
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(context.prefs("cinema").getFloat("defaultSpeed", 1f)) }
    var resize by remember { mutableIntStateOf(listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL)[context.cInt("defaultFit", 0).coerceIn(0, 2)]) }
    var loop by remember { mutableStateOf(context.cBool("defaultLoop", false)) }
    var hud by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var cast by remember { mutableStateOf<Cast.Session?>(null) }
    DisposableEffect(Unit) { onDispose { val c = cast; if (c != null) kotlinx.coroutines.MainScope().launch { c.stop() } } }
    val hostView = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(isPlaying) { hostView.keepScreenOn = isPlaying && context.cBool("keepAwake", true); onDispose { hostView.keepScreenOn = false } }
    var landscape by remember { mutableStateOf(false) }
    // Sleep timer: pause after a set time, or when this video ends (-1).
    var sleepAt by remember { mutableLongStateOf(0L) }
    // A-B repeat: loops the part between two marks.
    var abA by remember { mutableLongStateOf(-1L) }
    var abB by remember { mutableLongStateOf(-1L) }
    var brightness by remember { mutableFloatStateOf(activity.window.attributes.screenBrightness.takeIf { it >= 0 } ?: .5f) }
    val inPip by MainActivity.pip
    MainActivity.autoPip.value = context.prefs("cinema").getBoolean("autoPip", true)
    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) { isPlaying = p; MainActivity.playing.value = p }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onPlaybackStateChanged(state: Int) { stalled = state == Player.STATE_BUFFERING; if (state == Player.STATE_READY) streamError = null }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { streamError = when (error.errorCode) {
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Lost the connection to the source. Check that it is still reachable, then try again."
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The source no longer has this file."
                else -> "Couldn't play this video: " + (error.cause?.message ?: error.message ?: "unknown problem") } }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (context.cBool("autoLandscape", false) && v.width > v.height && !landscape) { landscape = true; activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }
            }
        }
        player.addListener(l)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            Resume.set(context, uri, player.currentPosition, player.duration); refreshWidgets(context, ContinueWidget::class.java)
            player.removeListener(l); player.release(); MainActivity.playing.value = false
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        }
    }
    // Position updates are quick while the controls show and slow while they're hidden, to save battery.
    LaunchedEffect(controls, isPlaying) {
        var saved = 0L
        while (true) {
            pos = player.currentPosition; dur = player.duration.coerceAtLeast(0)
            if (remote || stalled || !firstFrame) {
                aheadMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
                bufferedFrac = if (dur > 0) (player.bufferedPosition.toFloat() / dur).coerceIn(0f, 1f) else 0f
                bps = Streaming.bitsPerSecond(context); waited = System.currentTimeMillis() - openedAt
                val bp = player.bufferedPosition; val now = System.currentTimeMillis()
                if (bp != lastBuf) { lastBuf = bp; lastBufAt = now }
                idleMs = if (stalled || !firstFrame) now - lastBufAt else 0L
            }
            if (kotlin.math.abs(pos - saved) > 10_000) { Resume.set(context, uri, pos, dur); saved = pos }
            delay(if (stalled || !firstFrame) 300 else if (controls && isPlaying) 250 else 2000)
        }
    }
    LaunchedEffect(controls, isPlaying, sheet) { if (controls && isPlaying && sheet == null) { val d = context.cInt("hideDelay", 35); if (d > 0) { delay(d * 100L); controls = false } } }
    LaunchedEffect(hud) { if (hud != null) { delay(900); hud = null } }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0) { delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0)); player.pause(); hud = "Sleep timer: paused"; sleepAt = 0 }
    }
    LaunchedEffect(sleepAt == -1L) {
        if (sleepAt != -1L) return@LaunchedEffect
        player.repeatMode = Player.REPEAT_MODE_OFF; loop = false
        while (sleepAt == -1L) { if (player.playbackState == Player.STATE_ENDED) { sleepAt = 0; hud = "Sleep timer: done" }; delay(1000) }
    }
    LaunchedEffect(isPlaying) {
        if (!isPlaying && player.playbackState == Player.STATE_ENDED && !loop && sleepAt != -1L && context.prefs("cinema").getBoolean("autoNext", true)) { hud = "Next video…"; delay(700); onEnded() }
    }
    LaunchedEffect(abA, abB, isPlaying) {
        if (abA < 0 || abB <= abA) return@LaunchedEffect
        while (true) { if (player.currentPosition >= abB) player.seekTo(abA); delay(100) }
    }
    LaunchedEffect(subtitle) {
        val s = subtitle ?: return@LaunchedEffect
        val at = player.currentPosition
        val cfg = MediaItem.SubtitleConfiguration.Builder(s).setMimeType(if (context.displayName(s).endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        player.setMediaItem(MediaItem.Builder().setUri(uri).setSubtitleConfigurations(listOf(cfg)).build(), at); player.prepare()
    }
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { subtitle = it }
    BackHandler { if (locked) hud = "Unlock first" else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ ctx -> PlayerView(ctx).apply { useController = false; this.player = player; setShutterBackgroundColor(android.graphics.Color.BLACK)
            subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * ctx.cInt("subSize", 100) / 100f)
            if (!ctx.cBool("subBox", false)) subtitleView?.setStyle(androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT, androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null)) } }, Modifier.fillMaxSize(),
            update = { it.resizeMode = resize })
        if (inPip) return@Box
        // Gesture layer: tap toggles controls, double-tap seeks, drags change position/brightness/volume, pinch fills.
        Box(Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { o ->
                    if (locked) return@detectTapGestures
                    val step = context.cInt("seekStep", 10)
                    if (!context.cBool("gDouble", true)) return@detectTapGestures
                    when { o.x < size.width / 3 -> { player.seekBack(); hud = "−$step s" }; o.x > size.width * 2 / 3 -> { player.seekForward(); hud = "+$step s" }
                        else -> { if (player.isPlaying) player.pause() else player.play() } }
                })
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                var mode = 0; var startPos = 0L; var acc = 0f
                val swipeVideo = context.cBool("gSwipeVideo", true)
                detectDragGestures(onDragEnd = { if (mode == 2 && swipeVideo && kotlin.math.abs(acc) > size.width * .22f) onStep(if (acc < 0) 1 else -1); hud = null }, onDragStart = { o -> mode = 0; acc = 0f; startPos = player.currentPosition; mode = if (o.x < size.width / 2) -1 else 1 },
                    onDrag = { ch, d ->
                        if (mode == -1 || mode == 1) { if (kotlin.math.abs(d.x) > kotlin.math.abs(d.y) * 1.5f) mode = 2 else if (kotlin.math.abs(d.y) > 2) mode = if (mode == -1) 3 else 4 }
                        when (mode) {
                            2 -> { if (swipeVideo) { acc += d.x; hud = if (kotlin.math.abs(acc) > size.width * .22f) (if (acc < 0) "Next video  ›" else "‹  Previous video") else "Swipe further for another video"; ch.consume(); return@detectDragGestures }
                                if (!context.cBool("gSeek", true)) { ch.consume(); return@detectDragGestures }; acc += d.x; val target = (startPos + (acc / size.width * context.cInt("seekSens", 120) * 1000L).toLong()).coerceIn(0, dur); player.seekTo(target); hud = "${timeText(target)} / ${timeText(dur)}" }
                            3 -> { if (!context.cBool("gBright", true)) { ch.consume(); return@detectDragGestures }; brightness = (brightness - d.y / size.height).coerceIn(.02f, 1f); activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }; hud = "☀ ${(brightness * 100).toInt()}%" }
                            4 -> { if (!context.cBool("gVolume", true)) { ch.consume(); return@detectDragGestures }; acc -= d.y; val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val step = size.height / (max * 1.5f)
                                if (kotlin.math.abs(acc) > step) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (acc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0); acc = 0f }
                                hud = "🔊 ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%" }
                        }
                        ch.consume()
                    })
            }
            .pointerInput(locked) {
                if (locked) return@pointerInput
                // Pinch with two fingers only; a single finger must stay free for the swipe gestures above.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    var z = 1f
                    do {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            z *= ev.calculateZoom()
                            if (z > 1.05f) { resize = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; hud = "Fill screen"; z = 1f } else if (z < .95f) { resize = AspectRatioFrameLayout.RESIZE_MODE_FIT; hud = "Fit"; z = 1f }
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            })
        hud?.let { Text(it, Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .6f)).padding(16.dp, 10.dp), color = Color.White, style = MaterialTheme.typography.titleLarge) }
        // Opening or running dry: say so, with how much is ready and how fast the source is.
        if (!inPip && (streamError != null || !firstFrame || stalled)) StreamCard(Modifier.align(Alignment.Center), title, firstFrame, aheadMs, waited, bps, player.videoFormat?.bitrate?.toLong()?.takeIf { it > 0 } ?: 0L, remote, streamError, idleMs)
        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (locked) {
                    IconButton(onClick = { locked = false; hud = "Unlocked" }, Modifier.align(Alignment.CenterStart).padding(16.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f))) { Icon(WonderIcons.Lock, "Unlock", tint = Color.White) }
                    return@Box
                }
                val chrome = playerChrome(LocalLook.current)
                // title bar: a slim glass strip, like a QuickTime window title
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp).clip(RoundedCornerShape(chrome.radius)).background(Brush.verticalGradient(chrome.bar))
                    .border(1.dp, chrome.edge, RoundedCornerShape(chrome.radius)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(WonderIcons.ArrowBack, "Back", tint = chrome.ink) }
                    Text(title, Modifier.weight(1f), color = chrome.ink, style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { sheet = "cast" }) { Icon(WonderIcons.Cast, "Cast to a TV", tint = if (cast != null) Color(0xFF2EE6D6) else chrome.ink) }
                    IconButton(onClick = { sheet = "tracks" }) { Icon(WonderIcons.Translate, "Audio and subtitles", tint = chrome.ink) }
                    IconButton(onClick = { MainActivity.enterPip(activity) }) { Icon(WonderIcons.Layers, "Picture in picture", tint = chrome.ink) }
                    IconButton(onClick = { sheet = "more" }) { Icon(WonderIcons.MoreVert, "More", tint = chrome.ink) }
                }
                // floating HUD controller
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp).widthIn(max = 640.dp).fillMaxWidth()
                    .shadow(18.dp, RoundedCornerShape(chrome.radius + 6.dp)).clip(RoundedCornerShape(chrome.radius + 6.dp)).background(Brush.verticalGradient(chrome.hud))
                    .border(1.dp, chrome.edge, RoundedCornerShape(chrome.radius + 6.dp))
                    .drawBehind { drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = chrome.gloss), Color.Transparent), endY = size.height * .45f)) }
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { locked = true; controls = false; hud = "Locked" }) { Icon(WonderIcons.Lock, "Lock controls", tint = chrome.ink.copy(alpha = .85f)) }
                        IconButton(onClick = { loop = !loop; player.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; hud = if (loop) "Loop on" else "Loop off" }) {
                            Icon(WonderIcons.Repeat, "Loop", tint = if (loop) chrome.accent else chrome.ink.copy(alpha = .85f)) }
                        Spacer(Modifier.weight(1f))
                        HudButton(WonderIcons.SkipPrevious, "Previous video", chrome) { onStep(-1) }
                        Spacer(Modifier.width(10.dp))
                        HudButton(WonderIcons.Replay10, "Back 10 seconds", chrome) { player.seekBack() }
                        Spacer(Modifier.width(14.dp))
                        GelPlay(isPlaying, chrome) { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } }
                        Spacer(Modifier.width(14.dp))
                        HudButton(WonderIcons.Forward10, "Forward 10 seconds", chrome) { player.seekForward() }
                        Spacer(Modifier.width(10.dp))
                        HudButton(WonderIcons.SkipNext, "Next video", chrome) { onStep(1) }
                        Spacer(Modifier.weight(1f))
                        Text("${if (speed % 1 == 0f) speed.toInt() else speed}×", Modifier.clip(RoundedCornerShape(8.dp)).clickable { sheet = "speed" }.padding(10.dp), color = chrome.ink, style = MaterialTheme.typography.labelLarge)
                        IconButton(onClick = onEdit) { Icon(WonderIcons.ContentCut, "Edit", tint = chrome.ink.copy(alpha = .85f)) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(timeText(pos), color = chrome.ink.copy(alpha = .9f), style = MaterialTheme.typography.labelMedium)
                        Scrubber(if (dur > 0) pos.toFloat() / dur else 0f, chrome, Modifier.weight(1f).padding(horizontal = 10.dp)) { f -> player.seekTo((f * dur).toLong()); pos = (f * dur).toLong() }
                        Text("-" + timeText(dur - pos), color = chrome.ink.copy(alpha = .9f), style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = { resize = when (resize) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
                            hud = when (resize) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Fill screen"; AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"; else -> "Fit" } }) { Icon(WonderIcons.Fullscreen, "Aspect", tint = chrome.ink.copy(alpha = .85f)) }
                        IconButton(onClick = { landscape = !landscape; activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }) {
                            Icon(WonderIcons.RotateRight, "Rotate", tint = chrome.ink.copy(alpha = .85f)) }
                    }
                    if (remote) Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .15f))) { Box(Modifier.fillMaxWidth(bufferedFrac).fillMaxHeight().background(chrome.accent.copy(alpha = .75f))) }
                        Text("%.0f s buffered".format(aheadMs / 1000.0) + (if (bps > 0 && firstFrame) " · receiving ${"%.1f".format(bps / 1_000_000.0)} Mbit/s" else ""), color = chrome.ink.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    when (sheet) {
        "speed" -> WonderDialog(onDismissRequest = { sheet = null }, title = { Text("Playback speed") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${"%.2f".format(speed)}×", style = MaterialTheme.typography.headlineSmall)
                Slider(speed, { speed = (it * 20).toInt() / 20f; player.playbackParameters = PlaybackParameters(speed) }, valueRange = .25f..3f)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> Chip("${s}×", speed == s, { speed = s; player.playbackParameters = PlaybackParameters(s) }) }
                }
            }
        }, confirmButton = { TextButton(onClick = { sheet = null }) { Text("Done") } })
        "delete" -> ConfirmDialog("Delete this video?", "“$title” is removed from your phone.", "Delete", {
            sheet = null; player.pause()
            VideoDeleter.delete(context, uri, ask = { sender -> deleteAsk.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) }, done = { onDeleted() }, fail = { hud = "Couldn't delete it" })
        }, { sheet = null }, danger = true)
        "sleep" -> WonderDialog(onDismissRequest = { sheet = null }, title = { Text("Sleep timer") }, text = {
            Panel {
                listOf(15, 30, 45, 60, 90).forEachIndexed { i, m -> if (i > 0) PanelDivider(16.dp)
                    ListRow("In $m minutes", onClick = { sleepAt = System.currentTimeMillis() + m * 60_000L; sheet = null; hud = "Pausing in $m min" }) }
                PanelDivider(16.dp)
                ListRow("At the end of this video", selected = sleepAt == -1L, onClick = { sleepAt = -1L; sheet = null; hud = "Stops at the end" })
                if (sleepAt != 0L) { PanelDivider(16.dp); ListRow("Turn off", danger = true, onClick = { sleepAt = 0; sheet = null; hud = "Sleep timer off" }) }
            }
        }, confirmButton = { TextButton(onClick = { sheet = null }) { Text("Close") } })
        "cast" -> CastSheet(uri, title, cast, { cast = it }, { player.currentPosition }, { player.pause() }, { sheet = null })
        "tracks" -> TrackSheet(player, onLoadSubs = { sheet = null; subPicker.launch(arrayOf("application/x-subrip", "text/*", "*/*")) }) { sheet = null }
        "more" -> WonderDialog(onDismissRequest = { sheet = null }, title = { Text(title) }, text = {
            Panel {
                ListRow("Save this frame as a picture", icon = WonderIcons.PhotoCamera, tint = Color(0xFF2E9E44), onClick = {
                    sheet = null
                    val at = player.currentPosition
                    kotlinx.coroutines.MainScope().launch {
                        val r = withContext(Dispatchers.IO) { runCatching {
                            val bmp = Videos.frame(context, uri, at) ?: error("Couldn't read that frame")
                            val f = File(context.cacheDir, "frame.png"); f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            Videos.publish(context, f, title.substringBeforeLast('.') + " " + timeText(at).replace(':', '.') + ".png", image = true)
                        } }
                        hud = if (r.isSuccess) "Saved to Pictures/Cinema" else "Couldn't save the frame"
                    }
                })
                PanelDivider()
                ListRow(when { sleepAt == -1L -> "Sleep timer: end of this video"; sleepAt > 0 -> "Sleep timer: pauses at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleepAt)); else -> "Sleep timer" },
                    subtitle = "Pause after a while, for falling asleep to a film", icon = WonderIcons.Bedtime, tint = Color(0xFF5B4BC4), chevron = true, onClick = { sheet = "sleep" })
                PanelDivider()
                ListRow(when { abA < 0 -> "Repeat a part (A-B)"; abB <= abA -> "Set B: end of the part"; else -> "Repeating ${timeText(abA)} to ${timeText(abB)}" },
                    subtitle = when { abA < 0 -> "Tap to mark A here, then again later to mark B"; abB <= abA -> "A is at ${timeText(abA)}. Tap here at the end of the part"; else -> "Tap to stop repeating" },
                    icon = WonderIcons.Repeat, tint = Color(0xFFE39A12), onClick = {
                        val at = player.currentPosition
                        when { abA < 0 -> { abA = at; abB = -1; hud = "A set at ${timeText(at)}" }; abB <= abA -> { if (at > abA + 500) { abB = at; hud = "Repeating A-B" } else hud = "Play on a little, then set B" }; else -> { abA = -1; abB = -1; hud = "A-B repeat off" } }
                        sheet = null
                    })
                PanelDivider()
                ListRow("Share video", icon = WonderIcons.Share, tint = Color(0xFF1E7FE8), onClick = { sheet = null; context.share(uri, "video/*") {} })
                PanelDivider()
                ListRow("Delete video", icon = WonderIcons.Delete, tint = Color(0xFFE5483C), danger = true, onClick = { sheet = "delete" })
            }
        }, confirmButton = { TextButton(onClick = { sheet = null }) { Text("Close") } })
    }
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size((size * .55f).dp))
    }
}

@Composable
private fun TrackSheet(player: ExoPlayer, onLoadSubs: () -> Unit, onDismiss: () -> Unit) {
    val groups = player.currentTracks.groups
    fun langName(code: String?) = code?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } } ?: "Unknown"
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Audio and subtitles") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(C.TRACK_TYPE_AUDIO to "Audio", C.TRACK_TYPE_TEXT to "Subtitles").forEach { (type, label) ->
                val g = groups.filter { it.type == type }
                Panel(title = label) {
                    if (type == C.TRACK_TYPE_TEXT) {
                        val off = g.none { it.isSelected }
                        ListRow("Off", selected = off, onClick = { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(); onDismiss() })
                    }
                    g.forEach { grp ->
                        for (i in 0 until grp.length) {
                            val f = grp.getTrackFormat(i)
                            PanelDivider(16.dp)
                            ListRow(f.label ?: langName(f.language), subtitle = listOfNotNull(f.sampleMimeType?.substringAfter('/'), if (f.channelCount > 0) "${f.channelCount} ch" else null).joinToString(" · "),
                                selected = grp.isTrackSelected(i), onClick = {
                                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                                        .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, i)).build(); onDismiss()
                                })
                        }
                    }
                    if (g.isEmpty() && type == C.TRACK_TYPE_AUDIO) ListRow("No other audio tracks")
                    if (type == C.TRACK_TYPE_TEXT) { PanelDivider(0.dp); ListRow("Load subtitle file (.srt, .vtt)…", icon = WonderIcons.UploadFile, tint = Color(0xFF8E44AD), onClick = onLoadSubs) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}


/* -------------------------------------------------------- editor -------------------------------------------- */

@Composable
private fun EditorScreen(uri: Uri, title: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val player = remember { ExoPlayer.Builder(context).build().apply { prepare() } }
    // Every video in the movie, and the pieces of them in the order they play. Cut pieces stay on the timeline, dimmed, and are skipped.
    class Src(val uri: Uri, val name: String, val dur: Long, val w: Int, val h: Int, val strip: List<android.graphics.Bitmap>)
    data class Seg(val src: Int, val start: Long, val end: Long, val keep: Boolean = true) { val len get() = end - start }
    var srcs by remember { mutableStateOf<List<Src>>(emptyList()) }
    var segs by remember { mutableStateOf<List<Seg>>(emptyList()) }
    var selected by remember { mutableIntStateOf(-1) }
    val undo = remember { ArrayDeque<List<Seg>>() }
    fun change(n: List<Seg>) { undo.addLast(segs); if (undo.size > 40) undo.removeFirst(); segs = n }
    var mute by remember { mutableStateOf(false) }
    var rotate by remember { mutableIntStateOf(0) }
    var outSize by remember { mutableIntStateOf(context.cInt("outSize", 0)) } // 0 original, 1 1080p, 2 720p, 3 480p
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var warm by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var caption by remember { mutableStateOf("") }
    var music by remember { mutableStateOf<Uri?>(null) }
    var musicName by remember { mutableStateOf("") }
    var musicVol by remember { mutableFloatStateOf(.8f) }
    var autoBusy by remember { mutableStateOf(false) }
    var addingClip by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { music = u; musicName = context.displayName(u) } }
    var progress by remember { mutableStateOf<Int?>(null) }
    var pos by remember { mutableLongStateOf(0L) }          // position on the whole timeline
    var playing by remember { mutableStateOf(false) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    suspend fun loadSrc(u: Uri, name: String): Src? {
        val info = withContext(Dispatchers.IO) { Videos.probe(context, u) } ?: return null
        val frames = withContext(Dispatchers.IO) { (0 until 8).mapNotNull { i -> Videos.frame(context, u, info.first * i / 8)?.let { b -> android.graphics.Bitmap.createScaledBitmap(b, 160, (160f * b.height / b.width).toInt().coerceAtLeast(1), true) } } }
        return Src(u, name, info.first, info.second, info.third, frames)
    }
    val pickClip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val name = context.displayName(u); addingClip = "Reading $name"
            val s = loadSrc(u, name); addingClip = null
            if (s == null || s.dur <= 0) { toastRef.value("Couldn't read that video"); return@launch }
            srcs = srcs + s; change(segs + Seg(srcs.size - 1, 0, s.dur)); selected = segs.size - 1
            toastRef.value("Added to the end of the movie")
        }
    }
    DisposableEffect(Unit) { val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }; player.addListener(l); onDispose { player.release(); Editor.cancel() } }
    // The preview shows colour, zoom, caption, speed and volume as you change them.
    LaunchedEffect(bright, contrast, sat, warm, zoom, panX, panY, caption) {
        delay(120)
        runCatching { player.setVideoEffects(Editor.videoEffects(EditSpec(emptyList(), false, 0, null, bright, contrast, sat, warm, zoom, panX, panY, caption = caption), preview = true)) }
    }
    LaunchedEffect(speed, volume) { player.setPlaybackSpeed(speed); player.volume = volume.coerceAtMost(1f) }
    LaunchedEffect(Unit) {
        addingClip = "Reading the video"
        val s = loadSrc(uri, title)
        addingClip = null
        if (s != null) { srcs = listOf(s); segs = listOf(Seg(0, 0, s.dur)) }
    }
    val total = segs.sumOf { it.len }
    val kept = segs.filter { it.keep }
    val keptMs = kept.sumOf { it.len }
    fun segStart(i: Int) = segs.take(i).sumOf { it.len }
    // The preview plays the kept pieces back to back, so cutting and reordering are what you hear and see.
    fun seekGlobal(t: Long) {
        var acc = 0L; var k = 0
        for (sg in segs) {
            if (t < acc + sg.len || sg === segs.last()) {
                if (sg.keep) player.seekTo(k, (t - acc).coerceIn(0, sg.len)) else if (k < kept.size) player.seekTo(k, 0) else player.seekTo((kept.size - 1).coerceAtLeast(0), 0)
                return
            }
            acc += sg.len; if (sg.keep) k++
        }
    }
    LaunchedEffect(segs, srcs) {
        if (srcs.isEmpty()) return@LaunchedEffect
        val was = pos; val wasPlaying = player.isPlaying
        player.setMediaItems(kept.map { sg -> MediaItem.Builder().setUri(srcs[sg.src].uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(sg.start).setEndPositionMs(sg.end).build()).build() })
        player.prepare(); if (kept.isNotEmpty()) seekGlobal(was); player.playWhenReady = wasPlaying
    }
    LaunchedEffect(Unit) {
        while (true) {
            val i = player.currentMediaItemIndex
            if (kept.isNotEmpty() && i in kept.indices) {
                var k = -1; var g = 0L
                for (sg in segs) { if (sg.keep) { k++; if (k == i) break }; g += sg.len }
                pos = g + player.currentPosition.coerceAtLeast(0)
            }
            delay(if (player.isPlaying) 50 else 200)
        }
    }
    fun split() {
        var acc = 0L
        for ((i, sg) in segs.withIndex()) {
            if (pos > acc + 150 && pos < acc + sg.len - 150) {
                val at = sg.start + (pos - acc)
                change(segs.toMutableList().apply { set(i, sg.copy(end = at)); add(i + 1, sg.copy(start = at)) }); selected = i + 1; return
            }
            acc += sg.len
        }
        toastRef.value("Move the playhead inside a part to split it")
    }
    fun move(by: Int) { val i = selected; val j = i + by; if (i < 0 || j !in segs.indices) return
        change(segs.toMutableList().apply { val t = this[i]; this[i] = this[j]; this[j] = t }); selected = j }
    WonderApp(appName = "Memories", about = ABOUT, title = "Edit video", subtitle = title, onBack = onClose,
        actions = { ToolbarButton(WonderIcons.Undo, "Undo", { undo.removeLastOrNull()?.let { segs = it; selected = -1 } }, enabled = undo.isNotEmpty()) }) {
        toastRef.value = LocalToast.current
        if (srcs.isEmpty()) { LoadingPane(addingClip ?: "Reading the video", detail = "Getting the video ready for editing"); return@WonderApp }
        Page {
            Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                AndroidView({ PlayerView(it).apply { useController = false; this.player = player } }, Modifier.fillMaxSize().graphicsLayer { rotationZ = rotate.toFloat() })
                RoundBtnSmall(if (playing) WonderIcons.Pause else WonderIcons.PlayArrow, Modifier.align(Alignment.Center)) {
                    if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) seekGlobal(0); player.play() }
                }
                Text(timeText(pos) + " / " + timeText(total), Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .55f)).padding(6.dp, 2.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            addingClip?.let { LoadingPane(it, detail = "Getting the video ready for the timeline") }
            Panel(title = "Timeline", footer = "Add as many videos as you like. Tap a part to select it, then Cut it, or move it earlier or later to change the order. Cut parts are skipped when you play and left out when you save.") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // every part side by side, widths in proportion to length, with a draggable playhead
                    Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).pointerInput(total, segs) {
                        detectTapGestures { o -> if (total > 0) {
                            val t = (o.x / size.width * total).toLong().coerceIn(0, total); seekGlobal(t); pos = t
                            var acc = 0L; selected = segs.indexOfFirst { sg -> (t in acc until acc + sg.len).also { acc += sg.len } } } }
                    }.pointerInput(total, segs) {
                        detectHorizontalDragGestures { ch, _ -> if (total > 0) { val t = (ch.position.x / size.width * total).toLong().coerceIn(0, total); seekGlobal(t); pos = t } }
                    }) {
                        Row(Modifier.fillMaxSize()) {
                            segs.forEach { sg ->
                                val frames = srcs.getOrNull(sg.src)?.strip.orEmpty()
                                Row(Modifier.weight(sg.len.coerceAtLeast(1).toFloat()).fillMaxHeight()) {
                                    if (frames.isEmpty()) Box(Modifier.fillMaxSize().background(p.separator))
                                    else { val d = srcs[sg.src].dur.coerceAtLeast(1); val n = 4
                                        for (k in 0 until n) { val f = ((sg.start + sg.len * (2 * k + 1) / (2 * n)) * frames.size / d).toInt().coerceIn(0, frames.size - 1); Image(frames[f].asImageBitmap(), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop) } }
                                }
                            }
                        }
                        Canvas(Modifier.fillMaxSize()) {
                            if (total <= 0) return@Canvas
                            var acc = 0L
                            segs.forEachIndexed { i, sg ->
                                val x0 = size.width * acc / total; val x1 = size.width * (acc + sg.len) / total; acc += sg.len
                                if (!sg.keep) {
                                    drawRect(Color.Black.copy(alpha = .62f), androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Size(x1 - x0, size.height))
                                    var x = x0; while (x < x1) { drawLine(Color(0xFFE5483C).copy(alpha = .8f), androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset((x + size.height).coerceAtMost(x1), 0f), 3f); x += 18f }
                                }
                                if (i == selected) drawRect(Color(0xFFFFD34D), androidx.compose.ui.geometry.Offset(x0 + 2, 2f), androidx.compose.ui.geometry.Size(x1 - x0 - 4, size.height - 4), style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                                if (i > 0) drawLine(Color.White, androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Offset(x0, size.height), 4f)
                            }
                            val px = size.width * pos / total
                            drawLine(Color.White, androidx.compose.ui.geometry.Offset(px, 0f), androidx.compose.ui.geometry.Offset(px, size.height), 6f)
                            drawCircle(Color.White, 9f, androidx.compose.ui.geometry.Offset(px, 9f))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${srcs.size} video${if (srcs.size == 1) "" else "s"} · ${segs.size} part${if (segs.size == 1) "" else "s"} · ${segs.count { !it.keep }} cut", style = MaterialTheme.typography.labelMedium, color = p.secondaryText)
                        Spacer(Modifier.weight(1f))
                        Text("New length " + timeText(keptMs), style = MaterialTheme.typography.labelMedium, color = p.accent)
                    }
                    segs.getOrNull(selected)?.let { sg -> Text("Selected: ${srcs[sg.src].name}, ${timeText(sg.start)} to ${timeText(sg.end)}", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WButton("Add video", { pickClip.launch(arrayOf("video/*")) }, icon = WonderIcons.Add, kind = ButtonKind.Primary)
                        WButton("Split here", { split() }, icon = WonderIcons.ContentCut)
                        val sel = segs.getOrNull(selected)
                        WButton(if (sel?.keep == false) "Keep part" else "Cut part", { sel?.let { change(segs.toMutableList().apply { set(selected, it.copy(keep = !it.keep)) }) } ?: toastRef.value("Tap a part of the timeline first") },
                            icon = if (sel?.keep == false) WonderIcons.Restore else WonderIcons.Delete, kind = if (sel?.keep == false) ButtonKind.Secondary else ButtonKind.Danger, enabled = sel != null)
                        WButton("Move earlier", { move(-1) }, kind = ButtonKind.Secondary, enabled = selected > 0)
                        WButton("Move later", { move(1) }, kind = ButtonKind.Secondary, enabled = selected in 0 until segs.size - 1)
                        WButton("Remove part", { if (selected in segs.indices && segs.size > 1) { change(segs.toMutableList().apply { removeAt(selected) }); selected = -1 } else toastRef.value("Tap one of several parts first") }, kind = ButtonKind.Plain, enabled = selected >= 0 && segs.size > 1)
                        WButton("Cut everything before", { if (pos > 0) { var acc = 0L; val n = segs.flatMap { sg -> val s0 = acc; acc += sg.len; if (pos > s0 && pos < s0 + sg.len) listOf(sg.copy(end = sg.start + (pos - s0), keep = false), sg.copy(start = sg.start + (pos - s0))) else listOf(if (s0 + sg.len <= pos) sg.copy(keep = false) else sg) }; change(n) } }, kind = ButtonKind.Plain)
                        WButton("Cut everything after", { if (pos < total) { var acc = 0L; val n = segs.flatMap { sg -> val s0 = acc; acc += sg.len; if (pos > s0 && pos < s0 + sg.len) listOf(sg.copy(end = sg.start + (pos - s0)), sg.copy(start = sg.start + (pos - s0), keep = false)) else listOf(if (s0 >= pos) sg.copy(keep = false) else sg) }; change(n) } }, kind = ButtonKind.Plain)
                        WButton("Reset", { change(srcs.mapIndexed { i, s -> Seg(i, 0, s.dur) }); selected = -1 }, kind = ButtonKind.Plain)
                    }
                }
            }
            Panel(title = "Look", footer = "Auto enhance measures your video and sets brightness, contrast and colour for you. Everything below shows live in the preview.") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WButton(if (autoBusy) "Measuring your video…" else "Auto enhance", {
                        autoBusy = true
                        scope.launch {
                            val luma = withContext(Dispatchers.IO) {
                                val pts = listOf(.15f, .5f, .85f).mapNotNull { f -> Videos.frame(context, srcs[0].uri, (srcs[0].dur * f).toLong()) }
                                if (pts.isEmpty()) null else pts.map { b ->
                                    val sm = android.graphics.Bitmap.createScaledBitmap(b, 32, 32, true); var t = 0.0
                                    for (x in 0 until 32) for (y in 0 until 32) { val c = sm.getPixel(x, y); t += (0.2126 * android.graphics.Color.red(c) + 0.7152 * android.graphics.Color.green(c) + 0.0722 * android.graphics.Color.blue(c)) / 255.0 }
                                    t / 1024
                                }.average()
                            }
                            if (luma != null) { bright = ((.45 - luma) * .6).toFloat().coerceIn(-.25f, .25f); contrast = .12f; sat = .1f; warm = 0f; toastRef.value("Tuned for this video") }
                            else toastRef.value("Couldn't read the video to measure it")
                            autoBusy = false
                        }
                    }, icon = WonderIcons.Tune, enabled = !autoBusy && srcs.isNotEmpty())
                    SliderRow("Brightness", bright, -.5f..0.5f, { bright = it }) { "%+d".format((it * 100).toInt()) }
                    SliderRow("Contrast", contrast, -.5f..0.5f, { contrast = it }) { "%+d".format((it * 100).toInt()) }
                    SliderRow("Colour", sat, -1f..1f, { sat = it }) { "%+d".format((it * 100).toInt()) }
                    SliderRow("Warmth", warm, -1f..1f, { warm = it }) { "%+d".format((it * 100).toInt()) }
                    WButton("Reset look", { bright = 0f; contrast = 0f; sat = 0f; warm = 0f }, kind = ButtonKind.Plain)
                }
            }
            Panel(title = "Zoom and crop", footer = "Zoom in to cut away the edges, then move the picture to frame what matters.") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SliderRow("Zoom", zoom, 1f..4f, { zoom = it }) { "%.1fx".format(it) }
                    if (zoom > 1.001f) {
                        SliderRow("Left to right", panX, -1f..1f, { panX = it }) { "%+d".format((it * 100).toInt()) }
                        SliderRow("Up and down", panY, -1f..1f, { panY = it }) { "%+d".format((it * 100).toInt()) }
                    }
                    WButton("Reset zoom", { zoom = 1f; panX = 0f; panY = 0f }, kind = ButtonKind.Plain)
                }
            }
            Panel(title = "Sound and speed", footer = "Add music, a voiceover recorded in Echo, or a sound effect. It plays along the whole video, mixed under the original sound.") {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow("Remove the original sound", mute, { mute = it }, icon = WonderIcons.VolumeUp, tint = Color(0xFFE5483C), help = "Mutes the video's own audio so you can use your own.")
                    SliderRow("Original volume", volume, 0f..2f, { volume = it }) { "${(it * 100).toInt()}%" }
                    Text("Speed", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(.5f, .75f, 1f, 1.5f, 2f).forEach { v -> Chip(if (v == 1f) "Normal" else "${v}x", speed == v, { speed = v }) }
                    }
                    PanelDivider(inset = 0.dp)
                    ListRow(if (music == null) "Add music, voiceover or sound" else musicName, subtitle = if (music == null) "Pick any audio file, such as a recording from Echo" else "Tap to choose another", icon = WonderIcons.Mic, tint = Color(0xFF2E9E44),
                        onClick = { pickMusic.launch(arrayOf("audio/*")) })
                    if (music != null) {
                        SliderRow("Added sound volume", musicVol, 0f..1.5f, { musicVol = it }) { "${(it * 100).toInt()}%" }
                        WButton("Remove added sound", { music = null; musicName = "" }, kind = ButtonKind.Plain)
                    }
                }
            }
            Panel(title = "Caption", footer = "Shows along the bottom for the whole video.") {
                OutlinedTextField(caption, { caption = it.take(80) }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Text on the video") }, singleLine = true)
            }
            Panel(title = "Rotate and size") {
                ListRow("Rotate", subtitle = if (rotate == 0) "Original" else "$rotate°", icon = WonderIcons.RotateRight, tint = Color(0xFF8E44AD), trailing = {
                    Row { IconButton(onClick = { rotate = (rotate + 270) % 360 }) { Icon(WonderIcons.RotateLeft, "Rotate left") }; IconButton(onClick = { rotate = (rotate + 90) % 360 }) { Icon(WonderIcons.RotateRight, "Rotate right") } }
                })
                PanelDivider()
                Column(Modifier.padding(12.dp)) {
                    Text("Size", style = MaterialTheme.typography.titleSmall)
                    Segmented(listOf("Original", "1080p", "720p", "480p"), outSize) { outSize = it }
                    Text("Smaller sizes are quicker to send.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, modifier = Modifier.padding(top = 4.dp))
                }
            }
            WButton(if (progress != null) "Saving… ${progress!!.coerceAtLeast(0)}%" else "Save as new video", {
                val first = srcs[kept.first().src]
                val outH = listOf(null, 1080, 720, 480)[outSize]
                val multi = kept.map { it.src }.distinct().size > 1
                val canvas = if (!multi) null else { val sw = if (rotate % 180 != 0) first.h else first.w; val sh = if (rotate % 180 != 0) first.w else first.h
                    val h = (outH ?: sh) and 1.inv(); Pair(((sw.toLong() * h / sh.coerceAtLeast(1)).toInt()) and 1.inv(), h) }
                val spec = EditSpec(kept.map { Part(srcs[it.src].uri, it.start, it.end) }, mute, rotate, outH, bright, contrast, sat, warm, zoom, panX, panY, volume, speed, caption, music, musicVol, canvas)
                val out = File(context.cacheDir, "edit.mp4")
                progress = 0
                val poll = runCatching { Editor.export(context, spec, out) { r ->
                    kotlinx.coroutines.MainScope().launch {
                        val saved = r.mapCatching { withContext(Dispatchers.IO) { Videos.publish(context, it, title.substringBeforeLast('.') + " (edited).mp4", image = false) } }
                        progress = null
                        toastRef.value(saved.fold({ "Saved to Movies/Cinema" }, { "Couldn't export: ${it.message}" }))
                    }
                } }.getOrElse { toastRef.value(it.message ?: "Couldn't export"); progress = null; null }
                if (poll != null) kotlinx.coroutines.MainScope().launch { while (progress != null) { progress = poll().takeIf { it >= 0 } ?: progress; delay(300) } }
            }, Modifier.fillMaxWidth(), icon = WonderIcons.Save, kind = ButtonKind.Primary, enabled = progress == null && kept.isNotEmpty())
            if (progress != null) WButton("Cancel export", { Editor.cancel(); progress = null }, kind = ButtonKind.Plain)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, format: (Float) -> String) {
    val p = wonderPalette()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelMedium, color = p.accent)
        }
        Slider(value, onChange, valueRange = range)
    }
}

@Composable
private fun RoundBtnSmall(icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = .5f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
}


/** Only used if the player is opened on its own (a video link from another app, then Back to the list). */
@Composable
private fun StandaloneLibrary(onPlay: (Video) -> Unit, onEdit: (Video) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("videos") }
    WonderApp(needs = APP_NEEDS, appName = "Memories", about = ABOUT,
        tabs = listOf(WonderTab("videos", "Videos", WonderIcons.VideoLibrary), WonderTab("record", "Record", WonderIcons.Videocam)), selectedTab = tab, onTab = { tab = it },
        title = "Videos", actions = { CinemaToolbar() }, settings = { CinemaSettingsPanels() }) {
        CinemaLibrary(if (tab == "record") "record" else "videos", onPlay, onEdit)
    }
}

/** The toolbar buttons of the video library: open a network stream, open a file, refresh. Shared by Memories' toolbar. */
@Composable
fun CinemaToolbar() {
    ToolbarButton(WonderIcons.Cast, "Open a network stream", { CinemaBus.stream.intValue++ })
    ToolbarButton(WonderIcons.FolderOpen, "Open a video file", { CinemaBus.pick.intValue++ })
    ToolbarButton(WonderIcons.Refresh, "Refresh", { CinemaBus.refresh.intValue++ })
}

/** Lets the host app's toolbar tell the embedded video library what to do. */
object CinemaBus {
    val stream = mutableIntStateOf(0)
    val pick = mutableIntStateOf(0)
    val refresh = mutableIntStateOf(0)
}
