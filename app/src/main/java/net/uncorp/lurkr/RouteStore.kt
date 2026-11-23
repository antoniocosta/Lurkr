package net.uncorp.lurkr

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.routes by preferencesDataStore("routes")
private fun siteKey(id: String) = stringPreferencesKey("site_$id")

/**
 * Which app opens what: per-site picks ([overrides]). Without one, a site Lurkr can show opens in Lurkr, and any
 * other site asks every time.
 */
data class Routes(val overrides: Map<String, String> = emptyMap()) {
    /** The app for this site's links: its pick, else [lurkr] for a site Lurkr can show, else null (ask). */
    fun target(site: Site?, lurkr: String): String? = site?.let { overrides[it.id] } ?: lurkr.takeIf { Sites.viewable(site) }
}

object RouteStore {
    fun flow(ctx: Context): Flow<Routes> = ctx.applicationContext.routes.data.map { p ->
        Routes(overrides = Sites.ALL.mapNotNull { s -> p[siteKey(s.id)]?.let { s.id to it } }.toMap())
    }

    /** null = back to the default. */
    suspend fun setOverride(ctx: Context, site: Site, pkg: String?) {
        ctx.applicationContext.routes.edit { if (pkg == null) it.remove(siteKey(site.id)) else it[siteKey(site.id)] = pkg }
    }

    /** Every site back to its default (also drops the old "all sites" setting). */
    suspend fun reset(ctx: Context) {
        ctx.applicationContext.routes.edit { it.clear() }
    }
}
