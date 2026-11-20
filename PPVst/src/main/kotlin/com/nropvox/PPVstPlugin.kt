package com.nropvox

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PPVstPlugin : Plugin() {
    override fun load(context: Context) {
        PPVstBrowser.init(context)
        registerMainAPI(PPVst())
    }
}
