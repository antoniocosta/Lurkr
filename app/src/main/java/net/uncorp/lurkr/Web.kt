package net.uncorp.lurkr

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A WebView that doesn't look like one (sites give in-app WebViews worse pages, with more "open the app"
 * prompts), keeps nothing between visits, and runs the cleanup scripts on the sites' pages.
 */
object Web {
    /** Chrome's reduced user-agent (what Chrome on Android really sends), built from the WebView's own. */
    var userAgent: String = ""
        private set

    /**
     * Every link gets its own WebView profile (where the WebView supports them): its own cookies, storage, cache
     * and network state (open connections, TLS sessions, HSTS), so nothing ties one visit to the next. Profiles are
     * named "lurk-…" and deleted once their WebView is gone.
     */
    private const val PROFILE = "lurk-"
    val profiles: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** Deletes every Lurkr profile but [keep] (one still in use can't go: it goes next time). */
    fun deleteProfiles(keep: String?) {
        if (!profiles) return
        val store = ProfileStore.getInstance()
        store.allProfileNames.filter { it.startsWith(PROFILE) && it != keep }.forEach { runCatching { store.deleteProfile(it) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun create(ctx: Context): WebView {
        val debuggable = ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)
        val web = WebView(ctx)
        if (profiles) {
            val name = PROFILE + System.nanoTime()
            ProfileStore.getInstance().getOrCreateProfile(name)
            WebViewCompat.setProfile(web, name)
            web.tag = name
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true  // the sites don't work without it; wiped after every visit
            mediaPlaybackRequiresUserGesture = false  // sites autoplay muted, like in Chrome
            setSupportMultipleWindows(false)  // target=_blank and window.open load here, through our link checks
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = chromeUserAgent(userAgentString)
            userAgent = userAgentString
        }
        hideWebView(web.settings)
        cookies(web).setAcceptThirdPartyCookies(web, false)
        Cleanup.install(ctx, web)
        return web
    }

    /**
     * "Mozilla/5.0 (Linux; Android 16; Pixel 10 Build/…; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0
     * Chrome/142.0.0.0 Mobile Safari/537.36" → "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36
     * (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36": no "; wv", no "Version/4.0", and the frozen
     * platform part Chrome itself sends.
     */
    fun chromeUserAgent(webView: String): String {
        val chrome = Regex("""Chrome/(\d+)""").find(webView)?.groupValues?.get(1) ?: return webView.replace("; wv", "")
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Mobile Safari/537.36"
    }

    /**
     * The other two tells: the X-Requested-With header (the app's package name on every request) and the
     * "Android WebView" brand in the user-agent client hints (Sec-CH-UA).
     */
    @Suppress("DEPRECATION")  // newer WebViews no longer send the header at all; this covers older ones
    private fun hideWebView(settings: WebSettings) {
        // Dark mode for sites without one of their own (Facebook logged out): the WebView darkens them, only while
        // Lurkr is dark. Sites with a dark theme get it through prefers-color-scheme, as before.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val current = WebSettingsCompat.getUserAgentMetadata(settings)
            val major = current.brandVersionList.firstOrNull { it.brand.contains("Chromium") }?.majorVersion
                ?: Regex("""Chrome/(\d+)""").find(settings.userAgentString)?.groupValues?.get(1) ?: return
            fun brand(name: String, version: String) =
                UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(version).setFullVersion("$version.0.0.0").build()
            val metadata = UserAgentMetadata.Builder(current)
                .setBrandVersionList(listOf(brand("Google Chrome", major), brand("Chromium", major), brand("Not_A Brand", "24")))
                .build()
            WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        }
    }

    /** Cookies, localStorage, IndexedDB, the HTTP cache: all gone, so every link starts as a first-time visitor. */
    suspend fun wipe(web: WebView?) {
        clearStorage(web)
        val jar = web?.let(::cookies) ?: CookieManager.getInstance()
        suspendCoroutine { c -> jar.removeAllCookies { c.resume(Unit) } }
        declineConsent(jar)
        jar.flush()
    }

    /**
     * YouTube's cookie choice, made in advance as "reject all": Without it every visit first goes to its full-screen
     * consent page, which flashes by before the cleanup declines it. Says nothing about the visitor.
     */
    private fun declineConsent(jar: CookieManager) {
        for (host in listOf("https://www.youtube.com", "https://m.youtube.com")) {
            jar.setCookie(host, "SOCS=CAI; Domain=.youtube.com; Path=/; Secure; SameSite=Lax")
        }
    }

    /** The same, without waiting for the cookies to go (when the screen closes). */
    fun wipeNow(web: WebView?) {
        clearStorage(web)
        val jar = web?.let(::cookies) ?: CookieManager.getInstance()
        jar.removeAllCookies(null)
        jar.flush()
    }

    /** The cookie jar [web] uses: its profile's, or the shared one. */
    fun cookies(web: WebView): CookieManager =
        if (profiles) WebViewCompat.getProfile(web).cookieManager else CookieManager.getInstance()

    private fun clearStorage(web: WebView?) {
        web?.clearCache(true)
        web?.clearFormData()
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * What to load for a link, from its site's rules in site.json: wrappers unwrapped, short links followed (their
     * redirects), then the first matching rewrite (Instagram: skip the "Watch in the app" page; TikTok: the embed
     * player). Everything else loads as it is.
     */
    suspend fun target(uri: Uri): String {
        val site = Sites.match(uri)
        var url = Rewrites.unwrap(site, uri.toString())
        Rewrites.rewrite(site, url).let { if (it != url) return it }
        if (!Rewrites.needsResolve(site, url)) return url
        url = withContext(Dispatchers.IO) { runCatching { resolve(url) }.getOrNull() } ?: url
        url = Links.clean(url)  // what a short link points to usually carries the share's tags
        return Rewrites.rewrite(Sites.match(Uri.parse(url)), url)
    }

    /** Follows a short link's redirects (up to 5) to the full link. */
    private fun resolve(url: String): String {
        var next = url
        repeat(5) {
            val c = (URL(next).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", userAgent)
            }
            val location = try {
                c.responseCode
                c.getHeaderField("Location")
            } finally {
                c.disconnect()
            }
            next = URL(URL(next), location ?: return next).toString()
        }
        return next
    }
}

/**
 * The page cleanup: assets/cleanup/common.js (declines cookies, hides login walls, app banners and sign-up buttons,
 * unlocks scrolling, and keeps doing it as the page changes) with each site's own files from sites/<id>/: the "page"
 * settings in site.json, filters.txt (cosmetic filter rules) and site.js. Runs at document start in every frame of
 * the site's origins; on other websites shared to Lurkr, common.js alone.
 */
object Cleanup {
    private val installed = mutableSetOf<WebView>()

    fun install(ctx: Context, web: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return  // see scriptFor()
        Sites.ALL.filter(Sites::viewable).forEach { site -> WebViewCompat.addDocumentStartJavaScript(web, script(ctx, site), Sites.origins(site)) }
        // Every other website (shared to Lurkr), in every frame: consent dialogs often live in a third-party iframe.
        // Scripts run in the order they're added, so on Lurkr's own sites the site's script comes first, and
        // common.js runs only once per frame.
        WebViewCompat.addDocumentStartJavaScript(web, generic(ctx), setOf("*"))
        installed += web
    }

    /**
     * The script to run by hand when a page starts and loads, on older WebViews without document-start scripts (main
     * frame only). common.js runs once per page, whichever comes first.
     */
    fun scriptFor(ctx: Context, web: WebView, url: String?): String? {
        val uri = url?.let(Uri::parse)?.takeIf { it.scheme == "http" || it.scheme == "https" } ?: return null
        if (web in installed) return null
        val site = Sites.match(uri)?.takeIf(Sites::viewable) ?: return generic(ctx)  // any other website: shared rules
        return script(ctx, site)
    }

    fun forget(web: WebView) {
        installed -= web
    }

    private val cache = mutableMapOf<String, String>()

    /** common.js alone, for websites shared to Lurkr that aren't its sites. */
    private fun generic(ctx: Context): String = cache.getOrPut("") { fill(ctx, "", "{}", "", "") }

    private fun script(ctx: Context, site: Site): String = cache.getOrPut(site.id) {
        val json = asset(ctx, "sites/${site.id}/site.json")
        val page = json?.let { runCatching { JSONObject(it).optJSONObject("page")?.toString() }.getOrNull() } ?: "{}"
        fill(ctx, site.id, page, asset(ctx, "sites/${site.id}/filters.txt").orEmpty(), asset(ctx, "sites/${site.id}/site.js").orEmpty())
    }

    private fun fill(ctx: Context, id: String, page: String, filters: String, js: String): String =
        asset(ctx, "cleanup/common.js")!!
            .replace("\"__SITE__\"", JSONObject.quote(id))
            .replace("\"__PAGE__\"", page)
            .replace("\"__FILTERS__\"", JSONObject.quote(filters))
            .replace("/*__SITE_JS__*/", js)

    private fun asset(ctx: Context, path: String): String? =
        runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
}
