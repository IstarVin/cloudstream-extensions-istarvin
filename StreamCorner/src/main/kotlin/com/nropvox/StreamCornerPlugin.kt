package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamCornerPlugin : Plugin() {
    companion object {
        /**
         * This plugin's settings for Cloudstream Desktop's web UI, which can't show the Android sheet: one entry per
         * preference key (string, secret, boolean, int or choice). Android ignores it.
         */
        const val SETTINGS_SCHEMA = """[
            {"file": "${StreamCorner.PREF_FILE}", "key": "${StreamCorner.MIRROR_KEY}", "type": "string", "label": "Mirror URL",
             "help": "Use another StreamCorner mirror (the current ones are listed at ${StreamCorner.MIRRORS_PAGE}). Leave it empty to use the default. Restart the app after changing it.",
             "default": "${StreamCorner.DEFAULT_URL}"}
        ]"""
    }

    // A Plugin rather than a BasePlugin: the WebViews that run the site's decoder need a Context.
    override fun load(context: Context) {
        val app = context.applicationContext
        val sharedPref = context.getSharedPreferences(StreamCorner.PREF_FILE, Context.MODE_PRIVATE)
        val mirror = sharedPref.getString(StreamCorner.MIRROR_KEY, "").orEmpty()
        registerMainAPI(StreamCorner({ origin -> StreamCornerBridge(app, origin) }, mirror))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                PluginSettingsSheet(
                    this, "StreamCorner", streamCornerSettings(sharedPref),
                    "Changes take effect after you restart the app. Links you already opened keep their cached streams.",
                ).show(activity.supportFragmentManager, "streamcorner_settings")
            }
        }
    }
}
