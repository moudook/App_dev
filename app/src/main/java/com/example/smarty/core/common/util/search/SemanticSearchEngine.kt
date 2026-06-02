package com.example.smarty.core.common.util.search

import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * On-device semantic search engine using multiple fuzzy matching algorithms.
 * No API calls required - all processing is local.
 *
 * Algorithms used:
 * 1. Levenshtein distance (edit distance)
 * 2. Jaro-Winkler similarity (good for short strings)
 * 3. N-gram matching (substring overlap)
 * 4. Token overlap (word-level matching)
 * 5. Phonetic matching (Soundex for similar-sounding words)
 * 6. Prefix/suffix matching
 */
object SemanticSearchEngine {
    private const val TAG = "SemanticSearchEngine"

    // Thresholds for matching
    private const val EXACT_MATCH_SCORE = 1000
    private const val HIGH_SIMILARITY_THRESHOLD = 0.85
    private const val MEDIUM_SIMILARITY_THRESHOLD = 0.65
    private const val LOW_SIMILARITY_THRESHOLD = 0.45
    private const val MINIMUM_MATCH_THRESHOLD = 0.30

    // PERFORMANCE: Limits to prevent excessive CPU usage on large datasets
    private const val MAX_RESULTS_LIMIT = 50
    private const val EARLY_TERMINATION_EXACT_MATCHES = 10

    // GLUT-044 FIX: Pre-compile Regex patterns to avoid creation on every call
    private val SEPARATOR_REGEX = Regex("[_\\-.]")
    private val SPECIAL_CHAR_REGEX = Regex("[^a-z0-9\\s]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    // GLUT-047 FIX: Pre-compile soundex map to avoid creation on every call
    private val SOUNDEX_MAP =
        mapOf(
            'B' to '1',
            'F' to '1',
            'P' to '1',
            'V' to '1',
            'C' to '2',
            'G' to '2',
            'J' to '2',
            'K' to '2',
            'Q' to '2',
            'S' to '2',
            'X' to '2',
            'Z' to '2',
            'D' to '3',
            'T' to '3',
            'L' to '4',
            'M' to '5',
            'N' to '5',
            'R' to '6',
        )

    /**
     * Search result with relevance score and match details.
     */
    data class SearchResult<T>(
        val item: T,
        val score: Double,
        val matchType: MatchType,
        val matchedTerms: List<String> = emptyList(),
    )

    enum class MatchType {
        EXACT, // Exact string match
        CONTAINS, // Query contained in target
        FUZZY_HIGH, // High fuzzy similarity (>85%)
        FUZZY_MEDIUM, // Medium fuzzy similarity (65-85%)
        FUZZY_LOW, // Low fuzzy similarity (45-65%)
        TOKEN_MATCH, // Word-level token match
        PHONETIC, // Phonetic similarity (sounds like)
        PARTIAL, // Partial/prefix match
    }

    /**
     * Main search function - searches a list of items using semantic matching.
     *
     * @param query The search query
     * @param items List of items to search
     * @param textExtractor Function to extract searchable text from each item
     * @param minScore Minimum score threshold (0.0-1.0)
     * @return List of SearchResults sorted by relevance score (highest first)
     */
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
        val significantTokens = queryTokens.filter { it.length >= 3 }

        Log.d(TAG, " Search query: '$query' → normalized: '$normalizedQuery'")
        Log.d(TAG, " Tokens: $queryTokens, Significant tokens (3+ chars): $significantTokens")

        // OPTIMIZED: Early termination and result limiting for better performance
        val results = mutableListOf<SearchResult<T>>()
        var exactMatchCount = 0

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

                // Track exact matches for early termination
                if (bestMatch.matchType == MatchType.EXACT) {
                    exactMatchCount++
                }

                // Early termination: stop after enough exact matches or max results
                if (exactMatchCount >= EARLY_TERMINATION_EXACT_MATCHES ||
                    results.size >= MAX_RESULTS_LIMIT
                ) {
                    break
                }
            }
        }

        return results.sortedByDescending { it.score }
    }

    /**
     * Calculate similarity between query and a single text field.
     */
    fun calculateSimilarity(
        query: String,
        target: String,
    ): Double {
        val normalizedQuery = normalizeText(query)
        val normalizedTarget = normalizeText(target)

        if (normalizedQuery == normalizedTarget) return 1.0
        if (normalizedTarget.contains(normalizedQuery)) return 0.95
        if (normalizedQuery.contains(normalizedTarget)) return 0.90

        return combinedSimilarity(normalizedQuery, normalizedTarget)
    }

    // ==================== INTERNAL MATCHING LOGIC ====================

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

            // 1. Exact match (highest priority)
            if (normalizedText == query || textTokens.any { it == query }) {
                return MatchResult(1.0, MatchType.EXACT, listOf(text))
            }

            // 2. Contains match - query is contained in the text
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

            // 2b. Reverse contains - text is contained in query (user typed more than file name)
            if (query.contains(normalizedText) && normalizedText.length >= 3) {
                val reverseContainsScore = 0.90 - (0.1 * (query.length - normalizedText.length) / query.length)
                if (reverseContainsScore > bestScore) {
                    bestScore = reverseContainsScore
                    bestMatchType = MatchType.CONTAINS
                    matchedTerms.clear()
                    matchedTerms.add(text)
                }
                continue
            }

            // 2c. Word-by-word phrase containment check
            // Check if all significant query words appear in the text in any order
            val significantQueryTokens = queryTokens.filter { it.length >= 3 }
            if (significantQueryTokens.isNotEmpty()) {
                val matchedWords =
                    significantQueryTokens.count { qToken ->
                        textTokens.any { tToken -> tToken == qToken || tToken.contains(qToken) || qToken.contains(tToken) }
                    }
                Log.d(TAG, " Phrase check: '$text' → matched $matchedWords/${significantQueryTokens.size} words")
                if (matchedWords == significantQueryTokens.size) {
                    // All significant words found! This is a strong match
                    val phraseScore = 0.92
                    Log.d(TAG, " All significant words matched in '$text'! Score: $phraseScore")
                    if (phraseScore > bestScore) {
                        bestScore = phraseScore
                        bestMatchType = MatchType.CONTAINS
                        matchedTerms.clear()
                        matchedTerms.add(text)
                    }
                    continue
                }
            }

            // 3. Token overlap matching
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

            // 4. Fuzzy similarity (combines multiple algorithms)
            val fuzzySimilarity = combinedSimilarity(query, normalizedText)
            if (fuzzySimilarity > bestScore) {
                bestScore = fuzzySimilarity
                bestMatchType =
                    when {
                        fuzzySimilarity >= HIGH_SIMILARITY_THRESHOLD -> MatchType.FUZZY_HIGH
                        fuzzySimilarity >= MEDIUM_SIMILARITY_THRESHOLD -> MatchType.FUZZY_MEDIUM
                        fuzzySimilarity >= LOW_SIMILARITY_THRESHOLD -> MatchType.FUZZY_LOW
                        else -> MatchType.PARTIAL
                    }
                matchedTerms.clear()
                matchedTerms.add(text)
            }

            // 5. Phonetic matching (Soundex)
            val textSoundex = textTokens.map { soundex(it) }.filter { it.isNotEmpty() }
            val phoneticScore = phoneticMatchScore(querySoundex, textSoundex)
            if (phoneticScore > bestScore) {
                bestScore = phoneticScore
                bestMatchType = MatchType.PHONETIC
                matchedTerms.clear()
                matchedTerms.add(text)
            }

            // 6. Individual token fuzzy matching
            val tokenFuzzyScore = tokenFuzzyScore(queryTokens, textTokens)
            if (tokenFuzzyScore > bestScore) {
                bestScore = tokenFuzzyScore
                bestMatchType = MatchType.FUZZY_MEDIUM
                matchedTerms.clear()
                matchedTerms.add(text)
            }
        }

        return MatchResult(bestScore, bestMatchType, matchedTerms)
    }

    /**
     * Combined similarity using weighted average of multiple algorithms.
     */
    private fun combinedSimilarity(
        s1: String,
        s2: String,
    ): Double {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val jaroWinkler = jaroWinklerSimilarity(s1, s2)
        val levenshtein = 1.0 - (levenshteinDistance(s1, s2).toDouble() / max(s1.length, s2.length))
        val ngram = ngramSimilarity(s1, s2, 2) // Bigrams
        val trigram = ngramSimilarity(s1, s2, 3) // Trigrams

        // Weighted combination - Jaro-Winkler is best for names/titles
        return (jaroWinkler * 0.35) + (levenshtein * 0.25) + (ngram * 0.25) + (trigram * 0.15)
    }

    // ==================== SIMILARITY ALGORITHMS ====================

    /**
     * Levenshtein distance - minimum edits to transform s1 into s2.
     */
    fun levenshteinDistance(
        s1: String,
        s2: String,
    ): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] =
                    minOf(
                        dp[i - 1][j] + 1, // Deletion
                        dp[i][j - 1] + 1, // Insertion
                        dp[i - 1][j - 1] + cost, // Substitution
                    )
            }
        }

        return dp[s1.length][s2.length]
    }

    /**
     * Jaro-Winkler similarity - good for short strings like names.
     * Returns value between 0.0 and 1.0.
     */
    fun jaroWinklerSimilarity(
        s1: String,
        s2: String,
    ): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        val jaro = jaroSimilarity(s1, s2)

        // Winkler modification - boost for common prefix
        val prefixLength =
            s1
                .zip(s2)
                .takeWhile { (a, b) -> a == b }
                .size
                .coerceAtMost(4)
        val scalingFactor = 0.1

        return jaro + (prefixLength * scalingFactor * (1 - jaro))
    }

    private fun jaroSimilarity(
        s1: String,
        s2: String,
    ): Double {
        if (s1 == s2) return 1.0

        val matchWindow = max(s1.length, s2.length) / 2 - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)

        var matches = 0
        var transpositions = 0

        // Find matches
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

        // Count transpositions
        var k = 0
        for (i in s1.indices) {
            if (!s1Matches[i]) continue
            while (!s2Matches[k]) k++
            if (s1[i] != s2[k]) transpositions++
            k++
        }

        return (
            (matches.toDouble() / s1.length) +
                (matches.toDouble() / s2.length) +
                ((matches - transpositions / 2.0) / matches)
        ) / 3.0
    }

    /**
     * N-gram similarity using Jaccard index.
     */
    fun ngramSimilarity(
        s1: String,
        s2: String,
        n: Int = 2,
    ): Double {
        if (s1.length < n || s2.length < n) return 0.0

        val ngrams1 = s1.windowed(n).toSet()
        val ngrams2 = s2.windowed(n).toSet()

        val intersection = ngrams1.intersect(ngrams2).size
        val union = ngrams1.union(ngrams2).size

        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * Token overlap score - how many query tokens match target tokens.
     * Enhanced with consecutive word bonus for better phrase matching.
     */
    private fun tokenOverlapScore(
        queryTokens: List<String>,
        targetTokens: List<String>,
    ): Double {
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0.0

        var matchedTokens = 0
        var partialMatches = 0.0
        val matchedIndices = mutableListOf<Int>() // Track indices for consecutive check

        for (queryToken in queryTokens) {
            // Exact token match
            val exactMatchIndex = targetTokens.indexOfFirst { it == queryToken }
            if (exactMatchIndex >= 0) {
                matchedTokens++
                matchedIndices.add(exactMatchIndex)
                continue
            }

            // Partial token match (contains)
            val containsMatchIndex =
                targetTokens.indexOfFirst {
                    it.contains(queryToken) || queryToken.contains(it)
                }
            if (containsMatchIndex >= 0) {
                partialMatches += 0.7
                matchedIndices.add(containsMatchIndex)
                continue
            }

            // Fuzzy token match
            var bestFuzzyMatch = 0.0
            var bestFuzzyIndex = -1
            targetTokens.forEachIndexed { index, targetToken ->
                val similarity = jaroWinklerSimilarity(queryToken, targetToken)
                if (similarity > bestFuzzyMatch) {
                    bestFuzzyMatch = similarity
                    bestFuzzyIndex = index
                }
            }

            if (bestFuzzyMatch >= 0.85) {
                partialMatches += bestFuzzyMatch * 0.8
                if (bestFuzzyIndex >= 0) matchedIndices.add(bestFuzzyIndex)
            } else if (bestFuzzyMatch >= 0.70) {
                partialMatches += bestFuzzyMatch * 0.5
                if (bestFuzzyIndex >= 0) matchedIndices.add(bestFuzzyIndex)
            }
        }

        val baseScore = (matchedTokens + partialMatches) / queryTokens.size

        // BONUS: Add consecutive word bonus if query words appear in sequence
        val consecutiveBonus = calculateConsecutiveBonus(matchedIndices, queryTokens.size)

        // BONUS: If ALL query tokens matched exactly, give a high score
        val perfectMatchBonus = if (matchedTokens == queryTokens.size) 0.15 else 0.0

        return (baseScore + consecutiveBonus + perfectMatchBonus).coerceAtMost(1.0)
    }

    /**
     * Calculate bonus for consecutive word matches.
     * If query "deep in your love" matches indices [0,1,2,3] in target, give max bonus.
     */
    private fun calculateConsecutiveBonus(
        indices: List<Int>,
        querySize: Int,
    ): Double {
        if (indices.size < 2) return 0.0

        val sorted = indices.sorted()
        var consecutiveCount = 1
        var maxConsecutive = 1

        for (i in 1 until sorted.size) {
            if (sorted[i] == sorted[i - 1] + 1) {
                consecutiveCount++
                maxConsecutive = max(maxConsecutive, consecutiveCount)
            } else {
                consecutiveCount = 1
            }
        }

        // Bonus based on how many consecutive matches vs query size
        // All words consecutive = 0.10 bonus
        return (maxConsecutive.toDouble() / querySize) * 0.10
    }

    /**
     * Fuzzy matching between individual tokens.
     */
    private fun tokenFuzzyScore(
        queryTokens: List<String>,
        targetTokens: List<String>,
    ): Double {
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0.0

        var totalScore = 0.0

        for (queryToken in queryTokens) {
            val bestMatch =
                targetTokens.maxOfOrNull { targetToken ->
                    combinedSimilarity(queryToken, targetToken)
                } ?: 0.0

            totalScore += bestMatch
        }

        return totalScore / queryTokens.size
    }

    /**
     * Phonetic match score using Soundex.
     */
    private fun phoneticMatchScore(
        querySoundex: List<String>,
        targetSoundex: List<String>,
    ): Double {
        if (querySoundex.isEmpty() || targetSoundex.isEmpty()) return 0.0

        val matches =
            querySoundex.count { qs ->
                targetSoundex.any { ts -> ts == qs }
            }

        return (matches.toDouble() / querySoundex.size) * 0.85 // Cap at 0.85 for phonetic
    }

    /**
     * Soundex algorithm - converts words to phonetic codes.
     * Words that sound similar get the same code.
     */
    fun soundex(word: String): String {
        if (word.isEmpty()) return ""

        val cleaned = word.uppercase().filter { it.isLetter() }
        if (cleaned.isEmpty()) return ""

        // Uses pre-compiled SOUNDEX_MAP for better performance
        val result = StringBuilder()
        result.append(cleaned[0])

        var lastCode: Char? = SOUNDEX_MAP[cleaned[0]]

        for (i in 1 until cleaned.length) {
            val code = SOUNDEX_MAP[cleaned[i]]
            if (code != null && code != lastCode) {
                result.append(code)
                if (result.length == 4) break
            }
            lastCode = code ?: lastCode
        }

        // Pad with zeros
        while (result.length < 4) {
            result.append('0')
        }

        return result.toString()
    }

    // ==================== TEXT PROCESSING ====================

    /**
     * Normalize text for comparison - lowercase, remove special chars.
     * Uses pre-compiled Regex patterns for better performance.
     */
    fun normalizeText(text: String): String =
        text
            .lowercase()
            .replace(SEPARATOR_REGEX, " ") // Replace separators with spaces
            .replace(SPECIAL_CHAR_REGEX, "") // Remove special chars
            .replace(MULTI_SPACE_REGEX, " ") // Collapse multiple spaces
            .trim()

    /**
     * Tokenize text into individual words.
     */
    fun tokenize(text: String): List<String> =
        normalizeText(text)
            .split(" ")
            .filter { it.length >= 2 } // Filter out very short tokens
            .distinct()

    /**
     * Extract meaningful keywords from text, filtering common words.
     */
    fun extractKeywords(text: String): List<String> {
        val stopWords =
            setOf(
                "the",
                "a",
                "an",
                "and",
                "or",
                "but",
                "in",
                "on",
                "at",
                "to",
                "for",
                "of",
                "with",
                "by",
                "from",
                "as",
                "is",
                "was",
                "are",
                "were",
                "be",
                "been",
                "being",
                "have",
                "has",
                "had",
                "do",
                "does",
                "did",
                "will",
                "would",
                "could",
                "should",
                "may",
                "might",
                "must",
                "shall",
                "can",
                "this",
                "that",
                "these",
                "those",
                "it",
                "its",
                "my",
                "your",
                "his",
                "her",
                "our",
                "their",
                "me",
                "you",
                "him",
                "us",
                "them",
            )

        return tokenize(text).filter { it !in stopWords && it.length >= 3 }
    }
}
