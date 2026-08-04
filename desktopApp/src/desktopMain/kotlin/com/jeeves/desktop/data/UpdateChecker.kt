package com.jeeves.desktop.data

import com.jeeves.shared.ai.AppLogger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Checks for application updates by polling GitHub releases.
 * Shows a notification when a newer version is available.
 */
class UpdateChecker(
    private val httpClient: HttpClient,
    private val scope: CoroutineScope
) {
    companion object {
        /** Current application version. Update this when releasing. */
        const val CURRENT_VERSION = "1.2.0"

        /** GitHub owner/repo for release checks */
        private const val GITHUB_OWNER = "ColourTR33"
        private const val GITHUB_REPO = "Jeeves"

        /** GitHub API endpoint for latest release */
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _updateAvailable = MutableStateFlow<UpdateInfo?>(null)
    val updateAvailable: StateFlow<UpdateInfo?> = _updateAvailable.asStateFlow()

    private val _checkInProgress = MutableStateFlow(false)
    val checkInProgress: StateFlow<Boolean> = _checkInProgress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /**
     * Check for updates asynchronously. Results are published to [updateAvailable].
     */
    fun checkForUpdates() {
        if (_checkInProgress.value) return

        scope.launch(Dispatchers.IO) {
            _checkInProgress.value = true
            _lastError.value = null

            try {
                val response = httpClient.get(RELEASES_URL) {
                    header("Accept", "application/vnd.github.v3+json")
                    header("User-Agent", "Jeeves-Desktop/$CURRENT_VERSION")
                }

                if (response.status.value != 200) {
                    AppLogger.warn("UpdateChecker", "GitHub API returned ${response.status}")
                    _lastError.value = "Failed to check for updates (HTTP ${response.status.value})"
                    return@launch
                }

                val body = response.bodyAsText()
                val release = json.decodeFromString<GitHubRelease>(body)

                val latestVersion = release.tagName.removePrefix("v")
                if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    AppLogger.info("UpdateChecker", "Update available: $CURRENT_VERSION -> $latestVersion")
                    _updateAvailable.value = UpdateInfo(
                        currentVersion = CURRENT_VERSION,
                        latestVersion = latestVersion,
                        releaseUrl = release.htmlUrl,
                        releaseName = release.name,
                        releaseNotes = release.body,
                        publishedAt = release.publishedAt,
                        downloadUrls = release.assets.associate { it.name to it.browserDownloadUrl }
                    )
                } else {
                    AppLogger.info("UpdateChecker", "Already on latest version: $CURRENT_VERSION")
                    _updateAvailable.value = null
                }
            } catch (e: Exception) {
                AppLogger.error("UpdateChecker", "Failed to check for updates: ${e.message}")
                _lastError.value = "Failed to check for updates: ${e.message}"
            } finally {
                _checkInProgress.value = false
            }
        }
    }

    /**
     * Dismiss the update notification.
     */
    fun dismissUpdate() {
        _updateAvailable.value = null
    }

    /**
     * Compare semantic versions. Returns true if [latest] > [current].
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}

/**
 * Information about an available update.
 */
data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseName: String,
    val releaseNotes: String?,
    val publishedAt: String,
    val downloadUrls: Map<String, String>  // filename -> download URL
)

/**
 * GitHub release API response (partial).
 */
@Serializable
private data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String? = null,
    val html_url: String,
    val published_at: String,
    val assets: List<GitHubAsset> = emptyList()
) {
    val tagName: String get() = tag_name
    val htmlUrl: String get() = html_url
    val publishedAt: String get() = published_at
}

@Serializable
private data class GitHubAsset(
    val name: String,
    val browser_download_url: String
) {
    val browserDownloadUrl: String get() = browser_download_url
}
