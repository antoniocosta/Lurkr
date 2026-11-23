package net.uncorp.lurkr

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The link rules from the sites' site.json ("unwrap", "resolve", "rewrite"): what Lurkr loads for a link it's sent.
 * Plain strings (java.net.URI), so they're tested on the JVM against the real site.json files.
 */
object Rewrites {
    /** l.facebook.com/l.php?u=https://… → https://… (only web links; anything else stays as it is). */
    fun unwrap(site: Site?, url: String): String {
        val uri = parse(url) ?: return url
        val rule = site?.unwrap?.firstOrNull { it.host.equals(uri.host, ignoreCase = true) } ?: return url
        val inner = query(uri)[rule.param] ?: return url
        return if (inner.startsWith("https://") || inner.startsWith("http://")) inner else url
    }

    /** A short link to follow first (vm.tiktok.com/…, tiktok.com/t/…). */
    fun needsResolve(site: Site?, url: String): Boolean {
        val uri = parse(url) ?: return false
        site ?: return false
        return site.resolveHosts.any { it.equals(uri.host, ignoreCase = true) } ||
            site.resolvePaths.any { Regex(it).containsMatchIn(uri.rawPath.orEmpty()) }
    }

    /** The first rule whose path matches: its "to" URL, or the link with "addQuery" added. Else the link as it is. */
    fun rewrite(site: Site?, url: String): String {
        val uri = parse(url) ?: return url
        val path = uri.rawPath.orEmpty()
        for (rule in site?.rewrites.orEmpty()) {
            val m = Regex(rule.path).find(path) ?: continue
            rule.to?.let { to -> return m.groupValues.foldIndexed(to) { i, acc, g -> if (i == 0) acc else acc.replace("$$i", g) } }
            val missing = rule.addQuery.filterKeys { it !in query(uri) }
            if (missing.isEmpty()) return url
            val add = missing.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            val q = uri.rawQuery?.let { "$it&$add" } ?: add
            return URI(uri.scheme, uri.rawAuthority, null, null, null).toString() + path + "?" + q +
                (uri.rawFragment?.let { "#$it" } ?: "")
        }
        return url
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }

    private fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val i = it.indexOf('=')
        if (i < 0) dec(it) to "" else dec(it.substring(0, i)) to dec(it.substring(i + 1))
    }

    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
