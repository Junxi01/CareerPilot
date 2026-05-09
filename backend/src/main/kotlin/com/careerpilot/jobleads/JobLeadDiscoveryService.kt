package com.careerpilot.jobleads

import com.careerpilot.targetcompanies.TargetCompanyRecord
import com.careerpilot.targetcompanies.TargetCompanyRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.PriorityQueue

class JobLeadDiscoveryService(
    private val targetCompanies: TargetCompanyRepository,
    private val jobLeads: JobLeadRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(12))
            .build()

    fun discover(userId: Long, req: DiscoverJobLeadsRequest): DiscoverJobLeadsResponse {
        val companies =
            targetCompanies
                .listByUser(userId)
                .filter { it.active && it.careersUrl.isNotBlank() }
                .filter { req.company_id == null || it.id == req.company_id }
                .filter { allowedCareersUrl(it.careersUrl) }

        var leadsFound = 0
        var leadsCreated = 0
        var duplicates = 0
        var lowScore = 0
        var fetchErrors = 0
        val created = mutableListOf<JobLeadDto>()
        val maxPages = req.max_pages_per_company.coerceIn(1, 20)
        val maxDepth = req.max_depth.coerceIn(0, 4)
        val minScore = req.min_match_score.coerceIn(0.0, 100.0)

        for (company in companies) {
            val crawled = crawl(company.careersUrl, maxPages, maxDepth)
            fetchErrors += crawled.fetchErrors
            leadsFound += crawled.listings.size
            for (listing in crawled.listings) {
                val score = scoreListing(listing, company.keywords, company.locations)
                if (score.value < minScore) {
                    lowScore += 1
                    continue
                }
                when (
                    val inserted =
                        jobLeads.insert(
                            userId = userId,
                            companyId = company.id,
                            roleTitle = listing.title,
                            jobUrl = listing.url,
                            location = score.location,
                            rawDescription = listing.snippet.takeIf { it.isNotBlank() },
                            matchedKeywords = score.matchedKeywords,
                            matchScore = score.value,
                            discoveredAtIso = Instant.now().toString(),
                            savedToApplications = false,
                        )
                ) {
                    is InsertResult.Created -> {
                        leadsCreated += 1
                        created += inserted.record.toDto()
                    }
                    InsertResult.DuplicateJobUrl -> duplicates += 1
                    InsertResult.CompanyNotFound -> Unit
                }
            }
        }

        return DiscoverJobLeadsResponse(
            companies_scanned = companies.size,
            leads_found = leadsFound,
            leads_created = leadsCreated,
            duplicates_skipped = duplicates,
            low_score_skipped = lowScore,
            fetch_errors = fetchErrors,
            created_items = created,
        )
    }

    fun refreshInvalid(userId: Long, req: RefreshInvalidJobLeadsRequest): RefreshInvalidJobLeadsResponse {
        val rows = jobLeads.listByUser(userId = userId, companyId = req.company_id)
        var checked = 0
        var deleted = 0
        var kept = 0
        var skippedSaved = 0
        var uncertain = 0
        val deletedItems = mutableListOf<JobLeadDto>()

        for (lead in rows) {
            if (lead.savedToApplications && !req.delete_saved) {
                skippedSaved += 1
                continue
            }
            checked += 1
            when (checkJobUrl(lead.jobUrl)) {
                UrlState.Valid -> kept += 1
                UrlState.Invalid -> {
                    if (jobLeads.delete(userId, lead.id)) {
                        deleted += 1
                        deletedItems += lead.toDto()
                    }
                }
                UrlState.Uncertain -> uncertain += 1
            }
        }

        return RefreshInvalidJobLeadsResponse(
            checked = checked,
            deleted = deleted,
            kept = kept,
            skipped_saved = skippedSaved,
            uncertain = uncertain,
            deleted_items = deletedItems,
        )
    }

    private fun crawl(seedUrl: String, maxPages: Int, maxDepth: Int): CrawlResult {
        val queue = PriorityQueue<CrawlTarget>(compareByDescending<CrawlTarget> { it.score }.thenBy { it.depth })
        val seenPages = mutableSetOf<String>()
        val seenJobs = mutableSetOf<String>()
        val listings = mutableListOf<ParsedListing>()
        var fetchErrors = 0
        queue += CrawlTarget(url = normalizeUrl(seedUrl), depth = 0, score = 1000.0)

        while (queue.isNotEmpty() && seenPages.size < maxPages) {
            val target = queue.poll()
            val pageUrl = normalizeUrl(target.url)
            if (!seenPages.add(pageUrl)) continue
            val fetched = fetchHtml(pageUrl)
            if (fetched == null) {
                fetchErrors += 1
                continue
            }
            for (listing in atsApiListings(pageUrl)) {
                if (seenJobs.add(listing.url)) listings += listing
            }
            for (listing in extractListings(fetched, pageUrl, seedUrl)) {
                if (seenJobs.add(listing.url)) listings += listing
            }
            if (target.depth >= maxDepth) continue
            for (candidate in discoverLinks(fetched, pageUrl, seedUrl).take(120)) {
                if (candidate.url !in seenPages) queue += candidate.copy(depth = target.depth + 1)
            }
        }

        return CrawlResult(listings = listings, fetchErrors = fetchErrors)
    }

    private fun fetchHtml(url: String): String? {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "CareerPilotLocal/1.0 job-discovery")
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .GET()
                .build()
        return try {
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() in 200..299) res.body() else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchJson(url: String): JsonElement? {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "CareerPilotLocal/1.0 job-discovery")
                .header("Accept", "application/json")
                .GET()
                .build()
        return try {
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) return null
            json.parseToJsonElement(res.body())
        } catch (_: Throwable) {
            null
        }
    }

    private fun postJson(url: String, body: String): JsonElement? {
        val req =
            HttpRequest
                .newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "CareerPilotLocal/1.0 job-discovery")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return try {
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() !in 200..299) return null
            json.parseToJsonElement(res.body())
        } catch (_: Throwable) {
            null
        }
    }

    private fun atsApiListings(pageUrl: String): List<ParsedListing> {
        val uri =
            try {
                URI.create(pageUrl)
            } catch (_: Throwable) {
                return emptyList()
            }
        val host = uri.host?.lowercase().orEmpty()
        return when {
            host == "jobs.ashbyhq.com" || host.endsWith(".ashbyhq.com") -> ashbyListings(uri)
            host.endsWith("myworkdayjobs.com") -> workdayListings(uri)
            host == "jobs.smartrecruiters.com" || host == "careers.smartrecruiters.com" -> smartRecruitersListings(uri)
            else -> emptyList()
        }
    }

    private fun ashbyListings(uri: URI): List<ParsedListing> {
        val slug = uri.path.trim('/').split('/').firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val root = fetchJson("https://api.ashbyhq.com/posting-api/job-board/$slug?includeCompensation=false") as? JsonObject
            ?: return emptyList()
        val jobs = root["jobs"] as? JsonArray ?: return emptyList()
        return jobs.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val title = jsonString(obj["title"]) ?: return@mapNotNull null
            val id = jsonString(obj["id"])
            val url =
                jsonString(obj["jobUrl"])
                    ?: jsonString(obj["url"])
                    ?: id?.let { "https://jobs.ashbyhq.com/$slug/$it" }
                    ?: return@mapNotNull null
            val location = jsonString(obj["location"]).orEmpty()
            ParsedListing(title = title, url = normalizeUrl(url), snippet = cleanText("$title $location"))
        }
    }

    private fun workdayListings(uri: URI): List<ParsedListing> {
        val host = uri.host ?: return emptyList()
        val tenant = host.substringBefore('.').takeIf { it.isNotBlank() } ?: return emptyList()
        val site = uri.path.trim('/').split('/').firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val root =
            postJson(
                "https://$host/wday/cxs/$tenant/$site/jobs",
                """{"appliedFacets":{},"limit":20,"offset":0,"searchText":""}""",
            ) as? JsonObject ?: return emptyList()
        val jobs = root["jobPostings"] as? JsonArray ?: return emptyList()
        return jobs.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val title = jsonString(obj["title"]) ?: return@mapNotNull null
            val external = jsonString(obj["externalPath"]) ?: return@mapNotNull null
            val url = normalizeUrl("https://$host$external")
            val location = jsonString(obj["locationsText"]).orEmpty()
            ParsedListing(title = title, url = url, snippet = cleanText("$title $location"))
        }
    }

    private fun smartRecruitersListings(uri: URI): List<ParsedListing> {
        val company = uri.path.trim('/').split('/').firstOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val root = fetchJson("https://api.smartrecruiters.com/v1/companies/$company/postings?limit=100") as? JsonObject
            ?: return emptyList()
        val jobs = root["content"] as? JsonArray ?: return emptyList()
        return jobs.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val title = jsonString(obj["name"]) ?: return@mapNotNull null
            val id = jsonString(obj["id"]) ?: jsonString(obj["uuid"]) ?: return@mapNotNull null
            val refUrl =
                ((obj["ref"] as? JsonObject)?.let { jsonString(it["jobAd"]) })
                    ?: "https://jobs.smartrecruiters.com/$company/$id"
            val location = (obj["location"] as? JsonObject)?.let { loc ->
                listOfNotNull(jsonString(loc["city"]), jsonString(loc["region"]), jsonString(loc["country"])).joinToString(", ")
            }.orEmpty()
            ParsedListing(title = title, url = normalizeUrl(refUrl), snippet = cleanText("$title $location"))
        }
    }

    private fun extractListings(html: String, pageUrl: String, seedUrl: String): List<ParsedListing> {
        val out = linkedListings(html, pageUrl, seedUrl).toMutableList()
        val seen = out.mapTo(mutableSetOf()) { it.url }
        for (jsonListing in jsonLdListings(html, pageUrl, seedUrl)) {
            if (seen.add(jsonListing.url)) out += jsonListing
        }
        for (embeddedListing in embeddedUrlListings(html, pageUrl, seedUrl)) {
            if (seen.add(embeddedListing.url)) out += embeddedListing
        }
        return out
    }

    private fun linkedListings(html: String, pageUrl: String, seedUrl: String): List<ParsedListing> {
        val out = mutableListOf<ParsedListing>()
        val seen = mutableSetOf<String>()
        for (match in AnchorRegex.findAll(html)) {
            val attrs = match.groupValues[1]
            val href = attrValue(attrs, "href")?.trim().orEmpty()
            val title =
                (
                    cleanText(match.groupValues[2])
                        .ifBlank { attrValue(attrs, "aria-label") ?: "" }
                        .ifBlank { attrValue(attrs, "title") ?: "" }
                        .ifBlank { attrValue(attrs, "data-title") ?: "" }
                        .ifBlank { titleFromUrl(href) }
                ).take(500)
            if (title.length < 2) continue
            val abs = resolveUrl(pageUrl, href) ?: continue
            if (!looksLikeJob(abs, title)) continue
            if (genericJobNavigation(abs, title)) continue
            if (!jobDestinationAllowed(seedUrl, abs)) continue
            if (!seen.add(abs)) continue
            out += ParsedListing(title = title, url = abs, snippet = cleanText("${title} ${attrValue(attrs, "data-location").orEmpty()}"))
        }
        return out
    }

    private fun jsonLdListings(html: String, pageUrl: String, seedUrl: String): List<ParsedListing> {
        val out = mutableListOf<ParsedListing>()
        for (match in JsonLdRegex.findAll(html)) {
            val raw = match.groupValues[1].trim()
            val root =
                try {
                    json.parseToJsonElement(raw)
                } catch (_: Throwable) {
                    continue
                }
            visitJson(root) { obj ->
                val types = jsonStrings(obj["@type"]).map { it.lowercase() }
                if (types.none { "jobposting" in it }) return@visitJson
                val title = jsonString(obj["title"]) ?: jsonString(obj["name"]) ?: "Job posting"
                val urlRaw =
                    jsonString(obj["url"])
                        ?: jsonStrings(obj["sameAs"]).firstOrNull()
                        ?: jsonString(obj["applyUrl"])
                        ?: jsonString(obj["externalPath"])
                        ?: jsonString(obj["absolute_url"])
                        ?: jsonString(obj["@id"])
                val abs = urlRaw?.let { resolveUrl(pageUrl, it) } ?: return@visitJson
                if (!jobDestinationAllowed(seedUrl, abs)) return@visitJson
                val desc = jsonString(obj["description"]).orEmpty()
                out += ParsedListing(title = title.take(500), url = abs, snippet = cleanText(desc).take(2000))
            }
        }
        return out.distinctBy { it.url }
    }

    private fun embeddedUrlListings(html: String, pageUrl: String, seedUrl: String): List<ParsedListing> {
        val normalized = normalizeEmbeddedText(html)
        val out = mutableListOf<ParsedListing>()
        val seen = mutableSetOf<String>()

        for (match in AbsoluteUrlRegex.findAll(normalized)) {
            val raw = match.value.trimEnd('\\', '"', '\'', ')', ']', '}', ',', '.', ';')
            val abs = normalizeUrl(raw)
            if (!looksLikeJob(abs, titleFromUrl(abs))) continue
            if (genericJobNavigation(abs, titleFromUrl(abs))) continue
            if (!jobDestinationAllowed(seedUrl, abs)) continue
            if (!seen.add(abs)) continue
            out += ParsedListing(title = titleNearUrl(normalized, match.range, abs), url = abs, snippet = snippetNear(normalized, match.range))
        }

        for (match in RelativeJobPathRegex.findAll(normalized)) {
            val raw = match.value.trimEnd('\\', '"', '\'', ')', ']', '}', ',', '.', ';')
            val abs = resolveUrl(pageUrl, raw) ?: continue
            if (!looksLikeJob(abs, titleFromUrl(abs))) continue
            if (genericJobNavigation(abs, titleFromUrl(abs))) continue
            if (!jobDestinationAllowed(seedUrl, abs)) continue
            if (!seen.add(abs)) continue
            out += ParsedListing(title = titleNearUrl(normalized, match.range, abs), url = abs, snippet = snippetNear(normalized, match.range))
        }

        return out
    }

    private fun discoverLinks(html: String, pageUrl: String, seedUrl: String): List<CrawlTarget> {
        val out = mutableListOf<CrawlTarget>()
        for (match in AnchorRegex.findAll(html)) {
            val attrs = match.groupValues[1]
            val href = attrValue(attrs, "href")?.trim().orEmpty()
            val label = cleanText(match.groupValues[2])
            val abs = resolveUrl(pageUrl, href) ?: continue
            if (!discoveryDestinationAllowed(seedUrl, abs)) continue
            val score = discoveryScore(abs, label)
            if (score >= 15.0) out += CrawlTarget(url = abs, depth = 0, score = score)
        }
        for (match in AbsoluteUrlRegex.findAll(normalizeEmbeddedText(html))) {
            val abs = normalizeUrl(match.value.trimEnd('\\', '"', '\'', ')', ']', '}', ',', '.', ';'))
            if (!discoveryDestinationAllowed(seedUrl, abs)) continue
            val score = discoveryScore(abs, titleFromUrl(abs))
            if (score >= 15.0) out += CrawlTarget(url = abs, depth = 0, score = score)
        }
        return out.sortedByDescending { it.score }.distinctBy { it.url }
    }

    private fun checkJobUrl(url: String): UrlState {
        val req =
            try {
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "CareerPilotLocal/1.0 link-refresh")
                    .GET()
                    .build()
            } catch (_: Throwable) {
                return UrlState.Invalid
            }
        return try {
            val res = client.send(req, HttpResponse.BodyHandlers.ofString())
            when (res.statusCode()) {
                404, 410, 451 -> UrlState.Invalid
                in 200..299 -> if (looksClosed(res.body())) UrlState.Invalid else UrlState.Valid
                in 500..599, 429, 403 -> UrlState.Uncertain
                else -> UrlState.Uncertain
            }
        } catch (_: Throwable) {
            UrlState.Uncertain
        }
    }

    private fun scoreListing(
        listing: ParsedListing,
        keywords: List<String>,
        locations: List<String>,
    ): ScoreResult {
        val blob = "${listing.title} ${listing.snippet} ${listing.url}".lowercase()
        val matched = keywords.map { it.trim() }.filter { it.isNotBlank() && it.lowercase() in blob }
        val cleanLocations = locations.map { it.trim() }.filter { it.isNotBlank() }
        val location = cleanLocations.firstOrNull { it.lowercase() in blob }
        val kwCount = keywords.count { it.isNotBlank() }.coerceAtLeast(1)
        val keywordPart = ((matched.size.toDouble() / kwCount.toDouble()) * 70.0).coerceAtMost(70.0)
        val locationPart =
            if (cleanLocations.isEmpty()) {
                30.0
            } else if (location != null) {
                30.0
            } else {
                0.0
            }
        return ScoreResult(value = (keywordPart + locationPart).coerceAtMost(100.0), matchedKeywords = matched, location = location)
    }

    private fun visitJson(element: JsonElement, visitor: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                visitor(element)
                element.values.forEach { visitJson(it, visitor) }
            }
            is JsonArray -> element.forEach { visitJson(it, visitor) }
            else -> Unit
        }
    }

    private fun jsonString(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }

    private fun jsonStrings(el: JsonElement?): List<String> =
        when (el) {
            is JsonArray -> el.mapNotNull { jsonString(it) }
            else -> listOfNotNull(jsonString(el))
        }

    private fun cleanText(raw: String): String =
        raw
            .replace(Regex("<[^>]+>"), " ")
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun attrValue(attrs: String, name: String): String? {
        val quoted = Regex("""(?i)\b${Regex.escape(name)}\s*=\s*["']([^"']*)["']""").find(attrs)
        if (quoted != null) return decodeHtml(quoted.groupValues[1]).trim().takeIf { it.isNotBlank() }
        val bare = Regex("""(?i)\b${Regex.escape(name)}\s*=\s*([^\s"'=<>`]+)""").find(attrs)
        return bare?.groupValues?.getOrNull(1)?.let { decodeHtml(it).trim().takeIf { v -> v.isNotBlank() } }
    }

    private fun decodeHtml(raw: String): String =
        raw
            .replace("&amp;", "&")
            .replace("&nbsp;", " ")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")

    private fun normalizeEmbeddedText(raw: String): String =
        decodeHtml(raw)
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\\"", "\"")

    private fun titleNearUrl(text: String, range: IntRange, url: String): String {
        val start = (range.first - 500).coerceAtLeast(0)
        val end = (range.last + 500).coerceAtMost(text.length - 1)
        val local = text.substring(start..end)
        val title =
            listOf("title", "name", "text", "label", "jobTitle", "displayTitle")
                .firstNotNullOfOrNull { key ->
                    Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]{3,180})"""").find(local)?.groupValues?.getOrNull(1)
                        ?: Regex("""${Regex.escape(key)}\s*:\s*["']([^"']{3,180})["']""").find(local)?.groupValues?.getOrNull(1)
                }
        return cleanText(title.orEmpty()).ifBlank { titleFromUrl(url) }.take(500)
    }

    private fun snippetNear(text: String, range: IntRange): String {
        val start = (range.first - 700).coerceAtLeast(0)
        val end = (range.last + 700).coerceAtMost(text.length - 1)
        return cleanText(text.substring(start..end)).take(2000)
    }

    private fun normalizeUrl(raw: String): String = raw.substringBefore("#").trim()

    private fun titleFromUrl(raw: String): String {
        val path =
            try {
                URI.create(raw).path
            } catch (_: Throwable) {
                raw
            }
        val last = path.trim('/').split('/').lastOrNull().orEmpty()
        val cleaned =
            try {
                URLDecoder.decode(last, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                last
            }
                .replace(Regex("""^\d+[-_]?"""), "")
                .replace(Regex("""[-_]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        return cleaned.ifBlank { "Job posting" }
    }

    private fun resolveUrl(pageUrl: String, href: String): String? =
        try {
            normalizeUrl(URI.create(pageUrl).resolve(href).toString())
        } catch (_: Throwable) {
            null
        }

    private fun allowedCareersUrl(url: String): Boolean =
        try {
            val u = URI.create(url)
            val host = u.host ?: return false
            (u.scheme == "http" || u.scheme == "https") && !blockedHost(host)
        } catch (_: Throwable) {
            false
        }

    private fun jobDestinationAllowed(seedUrl: String, jobUrl: String): Boolean =
        try {
            val seedHost = URI.create(seedUrl).host ?: return false
            val job = URI.create(jobUrl)
            val jobHost = job.host ?: return false
            (job.scheme == "http" || job.scheme == "https") &&
                !blockedHost(jobHost) &&
                (relatedHosts(seedHost, jobHost) || knownAtsHost(jobHost))
        } catch (_: Throwable) {
            false
        }

    private fun discoveryDestinationAllowed(seedUrl: String, targetUrl: String): Boolean =
        try {
            val seedHost = URI.create(seedUrl).host ?: return false
            val target = URI.create(targetUrl)
            val targetHost = target.host ?: return false
            (target.scheme == "http" || target.scheme == "https") &&
                !blockedHost(targetHost) &&
                (relatedHosts(seedHost, targetHost) || knownAtsHost(targetHost))
        } catch (_: Throwable) {
            false
        }

    private fun relatedHosts(aRaw: String, bRaw: String): Boolean {
        val a = aRaw.lowercase()
        val b = bRaw.lowercase()
        return a == b || a.endsWith(".$b") || b.endsWith(".$a")
    }

    private fun blockedHost(host: String): Boolean {
        val h = host.lowercase()
        return BlockedHostSuffixes.any { h == it || h.endsWith(".$it") }
    }

    private fun knownAtsHost(host: String): Boolean {
        val h = host.lowercase()
        return AtsHostSuffixes.any { h == it || h.endsWith(".$it") }
    }

    private fun looksLikeJob(url: String, label: String): Boolean {
        val cleanLabel = label.trim().lowercase()
        val labelPart = if (cleanLabel == "job posting") "" else cleanLabel
        val blob = "$url $labelPart".lowercase()
        return !assetUrl(url) &&
            (JobHintRegex.containsMatchIn(blob) || knownAtsHost(URI.create(url).host ?: "")) &&
            !NegativeHintRegex.containsMatchIn(blob)
    }

    private fun genericJobNavigation(url: String, label: String): Boolean {
        val path = URI.create(url).path.trim('/').lowercase()
        val segments = path.split('/').filter { it.isNotBlank() }
        val text = label.trim().lowercase()
        val genericLabel = text in GenericNavLabels
        val genericPath = path in GenericNavPaths || path.endsWith("/careers") || path.endsWith("/jobs")
        val shallowCareerRoot = segments.size <= 1 && (path.contains("career") || path == "jobs")
        val weakJobsPath =
            !knownAtsHost(URI.create(url).host ?: "") &&
                (path.startsWith("jobs/") || path.endsWith("/jobs")) &&
                !JobIdHintRegex.containsMatchIn(path) &&
                !StrongRoleHintRegex.containsMatchIn("$path $text")
        return genericLabel ||
            text.endsWith(" open roles") ||
            (genericLabel && genericPath) ||
            (shallowCareerRoot && !JobIdHintRegex.containsMatchIn(path)) ||
            weakJobsPath
    }

    private fun assetUrl(url: String): Boolean {
        val path =
            try {
                URI.create(url).path.lowercase()
            } catch (_: Throwable) {
                url.lowercase()
            }
        return AssetPathRegex.containsMatchIn(path)
    }

    private fun discoveryScore(url: String, label: String): Double {
        val blob = "$url $label".lowercase()
        var score = 0.0
        if (JobHintRegex.containsMatchIn(blob)) score += 45.0
        if (ExtraHintRegex.containsMatchIn(blob)) score += 25.0
        if (knownAtsHost(URI.create(url).host ?: "")) score += 55.0
        if (NegativeHintRegex.containsMatchIn(blob)) score -= 70.0
        return score.coerceIn(0.0, 100.0)
    }

    private fun looksClosed(html: String): Boolean {
        val text = cleanText(html).lowercase().take(12000)
        return ClosedHintRegex.containsMatchIn(text)
    }
}

private data class ParsedListing(val title: String, val url: String, val snippet: String)
private data class CrawlResult(val listings: List<ParsedListing>, val fetchErrors: Int)
private data class CrawlTarget(val url: String, val depth: Int, val score: Double)
private data class ScoreResult(val value: Double, val matchedKeywords: List<String>, val location: String?)

private enum class UrlState {
    Valid,
    Invalid,
    Uncertain,
}

private val AnchorRegex = Regex("""<a\b([^>]*)>(.*?)</a>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val JsonLdRegex =
    Regex("""<script\b[^>]*type\s*=\s*["'][^"']*ld\+json[^"']*["'][^>]*>(.*?)</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val AbsoluteUrlRegex =
    Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
private val RelativeJobPathRegex =
    Regex("""(?<![A-Za-z0-9])/(?:jobs?|careers?|positions?|openings?|requisitions?|req|apply)/[A-Za-z0-9/_.,~:%?&=+#-]{5,}""", RegexOption.IGNORE_CASE)
private val JobHintRegex =
    Regex("""(job|jobs|career|careers|opening|openings|position|positions|requisition|req|apply|hiring|role|vacancy|vacancies|opportunit|internship|graduate|software|engineer|developer|designer|manager|analyst|scientist)""", RegexOption.IGNORE_CASE)
private val StrongRoleHintRegex =
    Regex("""(software|engineer|developer|designer|manager|analyst|scientist|director|lead|specialist|product|data|security|sales|marketing|finance|recruit|intern|graduate|requisition|opening|position|role)""", RegexOption.IGNORE_CASE)
private val ExtraHintRegex =
    Regex("""(join[-_ ]?team|browse[-_ ]?jobs|search[-_ ]?jobs|open[-_ ]?positions|work[-_ ]?with[-_ ]?us|employment|intern|university|early[-_ ]?career|talent|greenhouse|lever|workday|ashby|smartrecruiters)""", RegexOption.IGNORE_CASE)
private val NegativeHintRegex =
    Regex("""(/privacy|/cookie|/legal|/terms|mailto:|instagram\.com|facebook\.com|twitter\.com|x\.com|/static/|/assets/)""", RegexOption.IGNORE_CASE)
private val ClosedHintRegex =
    Regex("""(job no longer available|position has been filled|position is no longer available|posting has expired|job has expired|this job is closed|application closed|no longer accepting applications)""", RegexOption.IGNORE_CASE)
private val AssetPathRegex = Regex("""\.(?:css|js|mjs|map|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|otf)(?:$|[?#])|/(?:static|assets|images|img|fonts)/""", RegexOption.IGNORE_CASE)
private val JobIdHintRegex = Regex("""(\d{4,}|[0-9a-f]{8}-[0-9a-f-]{12,}|/job/|/jobs/|/positions/|/openings/|gh_jid=|jobid=|job_id=|requisition)""", RegexOption.IGNORE_CASE)
private val GenericNavLabels =
    setOf(
        "career",
        "careers",
        "jobs",
        "open roles",
        "open positions",
        "view jobs",
        "all jobs",
        "job openings",
        "see open roles",
        "life at stripe",
        "life at",
        "benefits",
        "university",
        "culture",
        "how we operate",
        "our opportunity",
        "apply",
        "apply now",
        "learn more",
        "english",
    )
private val GenericNavPaths = setOf("career", "careers", "jobs", "company/careers", "about/careers")
private val BlockedHostSuffixes =
    setOf("linkedin.com", "indeed.com", "glassdoor.com", "glassdoor.co.uk", "glassdoor.ie", "glassdoor.de", "glassdoor.fr")
private val AtsHostSuffixes =
    setOf(
        "greenhouse.io",
        "lever.co",
        "smartrecruiters.com",
        "workday.com",
        "myworkdayjobs.com",
        "icims.com",
        "teamtailor.com",
        "ashbyhq.com",
        "workable.com",
        "bamboohr.com",
        "oraclecloud.com",
        "oracle.com",
        "jobvite.com",
        "successfactors.com",
        "successfactors.eu",
        "taleo.net",
        "eightfold.ai",
        "rippling-ats.com",
        "pinpointhq.com",
        "recruiting.com",
        "recruitee.com",
        "breezyhr.com",
        "applytojob.com",
        "jobs.personio.de",
    )
