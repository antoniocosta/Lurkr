package net.uncorp.lurkr

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.key
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows one social link logged out: a slim bar (site, Share, Open in browser, Reload) over the hardened
 * WebView. Gets its links from Lurkr's router (links and shares) and from the main screen.
 * Links to sites Lurkr doesn't handle go straight to the browser. Everything the sites stored is wiped
 * before each link and when the screen closes.
 */
private const val TAG = "Lurkr"

/** Intent extra: show this link even though it's not one of Lurkr's sites (a website shared to Lurkr). */
const val EXTRA_ANY_SITE = "net.uncorp.lurkr.ANY_SITE"

class ViewerActivity : ComponentActivity() {
    // The WebView showing the link. With profiles, every link gets a new one (see Web.create), swapped in here.
    private val webState = mutableStateOf<WebView?>(null)
    // Following a share link in the page (see open)
    private var resolving = false
    private val web: WebView get() = webState.value!!

    private var url by mutableStateOf<String?>(null)
    private var loadProgress by mutableIntStateOf(100)

    /**
     * A website outside Lurkr's sites, shared to it: its base domain ("nytimes.com"), whose pages then stay here too
     * (with the shared cleanup only). Null for Lurkr's own sites.
     */
    private var homeSite: String? = null

    /** Pages shown here: Lurkr's sites, and the shared website's own pages. */
    private fun shows(uri: Uri): Boolean =
        Sites.viewable(uri) || (homeSite != null && (uri.scheme == "http" || uri.scheme == "https") && Links.baseDomain(uri.host) == homeSite)

    /** The link we were sent, and what we loaded for it (TikTok: the player). Share that link, not the player. */
    private var sentLink: String? = null
    private var loadedFor: String? = null

    /** After a new link, forget the previous one's pages once the new one shows (so Back doesn't return to it). */
    private var clearHistoryOnLoad = false

    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = linkFrom(intent)
        if (uri == null || !(Sites.viewable(uri) || intent.getBooleanExtra(EXTRA_ANY_SITE, false))) {
            uri?.let { Apps.inBrowser(this, it) }
            finish()
            return
        }
        Web.deleteProfiles(keep = null)  // anything an earlier visit left behind
        webState.value = newWeb()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreen != null -> fullscreenCallback?.onCustomViewHidden()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })
        setContent { AppTheme(this) { Screen() } }
        open(uri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = linkFrom(intent) ?: return
        if (Sites.viewable(uri) || intent.getBooleanExtra(EXTRA_ANY_SITE, false)) open(uri) else Apps.open(this, uri)
    }

    override fun onDestroy() {
        webState.value?.let { w ->
            // Wipe now, not on the next link: nothing a site stored outlives the screen
            if (isFinishing) Web.wipeNow(w)
            drop(w)
            Web.deleteProfiles(keep = null)
        }
        super.onDestroy()
    }

    private fun newWeb(): WebView = Web.create(this).apply {
        webViewClient = Client()
        webChromeClient = Chrome()
    }

    private fun drop(w: WebView) {
        Cleanup.forget(w)
        w.stopLoading()
        w.destroy()
    }

    private var opening: Job? = null

    /** The link in Recents still waiting for its title and picture (taken from the page once it has loaded). */
    private var describing: String? = null

    private fun describe(view: WebView) {
        val link = describing ?: return
        view.postDelayed({
            if (describing != link || view !== web) return@postDelayed
            view.evaluateJavascript(DESCRIBE) { json ->
                val o = runCatching { org.json.JSONObject(org.json.JSONTokener(json).nextValue() as String) }.getOrNull() ?: return@evaluateJavascript
                val title = o.optString("title").trim()
                if (title.isEmpty()) return@evaluateJavascript
                describing = null
                lifecycleScope.launch {
                    Recents.describe(this@ViewerActivity, link, title)
                    o.optString("image").takeIf { it.startsWith("http") }?.let { Recents.savePicture(this@ViewerActivity, link, it) }
                }
            }
        }, 2500)
    }

    private fun open(link: Uri) {
        // Without the tags that say who shared it, from where: they're not loaded, kept or shared on
        val uri = Uri.parse(Links.clean(link.toString()))
        homeSite = if (Sites.viewable(uri)) null else Links.baseDomain(uri.host)
        sentLink = uri.toString()
        // Only the latest link: one opened while the last is still starting replaces it (after an update Android
        // reopens the viewer with its old link just as the new one arrives)
        opening?.cancel()
        opening = lifecycleScope.launch {
            // A share link (it names who shared it) is never kept: what it points to is, once found
            // Only single posts and videos (and articles from other sites): not profiles, feeds or searches
            if (!Rewrites.needsResolve(Sites.match(uri), uri.toString()) && Links.postKind(uri) != null) {
                Recents.add(this@ViewerActivity, uri.toString())
                describing = uri.toString()
            }
            if (Web.profiles && loadedFor != null) {
                // A new link: a new WebView with a new, empty profile; the last one and its profile go
                val old = web
                webState.value = newWeb()
                drop(old)
                // A profile can only go once its WebView has fully shut down: a few seconds later
                val keep = web.tag as? String
                launch { delay(3000); Web.deleteProfiles(keep) }
            } else {
                web.stopLoading()
            }
            Web.wipe(web)
            val target = Web.target(uri)
            // A share link Lurkr couldn't follow itself (Facebook refuses plain requests): the page follows it, and the
            // first step to the real post is stopped and opened afresh (new profile, tags off), see Client
            resolving = Rewrites.needsResolve(Sites.match(Uri.parse(target)), target)
            loadedFor = target
            clearHistoryOnLoad = true
            web.loadUrl(target)
        }
    }

    /** VIEW: the link itself. SEND: the first link in the shared text. */
    private fun linkFrom(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> Links.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(Uri::parse)
        else -> null
    }?.takeIf { it.scheme == "http" || it.scheme == "https" }

    /** The page's link to share or open elsewhere: the link we were sent while its player is showing. */
    private fun shareUrl(): String? = if (url != null && url == loadedFor) sentLink else url

    @Composable
    private fun Screen() {
        Column(Modifier.fillMaxSize().background(Paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Bar()
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (loadProgress < 100) Box(Modifier.fillMaxWidth(loadProgress / 100f).height(2.dp).background(Blue))
                else HorizontalDivider(color = Line)
            }
            // MATCH_PARENT, not AndroidView's default WRAP_CONTENT: a wrap-content WebView sets the viewport height
            // to 0, so pages sized in vh (TikTok's player) collapse
            val current = webState.value ?: return@Column
            key(current) {
                AndroidView(
                    factory = { current.apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) } },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    /** Lurkr's home, in place of the viewer. */
    private fun home() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    /** Lurkr's logo with the site's as a badge, the name Lurkr, then Share, Open in browser and Reload. */
    @Composable
    private fun Bar() {
        val uri = url?.let(Uri::parse)
        val site = Sites.match(uri)
        Row(
            Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Lurkr's logo, with the site's as a badge on its bottom right, and the name: A tap goes to Lurkr's home
            Row(Modifier.weight(1f)) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { home() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Box(Modifier.size(34.dp)) {
                AppIcon(packageName, size = 30.dp)
                site?.let {
                    Box(
                        Modifier.align(Alignment.BottomEnd).size(18.dp).background(Color.White, CircleShape).padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) { SiteIcon(it, size = 14.dp) }
                }
            }
            Text(site?.let { "${it.name} Lurkr" } ?: "Lurkr", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(end = 8.dp))
            }
            }
            // Close together, as one group
            Row {
                BarButton(R.drawable.ic_reload, "Reload") { web.reload() }
                BarButton(R.drawable.ic_open_browser, "Open in browser") { shareUrl()?.let { Apps.inBrowser(this@ViewerActivity, Uri.parse(it)) } }
                BarButton(R.drawable.ic_share, "Share") { shareUrl()?.let { Apps.share(this@ViewerActivity, it) } }
            }
        }
    }

    @Composable
    private fun BarButton(icon: Int, description: String, onClick: () -> Unit) {
        Box(
            Modifier.size(42.dp).clickable(onClick = onClick).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) { TintedIcon(icon, description) }
    }

    private inner class Client : WebViewClient() {
        /** Nothing from ad and tracker servers: an empty answer. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (Links.isAd(request.url.host, request.url.path)) WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), null) else null

        /**
         * Pages of the sites Lurkr shows stay here (including going from one site to another). Other web links go out
         * the normal way. App links (intent:, instagram:, fb:, market: …) are dropped, except an intent: link's
         * web fallback.
         */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            Log.d(TAG, "navigate ${uri} redirect=${request.isRedirect} gesture=${request.hasGesture()}")
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> {
                    // Logged out by design: login and sign-up pages don't load
                    if (Sites.match(uri) != null && Links.isLogin(uri.path)) return true
                    if (resolving && !Rewrites.needsResolve(Sites.match(uri), uri.toString())) {
                        resolving = false
                        open(uri)
                        return true
                    }
                    val real = Uri.parse(Rewrites.unwrap(Sites.match(uri), uri.toString()))
                    when {
                        !shows(real) -> { leave(view, real); true }
                        real != uri -> { view.loadUrl(real.toString()); true }
                        else -> false
                    }
                }
                "intent" -> { openIntentLink(view, uri); true }
                "about", "data", "blob", "javascript" -> false
                else -> true
            }
        }

        /**
         * An intent: link ("open in the app"). If it carries a web link to a handled site that isn't this page, show
         * that here; else follow its web fallback, unless that's just an app store page. Otherwise: nothing.
         */
        private fun openIntentLink(view: WebView, uri: Uri) {
            val intent = runCatching { Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
            val web = intent.data?.takeIf { it.scheme == "http" || it.scheme == "https" }
            if (web != null && shows(web)) {
                val clean = Links.appPath(web)
                if (!Links.samePage(clean, view.url?.let(Uri::parse))) { view.loadUrl(clean.toString()); return }
            }
            val fallback = intent.getStringExtra("browser_fallback_url")?.let(Uri::parse)
                ?.takeIf { (it.scheme == "http" || it.scheme == "https") && !Links.isAppStore(it) } ?: return
            if (shows(fallback)) view.loadUrl(fallback.toString()) else leave(view, fallback)
        }

        /** A link elsewhere: open it outside, and close if it was the first page (nothing here to go back to). */
        private fun leave(view: WebView, uri: Uri) {
            Log.d(TAG, "leave $uri")
            Apps.open(this@ViewerActivity, uri)
            if (view.url.isNullOrEmpty() || view.url == "about:blank") finish()
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            this@ViewerActivity.url = url
            Cleanup.scriptFor(this@ViewerActivity, view, url)?.let { view.evaluateJavascript(it, null) }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            Cleanup.scriptFor(this@ViewerActivity, view, url)?.let { view.evaluateJavascript(it, null) }
            describe(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            this@ViewerActivity.url = url
            if (clearHistoryOnLoad && url != null && url != "about:blank") {
                clearHistoryOnLoad = false
                view.post { view.clearHistory() }
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            loadProgress = newProgress
        }

        /** Full-screen video: the video's view over everything, system bars hidden. */
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreen != null) { callback.onCustomViewHidden(); return }
            fullscreen = view
            fullscreenCallback = callback
            (window.decorView as FrameLayout).addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        override fun onHideCustomView() {
            fullscreen?.let { (window.decorView as FrameLayout).removeView(it) }
            fullscreen = null
            fullscreenCallback = null
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * The page's title and picture for Recents: Its share preview (og:title, og:image) when it has one, else the
 * document title and its largest picture or video poster. Returned as a JSON string.
 */
private const val DESCRIBE = """(function () {
  function meta(n) { var m = document.querySelector('meta[property="' + n + '"], meta[name="' + n + '"]'); return m && m.content; }
  var title = meta("og:title") || meta("twitter:title") || document.title || "";
  var image = meta("og:image") || meta("twitter:image");
  if (!image) {
    var best = null, area = 40000;
    document.querySelectorAll("img, video[poster]").forEach(function (e) {
      var r = e.getBoundingClientRect(), a = r.width * r.height;
      var src = e.tagName === "VIDEO" ? e.poster : (e.currentSrc || e.src);
      if (a > area && src && src.indexOf("http") === 0) { area = a; best = src; }
    });
    image = best;
  }
  return JSON.stringify({ title: title.replace(/\s+/g, " ").trim(), image: image || "" });
})()"""
