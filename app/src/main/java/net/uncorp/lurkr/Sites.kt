package net.uncorp.lurkr

import android.net.Uri

/**
 * One website Lurkr handles, from its folder in sites/ (site.json, logo.svg …; see README.md, "Adding a site"). The
 * build generates the list ([SITE_LIST]), the router's domains in the manifest and the logos from those folders.
 *
 * "*.example.com" in [hosts] covers every subdomain (www., m., l., vm. …) but not example.com itself.
 */
data class Site(
    val id: String,
    val name: String,
    /** Lurkr can show it itself, without the walls (else it only routes it). */
    val view: Boolean,
    val hosts: List<String>,
    /** The logo (res/drawable/site_<id>.xml), and whether it's black (drawn in the text colour instead, for dark mode). */
    val logo: Int,
    val ink: Boolean,
    val unwrap: List<Unwrap> = emptyList(),
    /** Short links to follow before the [rewrites]: these hosts, or paths matching these regular expressions. */
    val resolveHosts: List<String> = emptyList(),
    val resolvePaths: List<String> = emptyList(),
    val rewrites: List<Rewrite> = emptyList(),
)

/** A link wrapper that only redirects (l.facebook.com/l.php?u=…): the real link is in [param]. */
data class Unwrap(val host: String, val param: String)

/** What to load instead of a link whose path matches [path]: [to] ($1, $2 … from the path), or the link plus [addQuery]. */
data class Rewrite(val path: String, val to: String? = null, val addQuery: Map<String, String> = emptyMap())

object Sites {
    /** Alphabetical by name. */
    val ALL: List<Site> = SITE_LIST

    fun viewable(site: Site?): Boolean = site?.view == true

    fun viewable(uri: Uri?): Boolean = viewable(match(uri))

    val ALL_HOSTS: List<String> = ALL.flatMap { it.hosts }

    /** The site a link's host belongs to, or null. */
    fun match(host: String?): Site? {
        val h = host?.lowercase()?.trimEnd('.') ?: return null
        return ALL.firstOrNull { site -> site.hosts.any { matches(it, h) } }
    }

    fun match(uri: Uri?): Site? = uri?.takeIf { it.scheme == "http" || it.scheme == "https" }?.let { match(it.host) }

    fun matches(pattern: String, host: String): Boolean =
        if (pattern.startsWith("*.")) host.endsWith(pattern.substring(1)) else host == pattern

    /** A concrete URL for a host pattern ("*.tiktok.com" -> "https://www.tiktok.com/"), to ask Android who opens it. */
    fun sampleUrl(pattern: String): String = "https://" + (if (pattern.startsWith("*.")) "www" + pattern.substring(1) else pattern) + "/"

    /** The hosts as origin rules (https and http, wildcards kept), to scope the cleanup scripts. */
    fun origins(site: Site): Set<String> = site.hosts.flatMap { listOf("https://$it", "http://$it") }.toSet()
}

/** Pulling a link out of what other apps send. */
object Links {
    private val URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    /** Ad and tracker servers: nothing is loaded from them. */
    private val AD_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "adservice.google.com",
    )
    /** YouTube's ad and ad-tracking requests (its videos and pages come from the same servers). */
    private val AD_PATHS = Regex("""^/(pagead/|api/stats/ads|ptracking|get_midroll_)""")

    fun isAd(host: String?, path: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (AD_HOSTS.any { h == it || h.endsWith(".$it") }) return true
        return (h == "youtube.com" || h.endsWith(".youtube.com")) && AD_PATHS.containsMatchIn(path ?: "")
    }

    /** Query parameters that only say who shared a link, from where (and tie your visit to them). */
    private val TRACKING = setOf(
        "igsh", "igshid", "fqnt", "cplk", "fbclid", "mibextid", "rdid", "share_url", "sfnsn", "ref_src", "ref_url", "si", "feature",
        "_t", "_r", "is_from_webapp", "sender_device", "sender_web_id", "share_app_id", "share_item_id",
        "share_link_id", "social_share", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "__cft__", "__tn__",
    )
    /** Login and sign-up pages: Lurkr never shows them (it's logged out by design). */
    private val LOGIN = Regex("""^/(accounts/(login|emailsignup|signup)|login(\.php)?|signup|reg|r\.php|i/flow/(login|signup)|checkpoint|recover)(/|$|\?)""", RegexOption.IGNORE_CASE)

    fun isLogin(path: String?): Boolean = path != null && LOGIN.containsMatchIn(path)

    /** What a single post or video looks like on each site (its path, or path and query), and what to call it. */
    private val POSTS: Map<String, List<Pair<Regex, String>>> = mapOf(
        "x" to listOf(Regex("""^/[^/]+/status/\d+""") to "Post"),
        "instagram" to listOf(Regex("""^/(reels?|tv)/[^/]+""") to "Reel", Regex("""^/p/[^/]+""") to "Post"),
        "tiktok" to listOf(Regex("""^/(@[^/]+/video|player/v1|embed/v2)/\d+""") to "Video", Regex("""^/@[^/]+/photo/\d+""") to "Post"),
        "facebook" to listOf(
            Regex("""^/(reel/\d+|watch/?\?(.*&)?v=\d+|[^/]+/videos/)""") to "Video",
            Regex("""^/([^/]+/posts/|photo|permalink\.php|story\.php|groups/[^/]+/posts/)""") to "Post",
        ),
        "youtube" to listOf(Regex("""^/shorts/[\w-]+""") to "Short", Regex("""^/(watch\?(.*&)?v=[\w-]+|live/[\w-]+|[\w-]{11}$)""") to "Video"),
        "spotify" to listOf(Regex("""^/(intl-\w+/)?(track|album|playlist|episode|show)/\w+""") to "Track"),
    )

    /**
     * "Post", "Video", "Reel" … when [pathAndQuery] is a single post or video of the site [siteId], else null (a
     * profile, a feed, a search). Any other website: Any page but its front page.
     */
    fun postKind(siteId: String?, pathAndQuery: String): String? {
        if (siteId == null) return if (pathAndQuery.substringBefore('?').trim('/').isEmpty()) null else "Page"
        val rules = POSTS[siteId] ?: return null
        return rules.firstOrNull { it.first.containsMatchIn(pathAndQuery) }?.second
    }

    fun postKind(uri: Uri): String? =
        postKind(Sites.match(uri)?.id, uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: ""))

    /** X adds "s" (the app shared from) and "t" (who shared it) to its links. */
    private val X_TRACKING = setOf("s", "t")

    /** The link without its tracking tags (TRACKING, utm_*, and X's s and t). Everything else stays as it is. */
    fun clean(url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val query = uri.rawQuery ?: return url
        val host = uri.host.orEmpty().lowercase().removePrefix("www.").removePrefix("mobile.")
        val x = host == "x.com" || host == "twitter.com"
        val kept = query.split('&').filter { part ->
            val name = java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8").lowercase()
            name.isNotEmpty() && name !in TRACKING && !name.startsWith("utm_") && !(x && name in X_TRACKING)
        }
        if (kept.size == query.split('&').size) return url
        val base = url.substringBefore('?')
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return (if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")).substringBefore('#') + fragment
    }

    /** The first web link in shared text ("Look at this! https://… "), without trailing punctuation. */
    fun firstUrl(text: String?): String? = text?.let { URL.find(it)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':', '\'') }

    /**
     * The site part of a host: "www.nytimes.com" → "nytimes.com", "news.bbc.co.uk" → "bbc.co.uk" (two-letter country
     * domains with a short second level: co.uk, com.br …). Good enough to keep a shared site's pages together.
     */
    fun baseDomain(host: String?): String? {
        val labels = host?.lowercase()?.trimEnd('.')?.split('.')?.filter { it.isNotEmpty() } ?: return null
        if (labels.size <= 2) return labels.joinToString(".")
        val tld = labels.last()
        val second = labels[labels.size - 2]
        val keep = if (tld.length == 2 && second.length <= 3) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /** App-only path prefixes in "open in app" links (instagram.com/_u/name → instagram.com/name). */
    fun appPath(uri: Uri): Uri {
        val path = uri.path ?: return uri
        return if (path.startsWith("/_u/") || path.startsWith("/_n/")) uri.buildUpon().path(path.substring(3)).build() else uri
    }

    /** Same page, ignoring www., the query, the fragment and a trailing slash. */
    fun samePage(a: Uri, b: Uri?): Boolean {
        fun key(u: Uri) = u.host.orEmpty().lowercase().removePrefix("www.") + u.path.orEmpty().trimEnd('/')
        return b != null && key(a) == key(b)
    }

    /** Play Store / App Store pages: where "open in app" links fall back to. Never worth opening. */
    fun isAppStore(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return (host == "play.google.com" && uri.path.orEmpty().startsWith("/store")) ||
            host == "apps.apple.com" || host == "itunes.apple.com" || host == "market.android.com"
    }
}
