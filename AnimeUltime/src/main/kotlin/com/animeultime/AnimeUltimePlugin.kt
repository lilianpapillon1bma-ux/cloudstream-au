package com.animeultime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeUltimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeUltimeProvider())
    }
}
