package net.uncorp.lurkr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One link Lurkr opened, when, and the page's title once it loaded. */
data class Lurk(val url: String, val at: Long, val title: String? = null)

private val Context.recents by preferencesDataStore("recents")
private val RECENT = stringPreferencesKey("recent")

/**
 * The links Lurkr was sent (Recents, the home screen), newest first, with each page's title and a small picture
 * from it. Only those: what the sites stored is still wiped after every visit. Kept on this phone, never sent
 * anywhere.
 */
object Recents {
    const val MAX = 100

    fun flow(ctx: Context): Flow<List<Lurk>> = ctx.applicationContext.recents.data.map { decode(it[RECENT]) }

    /** Adds [url] on top (moving it up if it's already there, title kept), keeping the newest [MAX]. */
    suspend fun add(ctx: Context, url: String, now: Long = System.currentTimeMillis()) {
        edit(ctx) { list -> listOf(Lurk(url, now, list.firstOrNull { it.url == url }?.title)) + list.filter { it.url != url } }
    }

    /** The page's [title], once it loaded. */
    suspend fun describe(ctx: Context, url: String, title: String) {
        edit(ctx) { list -> list.map { if (it.url == url) it.copy(title = title) else it } }
    }

    suspend fun remove(ctx: Context, url: String) {
        edit(ctx) { list -> list.filter { it.url != url } }
    }

    suspend fun clear(ctx: Context) {
        ctx.applicationContext.recents.edit { it.remove(RECENT) }
        withContext(Dispatchers.IO) { thumbs(ctx).deleteRecursively() }
    }

    private suspend fun edit(ctx: Context, change: (List<Lurk>) -> List<Lurk>) {
        var kept = emptyList<Lurk>()
        ctx.applicationContext.recents.edit { p ->
            kept = change(decode(p[RECENT])).take(MAX)
            p[RECENT] = encode(kept)
        }
        // Pictures of links no longer listed go too
        val names = kept.map { name(it.url) }.toSet()
        withContext(Dispatchers.IO) { thumbs(ctx).listFiles()?.filter { it.name !in names }?.forEach { it.delete() } }
    }

    // One "time url" per line, then a tab and the title if there is one (URLs have no spaces, tabs or newlines)
    fun encode(list: List<Lurk>): String = list.joinToString("\n") { l ->
        "${l.at} ${l.url}" + (l.title?.let { "\t" + it.replace(Regex("\\s+"), " ").trim() } ?: "")
    }

    fun decode(s: String?): List<Lurk> = s.orEmpty().lines().mapNotNull { line ->
        val i = line.indexOf(' ')
        if (i <= 0) return@mapNotNull null
        val at = line.substring(0, i).toLongOrNull() ?: return@mapNotNull null
        val rest = line.substring(i + 1)
        val tab = rest.indexOf('\t')
        if (tab < 0) Lurk(rest, at) else Lurk(rest.substring(0, tab), at, rest.substring(tab + 1).ifBlank { null })
    }

    // Pictures: one small JPEG per link, in the app's own storage

    private fun thumbs(ctx: Context) = File(ctx.applicationContext.filesDir, "thumbs")

    private fun name(url: String) =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

    fun thumb(ctx: Context, url: String): File = File(thumbs(ctx), name(url))

    /**
     * Fetches the page's picture ([image], from the page itself, so at the same moment as the visit) without
     * cookies, and keeps a small copy for the link.
     */
    suspend fun savePicture(ctx: Context, url: String, image: String) = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(image).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", Web.userAgent)
            val bytes = c.inputStream.use { it.readBytes() }
            c.disconnect()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 240) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@runCatching
            val f = thumb(ctx, url)
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
    }
}
