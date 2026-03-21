package com.example.smarty.server.agent

import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty

/**
 * Research Agent Tools - Enhanced toolset for advanced research.
 * Includes web search, scraping, PDF extraction, GitHub discovery, and progress tracking.
 */
object ResearchAgentTools {

    /**
     * Get all tools available to Research Agent (Standard)
     */
    fun getTools(): List<ToolDefinition> {
        return listOf(
            webSearchToolDefinition(),
            webScrapeToolDefinition(),
            saveProgressToolDefinition(),
            readProgressToolDefinition()
        )
    }

    /**
     * Get enhanced tools for Advanced Research Agent
     */
    fun getEnhancedTools(): List<ToolDefinition> {
        return listOf(
            webSearchToolDefinition(),
            webScrapeToolDefinition(),
            pdfCrawlerToolDefinition(),
            githubCrawlerToolDefinition(),
            saveProgressToolDefinition(),
            readProgressToolDefinition()
        )
    }
    
    /**
     * Web Search Tool Definition
     */
    private fun webSearchToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "web_search",
            description = "Search the web for information. Use this to find current information, news, academic papers, and general knowledge.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty(
                        type = "string",
                        description = "The search query"
                    ),
                    "purpose" to ToolProperty(
                        type = "string",
                        description = "Why this search is being performed (for tracking)"
                    )
                ),
                required = listOf("query")
            )
        )
    }
    
    /**
     * Web Scrape Tool Definition
     */
    private fun webScrapeToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "web_scrape",
            description = "Extract full text content from a specific URL. Use this after finding a relevant URL to get detailed information.",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ToolProperty(
                        type = "string",
                        description = "The URL to scrape"
                    ),
                    "purpose" to ToolProperty(
                        type = "string",
                        description = "Why this page is being scraped (for tracking)"
                    )
                ),
                required = listOf("url")
            )
        )
    }
    
    /**
     * Save Progress Tool Definition - Track findings in progress file
     */
    private fun saveProgressToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "save_progress",
            description = "Save important findings to the research progress file. Use this to track useful information during long research sessions.",
            parameters = ToolParameters(
                properties = mapOf(
                    "finding" to ToolProperty(
                        type = "string",
                        description = "The key finding or information to save"
                    ),
                    "source" to ToolProperty(
                        type = "string",
                        description = "The source URL or reference for this finding"
                    ),
                    "category" to ToolProperty(
                        type = "string",
                        description = "Category or theme for this finding (e.g., 'background', 'methodology', 'results')"
                    )
                ),
                required = listOf("finding", "source")
            )
        )
    }
    
    /**
     * Read Progress Tool Definition - Read saved findings from progress file
     */
    private fun readProgressToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "read_progress",
            description = "Read previously saved findings from the research progress file. Use this when context is exceeded or to review accumulated knowledge.",
            parameters = ToolParameters(
                properties = mapOf(
                    "category" to ToolProperty(
                        type = "string",
                        description = "Optional category to filter findings (leave empty for all)"
                    )
                ),
                required = emptyList()
            )
        )
    }

    /**
     * PDF Crawler Tool Definition - Extract text from PDF documents
     */
    private fun pdfCrawlerToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "pdf_crawler",
            description = """Extract text from PDF documents (academic papers, technical reports, government documents).
Use this when you find PDF URLs in search results or when the user specifically mentions a PDF.

CAPABILITIES:
- Extract text from remote PDF URLs
- Process academic papers, government reports (NIST, CISA, NSA)
- Extract up to 100 pages (50MB max)
- Preserve document metadata (title, author, date)

EXAMPLES:
- pdf_crawler(url="https://arxiv.org/pdf/2301.12345.pdf")
- pdf_crawler(url="https://cisa.gov/sites/default/files/publications/report.pdf")

Use for: academic research, technical documentation, government reports.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "url" to ToolProperty(
                        type = "string",
                        description = "The PDF URL to extract content from"
                    ),
                    "purpose" to ToolProperty(
                        type = "string",
                        description = "Why this PDF is being extracted (for tracking)"
                    )
                ),
                required = listOf("url")
            )
        )
    }

    /**
     * GitHub Crawler Tool Definition - Extract technical information from GitHub
     */
    private fun githubCrawlerToolDefinition(): ToolDefinition {
        return ToolDefinition(
            name = "github_crawler",
            description = """Extract technical information from GitHub repositories.
Use this for technical research, code discovery, vulnerability research, and OSINT.

CAPABILITIES:
- Get repository metadata (stars, forks, contributors, language)
- Extract file content (source code, configs, documentation)
- Search code across repositories
- Get security advisories and CVE information
- Fetch release notes and changelogs
- Analyze issues and pull requests

ACTIONS:
- repo_info: Get repository metadata (owner, repo)
- file_content: Extract file content (owner, repo, path)
- search_code: Search for code patterns (query)
- security_advisories: Get security advisories (owner, repo)
- releases: Get release information (owner, repo)
- issues: Get issues (owner, repo, state)

EXAMPLES:
- github_crawler(action="repo_info", owner="microsoft", repo="vscode")
- github_crawler(action="file_content", owner="torvalds", repo="linux", path="README.md")
- github_crawler(action="search_code", query="CVE-2024-1234 exploit")
- github_crawler(action="security_advisories", owner="apache", repo="log4j")

Use for: technical research, vulnerability analysis, code discovery, OSINT.""",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty(
                        type = "string",
                        description = "Action: repo_info|file_content|search_code|security_advisories|releases|issues",
                        enum = listOf("repo_info", "file_content", "search_code", "security_advisories", "releases", "issues")
                    ),
                    "owner" to ToolProperty(
                        type = "string",
                        description = "GitHub owner/organization (e.g., 'microsoft', 'apache')"
                    ),
                    "repo" to ToolProperty(
                        type = "string",
                        description = "Repository name"
                    ),
                    "path" to ToolProperty(
                        type = "string",
                        description = "File path within repository (for file_content action)"
                    ),
                    "query" to ToolProperty(
                        type = "string",
                        description = "Search query (for search_code action)"
                    ),
                    "state" to ToolProperty(
                        type = "string",
                        description = "Issue state: open|closed|all (for issues action)",
                        enum = listOf("open", "closed", "all")
                    )
                ),
                required = listOf("action")
            )
        )
    }
}
