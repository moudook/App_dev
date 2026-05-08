package com.example.smarty.server.services

import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

/**
 * Server-side implementation of SemanticSearchEngine.
 * Ported from Android app for consistency and "advanced" capability.
 */
object SemanticSearchEngine {
    private val logger = LoggerFactory.getLogger(SemanticSearchEngine::class.java)

    // Thresholds for matching
    private const val EXACT_MATCH_SCORE = 1000
    private const val HIGH_SIMILARITY_THRESHOLD = 0.85
    private const val MEDIUM_SIMILARITY_THRESHOLD = 0.65
    private const val LOW_SIMILARITY_THRESHOLD = 0.45
    private const val MINIMUM_MATCH_THRESHOLD = 0.30

    private val SEPARATOR_REGEX = Regex("[_\\-.]")
    private val SPECIAL_CHAR_REGEX = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    private val SOUNDEX_MAP = mapOf(
        'B' to '1', 'F' to '1', 'P' to '1', 'V' to '1',
        'C' to '2', 'G' to '2', 'J' to '2', 'K' to '2', 'Q' to '2', 'S' to '2', 'X' to '2', 'Z' to '2',
        'D' to '3', 'T' to '3',
        'L' to '4',
        'M' to '5', 'N' to '5',
        'R' to '6',
    )

    data class SearchResult<T>(
        val item: T,
        val score: Double,
        val matchType: MatchType,
        val matchedTerms: List<String> = emptyList(),
    )

    enum class MatchType {
        EXACT, CONTAINS, FUZZY_HIGH, FUZZY_MEDIUM, FUZZY_LOW, TOKEN_MATCH, PHONETIC, PARTIAL,
    }

    fun <T> search(
        query: String,
        items: List<T>,
        textExtractor: (T) -> List<String>,
        minScore: Double = MINIMUM_MATCH_THRESHOLD,
    ): List<SearchResult<T>> {
        if (query.isBlank()) return emptyList()

        val normalizedQuery = normalizeText(query)
        val queryTokens = tokenize(normalizedQuery)
        val querySoundex = queryTokens.map { soundex(it) }.filter { it.isNotEmpty() }

        val results = mutableListOf<SearchResult<T>>()

        for (item in items) {
            val textFields = textExtractor(item)
            val bestMatch = findBestMatch(normalizedQuery, queryTokens, querySoundex, textFields)

            if (bestMatch.score >= minScore) {
                results.add(
                    SearchResult(
                        item = item,
                        score = bestMatch.score,
                        matchType = bestMatch.matchType,
                        matchedTerms = bestMatch.matchedTerms,
                    ),
                )
            }
        }

        return results.sortedByDescending { it.score }
    }

    fun calculateSimilarity(query: String, target: String): Double {
        val normalizedQuery = normalizeText(query)
        val normalizedTarget = normalizeText(target)

        if (normalizedQuery == normalizedTarget) return 1.0
        if (normalizedTarget.contains(normalizedQuery)) return 0.95
        if (normalizedQuery.contains(normalizedTarget)) return 0.90

        return combinedSimilarity(normalizedQuery, normalizedTarget)
    }

    private data class MatchResult(
        val score: Double,
        val matchType: MatchType,
        val matchedTerms: List<String>,
    )

    private fun findBestMatch(
        query: String,
        queryTokens: List<String>,
        querySoundex: List<String>,
        textFields: List<String>,
    ): MatchResult {
        var bestScore = 0.0
        var bestMatchType = MatchType.PARTIAL
        val matchedTerms = mutableListOf<String>()

        for (text in textFields) {
            if (text.isBlank()) continue

            val normalizedText = normalizeText(text)
            val textTokens = tokenize(normalizedText)

            if (normalizedText == query || textTokens.any { it == query }) {
                return MatchResult(1.0, MatchType.EXACT, listOf(text))
            }

            if (normalizedText.contains(query)) {
                val containsScore = 0.95 - (0.1 * (normalizedText.length - query.length) / normalizedText.length)
                if (containsScore > bestScore) {
                    bestScore = containsScore
                    bestMatchType = MatchType.CONTAINS
                    matchedTerms.clear()
                    matchedTerms.add(text)
                }
                continue
            }

            val tokenOverlapScore = tokenOverlapScore(queryTokens, textTokens)
            if (tokenOverlapScore > bestScore) {
                bestScore = tokenOverlapScore
                bestMatchType = MatchType.TOKEN_MATCH
                matchedTerms.clear()
                matchedTerms.addAll(
                    textTokens.filter { token ->
                        queryTokens.any { q -> token.contains(q) || q.contains(token) }
                    },
                )
            }

            val fuzzySimilarity = combinedSimilarity(query, normalizedText)
            if (fuzzySimilarity > bestScore) {
                bestScore = fuzzySimilarity
                bestMatchType = when {
                    fuzzySimilarity >= HIGH_SIMILARITY_THRESHOLD -> MatchType.FUZZY_HIGH
                    fuzzySimilarity >= MEDIUM_SIMILARITY_THRESHOLD -> MatchType.FUZZY_MEDIUM
                    fuzzySimilarity >= LOW_SIMILARITY_THRESHOLD -> MatchType.FUZZY_LOW
                    else -> MatchType.PARTIAL
                }
                matchedTerms.clear()
                matchedTerms.add(text)
            }

            val textSoundex = textTokens.map { soundex(it) }.filter { it.isNotEmpty() }
            val phoneticScore = phoneticMatchScore(querySoundex, textSoundex)
            if (phoneticScore > bestScore) {
                bestScore = phoneticScore
                bestMatchType = MatchType.PHONETIC
                matchedTerms.clear()
                matchedTerms.add(text)
            }
        }

        return MatchResult(bestScore, bestMatchType, matchedTerms)
    }

    private fun combinedSimilarity(s1: String, s2: String): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val jaroWinkler = jaroWinklerSimilarity(s1, s2)
        val levenshtein = 1.0 - (levenshteinDistance(s1, s2).toDouble() / max(s1.length, s2.length))
        val ngram = ngramSimilarity(s1, s2, 2)
        return (jaroWinkler * 0.4) + (levenshtein * 0.3) + (ngram * 0.3)
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val jaro = jaroSimilarity(s1, s2)
        val prefixLength = s1.zip(s2).takeWhile { (a, b) -> a == b }.size.coerceAtMost(4)
        return jaro + (prefixLength * 0.1 * (1 - jaro))
    }

    private fun jaroSimilarity(s1: String, s2: String): Double {
        val matchWindow = max(s1.length, s2.length) / 2 - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        var matches = 0
        var transpositions = 0
        for (i in s1.indices) {
            val start = max(0, i - matchWindow)
            val end = min(i + matchWindow + 1, s2.length)
            for (j in start until end) {
                if (s2Matches[j] || s1[i] != s2[j]) continue
                s1Matches[i] = true
                s2Matches[j] = true
                matches++
                break
            }
        }
        if (matches == 0) return 0.0
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }
        return ((matches.toDouble() / s1.length) + (matches.toDouble() / s2.length) + ((matches - transpositions / 2.0) / matches)) / 3.0
    }

    fun ngramSimilarity(s1: String, s2: String, n: Int = 2): Double {
        if (s1.length < n || s2.length < n) return 0.0
        val ngrams1 = s1.windowed(n).toSet()
        val ngrams2 = s2.windowed(n).toSet()
        val intersection = ngrams1.intersect(ngrams2).size
        val union = ngrams1.union(ngrams2).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun tokenOverlapScore(queryTokens: List<String>, targetTokens: List<String>): Double {
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0.0
        var matchedTokens = 0
        for (queryToken in queryTokens) {
            if (targetTokens.any { it == queryToken || it.contains(queryToken) || queryToken.contains(it) }) {
                matchedTokens++
            }
        }
        return matchedTokens.toDouble() / queryTokens.size
    }

    private fun phoneticMatchScore(querySoundex: List<String>, targetSoundex: List<String>): Double {
        if (querySoundex.isEmpty() || targetSoundex.isEmpty()) return 0.0
        val matches = querySoundex.count { qs -> targetSoundex.any { ts -> ts == qs } }
        return (matches.toDouble() / querySoundex.size) * 0.85
    }

    fun soundex(word: String): String {
        if (word.isEmpty()) return ""
        val cleaned = word.uppercase().filter { it.isLetter() }
        if (cleaned.isEmpty()) return ""
        val result = StringBuilder().append(cleaned[0])
        var lastCode: Char? = SOUNDEX_MAP[cleaned[0]]
        for (i in 1 until cleaned.length) {
            val code = SOUNDEX_MAP[cleaned[i]]
            if (code != null && code != lastCode) {
                result.append(code)
                if (result.length == 4) break
            }
            lastCode = code ?: lastCode
        }
        while (result.length < 4) result.append('0')
        return result.toString()
    }

    fun normalizeText(text: String): String {
        return text.lowercase().replace(SEPARATOR_REGEX, " ").replace(SPECIAL_CHAR_REGEX, "").replace(MULTI_SPACE_REGEX, " ").trim()
    }

    fun tokenize(text: String): List<String> {
        return normalizeText(text).split(" ").filter { it.length >= 2 }.distinct()
    }
}
