package com.example.smarty.server.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Crawler Tool - Extract technical information from GitHub repositories.
 *
 * Supports:
 * - Repository metadata (stars, forks, contributors)
 * - File content extraction
 * - Code search
 * - Issue and PR analysis
 * - Security advisories
 * - Release notes
 *
 * Use cases:
 * - Technical code discovery
 * - Vulnerability research (CVE, security advisories)
 * - Open-source intelligence (OSINT)
 * - Configuration file extraction
 * - Dependency analysis
 */
class GitHubCrawlerTool {
    private val logger = LoggerFactory.getLogger(GitHubCrawlerTool::class.java)

    companion object {
        private const val GITHUB_API_BASE = "https://api.github.com"
        private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com"
        private const val TIMEOUT_MS = 30000
        private const val MAX_FILES = 50
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Get repository information
     */
    suspend fun getRepositoryInfo(owner: String, repo: String): GitHubRepository? {
        logger.info("Fetching repository info: $owner/$repo")
        
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$GITHUB_API_BASE/repos/$owner/$repo")
                val response = makeRequest(url)
                
                if (response.first == 200) {
                    json.decodeFromString<GitHubRepository>(response.second)
                } else {
                    logger.warn("Failed to fetch repo info: HTTP ${response.first}")
                    null
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch repository info: $owner/$repo", e)
                null
            }
        }
    }

    /**
     * Get file content from repository
     */
    suspend fun getFileContent(
        owner: String,
        repo: String,
        path: String,
        ref: String = "main"
    ): String? {
        logger.info("Fetching file content: $owner/$repo/$path")
        
        return withContext(Dispatchers.IO) {
            try {
                // First get file metadata
                val apiUrl = URL("$GITHUB_API_BASE/repos/$owner/$repo/contents/$path?ref=$ref")
                val response = makeRequest(apiUrl)
                
                if (response.first != 200) {
                    logger.warn("Failed to fetch file metadata: HTTP ${response.first}")
                    return@withContext null
                }
                
                val fileResponse = json.decodeFromString<GitHubFileResponse>(response.second)
                
                // Download raw content
                val rawUrl = URL("$GITHUB_RAW_BASE/$owner/$repo/$ref/$path")
                val contentResponse = makeRequest(rawUrl)
                
                if (contentResponse.first == 200) {
                    contentResponse.second
                } else {
                    logger.warn("Failed to fetch raw content: HTTP ${contentResponse.first}")
                    null
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch file content: $path", e)
                null
            }
        }
    }

    /**
     * Search GitHub repositories
     */
    suspend fun searchRepositories(query: String, limit: Int = 10): List<GitHubRepository> {
        logger.info("Searching repositories: $query")
        
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("$GITHUB_API_BASE/search/repositories?q=$encodedQuery&per_page=$limit")
                val response = makeRequest(url)
                
                if (response.first == 200) {
                    val searchResult = json.decodeFromString<GitHubSearchResult>(response.second)
                    searchResult.items
                } else {
                    logger.warn("Search failed: HTTP ${response.first}")
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error("Failed to search repositories", e)
                emptyList()
            }
        }
    }

    /**
     * Search code within repositories
     */
    suspend fun searchCode(query: String, limit: Int = 20): List<GitHubCodeResult> {
        logger.info("Searching code: $query")
        
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("$GITHUB_API_BASE/search/code?q=$encodedQuery&per_page=$limit")
                val response = makeRequest(url)
                
                if (response.first == 200) {
                    val searchResult = json.decodeFromString<GitHubCodeSearchResult>(response.second)
                    searchResult.items
                } else {
                    logger.warn("Code search failed: HTTP ${response.first}")
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error("Failed to search code", e)
                emptyList()
            }
        }
    }

    /**
     * Get repository issues
     */
    suspend fun getIssues(
        owner: String,
        repo: String,
        state: String = "open",
        limit: Int = 20
    ): List<GitHubIssue> {
        logger.info("Fetching issues: $owner/$repo (state: $state)")
        
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$GITHUB_API_BASE/repos/$owner/$repo/issues?state=$state&per_page=$limit")
                val response = makeRequest(url)
                
                if (response.first == 200) {
                    json.decodeFromString<List<GitHubIssue>>(response.second)
                } else {
                    logger.warn("Failed to fetch issues: HTTP ${response.first}")
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch issues", e)
                emptyList()
            }
        }
    }

    /**
     * Get repository security advisories
     */
    suspend fun getSecurityAdvisories(owner: String, repo: String): List<GitHubSecurityAdvisory> {
        logger.info("Fetching security advisories: $owner/$repo")
        
        return withContext(Dispatchers.IO) {
            try {
                // GitHub security advisories endpoint
                val url = URL("$GITHUB_API_BASE/repos/$owner/$repo/security-advisories")
                val response = makeRequest(url)
                
                if (response.first == 200) {
                    val result = json.decodeFromString<GitHubSecurityAdvisoriesResponse>(response.second)
                    result.advisories
                } else {
                    logger.warn("Failed to fetch advisories: HTTP ${response.first}")
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch security advisories", e)
                emptyList()
            }
        }
    }

    /**
     * Get repository releases
     */
    suspend fun getReleases(owner: String, repo: String, limit: Int = 10): List<GitHubRelease> {
        logger.info("Fetching releases: $owner/$repo")
        
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$GITHUB_API_BASE/repos/$owner/$repo/releases?per_page=$limit")
                val response = makeRequest(url)
                
                if (response.first == 200) {
                    json.decodeFromString<List<GitHubRelease>>(response.second)
                } else {
                    logger.warn("Failed to fetch releases: HTTP ${response.first}")
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch releases", e)
                emptyList()
            }
        }
    }

    /**
     * Get README content
     */
    suspend fun getReadme(owner: String, repo: String, ref: String = "main"): String? {
        logger.info("Fetching README: $owner/$repo")
        return getFileContent(owner, repo, "README.md", ref)
    }

    /**
     * Extract repository structure
     */
    suspend fun getRepositoryStructure(
        owner: String,
        repo: String,
        path: String = "",
        ref: String = "main"
    ): List<GitHubFile> {
        logger.info("Fetching repository structure: $owner/$repo/$path")
        
        return withContext(Dispatchers.IO) {
            try {
                val apiUrl = if (path.isEmpty()) {
                    URL("$GITHUB_API_BASE/repos/$owner/$repo/contents?ref=$ref")
                } else {
                    URL("$GITHUB_API_BASE/repos/$owner/$repo/contents/$path?ref=$ref")
                }
                
                val response = makeRequest(apiUrl)
                
                if (response.first == 200) {
                    json.decodeFromString<List<GitHubFile>>(response.second)
                } else {
                    logger.warn("Failed to fetch structure: HTTP ${response.first}")
                    emptyList()
                }
            } catch (e: Exception) {
                logger.error("Failed to fetch repository structure", e)
                emptyList()
            }
        }
    }

    /**
     * Make HTTP request to GitHub API
     */
    private suspend fun makeRequest(url: URL): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github.v3+json")
            setRequestProperty("User-Agent", "Smarty-Research-Agent")
        }
        
        val responseCode = connection.responseCode
        val responseBody = if (responseCode == 200) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        }
        
        Pair(responseCode, responseBody)
    }
}

// ==================== GitHub API Response Models ====================

@Serializable
data class GitHubRepository(
    val id: Long,
    val name: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("html_url") val htmlUrl: String,
    val description: String?,
    val fork: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("pushed_at") val pushedAt: String,
    @SerialName("homepage") val homepage: String?,
    val size: Int,
    @SerialName("stargazers_count") val stargazersCount: Int,
    @SerialName("watchers_count") val watchersCount: Int,
    val language: String?,
    val forks: Int,
    @SerialName("open_issues") val openIssues: Int,
    @SerialName("watchers") val watchers: Int,
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("open_issues_count") val openIssuesCount: Int,
    @SerialName("topics") val topics: List<String>?,
    @SerialName("forks_count") val forksCount: Int,
    @SerialName("archived") val archived: Boolean,
    val disabled: Boolean,
    @SerialName("license") val license: GitHubLicense?,
    @SerialName("allow_forking") val allowForking: Boolean,
    @SerialName("is_template") val isTemplate: Boolean,
    @SerialName("visibility") val visibility: String,
    val owner: GitHubUser
)

@Serializable
data class GitHubUser(
    val login: String,
    val id: Long,
    @SerialName("avatar_url") val avatarUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    val type: String
)

@Serializable
data class GitHubLicense(
    val key: String,
    val name: String,
    val spdxId: String,
    val url: String?
)

@Serializable
data class GitHubFileResponse(
    val name: String,
    val path: String,
    val sha: String,
    val size: Int,
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("git_url") val gitUrl: String,
    @SerialName("download_url") val downloadUrl: String?,
    val type: String,
    val content: String?,
    val encoding: String?
)

@Serializable
data class GitHubFile(
    val name: String,
    val path: String,
    val sha: String,
    val size: Int?,
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("git_url") val gitUrl: String,
    @SerialName("download_url") val downloadUrl: String?,
    val type: String,
    @SerialName("_links") val links: GitHubFileLinks
)

@Serializable
data class GitHubFileLinks(
    val self: String,
    val git: String,
    val html: String
)

@Serializable
data class GitHubSearchResult(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("incomplete_results") val incompleteResults: Boolean,
    val items: List<GitHubRepository>
)

@Serializable
data class GitHubCodeSearchResult(
    @SerialName("total_count") val totalCount: Int,
    @SerialName("incomplete_results") val incompleteResults: Boolean,
    val items: List<GitHubCodeResult>
)

@Serializable
data class GitHubCodeResult(
    val name: String,
    val path: String,
    val sha: String,
    val url: String,
    @SerialName("git_url") val gitUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    val repository: GitHubRepository,
    @SerialName("score") val score: Double
)

@Serializable
data class GitHubIssue(
    val url: String,
    @SerialName("repository_url") val repositoryUrl: String,
    @SerialName("html_url") val htmlUrl: String,
    val id: Long,
    val number: Int,
    val state: String,
    val title: String,
    val body: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("closed_at") val closedAt: String?,
    val labels: List<GitHubLabel>,
    val user: GitHubUser,
    val assignees: List<GitHubUser>?,
    val comments: Int,
    @SerialName("closed_by") val closedBy: GitHubUser?
)

@Serializable
data class GitHubLabel(
    val id: Long,
    val url: String,
    val name: String,
    val color: String,
    val description: String?
)

@Serializable
data class GitHubSecurityAdvisoriesResponse(
    val advisories: List<GitHubSecurityAdvisory>
)

@Serializable
data class GitHubSecurityAdvisory(
    val id: String,
    val ghsaId: String,
    val cveId: String?,
    val summary: String,
    val description: String,
    val severity: String,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val identifiers: List<GitHubAdvisoryIdentifier>,
    val references: List<GitHubAdvisoryReference>,
    val vulnerabilities: List<GitHubVulnerability>
)

@Serializable
data class GitHubAdvisoryIdentifier(
    val value: String,
    val type: String
)

@Serializable
data class GitHubAdvisoryReference(
    val url: String
)

@Serializable
data class GitHubVulnerability(
    val packageName: String,
    val vulnerableVersionRange: String,
    @SerialName("first_patched_version") val firstPatchedVersion: GitHubPatchedVersion?,
    val vulnerableFunctions: List<String>?
)

@Serializable
data class GitHubPatchedVersion(
    val identifier: String
)

@Serializable
data class GitHubRelease(
    val url: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("assets_url") val assetsUrl: String,
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    @SerialName("tag_name") val tagName: String,
    @SerialName("target_commitish") val targetCommitish: String,
    val name: String,
    val draft: Boolean,
    val prerelease: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("published_at") val publishedAt: String,
    val body: String?,
    val author: GitHubUser,
    val assets: List<GitHubReleaseAsset>
)

@Serializable
data class GitHubReleaseAsset(
    val url: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val id: Long,
    @SerialName("node_id") val nodeId: String,
    val name: String,
    val label: String?,
    val state: String,
    @SerialName("content_type") val contentType: String,
    val size: Int,
    @SerialName("download_count") val downloadCount: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)
