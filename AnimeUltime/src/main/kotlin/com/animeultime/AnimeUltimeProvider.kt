package com.animeultime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class AnimeUltimeProvider : MainAPI() {
    override var mainUrl = "[v5.anime-ultime.net](https://v5.anime-ultime.net)"
    override var name = "Anime-Ultime"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

    override val requestHeaders: Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/Tokusatsu.html" to "Tokusatsu",
        "$mainUrl/Anime.html" to "Animés",
        "$mainUrl/Drama.html" to "Dramas",
        "$mainUrl/OST.html" to "OST"
    )

    private fun cleanTitle(t: String): String =
        t.replace(Regex("\\s+(vostfr|vf|streaming)\\s*$", RegexOption.IGNORE_CASE), "").trim()

    private fun fixUrl(url: String): String =
        when {
            url.startsWith("http") -> url
            url.startsWith("/") -> mainUrl + url
            else -> "$mainUrl/$url"
        }

    // ---------- Page d'accueil ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = requestHeaders).document
        val items = doc.select("a[href*=streaming]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val title = a.text().ifBlank { a.attr("title") }
                if (title.isBlank() || href.isBlank()) return@mapNotNull null
                if (!href.contains("-streaming")) return@mapNotNull null
                newAnimeSearchResponse(cleanTitle(title), fixUrl(href), TvType.Anime)
            }
            .distinctBy { it.url }
        return newHomePageResponse(request, items)
    }

    // ---------- Recherche ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            "$mainUrl/search-0-1",
            data = mapOf("search" to query),
            headers = requestHeaders
        ).document
        return doc.select("a[href*=streaming]")
            .mapNotNull { a ->
                val href = a.attr("href")
                val title = a.text().ifBlank { a.attr("title") }
                if (title.isBlank() || !href.contains("-streaming")) return@mapNotNull null
                newAnimeSearchResponse(cleanTitle(title), fixUrl(href), TvType.Anime)
            }
            .distinctBy { it.url }
    }

    // ---------- Fiche d'une série ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = requestHeaders).document

        val title = doc.selectFirst("h1")?.text()?.let { cleanTitle(it) } ?: "Sans titre"
        val poster = doc.selectFirst("img[src*=Affiche], img[src*=poster], div.principal_contain img")
            ?.attr("src")?.let { fixUrl(it) }
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?.substringAfter("Synopsis :")?.substringBeforeLast("-")?.trim()
        val year = Regex("Ann.e\\s*:?\\s*(\\d{4})").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        val serieId = doc.selectFirst("div.AUVideoPlayer")?.attr("data-serie").orEmpty()

        val episodes = doc.select("ul.ficheDownload li.file").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            if (!href.contains("streaming")) return@mapNotNull null
            val label = li.selectFirst("div.number label")?.text()?.trim()
                ?: li.selectFirst("div.title")?.text()?.trim()
                ?: return@mapNotNull null
            val number = Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
            newEpisode("$serieId|0") {
                this.name = label
                this.episode = number
                this.dataUrl = fixUrl(href)
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------- Liens vidéo (via VideoPlayer.html) ----------

    private suspend fun fetchPlayer(serieId: String, fileId: String): String? {
        val body = app.post(
            "$mainUrl/VideoPlayer.html",
            data = mapOf(
                "idserie" to serieId,
                "idfile" to fileId,
                "focusFile" to "0",
                "quality" to "1080p"
            ),
            headers = requestHeaders + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl
            )
        ).text
        return body.takeIf { it.contains("mp4") }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // "serieId|fileId" — fileId peut valoir "0" : on cherche alors l'épisode par sa page.
        val parts = data.split("|")
        val serieId = parts.getOrNull(0).orEmpty()
        val fileId = parts.getOrNull(1).orEmpty()
        if (serieId.isBlank()) return false

        val body = if (fileId.isNotBlank() && fileId != "0") {
            fetchPlayer(serieId, fileId)
        } else {
            null
        } ?: return false

        val json = app.parseJson(body)
        val quality = json["quality"]?.asString ?: "1080p"
        val url = json[quality]?.asJsonObject?.get("mp4")?.asJsonObject?.get("url")?.asString
            ?: return false

        callback.invoke(
            newExtractorLink(name, name, url, ExtractorLinkType.VIDEO) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName(quality)
            }
        )
        return true
    }
}
