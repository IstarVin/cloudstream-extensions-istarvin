package com.istarvin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class JavFC2Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(JavFC2())
    }
}