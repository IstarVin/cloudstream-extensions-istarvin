package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KayoanimePlugin : Plugin() {
    companion object {
        /**
         * This plugin's settings for Cloudstream Desktop's web UI, which can't show the Android sheet: one entry per
         * preference key (string, secret, boolean, int or choice). Android ignores it.
         */
        const val SETTINGS_SCHEMA = """[
            {"file": "${Kayoanime.PREF_FILE}", "key": "${Kayoanime.PROXY_URL_KEY}", "type": "string", "label": "Google Drive proxy URL",
             "help": "Episodes are Google Drive files served by your own Drive proxy. Leave it empty to use the default.",
             "default": "${Kayoanime.DEFAULT_PROXY_URL}"},
            {"file": "${Kayoanime.PREF_FILE}", "key": "${Kayoanime.PROXY_KEY_KEY}", "type": "secret", "label": "Proxy API key",
             "help": "The key your Drive proxy expects with every request. Posts hosted on the site's Drive index mirrors play without it."}
        ]"""
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(Kayoanime.PREF_FILE, Context.MODE_PRIVATE)
        registerMainAPI(Kayoanime(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                PluginSettingsSheet(
                    this, "Kayoanime", kayoanimeSettings(sharedPref),
                    "Keys are stored on this device only. Changes apply to links loaded after saving; an episode you already opened keeps its cached links.",
                ).show(activity.supportFragmentManager, "kayoanime_settings")
            }
        }
    }
}
