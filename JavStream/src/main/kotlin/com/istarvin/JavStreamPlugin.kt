package com.istarvin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.utils.ExtractorApi

val javExtractors = mutableListOf<ExtractorApi>()

@CloudstreamPlugin
class JavStreamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JavStream())

        myRegisterExtractorAPI(JavtifulExtractor())
        myRegisterExtractorAPI(SexTBExtractor())
        myRegisterExtractorAPI(MissAvExtractor())
        myRegisterExtractorAPI(JavGGStreamExtractor())
        myRegisterExtractorAPI(SupJavExtractor())
    }

    private fun myRegisterExtractorAPI(element: ExtractorApi) {
        javExtractors.add(element)
    }
}