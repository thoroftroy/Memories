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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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

private const val ABOUT = "Memories shows every photo and video on your phone in a timeline and by album. View with pinch zoom, see camera details and location, " +
    "mark favourites, move pictures between albums and folders, and edit: crop, rotate, filters, colour adjustments and drawing. Edits are saved as copies."

private val PERMS = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
private fun granted(c: Context) = PERMS.all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED } ||
    (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(c, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)

/** Where you are in the app; survives the window being rebuilt. */
private object Nav {
    val viewing = mutableStateOf<Pair<List<Photo>, Int>?>(null)
    val editing = mutableStateOf<Uri?>(null)
    val external = mutableStateOf<Uri?>(null)
}

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
        when (i?.action) { Intent.ACTION_VIEW -> external = i.data; Intent.ACTION_EDIT -> editing = i.data }
    }
    editing?.let { u -> EditorScreen(u, onClose = { editing = null; tick++ }); return }
    external?.let { u -> SingleViewer(u, onClose = { external = null; (context as Activity).takeIf { photos == null }?.finish() }, onEdit = { editing = u }); return }
    viewing?.let { (list, i) -> Viewer(list, i, onClose = { viewing = null; tick++ }, onEdit = { editing = it }); return }
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
    if (selected.isNotEmpty() || album != null) BackHandler { if (selected.isNotEmpty()) selected = emptySet() else album = null }
    WonderApp(appName = "Memories", about = ABOUT,
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
        )) {
        toastRef.value = LocalToast.current
        if (photos == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }; return@WonderApp }
        Column(Modifier.fillMaxSize()) {
            if (searching) SearchField(q, { q = it }, "Search names, albums or dates (2024-07)", Modifier.padding(16.dp, 10.dp, 16.dp, 6.dp))
            if (tab == "albums" && album == null) {
                val albums = all.groupBy { it.album }.toList().sortedWith(compareByDescending<Pair<String, List<Photo>>> { it.first == "Camera" }.thenByDescending { it.second.size })
                LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
            val groups = base.groupBy { it.day }
            // The size you pick is for portrait; landscape and tablets fit more of the same size across.
            val conf = androidx.compose.ui.platform.LocalConfiguration.current
            val across = (columns * conf.screenWidthDp / minOf(conf.screenWidthDp, conf.screenHeightDp).coerceAtLeast(1)).coerceIn(columns, 14)
            LazyVerticalGrid(GridCells.Fixed(across), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { (day, list) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "d$day") {
                        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(dayLabel(day), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            if (selected.isNotEmpty()) TextButton(onClick = { selected = selected + list.map { it.id } }) { Text("Select") }
                        }
                    }
                    items(list, key = { it.id }) { ph ->
                        val isSel = ph.id in selected
                        Box(Modifier.aspectRatio(1f).combinedClickable(onClick = { if (selected.isNotEmpty()) selected = if (isSel) selected - ph.id else selected + ph.id else onOpen(base, base.indexOf(ph)) },
                            onLongClick = { selected = selected + ph.id })) {
                            Thumb(ph, Modifier.fillMaxSize().graphicsLayer { val s = if (isSel) .86f else 1f; scaleX = s; scaleY = s }.clip(RoundedCornerShape(if (isSel) 8.dp else 0.dp)))
                            if (ph.video) Row(Modifier.align(Alignment.BottomEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(WonderIcons.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(list: List<Photo>, start: Int, onClose: () -> Unit, onEdit: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(start.coerceAtLeast(0)) { list.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var info by rememberSaveable { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(start < 0) }
    val current = list.getOrNull(pager.currentPage)
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { if (it.resultCode == Activity.RESULT_OK) onClose() }
    LaunchedEffect(slideshow) { while (slideshow) { delay(3500); if (pager.currentPage < list.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false } }
    BackHandler { if (info) info = false else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].id }) { i -> ZoomImage(list[i], onTap = { chrome = !chrome; slideshow = false }) }
        if (chrome && current != null) {
            Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent))).statusBarsPadding().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(WonderIcons.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(dayLabel(current.day), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(current.taken)) + " · " + current.album, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = { slideshow = !slideshow; chrome = !slideshow }) { Icon(WonderIcons.Slideshow, "Slideshow", tint = Color.White) }
                IconButton(onClick = { info = true }) { Icon(WonderIcons.Info, "Details", tint = Color.White) }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))).navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                BarButton(WonderIcons.Share, "Share") { context.share(current.uri, current.mime.ifBlank { "image/*" }) {} }
                BarButton(if (current.favorite) WonderIcons.Favorite else WonderIcons.FavoriteBorder, "Favourite") { sender.launch(IntentSenderRequest.Builder(Gallery.favoriteRequest(context, listOf(current.uri), !current.favorite).intentSender).build()) }
                if (!current.video) BarButton(WonderIcons.Edit, "Edit") { onEdit(current.uri) }
                if (current.video) BarButton(WonderIcons.PlayArrow, "Play") { context.launchSafely(Intent(Intent.ACTION_VIEW).setDataAndType(current.uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) {} }
                BarButton(WonderIcons.Delete, "Delete") { sender.launch(IntentSenderRequest.Builder(Gallery.trashRequest(context, listOf(current.uri)).intentSender).build()) }
            }
        }
    }
    if (info && current != null) InfoDialog(current, onDismiss = { info = false })
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White); Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomImage(ph: Photo, onTap: () -> Unit) {
    val context = LocalContext.current
    var bmp by remember(ph.id) { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(ph.id) { bmp = withContext(Dispatchers.IO) { Gallery.thumb(context, ph, 512) }; bmp = withContext(Dispatchers.IO) { if (ph.video) Gallery.thumb(context, ph, 1080) else Gallery.load(context, ph.uri) ?: bmp } }
    Box(Modifier.fillMaxSize()
        .pointerInput(ph.id) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { o -> if (scale > 1f) { scale = 1f; offset = Offset.Zero } else { scale = 2.5f; offset = (Offset(size.width / 2f, size.height / 2f) - o) * 1.5f } }) }
        .then(if (scale > 1f) Modifier.pointerInput(ph.id) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); offset = if (scale == 1f) Offset.Zero else offset + pan } }
              else Modifier.pointerInput(ph.id) { detectTransformGestures(panZoomLock = true) { _, _, zoom, _ -> if (zoom != 1f) scale = (scale * zoom).coerceIn(1f, 8f) } }),
        contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), ph.name, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit) }
        if (ph.video) Icon(WonderIcons.PlayArrow, null, Modifier.size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)).padding(12.dp), tint = Color.White)
    }
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
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun SingleViewer(uri: Uri, onClose: () -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val ph = remember(uri) { Photo(uri.hashCode().toLong(), uri, context.displayName(uri), System.currentTimeMillis(), 0, 0, 0, "", "", false, 0, false, "image/*") }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ZoomImage(ph, onTap = {})
        Row(Modifier.statusBarsPadding().padding(6.dp)) {
            IconButton(onClick = onClose) { Icon(WonderIcons.ArrowBack, "Back", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onEdit) { Icon(WonderIcons.Edit, "Edit", tint = Color.White) }
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
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF15171A)).padding(16.dp), contentAlignment = Alignment.Center) {
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
