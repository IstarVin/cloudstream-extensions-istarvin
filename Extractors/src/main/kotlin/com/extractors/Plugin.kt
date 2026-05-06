package com.extractors

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ExtractorPlugin : BasePlugin() {
    override fun load() {
        registerExtractorAPI(LuluStream())
        registerExtractorAPI(Vidara())
        registerExtractorAPI(StreamRuby())
    }
}
