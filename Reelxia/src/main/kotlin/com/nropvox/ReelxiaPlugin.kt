package com.nropvox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ReelxiaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Reelxia())
    }
}