package dev.wonderdesk.memories

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.zip.ZipInputStream

/** A user's own album: any mix of photos from any folder, and one photo can be in many albums. Kept as a small file, only on this phone. */
data class UserAlbum(val id: String, val name: String, val ids: Set<Long>)

object UserAlbums {
    private fun file(c: Context) = java.io.File(c.filesDir, "albums.json")
    fun load(c: Context): List<UserAlbum> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); UserAlbum(o.getString("id"), o.getString("name"), o.getJSONArray("ids").let { ids -> (0 until ids.length()).map { i -> ids.getLong(i) }.toSet() }) }
    }.getOrDefault(emptyList())
    fun save(c: Context, list: List<UserAlbum>) {
        file(c).writeText(JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("ids", JSONArray(it.ids.toList())) }).toString())
    }
    fun create(c: Context, name: String, ids: Set<Long> = emptySet()): UserAlbum { val a = UserAlbum(UUID.randomUUID().toString(), name.trim().ifBlank { "Album" }, ids); save(c, load(c) + a); return a }
    fun update(c: Context, id: String, f: (UserAlbum) -> UserAlbum?) { save(c, load(c).mapNotNull { if (it.id == id) f(it) else it }) }

    /**
     * Brings albums over from a Google Photos Takeout .zip: every folder in "Google Photos" that isn't a "Photos from 2020" year folder
     * becomes an album, matched to the photos already on this phone by file name. Returns (albums made, photos matched, photos not found).
     */
    fun importTakeout(c: Context, zip: Uri, library: List<Photo>, progress: (String) -> Unit): Triple<Int, Int, Int> {
        val byName = library.groupBy { it.name.lowercase() }
        val folders = LinkedHashMap<String, MutableSet<String>>()
        var seen = 0
        c.contentResolver.openInputStream(zip)!!.use { raw ->
            ZipInputStream(raw.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    seen++; if (seen % 200 == 0) progress("Reading the archive… $seen files")
                    val parts = e.name.split('/')
                    val gi = parts.indexOfFirst { it.equals("Google Photos", true) }
                    if (gi < 0 || parts.size < gi + 3) continue
                    val album = parts[gi + 1]
                    val file = parts.last()
                    if (file.endsWith(".json", true) || Regex("""Photos from \d{4}""").matches(album) || album.equals("Trash", true) || album.equals("Archive", true)) continue
                    folders.getOrPut(album) { linkedSetOf() }.add(file.lowercase())
                }
            }
        }
        var albums = 0; var matched = 0; var missing = 0
        for ((name, files) in folders) {
            val ids = files.flatMap { f -> byName[f].orEmpty() + byName[f.replace(Regex("""\(\d+\)(?=\.[^.]+$)"""), "")].orEmpty() }.map { it.id }.toSet()
            missing += files.size - ids.size.coerceAtMost(files.size)
            if (ids.isNotEmpty()) { create(c, name, ids); albums++; matched += ids.size }
        }
        return Triple(albums, matched, missing.coerceAtLeast(0))
    }
}
