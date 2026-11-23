package net.uncorp.lurkr

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Gets every link Android sends Lurkr (its sites' links) and every link shared to it. A site's link goes to the app
 * picked for it, flashing a small "logo → app" card on the way; with nothing picked, a site Lurkr can show opens in
 * its viewer, and any other site asks which app to use. A shared link to a site Lurkr can
 * show opens in the viewer; a shared link to any other website too, with the shared cleanup only.
 */
class RouterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> Links.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(Uri::parse)
            else -> null
        }?.takeIf { it.scheme == "http" || it.scheme == "https" }
        if (uri == null) {
            finish()
            return
        }
        val site = Sites.match(uri)
        if (site == null) {
            // Any other website: shared to Lurkr, so shown here with the shared cleanup; else to the browser
            if (intent.action == Intent.ACTION_SEND) {
                startActivity(
                    Intent(Intent.ACTION_VIEW, uri, this, ViewerActivity::class.java)
                        .putExtra(EXTRA_ANY_SITE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                Apps.inBrowser(this, uri)
            }
            finish()
            return
        }
        if (intent.action == Intent.ACTION_SEND && Sites.viewable(site)) {
            view(uri)
            return
        }
        lifecycleScope.launch {
            val picked = RouteStore.flow(this@RouterActivity).first().target(site, packageName)
                ?.takeIf { Apps.installed(this@RouterActivity, it) } // uninstalled since it was picked: ask
            // Lurkr for a site it can't show yet: ask, as if nothing was picked
            val target = picked?.takeIf { it != packageName || Sites.viewable(site) }
            Log.d(TAG, "${uri.host} (${site.id}) -> ${target ?: "ask"}")
            when (target) {
                null -> {
                    // Ask. Not the system chooser: on Android 12+ it only offers the app approved for the domain
                    // (Lurkr itself), and it shows at most 2 extra apps, so Lurkr lists them itself.
                    // The apps that open the site's links first (the YouTube app, NewPipe …), then the browsers
                    val apps = (Apps.siteApps(this@RouterActivity, site) + Apps.browsers(this@RouterActivity)).distinctBy { it.pkg }
                    val all = if (Sites.viewable(site)) listOf(App(packageName, "Lurkr")) + apps else apps
                    setContent { AppTheme(this@RouterActivity) { Ask(uri, site, all) } }
                }
                packageName -> view(uri)
                else -> {
                    setContent { AppTheme(this@RouterActivity) { Flash(site, target) } }
                    delay(FLASH_MS)
                    open(uri, target)
                }
            }
        }
    }

    /** Lurkr's own viewer. */
    private fun view(uri: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW, uri, this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    /** Opens the link with [pkg], or shares it to the app when the pick is a share target ("share:…"). */
    private fun open(uri: Uri, pkg: String) {
        if (pkg == packageName) return view(uri)
        val share = Apps.shareComponent(pkg)
        val view = if (share != null) {
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri.toString())
                .setComponent(share).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage(pkg)
        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "$pkg can't open $uri", e)
        }
        finish()
    }

    /** "Open instagram.com with" and the apps that can; tap outside (or back) to cancel. */
    @Composable
    private fun Ask(uri: Uri, site: Site?, apps: List<App>) {
        Box(
            Modifier.fillMaxSize().background(Color(0x66000000)).clickable(interactionSource = null, indication = null) { finish() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .padding(32.dp)
                    .widthIn(max = 360.dp)
                    .background(Paper, RoundedCornerShape(20.dp))
                    .clickable(interactionSource = null, indication = null) {} // taps on the card don't cancel
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    Modifier.padding(start = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    site?.let { SiteIcon(it) }
                    Text("Open ${uri.host?.removePrefix("www.")} with", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { open(uri, app.pkg) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(app.pkg)
                            Text(app.label, color = Ink, fontSize = 15.sp)
                        }
                    }
                    if (apps.isEmpty()) Text("No browser installed.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    /** "[Instagram logo] → [Brave icon]" on a small card, popping in at the centre. */
    @Composable
    private fun Flash(site: Site, pkg: String) {
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = 0.85f)) {
                Row(
                    Modifier
                        .shadow(12.dp, RoundedCornerShape(20.dp))
                        .background(Paper, RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SiteIcon(site, 44.dp)
                    Text("→", color = Muted, fontSize = 22.sp)
                    AppIcon(pkg, 44.dp)
                }
            }
        }
    }

    private companion object {
        const val TAG = "Lurkr"
        /** How long the card shows before the link opens: long enough to see, short enough not to wait for. */
        const val FLASH_MS = 600L
    }
}
