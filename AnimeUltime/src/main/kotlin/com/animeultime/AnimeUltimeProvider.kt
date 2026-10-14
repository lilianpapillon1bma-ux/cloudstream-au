package com.animeultime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeUltimeProvider : MainAPI() {
    override var mainUrl = "[v5.anime-ultime.net](https://v5.anime-ultime.net)"
    override var name = "Anime-Ultime"
    override var lang = "fr"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"
    private val reqHeaders get() = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")

    // Le site liste ses catégories par lettre : series-0-1/<categorie>/<lettre>---
    // "0" = "~" et "1" = "#" d'après les liens de la page.
    private val letters = listOf("0", "1") + ('a'..'z').map { it.toString() }

    override val mainPage = mainPageOf(
        "$mainUrl/series-0-1/tokusatsu" to "Tokusatsu",
        "$mainUrl/series-0-1/anime" to "Animes",
        "$mainUrl/series-0-1/drama" to "Dramas",
    )

    // ---------- Utilitaires ----------

    private fun String?.https() = this?.replace("http://", "https://")

    private fun String.cleanTitle() =
        this.replace(Regex("\\s*vostfr\\s*$", RegexOption.IGNORE_CASE), "").trim()

    // ---------- Page d'accueil (catalogue par lettre) ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val letter = letters[(page - 1).coerceIn(0, letters.size - 1)]
        val doc = app.get("${request.data}/$letter---", headers = reqHeaders).document
        val items = doc.select("table.jtable tr").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            "${request.name} (${if (letter == "0") "~" else if (letter == "1") "#" else letter.uppercase()})",
            items,
            hasNext = page < letters.size
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("td:nth-child(2) a[href*=file-0-1/]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = a.text().cleanTitle()
        if (title.isBlank()) return null
        val poster = selectFirst("td:nth-child(1) img")?.attr("data-href").https()
        val kind = selectFirst("td:nth-child(3)")?.text()?.trim()
        val type = if (kind.equals("Film", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ---------- Recherche ----------
    // La page de résultats n'a pas été vue : on récupère tous les liens vers des fiches.

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.post(
            "$mainUrl/search-0-1",
            data = mapOf("search" to query),
            headers = reqHeaders
        ).document
        return doc.select("a[href*=file-0-1/]")
            .filter { it.text().isNotBlank() }
            .distinctBy { it.attr("href").substringBefore("-vostfr").substringAfterLast("/") }
            .map {
                newAnimeSearchResponse(it.text().cleanTitle(), fixUrl(it.attr("href")), TvType.Anime)
            }
    }

    // ---------- Fiche d'une série ----------

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = reqHeaders).document

        val title = doc.selectFirst("h1")?.text()?.cleanTitle() ?: "Sans titre"
        val poster = doc.selectFirst("div.principal_contain img")?.attr("src")?.let { fixUrl(it) }
        val plot = doc.selectFirst("span[style*=FF00FF]")?.text()
        val year = Regex("Ann.e\\s*:\\s*(\\d{4})").find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        val serieId = doc.selectFirst("div.AUVideoPlayer")?.attr("data-serie").orEmpty()

        val episodes = doc.select("table.jtable tr").mapNotNull { row ->
            val link = row.selectFirst("a[href*=info-0-1/]") ?: return@mapNotNull null
            val href = link.attr("href").substringBefore("#")
            val epId = Regex("info-0-1/(\\d+)/").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val label = row.select("td").getOrNull(2)?.text()?.trim().orEmpty()
            val number = Regex("(\\d+)(?:\\.\\d+)?\\s+vostfr", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode("$serieId|$epId") {
                this.name = label
                this.episode = number
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------- Liens vidéo (via playlist-<serie>.xml) ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size != 2) return false
        val (serieId, epId) = parts

        val xml = app.get("$mainUrl/playlist-$serieId.xml", headers = reqHeaders).text

        // L'identifiant de l'épisode est suivi des deux liens .mp4 de cet épisode.
        val start = Regex("(?<![\\d/-])$epId(?!\\d)").find(xml)?.range?.last ?: return false
        val urls = Regex("https?://[^\\s<\"']+\\.mp4")
            .findAll(xml, start)
            .map { it.value }
            .take(2)
            .distinct()
            .toList()

        urls.forEachIndexed { index, url ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - Source ${index + 1}",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to ua)
                }
            )
        }
        return urls.isNotEmpty()
    }
}
