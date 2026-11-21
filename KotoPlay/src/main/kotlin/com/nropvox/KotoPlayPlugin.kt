package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class KotoPlayPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(KotoPlay())
    }
}
