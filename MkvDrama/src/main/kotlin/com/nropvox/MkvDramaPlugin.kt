package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MkvDramaPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "MkvDrama"

        /**
         * This plugin's settings for Cloudstream Desktop's web UI, which can't show the Android sheet: one entry per
         * preference key (string, secret, boolean, int or choice). Android ignores it.
         */
        const val SETTINGS_SCHEMA = """[
            {"file": "$PREF_FILE", "key": "$PIXELDRAIN_API_KEY_PREF_KEY", "type": "secret", "label": "Pixeldrain API key",
             "help": "Sent with every Pixeldrain link. Without one, links play as an anonymous visitor."}
        ]"""
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        registerMainAPI(MkvDrama(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                PluginSettingsSheet(
                    this, "MkvDrama", mkvDramaSettings(sharedPref),
                    "Keys are stored on this device only. Changes apply to links loaded after saving; an episode you already opened keeps its cached links.",
                ).show(activity.supportFragmentManager, "mkvdrama_settings")
            }
        }
    }
}
