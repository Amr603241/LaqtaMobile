package com.nuvio.app.features.addons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddonDirectoryTest {

    @Test
    fun `directory parser reads addon manifests and counts invalid entries`() {
        val result = AddonDirectoryParser.parse(
            """
            {
              "addons": [
                {
                  "transportName": "http",
                  "transportUrl": "https://example.test/manifest.json",
                  "manifest": {
                    "id": "org.example.catalog",
                    "name": "Example Catalog",
                    "description": "Movies and shows",
                    "version": "1.0.0",
                    "types": ["movie"],
                    "resources": ["catalog"],
                    "behaviorHints": {"adult": true}
                  }
                },
                {"transportName": "unsupported", "transportUrl": "not-a-url", "manifest": {}}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, result.addons.size)
        assertEquals("https://example.test/manifest.json", result.addons.single().transportUrl)
        assertTrue(result.addons.single().behaviorHints.adult)
        assertEquals(1, result.invalidEntryCount)
    }

    @Test
    fun `addon directory filter searches and hides adult addons by default`() {
        val regularAddon = directoryManifest(
            id = "org.catalog",
            name = "Movie Catalog",
            resources = listOf("catalog"),
        )
        val adultAddon = directoryManifest(
            id = "org.adult",
            name = "Adult Streams",
            resources = listOf("stream"),
            adult = true,
        )
        val addons = listOf(regularAddon, adultAddon)

        assertEquals(
            listOf(regularAddon),
            filterAddonDirectory(
                addons = addons,
                query = "movie",
                filter = AddonDirectoryFilter.ALL,
                includeAdult = false,
            ),
        )
        assertEquals(
            listOf(adultAddon),
            filterAddonDirectory(
                addons = addons,
                query = "adult",
                filter = AddonDirectoryFilter.STREAMS,
                includeAdult = true,
            ),
        )
        assertFalse(
            filterAddonDirectory(
                addons = addons,
                query = "",
                filter = AddonDirectoryFilter.STREAMS,
                includeAdult = false,
            ).contains(adultAddon),
        )
    }

    @Test
    fun `addon catalog URL follows the manifest resource route`() {
        assertEquals(
            "https://stremio-addons.net/api/addon_catalog/all/stremio-addons.net.json",
            addonCatalogUrl(
                "https://stremio-addons.net/api/manifest.json",
                AddonCatalog(type = "all", id = "stremio-addons.net", name = "All"),
            ),
        )
    }

    @Test
    fun `manifest parser reads addon catalogs independently from content catalogs`() {
        val manifest = AddonManifestParser.parse(
            manifestUrl = "https://directory.example/manifest.json",
            payload = """
                {
                  "id": "org.directory",
                  "name": "Directory",
                  "version": "1.0.0",
                  "types": ["movie"],
                  "resources": ["addon_catalog"],
                  "catalogs": [],
                  "addonCatalogs": [
                    {"type": "all", "id": "community", "name": "Community"}
                  ]
                }
            """.trimIndent(),
        )

        assertTrue(manifest.resources.any { it.name == "addon_catalog" })
        assertEquals(
            listOf(AddonCatalog(type = "all", id = "community", name = "Community")),
            manifest.addonCatalogs,
        )
        assertTrue(manifest.catalogs.isEmpty())
    }

    private fun directoryManifest(
        id: String,
        name: String,
        resources: List<String>,
        adult: Boolean = false,
    ) = AddonManifest(
        id = id,
        name = name,
        description = "",
        version = "1.0.0",
        resources = resources.map { AddonResource(name = it, types = listOf("movie")) },
        types = listOf("movie"),
        behaviorHints = AddonBehaviorHints(adult = adult),
        transportUrl = "https://$id.example/manifest.json",
    )
}
