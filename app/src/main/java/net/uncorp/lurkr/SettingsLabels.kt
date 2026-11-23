package net.uncorp.lurkr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The words Android's own "Open by default" screen uses ("Add link"), read from the phone's Settings app, so Lurkr's
 * instructions match what the user sees, in their language. Falls back to English if Settings can't be read.
 */
class SettingsLabels(val addLink: String) {

    companion object {
        private var cached: SettingsLabels? = null

        fun of(ctx: Context): SettingsLabels = cached ?: read(ctx).also { cached = it }

        @SuppressLint("DiscouragedApi")  // another app's resources: only reachable by name
        private fun read(ctx: Context): SettingsLabels {
            val pm = ctx.packageManager
            val settings = runCatching {
                val open = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                pm.resolveActivity(open, 0)?.activityInfo?.packageName
            }.getOrNull() ?: "com.android.settings"
            val res = runCatching { pm.getResourcesForApplication(settings) }.getOrNull()
            fun text(name: String): String? = res?.let { r ->
                r.getIdentifier(name, "string", settings).takeIf { it != 0 }?.let { runCatching { r.getString(it) }.getOrNull() }
            }?.trim()?.takeIf { it.isNotEmpty() }
            return SettingsLabels(addLink = text("app_launch_add_link") ?: "Add link")
        }
    }
}
