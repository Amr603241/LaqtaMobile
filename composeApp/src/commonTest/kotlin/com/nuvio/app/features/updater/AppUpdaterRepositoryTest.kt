package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdaterRepositoryTest {
    @Test
    fun channelsUseLaqtaRepositoryReleaseEndpoints() {
        assertEquals(
            "https://api.github.com/repos/Amr603241/LaqtaMobile/releases?per_page=100",
            AppUpdaterRepository.releaseUrl(),
        )
    }

    @Test
    fun stableAcceptsReleasesFromAnyBranch() {
        listOf("main", "master", "cmp-rewrite", "1234567").forEach { branch ->
            val response = """[{
                "tag_name": "v1.1.0", "target_commitish": "$branch",
                "assets": [{"name": "app-universal.apk", "browser_download_url": "https://example.com/app-universal.apk"}]
            }]"""
            assertEquals("v1.1.0", select(response, UpdateChannel.STABLE)?.tag)
        }
    }

    @Test
    fun stableRejectsLegacyBetaNamesEvenIfGithubMarksThemStable() {
        val response = """[{
            "tag_name": "1.1.0", "name": "Beta 1.1.0 Hotfix",
            "assets": [{"name": "app.apk", "browser_download_url": "https://example.com/app.apk"}]
        }]"""
        assertNull(select(response, UpdateChannel.STABLE))
    }

    @Test
    fun stableSkipsPrereleasesAndReleasesWithoutAnApk() {
        val response = """[
            {"tag_name": "1.3.0-beta.1", "prerelease": true, "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/beta.apk"}]},
            {"tag_name": "1.2.0", "assets": [
                {"name": "app.ipa", "browser_download_url": "https://example.com/app.ipa"}]},
            {"tag_name": "1.1.0", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/stable.apk"}]}
        ]"""
        assertEquals("1.1.0", select(response, UpdateChannel.STABLE)?.tag)
        assertEquals("1.3.0-beta.1", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun betaSkipsReleasesWithoutAnApkAndSortsByVersion() {
        val response = """[
            {"tag_name": "1.1.0-beta.9", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/beta9.apk"}]},
            {"tag_name": "1.2.0", "assets": [
                {"name": "app.ipa", "browser_download_url": "https://example.com/app.ipa"}]},
            {"tag_name": "1.1.0-beta.10", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/beta10.apk"}]}
        ]"""
        assertEquals("1.1.0-beta.10", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun betaReceivesFinalPromotion() {
        val response = """[
            {"tag_name": "1.1.0-beta.10", "prerelease": true, "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/beta.apk"}]},
            {"tag_name": "1.1.0", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/stable.apk"}]}
        ]"""
        assertEquals("1.1.0", select(response, UpdateChannel.BETA)?.tag)
    }

    @Test
    fun officialLaqtaReleaseIsInstallableOnBothChannels() {
        val response = """[{
            "tag_name": "1.1.0", "name": "Laqta 1.1.0", "prerelease": false,
            "assets": [
                {"name": "androidApp-full-universal-release.apk", "browser_download_url": "https://example.com/universal.apk"},
                {"name": "androidApp-full-arm64-v8a-release.apk", "browser_download_url": "https://example.com/arm64.apk"}
            ]
        }]"""
        for (channel in UpdateChannel.entries) {
            val update = select(response, channel)
            assertEquals("1.1.0", update?.tag)
            assertEquals("androidApp-full-arm64-v8a-release.apk", update?.assetName)
        }
    }

    @Test
    fun apkSelectionPreservesDeviceAbiPreference() {
        val response = """[{
            "tag_name": "1.1.0", "assets": [
                {"name": "app-universal.apk", "browser_download_url": "https://example.com/universal.apk"},
                {"name": "app-arm64-v8a.apk", "browser_download_url": "https://example.com/arm64.apk"},
                {"name": "app-x86_64.apk", "browser_download_url": "https://example.com/x86.apk"}
            ]
        }]"""
        assertEquals("app-arm64-v8a.apk", select(response, UpdateChannel.STABLE)?.assetName)
        assertEquals(
            "app-universal.apk",
            AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("armeabi-v7a"))?.assetName,
        )
    }

    @Test
    fun incompatibleAbiIsNotSelectedForDevice() {
        val response = """[{
            "tag_name": "1.1.0", "assets": [
                {"name": "app-x86_64_release.apk", "browser_download_url": "https://example.com/x86_64.apk"},
                {"name": "app-arm64-v8a_release.apk", "browser_download_url": "https://example.com/arm64.apk"}
            ]
        }]"""
        assertNull(AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("x86")))
        assertEquals(
            "app-arm64-v8a_release.apk",
            AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("arm64-v8a"))?.assetName,
        )
    }

    @Test
    fun compatibleAbiWinsOverSimilarName() {
        val response = """[{
            "tag_name": "1.1.0", "assets": [
                {"name": "app-x86_64.apk", "browser_download_url": "https://example.com/x86_64.apk"},
                {"name": "app-x86.apk", "browser_download_url": "https://example.com/x86.apk"}
            ]
        }]"""
        assertEquals(
            "app-x86.apk",
            AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("x86"))?.assetName,
        )
    }

    @Test
    fun rejectsGenericApksWithoutAnExplicitUniversalAssetName() {
        val response = """[{
            "tag_name": "1.1.0", "assets": [
                {"name": "app-release.apk", "browser_download_url": "https://example.com/app-release.apk"},
                {"name": "app-archive.bin", "content_type": "application/vnd.android.package-archive",
                 "browser_download_url": "https://example.com/app-archive.bin"}
            ]
        }]"""
        assertNull(AppUpdaterRepository.selectUpdate(response, UpdateChannel.STABLE, listOf("arm64-v8a")))
    }

    private fun select(response: String, channel: UpdateChannel): AppUpdate? =
        AppUpdaterRepository.selectUpdate(response, channel, listOf("arm64-v8a"))
}
