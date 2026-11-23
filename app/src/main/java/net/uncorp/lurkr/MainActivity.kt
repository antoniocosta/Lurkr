package net.uncorp.lurkr

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.ClipboardManager
import androidx.lifecycle.lifecycleScope
import android.app.UiModeManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch


/** The main screen: Recents (the home), Sites and Settings behind a left menu, like Reedr. */
/** Widths of the arrow and app columns in the site list (fixed, so they line up with the headers). */
private val ARROW = 20.dp
private val APP_COLUMN = 128.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lurkr's Light/Dark/Auto pick is the app's night mode too (Android 12+), so the viewer and the sites in it
        // follow it, not just the phone's setting
        if (Build.VERSION.SDK_INT >= 31) lifecycleScope.launch {
            ThemeStore.flow(this@MainActivity).collect { mode ->
                getSystemService(UiModeManager::class.java).setApplicationNightMode(
                    when (mode) {
                        ThemeMode.AUTO -> UiModeManager.MODE_NIGHT_AUTO
                        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                    }
                )
            }
        }
        setContent {
            AppTheme(this) { Home() }
        }
    }


    /** What Android does with each site's links, re-read whenever Lurkr comes back (e.g. from Android's settings). */
    private class Setup(
        val tick: Int,
        val browsers: List<App>,
        val taken: Map<String, String>,
        val inactive: Set<String>,
        val browser: String?,
    )

    @Composable
    private fun rememberSetup(): Setup {
        var tick by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
        val browsers = remember(tick) { Apps.browsers(this) }
        val approved = remember(tick) { if (Build.VERSION.SDK_INT >= 31) Apps.approvedHosts(this) else null }
        // Sites whose own app (not a browser: that's just "not approved yet") gets the links first
        val taken = remember(tick, browsers) {
            val browserPkgs = browsers.map { it.pkg }.toSet()
            Sites.ALL.mapNotNull { s -> Apps.takenBy(this, s, browserPkgs)?.let { s.id to it } }.toMap()
        }
        // Sites whose links don't all reach Lurkr yet (another app holds them, or they aren't approved): inactive,
        // showing what opens them instead. Android 8–11 asks on the first link, so nothing to check there.
        val inactive = remember(approved, taken) {
            if (approved == null) emptySet()
            else Sites.ALL.filter { s -> s.id in taken || s.hosts.any { approved[it] != true } }.map { it.id }.toSet()
        }
        val browser = remember(tick) { Apps.defaultBrowser(this) }
        return Setup(tick, browsers, taken, inactive, browser)
    }

    private enum class Page(val title: String, val icon: ImageVector) {
        RECENTS("Recents", Icons.Outlined.History),
        SETTINGS("Settings", Icons.Outlined.Settings),
    }

    /** The same frame as Reedr's: A left menu with two pages (Recents, Settings), opening on Recents. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Home() {
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var page by rememberSaveable { mutableStateOf(Page.RECENTS) }
        var pasting by rememberSaveable { mutableStateOf(false) }
        var confirmClear by remember { mutableStateOf(false) }
        var searching by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable { mutableStateOf("") }
        val setup = rememberSetup()
        val recents by remember { Recents.flow(this) }.collectAsState(initial = null)

        // Back: Closes the menu, else goes back to Recents
        BackHandler(enabled = drawer.isOpen || searching || page != Page.RECENTS) {
            when {
                drawer.isOpen -> scope.launch { drawer.close() }
                searching -> { searching = false; query = "" }
                else -> page = Page.RECENTS
            }
        }
        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(240.dp)) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        // The logo, then the name: A tap goes to Recents, the first page
                        Row(
                            Modifier.padding(vertical = 8.dp).clip(RoundedCornerShape(28.dp))
                                .clickable { page = Page.RECENTS; searching = false; query = ""; scope.launch { drawer.close() } }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppIcon(packageName, size = 32.dp)
                            Text("Lurkr", style = MaterialTheme.typography.headlineSmall)
                        }
                        Page.entries.forEach { p ->
                            // Settings below a line, as in Reedr
                            if (p == Page.SETTINGS) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Line)
                            NavigationDrawerItem(
                                icon = { Icon(p.icon, null) }, label = { Text(p.title) }, selected = page == p,
                                onClick = { page = p; searching = false; query = ""; scope.launch { drawer.close() } },
                            )
                        }
                    }
                }
            },
        ) {
            Scaffold(
                containerColor = Paper,
                topBar = {
                    if (searching) {
                        // Searching Recents, as in Reedr: The field in the bar, back closes it
                        val focus = remember { FocusRequester() }
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") } },
                            title = {
                                TextField(
                                    value = query, onValueChange = { query = it }, singleLine = true,
                                    placeholder = { Text("Search Recents") },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                    ),
                                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                )
                            },
                            actions = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                        )
                        LaunchedEffect(Unit) { focus.requestFocus() }
                    } else {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") } },
                            title = { Text(page.title) },
                            actions = {
                                if (page == Page.RECENTS) {
                                    IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Search") }
                                    if (!recents.isNullOrEmpty()) OverflowMenu("Clear all" to { confirmClear = true })
                                }
                            },
                        )
                    }
                },
                floatingActionButton = {
                    if (page == Page.RECENTS && !searching) {
                        FloatingActionButton(onClick = { pasting = true }, containerColor = Blue, contentColor = OnBlue) {
                            Icon(Icons.Default.Add, "Open a link")
                        }
                    }
                },
            ) { padding ->
                when (page) {
                    Page.RECENTS -> recents?.let { RecentsPage(Modifier.padding(padding), setup, it, if (searching) query.trim() else "") { page = Page.SETTINGS } }
                    Page.SETTINGS -> SettingsPage(Modifier.padding(padding), setup)
                }
            }
        }
        if (pasting) PasteDialog(onDismiss = { pasting = false })
        if (confirmClear) {
            val count = recents?.size ?: 0
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("Clear all recents?") },
                text = { Text(if (count == 1) "1 link will be removed." else "$count links will be removed.") },
                confirmButton = {
                    TextButton(onClick = { confirmClear = false; scope.launch { Recents.clear(this@MainActivity) } }) { Text("Clear all", color = Blue) }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = Blue) } },
            )
        }
    }

    /** "⋮" with a menu, as in Reedr. */
    @Composable
    private fun OverflowMenu(vararg items: Pair<String, () -> Unit>) {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
            }
        }
    }

    /** "Open a link": The link on the clipboard filled in, opened in Lurkr's viewer. */
    @Composable
    private fun PasteDialog(onDismiss: () -> Unit) {
        val clip = remember {
            getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this)?.toString()?.let(Links::firstUrl).orEmpty()
        }
        var text by remember { mutableStateOf(clip) }
        val link = Links.firstUrl(text)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Open a link") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = link != null, onClick = {
                    onDismiss()
                    link?.let { open(Uri.parse(it)) }
                }) { Text("Open", color = if (link != null) Blue else Muted) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Blue) } },
        )
    }

    /** A link, in Lurkr's viewer (any website: as if it was shared to Lurkr). */
    private fun open(uri: Uri) {
        startActivity(Intent(Intent.ACTION_VIEW, uri).setClass(this, ViewerActivity::class.java).putExtra(EXTRA_ANY_SITE, true))
    }

    /** Every link Lurkr opened, newest first: Tap one to open it again. The setup alert on top while needed. */
    @Composable
    private fun RecentsPage(modifier: Modifier, setup: Setup, all: List<Lurk>, query: String, onSettings: () -> Unit) {
        // Single posts and videos only (links kept before Lurkr told them apart included)
        val posts = remember(all) { all.filter { Links.postKind(Uri.parse(it.url)) != null } }
        // Searching: By title, site or link
        val recents = remember(posts, query) {
            if (query.isEmpty()) posts else posts.filter { l ->
                val site = Sites.match(Uri.parse(l.url))?.name.orEmpty()
                listOfNotNull(l.title, site, l.url).any { it.contains(query, ignoreCase = true) }
            }
        }
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (setup.inactive.isNotEmpty() && query.isEmpty()) Box(Modifier.padding(horizontal = 20.dp)) { SetupAlert() }
            if (query.isNotEmpty() && recents.isEmpty()) {
                Text("Nothing in Recents matches “$query”", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(20.dp))
            } else if (recents.isEmpty()) {
                // Empty: How links get here
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No lurks yet", color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Step("1", buildAnnotatedString {
                        append("In ")
                        withLink(LinkAnnotation.Clickable("settings", TextLinkStyles(SpanStyle(color = Blue, fontWeight = FontWeight.Bold))) { onSettings() }) {
                            append("Settings")
                        }
                        append(" make Lurkr the default app for the sites it supports.")
                    })
                    Step("2", "Now when you open a link to any of those sites from any other app,")
                    Step("3", "Lurkr shows you those sites as a logged out user, but with all the annoyances fixed and usability fixed.")
                    Text("Alternatively...", color = Ink, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    Step("•", "Share any link to Lurkr from another app.")
                    Step("•", "Just tap + button and paste one.")
                }
            }
            recents.forEach { lurk ->
                LurkRow(lurk)
                HorizontalDivider(Modifier.padding(start = 16.dp), color = Line)
            }
            Spacer(Modifier.height(88.dp)) // Clear of the + button
        }
    }

    /** The sites and where each one opens (Lurkr for the sites it shows, else ask every time, unless another app
     * is picked; "Reset to defaults" undoes that). */
    @Composable
    private fun SitesSection(setup: Setup) {
        val scope = rememberCoroutineScope()
        val routes by remember { RouteStore.flow(this) }.collectAsState(initial = Routes())
        var picking by remember { mutableStateOf<Site?>(null) }
        val inactive = setup.inactive

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Tap a site to open it with another app.", color = Muted, fontSize = 13.sp)
            if (inactive.isNotEmpty()) SetupAlert()
            // Column headers, lined up with the rows below
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Site", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(ARROW))
                Text("Opens with", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(APP_COLUMN))
            }
            HorizontalDivider(color = Line)
            Sites.ALL.forEachIndexed { i, site ->
                SiteRow(site, routes.overrides[site.id], setup.taken[site.id], site.id in inactive, setup.browser) { picking = site }
                // No line between the last site and "Reset to defaults"
                if (i < Sites.ALL.lastIndex || routes.overrides.isEmpty()) HorizontalDivider(color = Line)
            }
            // Only once something differs from the defaults (Lurkr for the sites it shows, ask for the others)
            if (routes.overrides.isNotEmpty()) {
                Text(
                    "Reset to defaults",
                    modifier = Modifier.clickable { scope.launch { RouteStore.reset(this@MainActivity) } }.padding(vertical = 12.dp),
                    color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = Line)
            }
        }

        picking?.let { site ->
            val siteApps = remember(site, setup.tick) { Apps.siteApps(this, site) }
            val (webApps, otherShares) = remember(setup.tick) { Apps.shareTargets(this) }
            Picker(
                title = site.name,
                site = site,
                // The default: Lurkr itself for the sites it shows, else ask every time
                none = if (Sites.viewable(site)) "Lurkr (default)" else "Ask every time (default)",
                noneIcon = if (Sites.viewable(site)) packageName else null,
                // The apps that open the site's links, then browsers
                groups = (if (siteApps.isEmpty()) emptyList() else listOf("Apps for ${site.name}" to siteApps)) +
                    listOf("Browsers" to setup.browsers) +
                    (if (webApps.isEmpty()) emptyList() else listOf("Share to" to webApps)),
                moreShares = otherShares,
                selected = routes.overrides[site.id]?.takeIf { it != packageName },
                onDismiss = { picking = null },
                onPick = { pkg ->
                    picking = null
                    scope.launch { RouteStore.setOverride(this@MainActivity, site, pkg) }
                },
            )
        }
    }

    /** The sites and where each one opens, the theme and the footer, laid out like Reedr's Settings. */
    @Composable
    private fun SettingsPage(modifier: Modifier, setup: Setup) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Section("Sites")
            SitesSection(setup)
            HorizontalDivider(color = Line, modifier = Modifier.padding(top = 12.dp))
            ThemePicker()
            Footer()
        }
    }

    @Composable
    private fun Section(title: String) {
        HorizontalDivider(color = Line, modifier = Modifier.padding(top = 12.dp))
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    }

    /** "Facebook / facebook.com · fb.me …  →  its override (blue, bold), or "Default". */
    @Composable
    private fun SiteRow(site: Site, override: String?, takenBy: String?, inactive: Boolean, browser: String?, onClick: () -> Unit) {
        Column(Modifier.fillMaxWidth().alpha(if (inactive) 0.4f else 1f).clickable(enabled = !inactive, onClick = onClick).padding(vertical = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SiteIcon(site)
                Column(Modifier.weight(1f)) {
                    Text(site.name, color = Ink, fontSize = 15.sp)
                    Text(site.hosts.map { it.removePrefix("*.") }.distinct().joinToString(" · "), color = Muted, fontSize = 12.sp)
                }
                Text("→", color = Ink, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.width(ARROW))
                // What really opens the links: while inactive, the app that holds them or the browser; else the pick
                // (blue, so it stands out), else the default. Fixed width, so the arrows line up.
                val actual = if (inactive) takenBy ?: browser else null
                val pkg = actual ?: override
                Row(
                    Modifier.width(APP_COLUMN),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (inactive && actual == null) {
                        // No default browser: Android asks which browser
                        Text("A browser", color = Muted, fontSize = 13.sp)
                    } else if (pkg == null && Sites.viewable(site)) {
                        // The default for a site Lurkr shows: Lurkr
                        AppIcon(packageName)
                        Text("Lurkr", color = Ink, fontSize = 13.sp, maxLines = 1)
                    } else if (pkg == null) {
                        Text("Ask every time", color = Muted, fontSize = 13.sp)
                    } else {
                        AppIcon(pkg)
                        Text(
                            Apps.label(this@MainActivity, pkg) ?: pkg,
                            color = if (actual == null) Blue else Muted,
                            fontWeight = if (actual == null) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    /** "uncorp.net · v0.1.0": The same footer in every app of the collection. Tap opens the site. */
    @Composable
    private fun Footer() {
        val version = remember { packageManager.getPackageInfo(packageName, 0).versionName }
        Text(
            "uncorp.net · v$version",
            modifier = Modifier.fillMaxWidth()
                .clickable { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://uncorp.net"))) }
                .padding(vertical = 20.dp),
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
    }

    /** A numbered one-line step in "How it works". */
    @Composable
    private fun Step(number: String, text: String) = Step(number, AnnotatedString(text))

    @Composable
    private fun Step(number: String, text: AnnotatedString) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(number, color = Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(14.dp))
            Text(text, color = Ink, fontSize = 14.sp)
        }
    }

    /**
     * A recent, like an article in Reedr: The page's title (bold), the site and when, its picture on the right, and
     * a ⋮ menu (share, open in the browser, remove). Tap opens it again.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun LurkRow(lurk: Lurk) {
        val scope = rememberCoroutineScope()
        val uri = remember(lurk.url) { Uri.parse(lurk.url) }
        val site = remember(lurk.url) { Sites.match(uri) }
        var menu by remember { mutableStateOf(false) }
        val picture by produceState<ImageBitmap?>(null, lurk.url, lurk.title) {
            value = withContext(Dispatchers.IO) {
                Recents.thumb(this@MainActivity, lurk.url).takeIf { it.exists() }
                    ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
        }
        // Without a title yet: What it is ("Video on YouTube"), not the link
        val host = uri.host.orEmpty().removePrefix("www.")
        val fallback = remember(lurk.url) { Links.postKind(uri)?.let { kind -> site?.let { "$kind on ${it.name}" } } ?: host }
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { open(uri) }, onLongClick = { menu = true })
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(lurk.title ?: fallback, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    site?.let { SiteIcon(it, size = 14.dp) }
                    val time = if (System.currentTimeMillis() - lurk.at < DateUtils.MINUTE_IN_MILLIS) "Just now"
                        else DateUtils.getRelativeTimeSpanString(lurk.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    Text(listOf(site?.name ?: host, time).joinToString(" · "), color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            picture?.let {
                Spacer(Modifier.width(12.dp))
                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(6.dp)))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share link") }, onClick = { menu = false; Apps.share(this@MainActivity, lurk.url) })
                    DropdownMenuItem(text = { Text("Open original") }, onClick = { menu = false; Apps.inBrowser(this@MainActivity, uri) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; scope.launch { Recents.remove(this@MainActivity, lurk.url) } })
                }
            }
        }
    }

    /** While some sites' links don't reach Lurkr (Android 12+): a short alert; Fix opens Lurkr's "Open by default". */
    @Composable
    private fun SetupAlert() {
        // Error red (Material's), the only place Lurkr uses it: a faint red box, red icon and title
        val dark = LocalPalette.current.dark
        val red = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E)
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(red.copy(alpha = if (dark) 0.14f else 0.08f))
                .padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TintedIcon(R.drawable.ic_warning, null, size = 20.dp, color = red)
                Text("Lurkr isn't set up", color = red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Tap Fix, then “${SettingsLabels.of(this@MainActivity).addLink}” and select all.",
                color = Ink, fontSize = 14.sp, modifier = Modifier.padding(start = 30.dp, top = 4.dp, end = 10.dp),
            )
            TextButton(
                onClick = { startActivity(Apps.openByDefaultSettings(packageName)) },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Fix", color = red, fontWeight = FontWeight.Bold) }
        }
    }

    /** Pick an app: a "none" choice (ask / default) on top, then the apps in groups. */
    @Composable
    private fun Picker(
        title: String,
        site: Site?,
        none: String,
        noneIcon: String?,
        groups: List<Pair<String, List<App>>>,
        moreShares: List<App>,
        selected: String?,
        onDismiss: () -> Unit,
        onPick: (String?) -> Unit,
    ) {
        var showMore by remember { mutableStateOf(moreShares.any { it.value == selected }) }
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Paper,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    site?.let { SiteIcon(it) }
                    Text(title, color = Ink)
                }
            },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item { PickerRow(noneIcon, none, selected == null) { onPick(null) } }
                    groups.forEach { (header, apps) ->
                        item { Text(header, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                        items(apps, key = { header + it.value }) { app -> PickerRow(app.pkg, app.label, selected == app.value) { onPick(app.value) } }
                    }
                    // Every other app that takes a shared link: hidden behind "Other apps…" (there are dozens),
                    // open from the start if the pick is one of them
                    if (moreShares.isNotEmpty()) {
                        if (!showMore) {
                            item {
                                Text(
                                    "Other apps…",
                                    color = Blue, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.fillMaxWidth().clickable { showMore = true }.padding(horizontal = 8.dp, vertical = 12.dp),
                                )
                            }
                        } else {
                            if (groups.none { it.first == "Share to" }) {
                                item { Text("Share to", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                            }
                            items(moreShares, key = { "more" + it.value }) { app -> PickerRow(app.pkg, app.label, selected == app.value) { onPick(app.value) } }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Blue) } },
        )
    }

    @Composable
    private fun PickerRow(pkg: String?, label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth()
                .alpha(if (enabled) 1f else 0.4f)
                .background(if (selected && enabled) Blue else Color.Transparent, RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(pkg, keepSpace = true)
            Text(label, color = if (selected && enabled) OnBlue else Ink, fontSize = 15.sp)
        }
    }
}
