package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Arc018Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Arc018())
    }
}
