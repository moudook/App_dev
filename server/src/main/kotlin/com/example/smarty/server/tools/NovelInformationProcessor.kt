package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

@Serializable
data class NoveltyReport(
    val id: String = UUID.randomUUID().toString(),
    val input: String,
    val noveltyScore: Double,
    val classification: String,
    val confidence: Double,
    val extractedConcepts: List<ExtractedConcept>,
    val analogies: List<Analogy>,
    val unknownAspects: List<UnknownAspect>,
    val recommendedActions: List<String>,
    val processedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ExtractedConcept(
    val text: String,
    val type: String,
    val certainty: Double,
    val relatedConcepts: List<String>
)

@Serializable
data class Analogy(
    val knownConcept: String,
    val similarity: Double,
    val explanation: String,
    val confidence: Double
)

@Serializable
data class UnknownAspect(
    val aspect: String,
    val uncertaintyType: String,
    val question: String,
    val priority: Int
)

@Serializable
data class ConceptRegistry(
    val concepts: MutableMap<String, ConceptEntry> = ConcurrentHashMap(),
    val relationships: MutableMap<String, MutableList<String>> = ConcurrentHashMap(),
    val categories: MutableMap<String, MutableSet<String>> = ConcurrentHashMap()
)

@Serializable
data class ConceptEntry(
    val id: String,
    val name: String,
    val category: String,
    val attributes: Map<String, Any>,
    val knownFrom: List<String>,
    val firstEncountered: Long,
    val encounterCount: Int = 1,
    val confidence: Double = 0.5
)

class NovelInformationProcessor(
    private val knowledgeGraph: KnowledgeGraphTool? = null
) {
    private val logger = LoggerFactory.getLogger(NovelInformationProcessor::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val conceptRegistry = ConceptRegistry()
    private val unknownQueue = ConcurrentLinkedQueue<NoveltyReport>()
    private val processingHistory = ConcurrentHashMap<String, NoveltyReport>()
    
    private val knownPatterns = mapOf(
        "technical" to listOf("algorithm", "code", "function", "system", "api", "data", "process"),
        "scientific" to listOf("research", "experiment", "theory", "hypothesis", "observation", "study"),
        "social" to listOf("people", "community", "culture", "behavior", "relationship", "communication"),
        "business" to listOf("market", "customer", "product", "revenue", "strategy", "investment"),
        "creative" to listOf("art", "design", "music", "writing", "story", "imagination"),
        "physical" to listOf("object", "material", "space", "location", "physical", "mechanical"),
        "emotional" to listOf("feeling", "emotion", "mood", "sentiment", "affect", "psychological"),
        "temporal" to listOf("time", "history", "past", "future", "event", "sequence")
    )
    
    private val uncertaintyIndicators = listOf(
        "unknown", "unclear", "uncertain", "unfamiliar", "new", "novel",
        "strange", "weird", "different", "unusual", "unexpected",
        "maybe", "possibly", "probably", "might", "could be"
    )
    
    fun analyzeNovelty(input: String, context: String? = null): NoveltyReport {
        val words = input.lowercase().split(Regex("\\W+"))
        
        val extractedConcepts = extractConcepts(input, context)
        val noveltyScore = calculateNoveltyScore(input, extractedConcepts)
        val classification = classifyInput(input, extractedConcepts)
        val confidence = calculateConfidence(extractedConcepts, noveltyScore)
        val analogies = findAnalogies(input, context)
        val unknownAspects = identifyUnknownAspects(input, extractedConcepts)
        val recommendedActions = generateRecommendedActions(noveltyScore, unknownAspects, classification)
        
        val report = NoveltyReport(
            input = input,
            noveltyScore = noveltyScore,
            classification = classification,
            confidence = confidence,
            extractedConcepts = extractedConcepts,
            analogies = analogies,
            unknownAspects = unknownAspects,
            recommendedActions = recommendedActions
        )
        
        processingHistory[report.id] = report
        
        if (noveltyScore > 0.6) {
            unknownQueue.offer(report)
        }
        
        return report
    }
    
    private fun extractConcepts(input: String, context: String?): List<ExtractedConcept> {
        val concepts = mutableListOf<ExtractedConcept>()
        val inputLower = input.lowercase()
        val contextLower = context?.lowercase() ?: ""
        
        for ((category, patterns) in knownPatterns) {
            val matches = patterns.filter { inputLower.contains(it) }
            if (matches.isNotEmpty()) {
                concepts.add(
                    ExtractedConcept(
                        text = category,
                        type = "category",
                        certainty = matches.size.toDouble() / patterns.size,
                        relatedConcepts = matches
                    )
                )
            }
        }
        
        val namedEntities = extractPotentialEntities(input)
        concepts.addAll(namedEntities)
        
        val actionPatterns = extractActions(input)
        concepts.addAll(actionPatterns)
        
        return concepts
    }
    
    private fun extractPotentialEntities(input: String): List<ExtractedConcept> {
        val entities = mutableListOf<ExtractedConcept>()
        
        val acronyms = Regex("[A-Z]{2,}").findAll(input)
        entities.addAll(acronyms.map {
            ExtractedConcept(
                text = it.value,
                type = "acronym",
                certainty = 0.8,
                relatedConcepts = emptyList()
            )
        })
        
        val numbers = Regex("\\d+\\.?\\d*").findAll(input)
        if (numbers.count() > 2) {
            entities.add(
                ExtractedConcept(
                    text = "quantitative_data",
                    type = "data",
                    certainty = 0.9,
                    relatedConcepts = emptyList()
                )
            )
        }
        
        return entities
    }
    
    private fun extractActions(input: String): List<ExtractedConcept> {
        val actionVerbs = listOf(
            "create", "build", "make", "do", "perform", "execute",
            "analyze", "examine", "study", "investigate", "explore",
            "understand", "learn", "discover", "find", "detect",
            "connect", "link", "relate", "associate",
            "change", "transform", "modify", "update", "improve"
        )
        
        val inputLower = input.lowercase()
        val foundActions = actionVerbs.filter { inputLower.contains(it) }
        
        return if (foundActions.isNotEmpty()) {
            listOf(
                ExtractedConcept(
                    text = foundActions.joinToString(","),
                    type = "action",
                    certainty = foundActions.size.toDouble() / actionVerbs.size,
                    relatedConcepts = foundActions
                )
            )
        } else {
            emptyList()
        }
    }
    
    private fun calculateNoveltyScore(input: String, concepts: List<ExtractedConcept>): Double {
        var score = 0.0
        
        val inputLower = input.lowercase()
        for (indicator in uncertaintyIndicators) {
            if (inputLower.contains(indicator)) {
                score += 0.15
            }
        }
        
        if (concepts.isEmpty()) {
            score += 0.4
        } else {
            val avgCertainty = concepts.map { it.certainty }.average()
            score += (1.0 - avgCertainty) * 0.5
        }
        
        val wordCount = input.split(Regex("\\W+")).size
        if (wordCount > 50) {
            score += 0.1
        }
        
        if (input.contains("?") || input.contains("what is") || input.contains("how does")) {
            score += 0.2
        }
        
        return score.coerceIn(0.0, 1.0)
    }
    
    private fun classifyInput(input: String, concepts: List<ExtractedConcept>): String {
        val inputLower = input.lowercase()
        
        val categoryScores = mutableMapOf<String, Double>()
        
        for ((category, patterns) in knownPatterns) {
            val matches = patterns.count { inputLower.contains(it) }
            categoryScores[category] = matches.toDouble()
        }
        
        if (inputLower.contains("what") || inputLower.contains("why") || 
            inputLower.contains("how") || inputLower.contains("explain")) {
            categoryScores["inquiry"] = categoryScores.getOrDefault("inquiry", 0.0) + 3
        }
        
        if (inputLower.contains("!") || inputLower.contains("amazing") || 
            inputLower.contains("incredible")) {
            categoryScores["exclamation"] = categoryScores.getOrDefault("exclamation", 0.0) + 2
        }
        
        return categoryScores.maxByOrNull { it.value }?.key ?: "unknown"
    }
    
    private fun calculateConfidence(concepts: List<ExtractedConcept>, noveltyScore: Double): Double {
        if (concepts.isEmpty()) return 0.2
        
        val avgCertainty = concepts.map { it.certainty }.average()
        val conceptBonus = concepts.size.coerceIn(0, 5) * 0.05
        val noveltyPenalty = noveltyScore * 0.3
        
        return (avgCertainty * 0.7 + conceptBonus - noveltyPenalty).coerceIn(0.0, 1.0)
    }
    
    private fun findAnalogies(input: String, context: String?): List<Analogy> {
        val analogies = mutableListOf<Analogy>()
        val inputLower = input.lowercase()
        
        val knownConcepts = conceptRegistry.concepts.values.toList()
        
        for (concept in knownConcepts.take(10)) {
            val similarity = calculateSimilarity(inputLower, concept.name.lowercase())
            if (similarity > 0.3) {
                analogies.add(
                    Analogy(
                        knownConcept = concept.name,
                        similarity = similarity,
                        explanation = "Similar to known concept: ${concept.category}",
                        confidence = concept.confidence
                    )
                )
            }
        }
        
        if (analogies.isEmpty() && conceptRegistry.categories.isNotEmpty()) {
            for ((category, concepts) in conceptRegistry.categories) {
                if (concepts.size >= 3) {
                    analogies.add(
                        Analogy(
                            knownConcept = "typical_$category",
                            similarity = 0.4,
                            explanation = "Fits pattern of known category: $category",
                            confidence = 0.5
                        )
                    )
                }
            }
        }
        
        return analogies.sortedByDescending { it.similarity }.take(5)
    }
    
    private fun calculateSimilarity(text1: String, text2: String): Double {
        val words1 = text1.split(Regex("\\W+")).toSet()
        val words2 = text2.split(Regex("\\W+")).toSet()
        
        if (words1.isEmpty() || words2.isEmpty()) return 0.0
        
        val intersection = words1.intersect(words2).size
        val union = words1.union(words2).size
        
        return if (union > 0) intersection.toDouble() / union else 0.0
    }
    
    private fun identifyUnknownAspects(input: String, concepts: List<ExtractedConcept>): List<UnknownAspect> {
        val unknownAspects = mutableListOf<UnknownAspect>()
        val inputLower = input.lowercase()
        
        if (concepts.isEmpty() || concepts.all { it.certainty < 0.5 }) {
            unknownAspects.add(
                UnknownAspect(
                    aspect = "core_concept",
                    uncertaintyType = "undefined",
                    question = "What is the fundamental nature of this?",
                    priority = 1
                )
            )
        }
        
        if (inputLower.contains("how") || inputLower.contains("why")) {
            unknownAspects.add(
                UnknownAspect(
                    aspect = "causality",
                    uncertaintyType = "mechanism_unknown",
                    question = "What is the underlying mechanism?",
                    priority = 2
                )
            )
        }
        
        if (inputLower.contains("new") || inputLower.contains("first") || inputLower.contains("never")) {
            unknownAspects.add(
                UnknownAspect(
                    aspect = "precedent",
                    uncertaintyType = "no_history",
                    question = "Has this been encountered before?",
                    priority = 1
                )
            )
        }
        
        if (inputLower.contains("?") || inputLower.contains("unknown") || inputLower.contains("unclear")) {
            unknownAspects.add(
                UnknownAspect(
                    aspect = "clarity",
                    uncertaintyType = "ambiguous",
                    question = "What specifically is uncertain?",
                    priority = 2
                )
            )
        }
        
        val techTerms = Regex("[A-Z][a-z]+(?:[A-Z][a-z]+)+").findAll(input)
        if (techTerms.count() > 2) {
            unknownAspects.add(
                UnknownAspect(
                    aspect = "technical_details",
                    uncertaintyType = "complex",
                    question = "What are the technical components?",
                    priority = 3
                )
            )
        }
        
        return unknownAspects.sortedBy { it.priority }
    }
    
    private fun generateRecommendedActions(
        noveltyScore: Double,
        unknownAspects: List<UnknownAspect>,
        classification: String
    ): List<String> {
        val actions = mutableListOf<String>()
        
        when {
            noveltyScore > 0.8 -> {
                actions.add("Search web for more information about this topic")
                actions.add("Break down into smaller components for analysis")
                actions.add("Ask user for clarification or examples")
            }
            noveltyScore > 0.5 -> {
                actions.add("Research similar concepts in knowledge base")
                actions.add("Check for analogies to known information")
                if (unknownAspects.any { it.uncertaintyType == "undefined" }) {
                    actions.add("Identify core concept first")
                }
            }
            else -> {
                actions.add("Proceed with available information")
                actions.add("Note any remaining uncertainties")
            }
        }
        
        if (classification == "inquiry" && unknownAspects.isNotEmpty()) {
            actions.add("Formulate specific questions to resolve unknowns")
        }
        
        if (unknownAspects.any { it.priority == 1 }) {
            actions.add("Prioritize resolving high-priority unknowns first")
        }
        
        return actions
    }
    
    fun learnConcept(
        name: String,
        category: String,
        attributes: Map<String, Any> = emptyMap(),
        source: String = "direct_interaction"
    ): Boolean {
        return try {
            val entry = ConceptEntry(
                id = UUID.randomUUID().toString(),
                name = name,
                category = category,
                attributes = attributes,
                knownFrom = listOf(source),
                firstEncountered = System.currentTimeMillis()
            )
            
            conceptRegistry.concepts[name.lowercase()] = entry
            
            conceptRegistry.categories.getOrPut(category) { mutableSetOf() }.add(name.lowercase())
            
            conceptRegistry.relationships.getOrPut(name.lowercase()) { mutableListOf() }
            
            logger.info("Learned new concept: $name (category: $category)")
            true
        } catch (e: Exception) {
            logger.error("Failed to learn concept: ${e.message}")
            false
        }
    }
    
    fun connectConcepts(concept1: String, concept2: String, relationship: String = "related") {
        val key1 = concept1.lowercase()
        val key2 = concept2.lowercase()
        
        conceptRegistry.relationships.getOrPut(key1) { mutableListOf() }.add(key2)
        conceptRegistry.relationships.getOrPut(key2) { mutableListOf() }.add(key1)
    }
    
    fun getRelatedConcepts(concept: String): List<String> {
        return conceptRegistry.relationships[concept.lowercase()] ?: emptyList()
    }
    
    fun getConceptsByCategory(category: String): List<String> {
        return conceptRegistry.categories[category]?.toList() ?: emptyList()
    }
    
    fun getPendingNovelItems(): List<NoveltyReport> {
        return unknownQueue.toList()
    }
    
    fun resolveNovelty(reportId: String, resolution: String, learnedConcept: String? = null): Boolean {
        val report = processingHistory[reportId] ?: return false
        
        if (learnedConcept != null) {
            learnConcept(learnedConcept, report.classification)
        }
        
        unknownQueue.remove(report)
        
        logger.info("Resolved novelty report $reportId: $resolution")
        return true
    }
    
    fun getConceptStatistics(): String {
        return buildString {
            appendLine("[Concept Registry Statistics]")
            appendLine("-".repeat(40))
            appendLine("Total concepts: ${conceptRegistry.concepts.size}")
            appendLine("Categories: ${conceptRegistry.categories.size}")
            appendLine("Relationships: ${conceptRegistry.relationships.values.flatten().size / 2}")
            appendLine("Pending novel items: ${unknownQueue.size}")
            appendLine("Processed reports: ${processingHistory.size}")
            appendLine()
            appendLine("[Categories]")
            for ((category, concepts) in conceptRegistry.categories) {
                appendLine("  $category: ${concepts.size} concepts")
            }
        }
    }
    
    fun exportKnowledge(): String {
        return json.encodeToString(ConceptRegistry.serializer(), conceptRegistry)
    }
    
    fun importKnowledge(data: String): Boolean {
        return try {
            val imported = json.decodeFromString(ConceptRegistry.serializer(), data)
            conceptRegistry.concepts.putAll(imported.concepts)
            conceptRegistry.relationships.putAll(imported.relationships)
            conceptRegistry.categories.putAll(imported.categories)
            logger.info("Imported knowledge with ${imported.concepts.size} concepts")
            true
        } catch (e: Exception) {
            logger.error("Failed to import knowledge: ${e.message}")
            false
        }
    }
}

class AdaptiveLearningEngine(
    private val processor: NovelInformationProcessor
) {
    private val logger = LoggerFactory.getLogger(AdaptiveLearningEngine::class.java)
    
    private val learningPatterns = ConcurrentHashMap<String, LearningPattern>()
    private val adaptiveThresholds = AdaptiveThresholds()
    
    data class LearningPattern(
        val trigger: String,
        val response: String,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val lastUsed: Long = 0
    )
    
    data class AdaptiveThresholds(
        var noveltyHigh: Double = 0.7,
        var noveltyMedium: Double = 0.4,
        var confidenceLow: Double = 0.3,
        var confidenceHigh: Double = 0.8
    )
    
    fun processWithLearning(input: String, context: String? = null): AdaptiveResult {
        val report = processor.analyzeNovelty(input, context)
        
        val adjustedReport = if (report.noveltyScore > adaptiveThresholds.noveltyHigh) {
            handleHighlyNovelInput(report, input, context)
        } else {
            report
        }
        
        val response = generateResponse(adjustedReport)
        
        return AdaptiveResult(
            report = adjustedReport,
            response = response,
            learnedFromThis = adjustThresholdsIfNeeded(adjustedReport)
        )
    }
    
    private fun handleHighlyNovelInput(report: NoveltyReport, input: String, context: String?): NoveltyReport {
        logger.info("Handling highly novel input: noveltyScore=${report.noveltyScore}")
        
        val expandedConcepts = report.extractedConcepts.toMutableList()
        
        val unknownCategories = listOf(
            "technology", "science", "arts", "business", "philosophy"
        )
        for (category in unknownCategories) {
            if (report.input.lowercase().contains(category)) {
                expandedConcepts.add(
                    ExtractedConcept(
                        text = category,
                        type = "discovered_category",
                        certainty = 0.6,
                        relatedConcepts = emptyList()
                    )
                )
            }
        }
        
        return report.copy(
            extractedConcepts = expandedConcepts,
            confidence = report.confidence * 0.8
        )
    }
    
    private fun generateResponse(report: NoveltyReport): String {
        return when {
            report.noveltyScore > 0.8 -> buildString {
                appendLine("[Novel Information Detected]")
                appendLine("-".repeat(40))
                appendLine("This appears to be something I haven't encountered before.")
                appendLine("Novelty Score: ${"%.1f".format(report.noveltyScore * 100)}%")
                appendLine("Classification: ${report.classification}")
                appendLine("Confidence: ${"%.1f".format(report.confidence * 100)}%")
                appendLine()
                if (report.unknownAspects.isNotEmpty()) {
                    appendLine("Unknown Aspects:")
                    report.unknownAspects.forEach { unknown ->
                        appendLine("  - ${unknown.aspect}: ${unknown.question}")
                    }
                    appendLine()
                }
                appendLine("Recommended Actions:")
                report.recommendedActions.take(3).forEach { action ->
                    appendLine("  * $action")
                }
            }
            report.noveltyScore > 0.5 -> buildString {
                appendLine("[Partially Novel Information]")
                appendLine("-".repeat(40))
                appendLine("Some aspects are familiar, others are new.")
                appendLine("Novelty Score: ${"%.1f".format(report.noveltyScore * 100)}%")
                appendLine("Classification: ${report.classification}")
                if (report.analogies.isNotEmpty()) {
                    appendLine()
                    appendLine("Possible Analogies:")
                    report.analogies.take(3).forEach { analogy ->
                        appendLine("  - ${analogy.knownConcept} (${"%.0f".format(analogy.similarity * 100)}% similar)")
                    }
                }
            }
            else -> buildString {
                appendLine("[Information Processed]")
                appendLine("-".repeat(40))
                appendLine("I understand this based on my knowledge.")
                appendLine("Novelty Score: ${"%.1f".format(report.noveltyScore * 100)}%")
                appendLine("Classification: ${report.classification}")
                appendLine("Confidence: ${"%.1f".format(report.confidence * 100)}%")
            }
        }
    }
    
    private fun adjustThresholdsIfNeeded(report: NoveltyReport): Boolean {
        var adjusted = false
        
        if (report.confidence < adaptiveThresholds.confidenceLow) {
            adaptiveThresholds.noveltyHigh = (adaptiveThresholds.noveltyHigh - 0.05).coerceAtLeast(0.5)
            adjusted = true
            logger.info("Adjusted novelty threshold down to ${adaptiveThresholds.noveltyHigh}")
        }
        
        if (report.confidence > adaptiveThresholds.confidenceHigh && report.noveltyScore < 0.3) {
            adaptiveThresholds.noveltyMedium = (adaptiveThresholds.noveltyMedium + 0.05).coerceAtMost(0.6)
            adjusted = true
        }
        
        return adjusted
    }
    
    fun recordSuccessfulLearning(trigger: String, response: String) {
        val pattern = learningPatterns.getOrPut(trigger.lowercase()) {
            LearningPattern(trigger, response)
        }
        val updated = pattern.copy(
            successCount = pattern.successCount + 1,
            lastUsed = System.currentTimeMillis()
        )
        learningPatterns[trigger.lowercase()] = updated
    }
    
    fun recordFailedLearning(trigger: String) {
        val pattern = learningPatterns[trigger.lowercase()] ?: return
        val updated = pattern.copy(
            failureCount = pattern.failureCount + 1
        )
        learningPatterns[trigger.lowercase()] = updated
        
        if (updated.failureCount > 5) {
            learningPatterns.remove(trigger.lowercase())
            logger.warn("Removed learning pattern after repeated failures: $trigger")
        }
    }
    
    fun getLearningStats(): String {
        return buildString {
            appendLine("[Adaptive Learning Statistics]")
            appendLine("-".repeat(40))
            appendLine("Active patterns: ${learningPatterns.size}")
            appendLine()
            appendLine("[Adaptive Thresholds]")
            appendLine("Novelty High: ${"%.2f".format(adaptiveThresholds.noveltyHigh)}")
            appendLine("Novelty Medium: ${"%.2f".format(adaptiveThresholds.noveltyMedium)}")
            appendLine("Confidence Low: ${"%.2f".format(adaptiveThresholds.confidenceLow)}")
            appendLine("Confidence High: ${"%.2f".format(adaptiveThresholds.confidenceHigh)}")
            appendLine()
            appendLine("[Top Patterns]")
            learningPatterns.values
                .sortedByDescending { it.successCount }
                .take(5)
                .forEach { pattern ->
                    appendLine("  ${pattern.trigger}: ${pattern.successCount} success, ${pattern.failureCount} failed")
                }
        }
    }
}

data class AdaptiveResult(
    val report: NoveltyReport,
    val response: String,
    val learnedFromThis: Boolean
)

class UnknownConceptResolver(
    private val processor: NovelInformationProcessor,
    private val tavilyTool: TavilySearchTool? = null
) {
    private val logger = LoggerFactory.getLogger(UnknownConceptResolver::class.java)
    
    private val pendingResolutions = ConcurrentHashMap<String, ResolutionRequest>()
    private val resolvedConcepts = ConcurrentHashMap<String, ResolvedConcept>()
    
    data class ResolutionRequest(
        val id: String = UUID.randomUUID().toString(),
        val concept: String,
        val context: String?,
        val createdAt: Long = System.currentTimeMillis(),
        var attempts: Int = 0
    )
    
    data class ResolvedConcept(
        val concept: String,
        val definition: String,
        val category: String,
        val relatedConcepts: List<String>,
        val confidence: Double,
        val resolvedAt: Long
    )
    
    fun requestResolution(concept: String, context: String? = null): String {
        val request = ResolutionRequest(concept = concept, context = context)
        pendingResolutions[request.id] = request
        
        logger.info("Created resolution request for: $concept")
        return request.id
    }
    
    fun attemptResolution(requestId: String): ResolutionResult? {
        val request = pendingResolutions[requestId] ?: return null
        request.attempts++
        
        val report = processor.analyzeNovelty(request.concept, request.context)
        
        if (report.confidence > 0.6 || report.noveltyScore < 0.4) {
            val resolved = ResolvedConcept(
                concept = request.concept,
                definition = generateDefinition(report),
                category = report.classification,
                relatedConcepts = report.analogies.map { it.knownConcept },
                confidence = report.confidence,
                resolvedAt = System.currentTimeMillis()
            )
            
            resolvedConcepts[request.concept.lowercase()] = resolved
            pendingResolutions.remove(requestId)
            
            processor.learnConcept(request.concept, report.classification)
            
            return ResolutionResult(
                success = true,
                concept = resolved,
                message = "Successfully resolved concept: ${request.concept}"
            )
        }
        
        if (request.attempts >= 3) {
            pendingResolutions.remove(requestId)
            return ResolutionResult(
                success = false,
                concept = null,
                message = "Failed to resolve after ${request.attempts} attempts"
            )
        }
        
        return ResolutionResult(
            success = false,
            concept = null,
            message = "Partial resolution - confidence: ${"%.1f".format(report.confidence * 100)}%"
        )
    }
    
    private fun generateDefinition(report: NoveltyReport): String {
        return buildString {
            append("Novel concept of type: ${report.classification}. ")
            if (report.extractedConcepts.isNotEmpty()) {
                append("Contains: ${report.extractedConcepts.joinToString(", ") { it.text }}. ")
            }
            if (report.analogies.isNotEmpty()) {
                append("Similar to: ${report.analogies.take(2).joinToString(", ") { it.knownConcept }}. ")
            }
            append("Novelty: ${"%.0f".format(report.noveltyScore * 100)}%")
        }
    }
    
    fun getResolvedConcept(concept: String): ResolvedConcept? {
        return resolvedConcepts[concept.lowercase()]
    }
    
    fun getPendingCount(): Int = pendingResolutions.size
    
    fun getResolvedCount(): Int = resolvedConcepts.size
}

data class ResolutionResult(
    val success: Boolean,
    val concept: ResolvedConcept?,
    val message: String
)
