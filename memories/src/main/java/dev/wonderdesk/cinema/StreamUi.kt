package dev.wonderdesk.cinema

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

private fun mbit(bps: Long) = "%.1f Mbit/s".format(bps / 1_000_000.0)

/**
 * Says what the player is waiting for: opening, filling the first buffer, or catching up after running dry, with a bar
 * that shows how much video is ready and, once known, how fast the source is sending it compared with what the video needs.
 */
@OptIn(UnstableApi::class)
@Composable
fun StreamCard(
    modifier: Modifier, title: String, firstFrame: Boolean, aheadMs: Long, waitedMs: Long, bps: Long, needBps: Long, remote: Boolean, error: String?, idleMs: Long = 0L,
) {
    val secs = waitedMs / 1000
    val target = if (firstFrame) Streaming.REBUFFER_AHEAD_MS else Streaming.START_AHEAD_MS
    val head: String; val line: String
    var progress: Float? = null
    if (error != null) { head = "Can't keep playing"; line = error }
    else if (!firstFrame) {
        head = if (remote) "Opening from the source" else "Opening the video"
        line = when {
            aheadMs > 0 -> "Filling the buffer: %.1f s of %.1f s ready".format(aheadMs / 1000.0, target / 1000.0)
            !remote -> "Reading the file"
            secs < 2 -> "Contacting the source"
            secs < 15 -> "Reading the start of $title (${secs}s so far)"
            else -> "Still reading the start of $title (${secs}s). The source is slow to answer, so a big file can need a moment."
        }
        if (aheadMs > 0) progress = (aheadMs.toFloat() / target).coerceIn(0f, 1f)
    } else {
        head = "Buffering"
        line = "%.1f s ready, %.1f s needed to carry on".format(aheadMs / 1000.0, target / 1000.0)
        progress = (aheadMs.toFloat() / target).coerceIn(0f, 1f)
    }
    val dry = error == null && remote && idleMs > 3_000
    val speed = if (error == null && !dry && bps > 0 && (aheadMs > 0 || firstFrame)) "Receiving ${mbit(bps)}" + (if (needBps > 0) ", this video needs about ${mbit(needBps)}" else "") else null
    val slow = error == null && bps > 0 && (aheadMs > 0 || firstFrame) && needBps > 0 && bps < needBps * 1.15
    Column(modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = .72f)).padding(18.dp, 14.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(head, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(line, color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (error == null) {
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = Color(0xFF2EE6D6), trackColor = Color.White.copy(alpha = .2f))
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF2EE6D6), trackColor = Color.White.copy(alpha = .2f))
        }
        speed?.let { Text(it, color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
        if (dry) Text("No new video has arrived for ${idleMs / 1000} s. Check that the source is still reachable.", color = Color(0xFFFFD34D), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        if (slow && !dry) Text("The source is sending slower than the video plays, so it keeps pausing. Waiting a little before pressing play gives it a bigger lead.", color = Color(0xFFFFD34D), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}
