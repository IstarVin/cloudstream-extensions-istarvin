package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JavHDPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "JavHD"

        /**
         * This plugin's settings for Cloudstream Desktop's web UI, which can't show the Android sheet: one entry per
         * preference key (string, secret, boolean, int or choice). Android ignores it.
         */
        const val SETTINGS_SCHEMA = """[
            {"file": "$PREF_FILE", "key": "$BASE_URL_PREF_KEY", "type": "string", "label": "Base URL",
             "help": "The site's address changes now and then. Set the new one here when it does; leave it empty to use the default.",
             "default": "$DEFAULT_MAIN_URL"},
            {"file": "$PREF_FILE", "key": "$GOOGLE_TRANSLATE_API_KEY_PREF_KEY", "type": "secret", "label": "Google Translate API key",
             "help": "Titles on this site are Vietnamese. With a Cloud Translation API key (console.cloud.google.com/apis/credentials) they are shown in English."}
        ]"""
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        registerMainAPI(JavHD(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                PluginSettingsSheet(
                    this, "JavHD", javHDSettings(sharedPref),
                    "Keys are stored on this device only. A new base URL applies the next time a page loads.",
                ).show(activity.supportFragmentManager, "javhd_settings")
            }
        }
    }
}