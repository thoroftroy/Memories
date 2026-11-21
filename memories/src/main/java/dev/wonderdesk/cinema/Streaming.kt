package dev.wonderdesk.cinema

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.io.File

/**
 * Playing video from somewhere far away, the way streaming services do it: keep a generous buffer filled ahead of
 * the picture, remember what was already fetched on disk so rewinding and replaying cost nothing, and measure the
 * connection so the player can say honestly what is slowing it down.
 */
@UnstableApi
object Streaming {
    /** Videos from a computer over Ripple, a network share or a web address, as opposed to files on this phone. */
    fun isRemote(uri: Uri): Boolean = when (uri.scheme) {
        "http", "https", "rtsp" -> true
        "content" -> uri.authority?.let { it.startsWith("dev.wonderdesk.ripple") || it.startsWith("dev.wonderdesk.wayfinder") } == true
        else -> false
    }

    private var cache: SimpleCache? = null
    private fun cache(c: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(c.cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(768L * 1024 * 1024), StandaloneDatabaseProvider(c.applicationContext)).also { cache = it }
    }

    /** The measured speed of the connection to the video source, in bits per second (0 until known). */
    fun bitsPerSecond(c: Context): Long = DefaultBandwidthMeter.getSingletonInstance(c).bitrateEstimate.coerceAtLeast(0)

    /** Seconds of video kept ready ahead of the picture before playback starts, and after it stalls. */
    const val START_AHEAD_MS = 2_500
    const val REBUFFER_AHEAD_MS = 6_000

    fun builder(c: Context, uri: Uri): ExoPlayer.Builder {
        val remote = isRemote(uri)
        val load = DefaultLoadControl.Builder()
            // Local files need little; far-away ones keep up to a minute ready and refill when it falls under 25 seconds.
            .setBufferDurationsMs(if (remote) 25_000 else 15_000, if (remote) 90_000 else 50_000, START_AHEAD_MS, REBUFFER_AHEAD_MS)
            .setTargetBufferBytes(if (remote) 160 * 1024 * 1024 else 64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val b = ExoPlayer.Builder(c).setLoadControl(load).setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(c))
        if (remote) {
            val upstream = DefaultDataSource.Factory(c)
            val cached = CacheDataSource.Factory().setCache(cache(c)).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            b.setMediaSourceFactory(DefaultMediaSourceFactory(c).setDataSourceFactory(cached))
        }
        return b
    }

    fun item(uri: Uri): MediaItem = MediaItem.Builder().setUri(uri).setCustomCacheKey(uri.toString()).build()
}
