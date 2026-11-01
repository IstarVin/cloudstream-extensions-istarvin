package com.nropvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KayoanimePlugin : Plugin() {
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
