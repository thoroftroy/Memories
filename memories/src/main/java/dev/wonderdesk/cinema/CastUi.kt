package dev.wonderdesk.cinema

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** Pick a TV, send the video to it, and control it from the phone. Casting carries on after this closes. */
@Composable
fun CastSheet(uri: Uri, title: String, session: Cast.Session?, setSession: (Cast.Session?) -> Unit, position: () -> Long, pauseLocal: () -> Unit, close: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope(); val p = wonderPalette()
    val found = remember { mutableStateListOf<Tv>() }
    var scanning by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<Cast.State?>(null) }
    fun scan(addr: String? = null) { scope.launch { scanning = true; error = null; step = if (addr == null) "Looking for TVs on your Wi-Fi (about 5 seconds)" else "Asking $addr"
        runCatching { Cast.discover(c, addr) { tv -> if (found.none { it.control == tv.control }) found.add(tv) } }.onFailure { error = "Couldn't search the network: ${it.message}" }
        scanning = false; if (found.isEmpty() && error == null) error = "No TV answered. Check that the TV and phone are on the same Wi-Fi and that the TV's media or cast receiver is turned on." } }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { if (session == null) scan() }
    LaunchedEffect(Unit) { if (session == null) {
        val need = listOf("android.permission.NEARBY_WIFI_DEVICES", "android.permission.ACCESS_LOCAL_NETWORK").filter { androidx.core.content.ContextCompat.checkSelfPermission(c, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) scan() else runCatching { ask.launch(need.toTypedArray()) }.onFailure { scan() } } }
    LaunchedEffect(session) { while (session != null) { state = session.state(); delay(1000) } }
    WonderDialog(onDismissRequest = close, title = { Text(if (session != null) "Casting to ${session.tv.name}" else "Cast to a TV") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session != null) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                val st = state
                Text(if (st == null) "Waiting for the TV to answer" else clock(st.pos) + " / " + clock(st.dur) + (if (st.playing) "" else "  (paused)"), color = p.accent)
                if (st != null && st.dur > 0) LinearProgressIndicator(progress = { (st.pos.toFloat() / st.dur).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WButton("Back 10s", { scope.launch { runCatching { session.seek((state?.pos ?: 0) - 10_000) } } }, kind = ButtonKind.Secondary)
                    WButton(if (state?.playing == false) "Play" else "Pause", { scope.launch { runCatching { if (state?.playing == false) session.play() else session.pause() } } }, kind = ButtonKind.Primary)
                    WButton("Ahead 10s", { scope.launch { runCatching { session.seek((state?.pos ?: 0) + 10_000) } } }, kind = ButtonKind.Secondary)
                }
                WButton("Stop casting", { scope.launch { session.stop(); setSession(null); close() } }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger)
            } else if (connecting != null) {
                Working("Sending to $connecting", "Step 2 of 2: telling the TV where the video is and starting it")
            } else {
                if (scanning) Working("Searching", step)
                found.forEach { tv -> ListRow(tv.name, subtitle = "Tap to play this video there", icon = WonderIcons.Cast, tint = Color(0xFF1E7FE8), onClick = {
                    scope.launch {
                        connecting = tv.name
                        val s = Cast.Session(tv, c, uri, title)
                        runCatching { s.start(position()) }.onSuccess { pauseLocal(); setSession(s) }.onFailure { s.stop(); error = "Couldn't start it on ${tv.name}: ${it.message}" }
                        connecting = null
                    } }) }
                error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall) }
                if (!scanning) WButton("Search again", { found.clear(); scan() }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = WonderIcons.Refresh)
                if (manual) { OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("The TV's address, like 192.168.1.20") }, singleLine = true)
                    WButton("Look there", { if (address.isNotBlank()) scan(address.trim()) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary) }
                else TextButton(onClick = { manual = true }) { Text("Enter a TV's address") }
                TextButton(onClick = { runCatching { c.startActivity(android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Mirror the whole screen instead") }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

@Composable
private fun Working(title: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(detail, style = MaterialTheme.typography.bodySmall) }
    }
}
