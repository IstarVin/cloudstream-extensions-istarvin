package com.istarvin

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XPinayPlugin : BasePlugin() {


    override fun load() {
        registerMainAPI(XPinay())
    }
}
