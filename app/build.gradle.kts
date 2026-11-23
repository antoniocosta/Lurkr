plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.uncorp.lurkr"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.uncorp.lurkr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Personal sideloading: signed with the local debug key, so it installs over debug builds
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
}

/*
 * The sites: one folder each in ../sites (site.json, logo.svg, optional filters.txt and site.js; see README.md,
 * "Adding a site"). This task checks them and generates everything else, so adding a site is just adding a folder:
 * - the site list (SiteList.kt), with each site's domains, link rules and logo
 * - the domains in the router's intent filter (a manifest merged into AndroidManifest.xml)
 * - the logos as vector drawables (site_<id>.xml), in the site's colour
 * - the site's files as assets (sites/<id>/…), for the page cleanup
 */
abstract class GenSites : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sitesDir: DirectoryProperty

    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty
    @get:OutputFile abstract val manifest: RegularFileProperty
    @get:OutputDirectory abstract val resDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    private val topKeys = setOf("\$schema", "name", "color", "domains", "view", "unwrap", "resolve", "rewrite", "page")
    private val pageKeys = setOf("click", "hideText", "decline")
    private val domain = Regex("""^(\*\.)?([a-z0-9-]+\.)+[a-z]{2,}$""")

    @TaskAction
    fun run() {
        val errors = mutableListOf<String>()
        val folders = sitesDir.get().asFile.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        @Suppress("UNCHECKED_CAST")
        val sites = folders.mapNotNull { dir ->
            val id = dir.name
            fun err(msg: String) { errors += "sites/$id: $msg" }
            if (!Regex("^[a-z0-9]+$").matches(id)) err("folder name must be lowercase letters and digits")
            val json = File(dir, "site.json")
            if (!json.exists()) { err("no site.json"); return@mapNotNull null }
            val s = runCatching { groovy.json.JsonSlurper().parse(json) as Map<String, Any?> }
                .getOrElse { err("site.json isn't valid JSON: ${it.message}"); return@mapNotNull null }
            (s.keys - topKeys).forEach { err("site.json: unknown key \"$it\"") }
            ((s["page"] as? Map<String, Any?>)?.keys.orEmpty() - pageKeys).forEach { err("site.json: unknown key \"page.$it\"") }
            if ((s["name"] as? String).isNullOrBlank()) err("site.json: \"name\" is missing")
            if (!Regex("^[0-9A-Fa-f]{6}$").matches(s["color"] as? String ?: "")) err("site.json: \"color\" must be 6 hex digits, e.g. \"FF0069\"")
            if (s["view"] !is Boolean) err("site.json: \"view\" must be true or false")
            val domains = (s["domains"] as? List<String>).orEmpty()
            if (domains.isEmpty()) err("site.json: \"domains\" is missing")
            domains.filterNot { domain.matches(it) }.forEach { err("site.json: \"$it\" isn't a domain (example.com or *.example.com)") }
            if (s["view"] == true && !File(dir, "filters.txt").exists() && !File(dir, "site.js").exists() && s["page"] == null) {
                logger.info("sites/$id: shown in Lurkr with the shared cleanup only")
            }
            val logo = File(dir, "logo.svg")
            val path = if (logo.exists()) Regex("""<path[^>]* d="([^"]+)"""").findAll(logo.readText()).joinToString(" ") { it.groupValues[1] } else ""
            if (path.isEmpty()) err("logo.svg is missing or has no <path d=\"…\">")
            val viewBox = Regex("""viewBox="([^"]+)"""").find(logo.takeIf { it.exists() }?.readText() ?: "")?.groupValues?.get(1) ?: "0 0 24 24"
            Triple(id, s, path to viewBox)
        }
        val allDomains = sites.flatMap { (id, s, _) -> (s["domains"] as? List<*>).orEmpty().map { it to id } }
        allDomains.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
            .forEach { (d, ids) -> errors += "\"$d\" is in more than one site: ${ids.joinToString()}" }
        if (sites.isEmpty()) errors += "no sites in ${sitesDir.get().asFile}"
        if (errors.isNotEmpty()) throw GradleException("Problems in sites/:\n" + errors.joinToString("\n") { "  - $it" })

        fun q(v: Any?) = "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        @Suppress("UNCHECKED_CAST")
        fun strs(v: Any?) = (v as? List<Any?>).orEmpty().joinToString(", ", "listOf(", ")") { q(it) }
        val byName = sites.sortedBy { (_, s, _) -> (s["name"] as String).lowercase() }

        // SiteList.kt
        val kt = StringBuilder()
        kt.append("package net.uncorp.lurkr\n\n")
        kt.append("// Generated from sites/*/site.json by the build (GenSites in app/build.gradle.kts). Do not edit.\n\n")
        kt.append("internal val SITE_LIST: List<Site> = listOf(\n")
        @Suppress("UNCHECKED_CAST")
        byName.forEach { (id, s, _) ->
            val unwrap = (s["unwrap"] as? List<Map<String, Any?>>).orEmpty().joinToString(", ", "listOf(", ")") { "Unwrap(${q(it["host"])}, ${q(it["param"])})" }
            val resolve = s["resolve"] as? Map<String, Any?>
            val rewrites = (s["rewrite"] as? List<Map<String, Any?>>).orEmpty().joinToString(",\n            ", "listOf(\n            ", ",\n        )") { r ->
                val add = (r["addQuery"] as? Map<String, Any?>).orEmpty().entries.joinToString(", ", "mapOf(", ")") { "${q(it.key)} to ${q(it.value)}" }
                "Rewrite(path = ${q(r["path"])}, to = ${r["to"]?.let(::q) ?: "null"}, addQuery = $add)"
            }
            kt.append("    Site(\n")
            kt.append("        id = ${q(id)}, name = ${q(s["name"])}, view = ${s["view"]},\n")
            kt.append("        hosts = ${strs(s["domains"])},\n")
            kt.append("        logo = R.drawable.site_$id, ink = ${(s["color"] as String).equals("000000", true)},\n")
            kt.append("        unwrap = $unwrap,\n")
            kt.append("        resolveHosts = ${strs(resolve?.get("hosts"))}, resolvePaths = ${strs(resolve?.get("paths"))},\n")
            kt.append("        rewrites = ${if (rewrites.contains("Rewrite(")) rewrites else "listOf()"},\n")
            kt.append("    ),\n")
        }
        kt.append(")\n")
        val ktDir = kotlinDir.get().asFile.also { it.deleteRecursively() }.resolve("net/uncorp/lurkr").also { it.mkdirs() }
        File(ktDir, "SiteList.kt").writeText(kt.toString())

        // The router's domains, merged into AndroidManifest.xml
        val hosts = byName.flatMap { (_, s, _) -> (s["domains"] as List<*>) }.joinToString("") { "                <data android:host=\"$it\" />\n" }
        // And the apps that open those domains (the YouTube app, NewPipe …), so Lurkr can see them (Android 11+ only
        // shows an app the apps it asks about): one query per domain, "*.x.com" asked as "www.x.com"
        val queries = byName.flatMap { (_, s, _) -> (s["domains"] as List<*>) }.joinToString("") {
            val host = it.toString().let { d -> if (d.startsWith("*.")) "www" + d.substring(1) else d }
            "        <intent>\n            <action android:name=\"android.intent.action.VIEW\" />\n" +
                "            <data android:scheme=\"https\" android:host=\"$host\" />\n        </intent>\n"
        }
        manifest.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from sites/*/site.json by the build. Do not edit. -->
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |    <queries>
            |""".trimMargin() + "\n" + queries + """
            |    </queries>
            |    <application>
            |        <!-- No autoVerify: we don't own these domains, so the user approves them in
            |             Settings → Apps → Lurkr → Open by default → Add link. -->
            |        <activity android:name="net.uncorp.lurkr.RouterActivity">
            |            <intent-filter>
            |                <action android:name="android.intent.action.VIEW" />
            |                <category android:name="android.intent.category.DEFAULT" />
            |                <category android:name="android.intent.category.BROWSABLE" />
            |                <data android:scheme="http" />
            |                <data android:scheme="https" />
            |""".trimMargin() + "\n" + hosts + """
            |            </intent-filter>
            |        </activity>
            |    </application>
            |</manifest>
            |""".trimMargin(),
        )

        // Logos: one vector drawable per site, in its colour
        val drawables = resDir.get().asFile.also { it.deleteRecursively() }.resolve("drawable").also { it.mkdirs() }
        sites.forEach { (id, s, logo) ->
            val (path, viewBox) = logo
            val (_, _, w, h) = viewBox.trim().split(Regex("[\\s,]+")).map { it.toFloat() }
            File(drawables, "site_$id.xml").writeText(
                """
                |<?xml version="1.0" encoding="utf-8"?>
                |<!-- Generated from sites/$id/logo.svg by the build. Do not edit. -->
                |<vector xmlns:android="http://schemas.android.com/apk/res/android"
                |    android:width="24dp"
                |    android:height="24dp"
                |    android:viewportWidth="$w"
                |    android:viewportHeight="$h">
                |    <path android:fillColor="#FF${(s["color"] as String).uppercase()}" android:pathData="${normalise(path)}" />
                |</vector>
                |""".trimMargin(),
            )
        }

        // The sites' files, for the page cleanup
        val assets = assetsDir.get().asFile.also { it.deleteRecursively() }
        sites.forEach { (id, _, _) ->
            val out = File(assets, "sites/$id").also { it.mkdirs() }
            listOf("site.json", "filters.txt", "site.js").map { File(sitesDir.get().asFile, "$id/$it") }
                .filter { it.exists() }.forEach { it.copyTo(File(out, it.name), overwrite = true) }
        }
    }

    /**
     * Path data with every number separated: Android's parser misreads packed arc flags such as "a19.8 19.8 0 00-4.9",
     * which SVG allows (flags are single 0/1 digits).
     */
    private fun normalise(d: String): String {
        val num = Regex("""[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""")
        val out = mutableListOf<String>()
        var i = 0
        var cmd = ' '
        var n = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() -> { cmd = c; n = 0; out += c.toString(); i++ }
                c == ' ' || c == ',' || c == '\t' || c == '\n' -> i++
                (cmd == 'a' || cmd == 'A') && (n % 7 == 3 || n % 7 == 4) -> { out += c.toString(); n++; i++ }
                else -> {
                    val m = num.find(d, i)?.takeIf { it.range.first == i } ?: throw GradleException("logo.svg: can't read path data at \"${d.substring(i).take(20)}\"")
                    out += m.value; n++; i = m.range.last + 1
                }
            }
        }
        return out.joinToString(" ")
    }
}

val genSites = tasks.register<GenSites>("genSites") {
    sitesDir.set(layout.projectDirectory.dir("../sites"))
    kotlinDir.set(layout.buildDirectory.dir("generated/sites/kotlin"))
    manifest.set(layout.buildDirectory.file("generated/sites/AndroidManifest.xml"))
    resDir.set(layout.buildDirectory.dir("generated/sites/res"))
    assetsDir.set(layout.buildDirectory.dir("generated/sites/assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(genSites, GenSites::kotlinDir)
        variant.sources.res?.addGeneratedSourceDirectory(genSites, GenSites::resDir)
        variant.sources.assets?.addGeneratedSourceDirectory(genSites, GenSites::assetsDir)
        variant.sources.manifests.addGeneratedManifestFile(genSites, GenSites::manifest)
    }
}
