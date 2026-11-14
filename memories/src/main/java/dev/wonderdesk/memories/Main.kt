package dev.wonderdesk.memories

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : WonderActivity() {
    @Composable override fun Content() = MemoriesApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent }
    companion object { val incoming = mutableStateOf<Intent?>(null) }
}

/** What Android must allow for this app to work fully; shown as a banner when off and in Settings. */
private val APP_NEEDS by lazy { listOf(
    WonderNeed("Photos and videos", "Shows every picture and video on your phone; they stay on the phone", anyOf = true,
        permissions = if (android.os.Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO) else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)),
    WonderNeed("Photo locations", "Shows where each photo was taken in Details", required = false, permissions = listOf(android.Manifest.permission.ACCESS_MEDIA_LOCATION)),
    WonderNeed("Pixel Camera link", "Makes Pixel Camera's thumbnail open Memories while Google Photos is off", required = false, access = SpecialAccess.Kind.ACCESSIBILITY, component = CameraLink::class.java),
) }

private const val ABOUT = "Memories shows every photo and video on your phone in a timeline and by album, with On this day, favourites and a 30-day trash. " +
    "Photos zoom and swipe away, videos play right in the viewer, and camera apps open their newest shot here. Edit with crop, rotate, filters, colour and drawing; edits are saved as copies."

private val PERMS = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
private fun granted(c: Context) = PERMS.all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED } ||
    (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(c, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)

/** Where you are in the app; survives the window being rebuilt. */
private object Nav {
    val viewing = mutableStateOf<Pair<List<Photo>, Int>?>(null)
    val editing = mutableStateOf<Uri?>(null)
    val external = mutableStateOf<Uri?>(null)
    var externalMime: String? = null
    /** Opened by another app (a camera's thumbnail, Files, a chat): closing the photo goes back there. */
    var fromApp = false
    /** Opened from the camera over the lock screen: view only. */
    var secure = false
    /** "Newest photo" from the home-screen shortcut or Pixel Camera: opens once the library has loaded. */
    var latest = mutableStateOf(false)
    val startTab = mutableStateOf<String?>(null)
}

/** What other apps send to open a photo: VIEW from Files and chats, REVIEW from camera apps. */
private val REVIEW_ACTIONS = setOf(Intent.ACTION_VIEW, "com.android.camera.action.REVIEW", "android.provider.action.REVIEW", "android.provider.action.REVIEW_SECURE")

@Composable
fun MemoriesApp() {
    val context = LocalContext.current
    var ok by remember { mutableStateOf(granted(context)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { ok = granted(context) }
    var photos by remember { mutableStateOf<List<Photo>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    // Kept outside the screen so a wallpaper or theme change (which makes Android rebuild the window) returns you to the same photo.
    var viewing by Nav.viewing
    var editing by Nav.editing
    var external by Nav.external
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { ok = granted(context); tick++ } }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(ok, tick) { if (ok) photos = withContext(Dispatchers.IO) { Gallery.all(context) } }
    val incoming by MainActivity.incoming
    LaunchedEffect(incoming) {
        val i = incoming ?: (context as ComponentActivity).intent
        MainActivity.incoming.value = null; (context as ComponentActivity).intent = Intent()
        when (i?.action) {
            in REVIEW_ACTIONS -> if (i.data != null) {
                Nav.fromApp = true; Nav.secure = i.action == "android.provider.action.REVIEW_SECURE"; Nav.externalMime = i.type; viewing = null; external = i.data
            }
            Intent.ACTION_EDIT -> { Nav.fromApp = true; editing = i.data }
            CameraLink.ACTION_LATEST -> { Nav.secure = i.getBooleanExtra("secure", false); Nav.fromApp = Nav.secure; editing = null; external = null; Nav.latest.value = true }
            "dev.wonderdesk.memories.FAVOURITES" -> { viewing = null; external = null; Nav.startTab.value = "favorites" }
        }
    }
    val latest by Nav.latest
    LaunchedEffect(latest, photos) {
        val list = photos ?: return@LaunchedEffect
        if (!latest) return@LaunchedEffect
        Nav.latest.value = false
        if (list.isEmpty()) return@LaunchedEffect
        val shown = if (Nav.secure) list.filter { it.taken >= System.currentTimeMillis() - 10 * 60_000 }.ifEmpty { list.take(1) } else list
        viewing = shown to 0
    }
    // A photo from the library (a camera's newest shot) opens in the full timeline, so you can swipe back through older ones.
    LaunchedEffect(external, photos) {
        val u = external ?: return@LaunchedEffect
        val list = photos ?: return@LaunchedEffect
        val id = u.lastPathSegment?.toLongOrNull() ?: return@LaunchedEffect
        if (u.authority != "media") return@LaunchedEffect
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) { viewing = (if (Nav.secure) list.filter { it.id == id || it.taken >= System.currentTimeMillis() - 10 * 60_000 } else list).let { l -> l to l.indexOfFirst { it.id == id } }; external = null }
    }
    fun closeExternal() { val back = Nav.fromApp; Nav.fromApp = false; Nav.secure = false; if (back) (context as Activity).finish() }
    editing?.let { u -> EditorScreen(u, onClose = { editing = null; tick++; if (viewing == null && external == null) closeExternal() }); return }
    external?.let { u -> SingleViewer(u, Nav.externalMime, onClose = { external = null; closeExternal() }, onEdit = { editing = u }); return }
    viewing?.let { (list, i) -> Viewer(list, i, onClose = { viewing = null; tick++; closeExternal() }, onEdit = { editing = it }, secure = Nav.secure); return }
    if (!ok) {
        WonderApp(appName = "Memories", about = ABOUT) {
            EmptyState("All your photos in one place", "Allow Memories to see the photos and videos on this phone. They stay on your phone.",
                action = { WButton("Allow access", { ask.launch(PERMS) }, icon = WonderIcons.PhotoLibrary, kind = ButtonKind.Primary) })
        }
        return
    }
    Library(photos, onOpen = { list, i -> viewing = list to i }, refresh = { tick++ })
}

/* -------------------------------------------------------- library ------------------------------------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(photos: List<Photo>?, onOpen: (List<Photo>, Int) -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var tab by rememberSaveable { mutableStateOf("photos") }
    var album by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var q by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var columns by rememberSaveable { mutableIntStateOf(context.prefs("memories").getInt("cols", 4)) }
    var moveTo by remember { mutableStateOf(false) }
    var pendingMove by remember { mutableStateOf<Pair<List<Uri>, String>?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var showClean by remember { mutableStateOf(false) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val dragScope = rememberCoroutineScope()
    val albumState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            pendingMove?.let { (uris, a) -> runCatching { Gallery.move(context, uris, a) }.onSuccess { toastRef.value("Moved to $a") }.onFailure { toastRef.value(it.message ?: "Couldn't move") } }
            selected = emptySet(); refresh()
        }
        pendingMove = null
    }
    val all = photos.orEmpty()
    val base = when {
        album != null -> all.filter { it.album == album }
        tab == "favorites" -> all.filter { it.favorite }
        tab == "videos" -> all.filter { it.video }
        else -> all
    }.filter { q.isBlank() || it.name.contains(q, true) || it.album.contains(q, true) || it.day.toString().contains(q) }
    val sel = base.filter { it.id in selected }
    val startTab by Nav.startTab
    LaunchedEffect(startTab) { startTab?.let { tab = it; album = null; Nav.startTab.value = null } }
    if (showTrash) { TrashScreen(onClose = { showTrash = false; refresh() }); return }
    if (showClean) { CleanUpScreen(photos.orEmpty(), onClose = { showClean = false; refresh() }); return }
    if (selected.isNotEmpty() || album != null) BackHandler { if (selected.isNotEmpty()) selected = emptySet() else album = null }
    WonderApp(needs = APP_NEEDS, appName = "Memories", about = ABOUT,
        tabs = if (selected.isNotEmpty()) emptyList() else listOf(WonderTab("photos", "Photos", WonderIcons.PhotoLibrary), WonderTab("albums", "Albums", WonderIcons.Collections),
            WonderTab("favorites", "Favourites", WonderIcons.Favorite), WonderTab("videos", "Videos", WonderIcons.Movie)),
        selectedTab = tab, onTab = { tab = it; album = null },
        title = when { selected.isNotEmpty() -> "${selected.size} selected"; album != null -> album!!; tab == "albums" -> "Albums"; tab == "favorites" -> "Favourites"; tab == "videos" -> "Videos"; else -> "Memories" },
        onBack = when { selected.isNotEmpty() -> ({ selected = emptySet() }); album != null -> ({ album = null }); else -> null },
        actions = {
            if (selected.isNotEmpty()) {
                ToolbarButton(WonderIcons.Share, "Share", {
                    context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sel.map { it.uri }))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) {}
                })
                ToolbarButton(WonderIcons.Favorite, "Favourite", { sender.launch(IntentSenderRequest.Builder(Gallery.favoriteRequest(context, sel.map { it.uri }, !sel.all { it.favorite }).intentSender).build()) })
                ToolbarButton(WonderIcons.DriveFileMove, "Move to album", { moveTo = true })
                ToolbarButton(WonderIcons.Delete, "Delete", { sender.launch(IntentSenderRequest.Builder(Gallery.trashRequest(context, sel.map { it.uri }).intentSender).build()) })
            } else {
                ToolbarButton(WonderIcons.Search, "Search", { searching = !searching; if (!searching) q = "" })
                if (tab != "albums" || album != null) ToolbarButton(if (columns > 3) WonderIcons.ZoomIn else WonderIcons.ZoomOut, "Grid size", { columns = if (columns >= 6) 3 else columns + 1; context.prefs("memories").edit().putInt("cols", columns).apply() })
            }
        },
        menu = if (selected.isNotEmpty()) listOf(WonderMenuItem("Select all", WonderIcons.SelectAll) { selected = base.map { it.id }.toSet() }) else listOf(
            WonderMenuItem("Slideshow", WonderIcons.Slideshow) { if (base.isNotEmpty()) onOpen(base.filter { !it.video }.ifEmpty { base }, -1) },
            WonderMenuItem("Free up space", WonderIcons.DeleteSweep) { showClean = true },
            WonderMenuItem("Trash", WonderIcons.Delete) { showTrash = true },
        ), settings = { MemoriesSettings() }) {
        toastRef.value = LocalToast.current
        if (photos == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }; return@WonderApp }
        Column(Modifier.fillMaxSize()) {
            if (searching) SearchField(q, { q = it }, "Search names, albums or dates (2024-07)", Modifier.padding(16.dp, 10.dp, 16.dp, 6.dp))
            if (tab == "albums" && album == null) {
                val albums = all.groupBy { it.album }.toList().sortedWith(compareByDescending<Pair<String, List<Photo>>> { it.first == "Camera" }.thenByDescending { it.second.size })
                ScrollToTopOnReselect(albumState)
                LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), state = albumState, contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "special") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Videos · ${all.count { it.video }}", false, { tab = "videos" }, icon = WonderIcons.Movie)
                            Chip("Favourites · ${all.count { it.favorite }}", false, { tab = "favorites" }, icon = WonderIcons.Favorite)
                            Chip("Trash", false, { showTrash = true }, icon = WonderIcons.Delete)
                        }
                    }
                    items(albums, key = { it.first }) { (name, list) ->
                        Column(Modifier.clickable { album = name }) {
                            Thumb(list.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)), 384)
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                            Text("${list.size}", style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
                        }
                    }
                }
                return@Column
            }
            if (base.isEmpty()) { EmptyState(if (q.isNotBlank()) "No matches" else if (tab == "favorites") "No favourites yet" else "Nothing here", if (tab == "favorites") "Tap the heart on any photo to keep it here." else "Photos you take or save appear here.", icon = WonderIcons.PhotoLibrary); return@Column }
            val groups = remember(base) { base.groupBy { it.day } }
            // The size you pick is for portrait; landscape and tablets fit more of the same size across.
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val across = (columns * conf.screenWidthDp / minOf(conf.screenWidthDp, conf.screenHeightDp).coerceAtLeast(1)).coerceIn(columns, 14)
            val showMemories = tab == "photos" && album == null && q.isBlank() && selected.isEmpty()
            val memories = remember(all, showMemories) { if (showMemories) onThisDay(all) else emptyList() }
            // Where each day starts in the grid, for the date shown while dragging the scroller.
            val starts = remember(groups, memories.isNotEmpty()) { var i = if (memories.isNotEmpty()) 1 else 0; groups.map { (d, l) -> (i to d).also { i += 1 + l.size } } }
            ScrollToTopOnReselect(gridState)
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                // Pinch the grid to make the photos bigger or smaller, like Google Photos.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var acc = 1f
                    do {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (ev.changes.count { it.pressed } >= 2) {
                            acc *= ev.calculateZoom()
                            if (acc > 1.3f && columns > 2) { columns--; acc = 1f; context.prefs("memories").edit().putInt("cols", columns).apply() }
                            if (acc < .77f && columns < 7) { columns++; acc = 1f; context.prefs("memories").edit().putInt("cols", columns).apply() }
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }) {
                PullToRefresh(onRefresh = { refresh(); delay(700) }) {
                    LazyVerticalGrid(GridCells.Fixed(across), Modifier.fillMaxSize().pointerInput(base) {
                        // Hold a photo, then slide across others to select a whole range (the grid scrolls at the edges).
                        fun idAt(o: Offset) = gridState.layoutInfo.visibleItemsInfo.firstOrNull { o.x >= it.offset.x && o.x < it.offset.x + it.size.width && o.y >= it.offset.y && o.y < it.offset.y + it.size.height }?.key as? Long
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            val early = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis + 60) {
                                while (true) {
                                    val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: return@withTimeoutOrNull true
                                    if (!c.pressed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                                }
                                @Suppress("UNREACHABLE_CODE") true
                            }
                            if (early != null) return@awaitEachGesture
                            val anchorId = idAt(down.position) ?: return@awaitEachGesture
                            val anchor = base.indexOfFirst { it.id == anchorId }.takeIf { it >= 0 } ?: return@awaitEachGesture
                            val start = selected
                            selected = start + anchorId
                            while (true) {
                                val c = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.firstOrNull() ?: break
                                if (!c.pressed) break
                                idAt(c.position)?.let { id -> val i = base.indexOfFirst { it.id == id }
                                    if (i >= 0) selected = start + base.subList(minOf(anchor, i), maxOf(anchor, i) + 1).map { it.id } }
                                val edge = 90f
                                if (c.position.y > size.height - edge) dragScope.launch { gridState.scrollBy(24f) } else if (c.position.y < edge) dragScope.launch { gridState.scrollBy(-24f) }
                                if (c.positionChanged()) c.consume()
                            }
                        }
                    }, state = gridState, contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (memories.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "memories") {
                            OnThisDayRow(memories) { list -> onOpen(list, 0) }
                        }
                        groups.forEach { (day, list) ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "d$day") {
                                Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(dayLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                    if (selected.isNotEmpty()) TextButton(onClick = { selected = if (list.all { it.id in selected }) selected - list.map { it.id }.toSet() else selected + list.map { it.id } }) {
                                        Text(if (list.all { it.id in selected }) "Deselect" else "Select") }
                                }
                            }
                            items(list, key = { it.id }) { ph ->
                                val isSel = ph.id in selected
                                val sc by androidx.compose.animation.core.animateFloatAsState(if (isSel) .86f else 1f, label = "sel")
                                Box(Modifier.aspectRatio(1f).combinedClickable(onClick = { if (selected.isNotEmpty()) selected = if (isSel) selected - ph.id else selected + ph.id else onOpen(base, base.indexOf(ph)) },
                                    onLongClick = { selected = selected + ph.id })) {
                                    Thumb(ph, Modifier.fillMaxSize().graphicsLayer { scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(if (isSel) 8.dp else 0.dp)))
                                    if (ph.video) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .35f)).padding(horizontal = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(WonderIcons.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text(time(ph.duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (ph.favorite) Icon(WonderIcons.Favorite, null, Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp), tint = Color.White)
                                    if (selected.isNotEmpty()) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(if (isSel) p.accent else Color.Black.copy(alpha = .3f)).border(2.dp, Color.White, CircleShape),
                                        contentAlignment = Alignment.Center) { if (isSel) Icon(WonderIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                    }
                }
                DateScroller(gridState, starts, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
    if (moveTo) {
        val albums = all.map { it.album }.distinct().sorted()
        var newName by remember { mutableStateOf("") }
        WonderDialog(onDismissRequest = { moveTo = false }, title = { Text("Move ${sel.size} to…") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(newName, { newName = it }, "New album", Modifier.weight(1f))
                    TextButton(enabled = newName.isNotBlank(), onClick = { moveTo = false; pendingMove = sel.map { it.uri } to newName.trim(); sender.launch(IntentSenderRequest.Builder(Gallery.writeRequest(context, sel.map { it.uri }).intentSender).build()) }) { Text("Create") }
                }
                Panel { albums.forEachIndexed { i, a -> if (i > 0) PanelDivider(16.dp); ListRow(a, icon = WonderIcons.Folder, tint = Color(0xFF4A90E2), onClick = {
                    moveTo = false; pendingMove = sel.map { it.uri } to a; sender.launch(IntentSenderRequest.Builder(Gallery.writeRequest(context, sel.map { it.uri }).intentSender).build()) }) } }
            }
        }, confirmButton = { TextButton(onClick = { moveTo = false }) { Text("Cancel") } })
    }
}

/* ------------------------------------------------ on this day, scroller, trash ------------------------------------------------ */

/** Photos taken on today's date (± a day) in earlier years, one group per year, most recent year first. */
private fun onThisDay(all: List<Photo>): List<Pair<Int, List<Photo>>> {
    val t = LocalDate.now()
    return all.filter { it.day.year < t.year && kotlin.math.abs(it.day.withYear(t.year).toEpochDay() - t.toEpochDay()) <= 1 }
        .groupBy { t.year - it.day.year }.toList().sortedBy { it.first }.map { (y, l) -> y to l.sortedBy { it.taken } }
}

@Composable
private fun OnThisDayRow(groups: List<Pair<Int, List<Photo>>>, onOpen: (List<Photo>) -> Unit) {
    val p = wonderPalette()
    Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
        Text("On this day", Modifier.padding(start = 12.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (years, list) ->
                Box(Modifier.size(132.dp, 176.dp).shadow(6.dp, RoundedCornerShape(p.panelRadius + 4.dp)).clip(RoundedCornerShape(p.panelRadius + 4.dp)).clickable { onOpen(list) }) {
                    Thumb(list.firstOrNull { !it.video } ?: list.first(), Modifier.fillMaxSize(), 384)
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .65f)), startY = 160f)))
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(if (years == 1) "1 year ago" else "$years years ago", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                        Text("${list.first().day.year} · ${list.size}", color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Drag the handle on the right edge to fly through years of photos; a bubble shows the month you're at. */
@Composable
private fun DateScroller(state: androidx.compose.foundation.lazy.grid.LazyGridState, starts: List<Pair<Int, LocalDate>>, modifier: Modifier) {
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val total = state.layoutInfo.totalItemsCount
    if (total < 120 || starts.size < 3) return
    val scrolling = state.isScrollInProgress || dragging != null
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) { if (scrolling) shown = true else { delay(1400); shown = false } }
    val frac = dragging ?: (state.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1))
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "scroller")
    if (alpha == 0f && !shown) return
    BoxWithConstraints(modifier.fillMaxHeight().width(120.dp).padding(vertical = 16.dp)) {
        val track = constraints.maxHeight.toFloat()
        val thumbPx = with(androidx.compose.ui.platform.LocalDensity.current) { 44.dp.toPx() }
        val y = (track - thumbPx) * frac.coerceIn(0f, 1f)
        val day = starts.lastOrNull { it.first <= (frac * (total - 1)).toInt() }?.second
        Row(Modifier.align(Alignment.TopEnd).graphicsLayer { translationY = y; this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically) {
            if (dragging != null && day != null) Text(day.format(DateTimeFormatter.ofPattern("MMM yyyy")), Modifier.padding(end = 8.dp).shadow(4.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).background(p.accent).padding(horizontal = 12.dp, vertical = 6.dp), color = p.onAccent, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.size(28.dp, 44.dp).shadow(4.dp, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)).clip(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                .background(p.panel).border(1.dp, p.panelBorder, RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp))
                .pointerInput(total) {
                    detectVerticalDragGestures(onDragStart = { dragging = frac }, onDragEnd = { dragging = null }, onDragCancel = { dragging = null }) { ch, d ->
                        ch.consume()
                        val f = ((dragging ?: frac) + d / (track - thumbPx)).coerceIn(0f, 1f); dragging = f
                        scope.launch { state.scrollToItem((f * (total - 1)).toInt()) }
                    }
                }, contentAlignment = Alignment.Center) { Icon(WonderIcons.SwapVert, "Scroll through dates", Modifier.size(18.dp), tint = p.secondaryText) }
        }
    }
}

/** Android's trash: everything deleted in the last 30 days, with restore and delete-for-good. */
@Composable
private fun TrashScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var items by remember { mutableStateOf<List<Photo>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(tick) { items = withContext(Dispatchers.IO) { Gallery.trashed(context) }; selected = emptySet() }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tick++ }
    val list = items.orEmpty()
    val sel = list.filter { it.id in selected }.ifEmpty { list }
    WonderApp(appName = "Memories", about = ABOUT, title = if (selected.isEmpty()) "Trash" else "${selected.size} selected", subtitle = if (selected.isEmpty() && list.isNotEmpty()) "${list.size} items · removed for good after 30 days" else null,
        onBack = { if (selected.isNotEmpty()) selected = emptySet() else onClose() },
        actions = {
            if (list.isNotEmpty()) {
                TextButton(onClick = { sender.launch(IntentSenderRequest.Builder(Gallery.trashRequest(context, sel.map { it.uri }, false).intentSender).build()) }) {
                    Text(if (selected.isEmpty()) "Restore all" else "Restore", color = p.toolbarText) }
                ToolbarButton(WonderIcons.Delete, if (selected.isEmpty()) "Empty trash" else "Delete for good", { sender.launch(IntentSenderRequest.Builder(Gallery.deleteRequest(context, sel.map { it.uri }).intentSender).build()) })
            }
        }) {
        when {
            items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
            list.isEmpty() -> EmptyState("Trash is empty", "Photos and videos you delete stay here for 30 days, so you can bring them back.", icon = WonderIcons.Delete)
            else -> LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Tap items to choose some, or use Restore all / Empty trash.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                }
                items(list, key = { it.id }) { ph ->
                    val on = ph.id in selected
                    val days = ((ph.taken - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                    Box(Modifier.aspectRatio(1f).clickable { selected = if (on) selected - ph.id else selected + ph.id }) {
                        Thumb(ph, Modifier.fillMaxSize().graphicsLayer { val sc = if (on) .86f else 1f; scaleX = sc; scaleY = sc; alpha = if (on) 1f else .85f }.clip(RoundedCornerShape(if (on) 8.dp else 0.dp)))
                        Text(if (days == 0L) "Today" else "$days d", Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 4.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                        if (ph.video) Icon(WonderIcons.PlayArrow, null, Modifier.align(Alignment.BottomStart).padding(4.dp).size(16.dp), tint = Color.White)
                        if (on) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(p.accent).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(WonderIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
    }
}

/** Memories' own section in Settings: opening photos from other apps and the camera. */
@Composable
private fun ColumnScope.MemoriesSettings() {
    val context = LocalContext.current
    val p = wonderPalette()
    var guide by remember { mutableStateOf(false) }
    val resumed = rememberIsResumed()
    var linked by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) { linked = SpecialAccess.granted(context, SpecialAccess.Kind.ACCESSIBILITY, CameraLink::class.java) }
    val photosOff = remember(resumed) { runCatching { !context.packageManager.getApplicationInfo("com.google.android.apps.photos", 0).enabled }.getOrDefault(true) }
    Panel(title = "Opening photos", footer = "Files, chats and most camera apps open pictures and videos in Memories. When Android asks which app to use, pick Memories and “Always”.") {
        ListRow("Choose default apps", subtitle = "If another gallery is set to always open photos, clear it in its App info › Open by default", icon = WonderIcons.OpenInNew, tint = p.accent, chevron = true,
            onClick = { context.launchSafely(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) { SpecialAccess.openAppInfo(context) } })
        PanelDivider()
        ListRow("Open from Pixel Camera", subtitle = when {
                linked -> "On: tapping the photo in the corner of Pixel Camera opens it in Memories"
                photosOff -> "Pixel Camera only opens Google Photos. Turn this on so its corner photo opens Memories instead"
                else -> "Pixel Camera opens Google Photos while it's turned on. This takes over once Google Photos is off"
            }, icon = WonderIcons.PhotoCamera, tint = Color(0xFF2E9E44),
            trailing = { Switch(linked, { if (it) guide = true else SpecialAccess.open(context, SpecialAccess.Kind.ACCESSIBILITY, CameraLink::class.java) }) },
            onClick = { if (linked) SpecialAccess.open(context, SpecialAccess.Kind.ACCESSIBILITY, CameraLink::class.java) else guide = true })
        PanelDivider()
        ListRow("Newest photo shortcut", subtitle = "Hold the Memories icon on your home screen for “Newest photo”", icon = WonderIcons.PhotoLibrary, tint = Color(0xFF8E44AD))
    }
    if (guide) AccessGuide(SpecialAccess.Kind.ACCESSIBILITY, "Pixel Camera is built to open Google Photos only. With this switch on, Memories notices when you tap the photo in the corner of Pixel Camera and opens your newest shot itself. It only watches Pixel Camera, and only for that tap.",
        onDismiss = { guide = false }, component = CameraLink::class.java, onGranted = { linked = true })
}

private fun dayLabel(d: LocalDate): String { val t = LocalDate.now()
    return when (d) { t -> "Today"; t.minusDays(1) -> "Yesterday"; else -> d.format(DateTimeFormatter.ofPattern(if (d.year == t.year) "EEEE, d MMMM" else "d MMMM yyyy")) } }
private fun time(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

@Composable
private fun Thumb(ph: Photo, modifier: Modifier, px: Int = 256) {
    val context = LocalContext.current
    var b by remember(ph.id, px) { mutableStateOf(Gallery.cached(ph, px)) }
    LaunchedEffect(ph.id, px) { if (b == null) b = withContext(Gallery.loader) { Gallery.thumb(context, ph, px) } }
    val img = remember(b) { b?.asImageBitmap() }
    Box(modifier.background(Color(0x22888888))) { img?.let { Image(it, ph.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

/* -------------------------------------------------------- viewer -------------------------------------------- */

private fun Context.hasApp(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/**
 * Full-screen viewer in the suite look: glass title bar and dock, a filmstrip to jump around, photos that zoom
 * and swipe away to close, and videos that play right here with the same controls as Cinema.
 * [secure]: opened from the camera over the lock screen, so only viewing is allowed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(list: List<Photo>, start: Int, onClose: () -> Unit, onEdit: (Uri) -> Unit, secure: Boolean = false) = WonderKitTheme {
    val context = LocalContext.current
    val activity = context as Activity
    val c = rememberPlayerChrome()
    val scope = rememberCoroutineScope()
    val gone = remember { mutableStateListOf<Long>() }
    val favs = remember { mutableStateMapOf<Long, Boolean>() }
    val items = remember(list, gone.size) { list.filter { it.id !in gone } }
    val pager = rememberPagerState(start.coerceIn(0, (list.size - 1).coerceAtLeast(0))) { items.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var info by rememberSaveable { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(start < 0) }
    var fade by remember { mutableFloatStateOf(1f) }
    var pending by remember { mutableStateOf<Pair<String, Photo>?>(null) }
    val current = items.getOrNull(pager.currentPage)
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val (what, ph) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        when (what) {
            "fav" -> favs[ph.id] = !(favs[ph.id] ?: ph.favorite)
            "trash" -> { gone += ph.id; if (gone.size >= list.size) onClose() }
        }
    }
    fun isFav(ph: Photo) = favs[ph.id] ?: ph.favorite
    LaunchedEffect(slideshow) { while (slideshow) { delay(3500); if (pager.currentPage < items.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false } }
    // Hide the status and navigation bars along with the controls, like a proper photo viewer.
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(Unit) {
        if (secure && Build.VERSION.SDK_INT >= 27) activity.setShowWhenLocked(true)
        onDispose {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, view).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            if (secure && Build.VERSION.SDK_INT >= 27) activity.setShowWhenLocked(false)
        }
    }
    LaunchedEffect(chrome) {
        val ctl = androidx.core.view.WindowCompat.getInsetsController(activity.window, view)
        ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) ctl.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else ctl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    BackHandler { if (info) info = false else onClose() }
    Box(Modifier.fillMaxSize().viewerBackdrop(c, fade)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { items[it].id }, beyondBoundsPageCount = 1) { i ->
            val ph = items[i]
            if (ph.video) WonderVideo(ph.uri, active = pager.currentPage == i && !pager.isScrollInProgress, controls = chrome, onToggleControls = { chrome = it; slideshow = false },
                bottomSpace = if (secure) 0.dp else 150.dp)
            else ZoomImage(ph, onTap = { chrome = !chrome; slideshow = false }, onDismiss = onClose, onFade = { fade = 1f - it * .85f; if (it > .05f) chrome = false })
        }
        AnimatedVisibility(chrome && current != null, Modifier.align(Alignment.TopCenter), enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
            val ph = current ?: return@AnimatedVisibility
            ViewerTopBar(dayLabel(ph.day), java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(ph.taken)) + " · " + ph.album +
                if (items.size > 1) " · ${pager.currentPage + 1} of ${items.size}" else "", onBack = onClose) {
                if (!ph.video) HudButton(WonderIcons.Slideshow, if (slideshow) "Stop slideshow" else "Slideshow", c, 44.dp, tint = if (slideshow) c.accent else c.ink) { slideshow = !slideshow; chrome = !slideshow }
                HudButton(WonderIcons.Info, "Details", c, 44.dp) { info = true }
            }
        }
        AnimatedVisibility(chrome && current != null && !secure, Modifier.align(Alignment.BottomCenter), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
            val ph = current ?: return@AnimatedVisibility
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (items.size > 1) Filmstrip(items, pager.currentPage) { i -> slideshow = false; scope.launch { pager.animateScrollToPage(i) } }
                ViewerDock {
                    ViewerAction(WonderIcons.Share, "Share") { context.share(ph.uri, ph.mime.ifBlank { if (ph.video) "video/*" else "image/*" }) {} }
                    ViewerAction(if (isFav(ph)) WonderIcons.Favorite else WonderIcons.FavoriteBorder, "Favourite", active = isFav(ph)) {
                        pending = "fav" to ph; sender.launch(IntentSenderRequest.Builder(Gallery.favoriteRequest(context, listOf(ph.uri), !isFav(ph)).intentSender).build()) }
                    if (!ph.video) ViewerAction(WonderIcons.Edit, "Edit") { onEdit(ph.uri) }
                    else if (context.hasApp("dev.wonderdesk.cinema")) ViewerAction(WonderIcons.ContentCut, "Edit") {
                        context.launchSafely(Intent("dev.wonderdesk.cinema.EDIT").setPackage("dev.wonderdesk.cinema").setDataAndType(ph.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                            context.launchSafely(Intent(Intent.ACTION_VIEW).setPackage("dev.wonderdesk.cinema").setDataAndType(ph.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) {}
                        }
                    }
                    ViewerAction(WonderIcons.Delete, "Delete", danger = true) { pending = "trash" to ph; sender.launch(IntentSenderRequest.Builder(Gallery.trashRequest(context, listOf(ph.uri)).intentSender).build()) }
                }
            }
        }
    }
    if (info && current != null) InfoDialog(current, onDismiss = { info = false })
}

/** A strip of small thumbnails under the photo; the current one is framed in the look's accent. */
@Composable
private fun Filmstrip(items: List<Photo>, current: Int, onPick: (Int) -> Unit) {
    val c = rememberPlayerChrome()
    val state = androidx.compose.foundation.lazy.rememberLazyListState(maxOf(0, current - 3))
    LaunchedEffect(current) { val vis = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(7); state.animateScrollToItem(maxOf(0, current - vis / 2)) }
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth().height(52.dp), state = state, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items.size, key = { items[it].id }) { i ->
            val on = i == current
            Box(Modifier.size(if (on) 52.dp else 42.dp, 52.dp).padding(vertical = if (on) 0.dp else 5.dp).clip(RoundedCornerShape(6.dp))
                .border(if (on) 2.dp else 0.dp, if (on) c.accent else Color.Transparent, RoundedCornerShape(6.dp)).clickable { onPick(i) }) {
                Thumb(items[i], Modifier.fillMaxSize(), 128)
                if (items[i].video) Icon(WonderIcons.PlayArrow, null, Modifier.align(Alignment.Center).size(16.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun ZoomImage(ph: Photo, onTap: () -> Unit, onDismiss: (() -> Unit)? = null, onFade: (Float) -> Unit = {}) {
    val context = LocalContext.current
    var bmp by remember(ph.id) { mutableStateOf(Gallery.cached(ph, 512) ?: Gallery.cached(ph, 256)) }
    LaunchedEffect(ph.id) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { Gallery.thumb(context, ph, 512) }
        bmp = withContext(Dispatchers.IO) { Gallery.load(context, ph.uri) ?: bmp }
    }
    val img = remember(bmp) { bmp?.asImageBitmap() }
    ZoomableImage(img, ph.name, onTap = onTap, onDismiss = onDismiss, onDismissProgress = onFade)
}

@Composable
private fun InfoDialog(ph: Photo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var info by remember { mutableStateOf<Gallery.Info?>(null) }
    LaunchedEffect(ph.id) { info = withContext(Dispatchers.IO) { Gallery.info(context, ph) } }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Details") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            info?.rows?.forEach { (k, v) -> Row { Text(k, Modifier.width(90.dp), color = p.secondaryText); Text(v) } } ?: AquaProgress(null, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Panel {
                info?.lat?.let { lat -> ListRow("Show on map", icon = WonderIcons.LocationOn, tint = Color(0xFF2E9E44), onClick = { context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,${info!!.lon}?q=$lat,${info!!.lon}"))) {} }); PanelDivider() }
                ListRow("Show in Wayfinder", icon = WonderIcons.FolderOpen, tint = Color(0xFF4A90E2), onClick = {
                    context.launchSafely(Intent("dev.wonderdesk.wayfinder.SHOW").setPackage("dev.wonderdesk.wayfinder").putExtra("path", ph.path)) {}
                })
                if (!ph.video) { PanelDivider(); ListRow("Set as wallpaper", icon = WonderIcons.Wallpaper, tint = Color(0xFF8E44AD), onClick = {
                    val attach = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(ph.uri, "image/*").putExtra("mimeType", "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val crop = runCatching { WallpaperManager.getInstance(context).getCropAndSetWallpaperIntent(ph.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }.getOrNull()
                    if (crop != null) context.launchSafely(crop) { context.launchSafely(Intent.createChooser(attach, "Set as")) {} }
                    else context.launchSafely(Intent.createChooser(attach, "Set as")) {}
                }) }
                PanelDivider()
                ListRow("Copy to clipboard", icon = WonderIcons.ContentCopy, tint = Color(0xFF1E7FE8), onClick = {
                    runCatching { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newUri(context.contentResolver, ph.name, ph.uri)) }
                    onDismiss()
                })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

/** A photo or video opened from another app (Files, a chat, a camera) that isn't in the library. */
@Composable
private fun SingleViewer(uri: Uri, mime: String?, onClose: () -> Unit, onEdit: () -> Unit) = WonderKitTheme {
    val context = LocalContext.current
    val c = rememberPlayerChrome()
    val video = mime?.startsWith("video") == true || (mime == null && context.contentResolver.getType(uri)?.startsWith("video") == true)
    val ph = remember(uri) { Photo(uri.hashCode().toLong(), uri, context.displayName(uri), System.currentTimeMillis(), 0, 0, 0, "", "", video, 0, false, mime ?: if (video) "video/*" else "image/*") }
    var chrome by remember { mutableStateOf(true) }
    var fade by remember { mutableFloatStateOf(1f) }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().viewerBackdrop(c, fade)) {
        if (video) WonderVideo(uri, active = true, controls = chrome, onToggleControls = { chrome = it })
        else ZoomImage(ph, onTap = { chrome = !chrome }, onDismiss = onClose, onFade = { fade = 1f - it * .85f })
        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(ph.name, null, onBack = onClose) {
                HudButton(WonderIcons.Share, "Share", c, 44.dp) { context.share(uri, ph.mime) {} }
                if (!video) HudButton(WonderIcons.Edit, "Edit", c, 44.dp) { onEdit() }
            }
        }
    }
}


/* -------------------------------------------------------- editor -------------------------------------------- */

private enum class EditTab(val label: String) { Crop("Crop"), Adjust("Adjust"), Filters("Filters"), Markup("Markup") }

@Composable
private fun EditorScreen(uri: Uri, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var tab by remember { mutableStateOf(EditTab.Crop) }
    var rotate by remember { mutableIntStateOf(0) }
    var flip by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }
    var aspect by remember { mutableStateOf<Float?>(null) }
    var adjust by remember { mutableStateOf(Adjust()) }
    var filter by remember { mutableStateOf(Filter.None) }
    val strokes = remember { mutableStateListOf<Markup>() }
    var penColor by remember { mutableIntStateOf(0xFFE5483C.toInt()) }
    var penWidth by remember { mutableFloatStateOf(.012f) }
    var saving by remember { mutableStateOf(false) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    LaunchedEffect(uri) { src = withContext(Dispatchers.IO) { Gallery.load(context, uri, 2048) } }
    // Preview: rotated/flipped source; crop overlay shows the kept area; markup draws inside the crop.
    val preview = remember(src, rotate, flip) { src?.let { s -> Bitmap.createBitmap(s, 0, 0, s.width, s.height, android.graphics.Matrix().apply { postRotate(rotate.toFloat()); if (flip) postScale(-1f, 1f) }, true) } }
    WonderApp(appName = "Memories", about = ABOUT, title = "Edit", onBack = onClose, actions = {
        TextButton(enabled = src != null && !saving, onClick = {
            val s = src ?: return@TextButton
            saving = true
            scope.launch {
                val r = withContext(Dispatchers.Default) { runCatching {
                    val out = Edits.render(s, rotate, flip, crop, adjust, filter, strokes.toList())
                    Gallery.saveCopy(context, out, context.displayName(uri).substringBeforeLast('.') + " (edited).jpg")
                } }
                saving = false
                r.onSuccess { toastRef.value("Saved a copy in Pictures/Memories"); onClose() }.onFailure { toastRef.value(it.message ?: "Couldn't save") }
            }
        }) { Text(if (saving) "Saving…" else "Save copy", color = p.toolbarText, style = MaterialTheme.typography.titleMedium) }
    }) {
        toastRef.value = LocalToast.current
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().viewerBackdrop(rememberPlayerChrome()).padding(16.dp), contentAlignment = Alignment.Center) {
                val bmp = preview
                if (bmp == null) CircularProgressIndicator(color = p.accent) else {
                    val ratio = bmp.width.toFloat() / bmp.height
                    var box by remember { mutableStateOf(IntSize(1, 1)) }
                    var live by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
                    Box(Modifier.aspectRatio(ratio).onSizeChanged { box = it }) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.colorMatrix(ColorMatrix(Edits.matrix(adjust, filter).array)))
                        Canvas(Modifier.fillMaxSize()
                            .pointerInput(tab, aspect, ratio) {
                                if (tab == EditTab.Crop) detectDragGestures { ch, d ->
                                    val pos = ch.position; val w = size.width.toFloat(); val h = size.height.toFloat()
                                    val c = RectF(crop); val dx = d.x / w; val dy = d.y / h
                                    val nearL = kotlin.math.abs(pos.x / w - c.left) < .12f; val nearR = kotlin.math.abs(pos.x / w - c.right) < .12f
                                    val nearT = kotlin.math.abs(pos.y / h - c.top) < .12f; val nearB = kotlin.math.abs(pos.y / h - c.bottom) < .12f
                                    if (!(nearL || nearR || nearT || nearB)) c.offset(dx.coerceIn(-c.left, 1 - c.right), dy.coerceIn(-c.top, 1 - c.bottom)) else {
                                        if (nearL) c.left = (c.left + dx).coerceIn(0f, c.right - .1f); if (nearR) c.right = (c.right + dx).coerceIn(c.left + .1f, 1f)
                                        if (nearT) c.top = (c.top + dy).coerceIn(0f, c.bottom - .1f); if (nearB) c.bottom = (c.bottom + dy).coerceIn(c.top + .1f, 1f)
                                        aspect?.let { a -> val wantH = c.width() * ratio / a; c.bottom = (c.top + wantH).coerceAtMost(1f) }
                                    }
                                    crop = c; ch.consume()
                                } else if (tab == EditTab.Markup) detectDragGestures(
                                    onDragStart = { o -> live = listOf(o.x / size.width to o.y / size.height) },
                                    onDrag = { ch, _ -> live = live + (ch.position.x / size.width to ch.position.y / size.height) },
                                    onDragEnd = {
                                        // store relative to the crop so it lines up with the saved image
                                        strokes += Markup(penColor, penWidth / crop.width(), live.map { (x, y) -> (x - crop.left) / crop.width() to (y - crop.top) / crop.height() }); live = emptyList()
                                    })
                            }) {
                            val w = size.width; val h = size.height
                            val l = crop.left * w; val t = crop.top * h; val r = crop.right * w; val b = crop.bottom * h
                            val dim = Color.Black.copy(alpha = .55f)
                            drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(w, t)); drawRect(dim, Offset(0f, b), androidx.compose.ui.geometry.Size(w, h - b))
                            drawRect(dim, Offset(0f, t), androidx.compose.ui.geometry.Size(l, b - t)); drawRect(dim, Offset(r, t), androidx.compose.ui.geometry.Size(w - r, b - t))
                            if (tab == EditTab.Crop) {
                                drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), style = Stroke(3f))
                                for (k in 1..2) { drawLine(Color.White.copy(alpha = .5f), Offset(l + (r - l) * k / 3, t), Offset(l + (r - l) * k / 3, b), 1.5f); drawLine(Color.White.copy(alpha = .5f), Offset(l, t + (b - t) * k / 3), Offset(r, t + (b - t) * k / 3), 1.5f) }
                            }
                            fun drawStroke(color: Int, width: Float, pts: List<Pair<Float, Float>>) {
                                val path = Path(); pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
                                drawPath(path, Color(color), style = Stroke(width * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                            strokes.forEach { s -> drawStroke(s.color, s.width * crop.width(), s.points.map { (x, y) -> crop.left + x * crop.width() to crop.top + y * crop.height() }) }
                            if (live.size > 1) drawStroke(penColor, penWidth, live)
                        }
                    }
                }
            }
            Column(Modifier.background(p.panel).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    EditTab.Crop -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf<Pair<String, Float?>>("Free" to null, "Square" to 1f, "4:3" to 4f / 3, "3:2" to 1.5f, "16:9" to 16f / 9, "9:16" to 9f / 16).forEach { (l, a) ->
                                Chip(l, aspect == a, {
                                    aspect = a
                                    if (a != null) preview?.let { bmp -> val ratio = bmp.width.toFloat() / bmp.height
                                        val wFrac = if (a > ratio) 1f else a / ratio; val hFrac = if (a > ratio) ratio / a else 1f
                                        crop = RectF((1 - wFrac) / 2, (1 - hFrac) / 2, (1 + wFrac) / 2, (1 + hFrac) / 2) } else crop = RectF(0f, 0f, 1f, 1f)
                                })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WButton("Rotate", { rotate = (rotate + 90) % 360; crop = RectF(0f, 0f, 1f, 1f) }, icon = WonderIcons.RotateRight)
                            WButton("Flip", { flip = !flip }, icon = WonderIcons.SwapHoriz)
                            WButton("Reset", { rotate = 0; flip = false; crop = RectF(0f, 0f, 1f, 1f); aspect = null }, kind = ButtonKind.Plain)
                        }
                    }
                    EditTab.Adjust -> {
                        listOf(Triple("Brightness", adjust.brightness) { v: Float -> adjust = adjust.copy(brightness = v) }, Triple("Contrast", adjust.contrast) { v: Float -> adjust = adjust.copy(contrast = v) },
                            Triple("Saturation", adjust.saturation) { v: Float -> adjust = adjust.copy(saturation = v) }, Triple("Warmth", adjust.warmth) { v: Float -> adjust = adjust.copy(warmth = v) }).forEach { (l, v, set) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(l, Modifier.width(90.dp), style = MaterialTheme.typography.labelLarge)
                                Slider(v, set, Modifier.weight(1f), valueRange = -1f..1f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                                Text("${(v * 100).toInt()}", Modifier.width(36.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    EditTab.Filters -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val small = remember(preview) { preview?.let { Bitmap.createScaledBitmap(it, 160, (160f * it.height / it.width).toInt().coerceAtLeast(1), true) } }
                        Filter.entries.forEach { f ->
                            Column(Modifier.clickable { filter = f }, horizontalAlignment = Alignment.CenterHorizontally) {
                                small?.let { Image(it.asImageBitmap(), f.label, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).border(if (filter == f) 3.dp else 0.dp, p.accent, RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop, colorFilter = ColorFilter.colorMatrix(ColorMatrix(Edits.matrix(adjust, f).array))) }
                                Text(f.label, style = MaterialTheme.typography.labelSmall, color = if (filter == f) p.accent else p.text)
                            }
                        }
                    }
                    EditTab.Markup -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(0xFFE5483C, 0xFFF2A516, 0xFF2E9E44, 0xFF1E7FE8, 0xFFFFFFFF, 0xFF1B1D21).forEach { c ->
                                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (penColor == c.toInt()) 3.dp else 1.dp, if (penColor == c.toInt()) p.accent else p.panelBorder, CircleShape).clickable { penColor = c.toInt() })
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Icon(WonderIcons.Undo, "Undo") }
                        }
                        Slider(penWidth, { penWidth = it }, valueRange = 0.004f..0.04f, colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                    }
                }
                Segmented(EditTab.entries.map { it.label }, tab.ordinal) { tab = EditTab.entries[it] }
            }
        }
    }
}


/**
 * Free up space: big videos, old screenshots and exact duplicates, biggest savings first. Everything chosen goes to the
 * Trash (30 days to change your mind), never straight to deletion.
 */
@Composable
private fun CleanUpScreen(all: List<Photo>, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val monthAgo = System.currentTimeMillis() - 30L * 86_400_000L
    val groups = remember(all) {
        val big = all.filter { it.video && it.size > 100L * 1024 * 1024 }.sortedByDescending { it.size }
        val shots = all.filter { (it.album.contains("screenshot", true) || it.relPath.contains("Screenshots", true)) && it.taken < monthAgo }
        // Exact duplicates: same size and same picture size; keep the oldest, offer the rest.
        val dups = all.groupBy { Triple(it.size, it.width, it.height) }.values.filter { it.size > 1 && it.first().size > 0 }.flatMap { g -> g.sortedBy { it.taken }.drop(1) }
        listOf(Triple("Duplicates", "Exact copies; the first one is kept", dups), Triple("Large videos", "Over 100 MB each", big), Triple("Old screenshots", "Taken more than a month ago", shots)).filter { it.third.isNotEmpty() }
    }
    var chosen by remember(all) { mutableStateOf(groups.firstOrNull { it.first == "Duplicates" }?.third?.map { it.id }?.toSet() ?: emptySet()) }
    val sel = all.filter { it.id in chosen }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { if (it.resultCode == Activity.RESULT_OK) onClose() }
    WonderApp(appName = "Memories", about = ABOUT, title = "Free up space", subtitle = if (sel.isNotEmpty()) "${sel.size} chosen · ${formatBytes(sel.sumOf { it.size })}" else null, onBack = onClose,
        actions = { if (sel.isNotEmpty()) TextButton(onClick = { sender.launch(IntentSenderRequest.Builder(Gallery.trashRequest(context, sel.map { it.uri }).intentSender).build()) }) { Text("Move to Trash", color = p.toolbarText) } }) {
        if (groups.isEmpty()) { EmptyState("Nothing to clear", "No duplicates, huge videos or old screenshots. Nice and tidy.", icon = WonderIcons.CheckCircle); return@WonderApp }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            groups.forEach { (title, why, list) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "h$title") {
                    Row(Modifier.padding(6.dp, 14.dp, 6.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("$title · ${formatBytes(list.sumOf { it.size })}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text(why, style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
                        }
                        val allOn = list.all { it.id in chosen }
                        TextButton(onClick = { chosen = if (allOn) chosen - list.map { it.id }.toSet() else chosen + list.map { it.id } }) { Text(if (allOn) "Deselect" else "Select all") }
                    }
                }
                items(list, key = { title + it.id }) { ph ->
                    val on = ph.id in chosen
                    Box(Modifier.aspectRatio(1f).clickable { chosen = if (on) chosen - ph.id else chosen + ph.id }) {
                        Thumb(ph, Modifier.fillMaxSize().graphicsLayer { val sc = if (on) .88f else 1f; scaleX = sc; scaleY = sc }.clip(RoundedCornerShape(if (on) 8.dp else 0.dp)))
                        Text(formatBytes(ph.size), Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 4.dp),
                            color = Color.White, style = MaterialTheme.typography.labelSmall)
                        if (on) Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(p.accent).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(WonderIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
        }
    }
}
