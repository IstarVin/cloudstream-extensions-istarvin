package com.istarvin

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JavHDPlugin : Plugin() {
    companion object {
        private const val PREF_FILE = "JavHD"
    }

    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        registerMainAPI(JavHD(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            if (!activity.isFinishing && !activity.isDestroyed) {
                JavHDSettingsFragment(this, sharedPref)
                    .show(activity.supportFragmentManager, "javhd_settings")
            }
        }
    }
}