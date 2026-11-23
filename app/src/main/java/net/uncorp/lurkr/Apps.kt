package net.uncorp.lurkr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * An app Lurkr can send a site's links to. [value] is what a pick stores: the package, to open links with it, or
 * "share:<package>/<activity>", to share them to it (see [Apps.shareTargets]).
 */
data class App(val pkg: String, val label: String, val value: String = pkg)

/** Other apps: where links Lurkr doesn't show go, who gets the sites' links, and what can open them. */
object Apps {
    /**
     * A link Lurkr doesn't show (tapped inside a post, or sent here): opened the normal Android way (the site's own
     * app, the browser). If Android gives it to Lurkr, a social site goes through Lurkr's router; anything else to
     * the browser, never back into the viewer.
     */
    fun open(ctx: Context, uri: Uri) {
        val intent = view(uri)
        when {
            // A site whose links Android gives Lurkr (e.g. YouTube): Lurkr's router, so it goes where it's set to
            defaultFor(ctx, intent) == ctx.packageName && Sites.match(uri) != null ->
                start(ctx, intent.setClass(ctx, RouterActivity::class.java))
            defaultFor(ctx, intent) == ctx.packageName -> inBrowser(ctx, uri)
            else -> start(ctx, intent)
        }
    }

    /**
     * Straight to the user's browser, skipping the sites' apps: for links Lurkr was sent but doesn't show and for
     * "Open in browser".
     */
    fun inBrowser(ctx: Context, uri: Uri) {
        val browser = defaultFor(ctx, view(Uri.parse("https://lurkr.invalid/")))
        if (browser != null && browser != ctx.packageName && browser != "android") {
            start(ctx, view(uri).setPackage(browser))
        } else {
            // No default browser (or it's Lurkr): Android's chooser, without Lurkr in it
            val chooser = Intent.createChooser(view(uri), null)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, RouterActivity::class.java)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            start(ctx, chooser)
        }
    }

    /** The default browser's package; null when there's none (Android asks). */
    fun defaultBrowser(ctx: Context): String? =
        defaultFor(ctx, view(Uri.parse("https://lurkr.invalid/")))?.takeIf { it != ctx.packageName && it != "android" }

    fun defaultBrowserLabel(ctx: Context): String? = defaultBrowser(ctx)?.let { label(ctx, it) }

    fun share(ctx: Context, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        start(ctx, Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A pick's package: the package itself, or the one in "share:<package>/<activity>". */
    fun pkgOf(value: String): String = value.removePrefix(SHARE).substringBefore('/')

    /** The share activity in a "share:<package>/<activity>" pick; null for a plain package. */
    fun shareComponent(value: String): ComponentName? =
        value.takeIf { it.startsWith(SHARE) }?.removePrefix(SHARE)?.split('/', limit = 2)
            ?.takeIf { it.size == 2 }?.let { ComponentName(it[0], it[1]) }

    fun label(ctx: Context, value: String): String? = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationInfo(pkgOf(value), 0).loadLabel(pm).toString()
    }.getOrNull()

    fun icon(ctx: Context, value: String): Drawable? = runCatching { ctx.packageManager.getApplicationIcon(pkgOf(value)) }.getOrNull()

    fun installed(ctx: Context, pkg: String) = label(ctx, pkg) != null

    /** Every browser but Lurkr: apps that open any website (asked with a made-up domain). */
    fun browsers(ctx: Context): List<App> = resolve(ctx, "https://lurkr.invalid/")

    /**
     * Apps made for this site (e.g. the TikTok app): they open its links but aren't browsers.
     */
    fun siteApps(ctx: Context, site: Site): List<App> {
        val browsers = browsers(ctx).map { it.pkg }.toSet()
        return site.hosts.map(Sites::sampleUrl).flatMap { resolve(ctx, it) }
            .distinctBy { it.pkg }.filter { it.pkg !in browsers }
    }

    /**
     * Apps that take a shared link (Share → app), for sending a site's links somewhere that can't open them as links:
     * installed web apps with a share target first ([webApps]), and every other app that takes shared text.
     */
    fun shareTargets(ctx: Context): Pair<List<App>, List<App>> {
        val pm = ctx.packageManager
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(send, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(send, 0)
        }
        val all = found.map { it.activityInfo }
            .filter { it.packageName != ctx.packageName }
            .map { a -> App(a.packageName, a.loadLabel(pm).toString(), "$SHARE${a.packageName}/${a.name}") }
            .distinctBy { it.value }
            .sortedBy { it.label.lowercase() }
        return all.partition { webApp(it.pkg) }
    }

    /** A web app installed from a browser (Chrome's "Install app" makes a WebAPK). */
    private fun webApp(pkg: String) = ".webapk." in pkg || pkg.endsWith(".webapk")

    private const val SHARE = "share:"

    private fun resolve(ctx: Context, url: String): List<App> {
        val pm = ctx.packageManager
        val intent = view(Uri.parse(url))
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return found.map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName }
            .map { App(it, label(ctx, it) ?: it) }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The app (not a browser, not Lurkr) that gets this site's links before Lurkr: the site's own
     * app set to open its links itself ("Open supported links" on; "In the app" on newer Androids). Null if they reach Lurkr or nobody has them yet.
     */
    fun takenBy(ctx: Context, site: Site, browsers: Set<String>): String? =
        site.hosts.map(Sites::sampleUrl)
            .firstNotNullOfOrNull { url ->
                defaultFor(ctx, view(Uri.parse(url)))?.takeIf { it != ctx.packageName && it != "android" && it !in browsers }
            }

    /** An app's "Open by default" screen (Lurkr's own, to approve its links; another's, to turn them off). */
    fun openByDefaultSettings(pkg: String): Intent =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:$pkg"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    /**
     * Per host: has the user approved Lurkr to open it (Settings → Open by default)?
     * Empty before Android 12, where there's nothing to approve (Android asks on first use).
     */
    @RequiresApi(31)
    fun approvedHosts(ctx: Context): Map<String, Boolean> {
        val state = ctx.getSystemService(DomainVerificationManager::class.java)
            .getDomainVerificationUserState(ctx.packageName) ?: return emptyMap()
        return state.hostToStateMap.mapValues { (_, s) ->
            state.isLinkHandlingAllowed && s != DomainVerificationUserState.DOMAIN_STATE_NONE
        }
    }

    private fun view(uri: Uri) = Intent(Intent.ACTION_VIEW, uri)
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Who Android sends this intent to without asking ("android" = it would ask). */
    private fun defaultFor(ctx: Context, intent: Intent): String? {
        val pm = ctx.packageManager
        return if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }?.activityInfo?.packageName
    }

    private fun start(ctx: Context, intent: Intent) {
        runCatching { ctx.startActivity(intent) }
    }
}
