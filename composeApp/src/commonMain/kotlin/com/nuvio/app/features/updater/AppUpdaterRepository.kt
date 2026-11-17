package com.nuvio.app.features.updater

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_github_api_error
import org.jetbrains.compose.resources.getString

private const val GitHubRepositoryApiUrl = "https://api.github.com/repos/Amr603241/LaqtaMobile"

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

internal class NoChannelReleaseException : IllegalStateException()

internal object AppUpdaterRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> = runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = releaseUrl(),
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "LaqtaMobile",
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        selectUpdate(response.body, channel, AppUpdaterPlatform.getSupportedAbis())
            ?: throw NoChannelReleaseException()
    }

    internal fun releaseUrl(): String = "$GitHubRepositoryApiUrl/releases?per_page=100"

    internal fun selectUpdate(
        responseBody: String,
        channel: UpdateChannel,
        supportedAbis: List<String>,
    ): AppUpdate? {
        val releases = json.decodeFromString<List<GitHubReleaseDto>>(responseBody)
        return ReleaseSelector.eligibleReleases(releases, channel).firstNotNullOfOrNull { release ->
            val asset = chooseBestApkAsset(release.assets, supportedAbis) ?: return@firstNotNullOfOrNull null
            val tag = release.tagName?.takeIf { VersionUtils.parse(it) != null }
                ?: release.name.orEmpty()
            AppUpdate(
                tag = tag,
                title = release.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = release.body.orEmpty(),
                releaseUrl = release.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
            )
        }
    }

    private fun chooseBestApkAsset(
        assets: List<GitHubAssetDto>,
        supportedAbis: List<String>,
    ): GitHubAssetDto? {
        val apkAssets = assets.filter { asset ->
            asset.name.endsWith(".apk", ignoreCase = true) ||
                asset.contentType == "application/vnd.android.package-archive"
        }
        fun matchesAbi(name: String, abi: String): Boolean {
            val suffix = if (abi.equals("x86", ignoreCase = true)) "(?!_64)" else ""
            return Regex("(^|[^a-z0-9])${Regex.escape(abi)}$suffix(?=$|[^a-z0-9])", RegexOption.IGNORE_CASE)
                .containsMatchIn(name)
        }

        for (abi in supportedAbis) {
            apkAssets.firstOrNull { matchesAbi(it.name, abi) }?.let { return it }
        }
        val genericAssets = apkAssets.filter { asset ->
            listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").none { matchesAbi(asset.name, it) }
        }
        val universalName = Regex("(^|[^a-z0-9])(universal|all)(?=$|[^a-z0-9])", RegexOption.IGNORE_CASE)
        return genericAssets.firstOrNull { universalName.containsMatchIn(it.name) }
            ?: genericAssets.firstOrNull()
    }
}
