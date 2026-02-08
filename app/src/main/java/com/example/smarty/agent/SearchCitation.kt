package com.example.smarty.agent

/**
 * Represents a citation for a search result.
 * Used by search tools to pass citation data back to the agent.
 */
data class SearchCitation(
    val title: String,
    val url: String,
    val snippet: String
)
