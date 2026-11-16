package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal data class AddonDirectoryResult(
    val addons: List<AddonManifest>,
    val invalidEntryCount: Int,
)

internal enum class AddonDirectoryFilter(val resourceName: String?) {
    ALL(null),
    CATALOGS("catalog"),
    STREAMS("stream"),
    SUBTITLES("subtitles"),
}

internal fun filterAddonDirectory(
    addons: List<AddonManifest>,
    query: String,
    filter: AddonDirectoryFilter,
    includeAdult: Boolean,
): List<AddonManifest> {
    val normalizedQuery = query.trim()
    return addons.filter { addon ->
        (includeAdult || !addon.behaviorHints.adult) &&
            (filter.resourceName == null || addon.resources.any { it.name == filter.resourceName }) &&
            (
                normalizedQuery.isBlank() ||
                    addon.name.contains(normalizedQuery, ignoreCase = true) ||
                    addon.description.contains(normalizedQuery, ignoreCase = true) ||
                    addon.id.contains(normalizedQuery, ignoreCase = true)
                )
    }
}

internal object AddonDirectoryParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): AddonDirectoryResult {
        val root = json.parseToJsonElement(payload) as? JsonObject
            ?: throw IllegalArgumentException("The addon directory response must be a JSON object.")
        val entries = root["addons"] as? JsonArray
            ?: throw IllegalArgumentException("The addon directory response is missing its addons list.")
        val addons = mutableListOf<AddonManifest>()
        var invalidEntryCount = 0

        entries.forEach { entry ->
            val addon = entry as? JsonObject
            val transportName = addon?.string("transportName")
            val transportUrl = addon?.string("transportUrl")
            val manifest = addon?.get("manifest") as? JsonObject
            if (
                transportName != "http" ||
                transportUrl.isNullOrBlank() ||
                !(transportUrl.startsWith("http://") || transportUrl.startsWith("https://")) ||
                manifest == null
            ) {
                invalidEntryCount++
                return@forEach
            }

            try {
                addons += AddonManifestParser.parse(
                    manifestUrl = transportUrl,
                    payload = manifest.toString(),
                )
            } catch (_: IllegalArgumentException) {
                invalidEntryCount++
            }
        }

        return AddonDirectoryResult(
            addons = addons,
            invalidEntryCount = invalidEntryCount,
        )
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
}

internal object AddonDirectoryRepository {
    private const val MANIFEST_URL = "https://stremio-addons.net/api/manifest.json"
    private val log = Logger.withTag("AddonDirectoryRepository")

    suspend fun load(): AddonDirectoryResult = withContext(Dispatchers.Default) {
        val directoryManifest = AddonManifestParser.parse(
            manifestUrl = MANIFEST_URL,
            payload = fetchAddonResponseText(MANIFEST_URL),
        )
        if (directoryManifest.resources.none { it.name == "addon_catalog" }) {
            throw IllegalArgumentException("The directory manifest does not provide addon_catalog.")
        }
        val catalog = directoryManifest.addonCatalogs.singleOrNull {
            it.type == "all" && it.id == "stremio-addons.net"
        } ?: throw IllegalArgumentException("The directory manifest does not provide the expected catalog.")

        val catalogUrl = addonCatalogUrl(directoryManifest.transportUrl, catalog)
        val result = AddonDirectoryParser.parse(fetchAddonResponseText(catalogUrl))
        if (result.invalidEntryCount > 0) {
            log.w { "Skipped ${result.invalidEntryCount} invalid addon directory entries" }
        }
        result
    }
}

internal fun addonCatalogUrl(
    manifestUrl: String,
    catalog: AddonCatalog,
): String {
    val manifestBase = manifestUrl.substringBefore("?").substringBeforeLast('/')
    return "$manifestBase/addon_catalog/${catalog.type}/${catalog.id}.json"
}
