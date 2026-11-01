package com.nropvox

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OmniStreamPlugin : Plugin() {
    companion object {
        /**
         * This plugin's settings for Cloudstream Desktop's web UI, which can't show the Android sheet: one entry per
         * preference key (string, secret, boolean, int or choice). Android ignores it.
         */
        const val SETTINGS_SCHEMA = """[
            {"file": "${OmniSettings.PREF_FILE}", "key": "${OmniSettings.PIXELDRAIN_API_KEY}", "type": "secret", "label": "Pixeldrain API key",
             "help": "Sent with every Pixeldrain link. Without one, links play as an anonymous visitor."}
        ]"""
    }

    override fun load(context: Context) {
        OmniSettings.init(context.getSharedPreferences(OmniSettings.PREF_FILE, Context.MODE_PRIVATE))
        registerMainAPI(OmniStream())
        registerMainAPI(OmniAnime())

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                OmniStreamSettingsFragment(this).show(activity.supportFragmentManager, "omnistream_settings")
            }
        }
    }
}

/** User settings in the plugin's SharedPreferences, read on every use so a saved change applies to the next loadLinks. */
internal object OmniSettings {
    const val PREF_FILE = "OmniStream"
    const val PIXELDRAIN_API_KEY = "pixeldrain_api_key"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    /** Sent with every Pixeldrain link (see [OmniLinkRace]); empty when unset. */
    var pixeldrainKey: String
        get() = prefs?.getString(PIXELDRAIN_API_KEY, null)?.trim().orEmpty()
        set(value) {
            prefs?.edit { putString(PIXELDRAIN_API_KEY, value.trim()) }
        }
}
