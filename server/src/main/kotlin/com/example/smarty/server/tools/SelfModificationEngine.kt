package com.example.smarty.server.tools

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class SelfModification(
    val id: String,
    val type: String,
    val change: String,
    val reason: String,
    val appliedAt: Long,
    val isActive: Boolean = true,
    val effectiveness: Double = 0.5,
    val usageCount: Int = 0,
    val context匹配: Map<String, Double> = emptyMap()
)

@Serializable
data class BehaviorRule(
    val id: String,
    val rule: String,
    val category: String,
    val priority: Int = 5,
    val addedAt: Long,
    val source: String = "self",
    val weight: Double = 0.5,
    val effectiveness: Double = 0.5,
    val activationCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val contextRequirements: Map<String, Double> = emptyMap(),
    val conflictWith: List<String> = emptyList()
)

@Serializable
data class LearningRecord(
    val id: String,
    val observation: String,
    val lesson: String,
    val context: String,
    val timestamp: Long,
    val outcome: String? = null,
    val reinforcement: Double = 0.0,
    val transferable: Boolean = false,
    val sourceDomain: String = "general",
    val targetDomains: List<String> = emptyList()
)

@Serializable
data class RuleEvaluation(
    val ruleId: String,
    val context: Map<String, Double>,
    val activationProbability: Double,
    val expectedUtility: Double,
    val conflicts: List<String>,
    val reasoning: String
)

@Serializable
data class LearningTransfer(
    val sourceLesson: String,
    val targetContext: String,
    val similarity: Double,
    val transferredRule: String,
    val successRate: Double,
    val adaptationNeeded: String?
)

@Serializable
data class MetaLearningConfig(
    val learningRate: Double = 0.1,
    val discountFactor: Double = 0.9,
    val explorationRate: Double = 0.2,
    val retentionThreshold: Double = 0.3
)

@Serializable
data class RuleConflict(
    val ruleId1: String,
    val ruleId2: String,
    val conflictType: String,
    val severity: Double,
    val resolution: String?
)

@Serializable
data class LearningOutcome(
    val recordId: String,
    val context: Map<String, Double>,
    val outcome: String,
    val reinforcement: Double,
    val ruleUpdates: List<RuleUpdate>
)

@Serializable
data class RuleUpdate(
    val ruleId: String,
    val weightDelta: Double,
    val effectivenessDelta: Double,
    val priorityDelta: Int
)

class SelfModificationEngine {
    private val logger = LoggerFactory.getLogger(SelfModificationEngine::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val modifications = ConcurrentHashMap<String, SelfModification>()
    private val behaviorRules = ConcurrentHashMap<String, BehaviorRule>()
    private val learningRecords = ConcurrentHashMap<String, LearningRecord>()
    private val dynamicPromptAdditions = mutableListOf<String>()
    private val learningOutcomes = mutableListOf<LearningOutcome>()
    private val ruleConflicts = mutableListOf<RuleConflict>()
    private val learningTransfers = mutableListOf<LearningTransfer>()
    
    private val modCounter = AtomicLong(0)
    private val ruleCounter = AtomicLong(0)
    private val learnCounter = AtomicLong(0)
    
    private var metaConfig = MetaLearningConfig(
        learningRate = 0.1,
        discountFactor = 0.9,
        explorationRate = 0.2,
        retentionThreshold = 0.3
    )
    
    private val contextEmbeddings = ConcurrentHashMap<String, DoubleArray>()
    private val ruleActivationHistory = ConcurrentHashMap<String, MutableList<Long>>()
    
    companion object {
        private const val MAX_RULES = 100
        private const val MAX_LEARNING_RECORDS = 500
        private const val CONFLICT_DETECTION_INTERVAL = 10000
        private const val RULE_DECAY_RATE = 0.001
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        initializeBaseRules()
        startConflictDetection()
    }
    
    private fun initializeBaseRules() {
        listOf(
            Triple("Always prioritize user safety and privacy", "safety", 10),
            Triple("Learn from both successes and failures", "learning", 8),
            Triple("Adapt communication style to user preferences", "adaptation", 7),
            Triple("Maintain consistency in factual information", "accuracy", 9),
            Triple("Acknowledge uncertainty when present", "honesty", 8)
        ).forEach { (rule, category, priority) ->
            addRule(rule, category, priority, "base")
        }
    }
    
    private fun startConflictDetection() {
        scope.launch {
            while (true) {
                delay(CONFLICT_DETECTION_INTERVAL)
                detectAndResolveConflicts()
            }
        }
    }
    
    fun addRule(
        rule: String,
        category: String = "general",
        priority: Int = 5,
        source: String = "self",
        weight: Double = 0.5,
        contextRequirements: Map<String, Double> = emptyMap()
    ): String {
        val ruleId = "rule_${System.currentTimeMillis()}_${ruleCounter.incrementAndGet()}"
        
        val existingSimilar = findSimilarRules(rule)
        val conflicts = existingSimilar.map { it.id }
        
        val behaviorRule = BehaviorRule(
            id = ruleId,
            rule = rule,
            category = category,
            priority = priority,
            addedAt = System.currentTimeMillis(),
            source = source,
            weight = weight,
            effectiveness = 0.5,
            activationCount = 0,
            successCount = 0,
            failureCount = 0,
            contextRequirements = contextRequirements,
            conflictWith = conflicts
        )
        
        behaviorRules[ruleId] = behaviorRule
        ruleActivationHistory[ruleId] = mutableListOf()
        
        conflicts.forEach { conflictId ->
            ruleConflicts.add(RuleConflict(
                ruleId1 = ruleId,
                ruleId2 = conflictId,
                conflictType = "similarity",
                severity = calculateSimilarity(rule, behaviorRules[conflictId]?.rule ?: ""),
                resolution = null
            ))
        }
        
        logger.info("Added behavior rule: $rule (id: $ruleId)")
        
        return ruleId
    }
    
    private fun findSimilarRules(newRule: String): List<BehaviorRule> {
        return behaviorRules.values.filter { existing ->
            val similarity = calculateSimilarity(newRule, existing.rule)
            similarity > 0.6
        }
    }
    
    private fun calculateSimilarity(rule1: String, rule2: String): Double {
        val words1 = rule1.lowercase().split(" ").toSet()
        val words2 = rule2.lowercase().split(" ").toSet()
        
        val intersection = words1.intersect(words2).size.toDouble()
        val union = words1.union(words2).size.toDouble()
        
        return if (union > 0) intersection / union else 0.0
    }
    
    private fun detectAndResolveConflicts() {
        val rules = behaviorRules.values.toList()
        
        for (i in rules.indices) {
            for (j in i + 1 until rules.size) {
                val rule1 = rules[i]
                val rule2 = rules[j]
                
                val conflictScore = evaluateConflict(rule1, rule2)
                
                if (conflictScore > 0.7) {
                    val existing = ruleConflicts.find { 
                        (it.ruleId1 == rule1.id && it.ruleId2 == rule2.id) ||
                        (it.ruleId1 == rule2.id && it.ruleId2 == rule1.id)
                    }
                    
                    if (existing == null) {
                        ruleConflicts.add(RuleConflict(
                            ruleId1 = rule1.id,
                            ruleId2 = rule2.id,
                            conflictType = "contradiction",
                            severity = conflictScore,
                            resolution = resolveConflict(rule1, rule2)
                        ))
                    }
                }
            }
        }
    }
    
    private fun evaluateConflict(rule1: BehaviorRule, rule2: BehaviorRule): Double {
        val similarity = calculateSimilarity(rule1.rule, rule2.rule)
        
        if (similarity > 0.8) return similarity
        
        if (rule1.category != rule2.category) return 0.0
        
        return similarity * 0.5
    }
    
    private fun resolveConflict(rule1: BehaviorRule, rule2: BehaviorRule): String {
        return when {
            rule1.priority > rule2.priority -> "Keep rule: ${rule1.id}, deprecate: ${rule2.id}"
            rule2.priority > rule1.priority -> "Keep rule: ${rule2.id}, deprecate: ${rule1.id}"
            rule1.effectiveness > rule2.effectiveness -> "Keep rule: ${rule1.id}, deprecate: ${rule2.id}"
            else -> "Merge rules into: ${rule1.rule} AND ${rule2.rule}"
        }
    }
    
    fun removeRule(ruleId: String): Boolean {
        val removed = behaviorRules.remove(ruleId) != null
        if (removed) {
            ruleActivationHistory.remove(ruleId)
            ruleConflicts.removeIf { it.ruleId1 == ruleId || it.ruleId2 == ruleId }
        }
        return removed
    }
    
    fun learn(
        observation: String, 
        lesson: String, 
        context: String = "",
        contextFeatures: Map<String, Double> = emptyMap()
    ): String {
        val recordId = "learn_${System.currentTimeMillis()}_${learnCounter.incrementAndGet()}"
        
        val transferPotential = assessTransferPotential(lesson, context)
        
        val record = LearningRecord(
            id = recordId,
            observation = observation,
            lesson = lesson,
            context = context,
            timestamp = System.currentTimeMillis(),
            reinforcement = 0.0,
            transferable = transferPotential.transferable,
            sourceDomain = context,
            targetDomains = transferPotential.targetDomains
        )
        
        learningRecords[recordId] = record
        
        if (learningRecords.size > MAX_LEARNING_RECORDS) {
            pruneOldLearnings()
        }
        
        val existingRule = behaviorRules.values.any { 
            it.rule.contains(lesson, ignoreCase = true) || 
            calculateSimilarity(lesson, it.rule) > 0.7
        }
        
        if (!existingRule && lesson.isNotBlank() && lesson.length > 5) {
            val priority = if (transferPotential.transferable) 8 else 7
            addRule(lesson, "learned", priority, "self_learning", 
                contextRequirements = contextFeatures)
        }
        
        if (transferPotential.transferable) {
            applyTransferLearning(lesson, transferPotential.targetDomains)
        }
        
        logger.info("Recorded learning: $observation -> $lesson")
        return recordId
    }
    
    private fun assessTransferPotential(lesson: String, context: String): TransferAssessment {
        val abstractConcepts = extractAbstractConcepts(lesson)
        
        val targetDomains = when {
            context.contains("coding", ignoreCase = true) -> listOf("reasoning", "problem_solving")
            context.contains("conversation", ignoreCase = true) -> listOf("empathy", "clarity")
            context.contains("analysis", ignoreCase = true) -> listOf("pattern_recognition", "synthesis")
            else -> emptyList()
        }
        
        return TransferAssessment(
            transferable = abstractConcepts.size > 2 && targetDomains.isNotEmpty(),
            abstractConcepts = abstractConcepts,
            targetDomains = targetDomains,
            confidence = minOf(abstractConcepts.size * 0.3, 0.9)
        )
    }
    
    private data class TransferAssessment(
        val transferable: Boolean,
        val abstractConcepts: List<String>,
        val targetDomains: List<String>,
        val confidence: Double
    )
    
    private fun extractAbstractConcepts(text: String): List<String> {
        val abstractIndicators = listOf("always", "never", "should", "must", "better", "prefer", "avoid", "learn")
        return text.lowercase().split(" ").filter { it in abstractIndicators }
    }
    
    private fun applyTransferLearning(lesson: String, targetDomains: List<String>) {
        targetDomains.forEach { domain ->
            val similarLessons = learningRecords.values
                .filter { it.sourceDomain == domain }
                .take(5)
            
            similarLessons.forEach { similar ->
                val similarity = calculateSimilarity(lesson, similar.lesson)
                if (similarity > 0.4) {
                    learningTransfers.add(LearningTransfer(
                        sourceLesson = similar.lesson,
                        targetContext = domain,
                        similarity = similarity,
                        transferredRule = lesson,
                        successRate = similar.reinforcement,
                        adaptationNeeded = if (similarity < 0.7) "Context adaptation required" else null
                    ))
                }
            }
        }
    }
    
    private fun pruneOldLearnings() {
        val byRelevance = learningRecords.values
            .sortedByDescending { record ->
                val recency = 1.0 - (System.currentTimeMillis() - record.timestamp) / 1e9
                val reinforcement = record.reinforcement
                recency * 0.3 + reinforcement * 0.7
            }
        
        val toRemove = learningRecords.values.filter { it.id !in byRelevance.take(MAX_LEARNING_RECORDS / 2).map { it.id } }
        toRemove.forEach { learningRecords.remove(it.id) }
    }
    
    fun learnFromError(error: String, correction: String, context: String = "error_correction"): String {
        val reinforcedLesson = "In the future, $correction"
        
        return learn(
            observation = "Error occurred: $error",
            lesson = reinforcedLesson,
            context = context,
            contextFeatures = mapOf("error_rate" to 1.0, "severity" to 0.8)
        )
    }
    
    fun learnFromSuccess(success: String, pattern: String, context: String = "success_pattern"): String {
        return learn(
            observation = "Success: $success",
            lesson = "Pattern that worked: $pattern",
            context = context,
            contextFeatures = mapOf("success_rate" to 1.0, "reliability" to 0.9)
        )
    }
    
    fun evaluateRules(context: Map<String, Double>): List<RuleEvaluation> {
        return behaviorRules.values.map { rule ->
            evaluateRule(rule, context)
        }.sortedByDescending { it.expectedUtility }
    }
    
    private fun evaluateRule(rule: BehaviorRule, context: Map<String, Double>): RuleEvaluation {
        val context匹配 = calculateContextMatch(rule.contextRequirements, context)
        
        val baseProbability = rule.weight * context匹配
        
        val recencyBonus = ruleActivationHistory[rule.id]?.let { history ->
            val recentActivations = history.filter { 
                System.currentTimeMillis() - it < 3600000 
            }.size
            minOf(recentActivations * 0.05, 0.2)
        } ?: 0.0
        
        val activationProbability = (baseProbability + recencyBonus).coerceIn(0.0, 1.0)
        
        val successRate = if (rule.activationCount > 0) {
            rule.successCount.toDouble() / rule.activationCount
        } else 0.5
        
        val expectedUtility = activationProbability * (rule.effectiveness * 0.7 + successRate * 0.3)
        
        val conflicts = rule.conflictWith.mapNotNull { behaviorRules[it]?.rule }
        
        return RuleEvaluation(
            ruleId = rule.id,
            context = context,
            activationProbability = activationProbability,
            expectedUtility = expectedUtility,
            conflicts = conflicts,
            reasoning = "Context match: ${"%.2f".format(context匹配)}, Success rate: ${"%.1f".format(successRate * 100)}%"
        )
    }
    
    private fun calculateContextMatch(requirements: Map<String, Double>, context: Map<String, Double>): Double {
        if (requirements.isEmpty()) return 0.5
        
        val matches = requirements.entries.sumOf { (key, required) ->
            val actual = context[key] ?: 0.5
            1.0 - minOf(kotlin.math.abs(actual - required), 1.0)
        }
        
        return matches / requirements.size
    }
    
    fun recordRuleActivation(ruleId: String, success: Boolean) {
        val rule = behaviorRules[ruleId] ?: return
        
        ruleActivationHistory[ruleId]?.add(System.currentTimeMillis())
        
        val activationCount = rule.activationCount + 1
        val successCount = if (success) rule.successCount + 1 else rule.successCount
        val failureCount = if (!success) rule.failureCount + 1 else rule.failureCount
        
        val effectiveness = if (activationCount > 0) {
            successCount.toDouble() / activationCount
        } else 0.5
        
        val weightDelta = if (success) metaConfig.learningRate * (1.0 - rule.weight)
            else -metaConfig.learningRate * rule.weight
        
        behaviorRules[ruleId] = rule.copy(
            activationCount = activationCount,
            successCount = successCount,
            failureCount = failureCount,
            effectiveness = effectiveness,
            weight = (rule.weight + weightDelta).coerceIn(0.1, 1.0)
        )
    }
    
    fun applyReinforcement(recordId: String, outcome: String, context: Map<String, Double>) {
        val record = learningRecords[recordId] ?: return
        
        val reinforcement = when (outcome) {
            "success" -> 1.0
            "partial" -> 0.5
            "failure" -> -0.5
            else -> 0.0
        }
        
        val updatedRecord = record.copy(
            outcome = outcome,
            reinforcement = reinforcement
        )
        
        learningRecords[recordId] = updatedRecord
        
        val applicableRules = behaviorRules.values.filter { rule ->
            calculateSimilarity(record.lesson, rule.rule) > 0.5
        }
        
        val ruleUpdates = applicableRules.map { rule ->
            val weightDelta = reinforcement * metaConfig.learningRate * rule.weight
            val effectivenessDelta = if (outcome == "success") 0.01 else -0.01
            
            behaviorRules[rule.id] = rule.copy(
                weight = (rule.weight + weightDelta).coerceIn(0.1, 1.0),
                effectiveness = (rule.effectiveness + effectivenessDelta).coerceIn(0.0, 1.0)
            )
            
            RuleUpdate(
                ruleId = rule.id,
                weightDelta = weightDelta,
                effectivenessDelta = effectivenessDelta,
                priorityDelta = 0
            )
        }
        
        learningOutcomes.add(LearningOutcome(
            recordId = recordId,
            context = context,
            outcome = outcome,
            reinforcement = reinforcement,
            ruleUpdates = ruleUpdates
        ))
        
        if (learningOutcomes.size > 200) {
            learningOutcomes.removeAt(0)
        }
    }
    
    fun getActiveRules(context: Map<String, Double> = emptyMap()): List<BehaviorRule> {
        return if (context.isEmpty()) {
            behaviorRules.values
                .sortedByDescending { it.priority * it.effectiveness }
        } else {
            evaluateRules(context)
                .sortedByDescending { it.expectedUtility }
                .mapNotNull { behaviorRules[it.ruleId] }
        }
    }
    
    fun getRulesByCategory(category: String): List<BehaviorRule> {
        return behaviorRules.values
            .filter { it.category == category }
            .sortedByDescending { it.priority * it.effectiveness }
    }
    
    fun getLearnings(limit: Int = 20): List<LearningRecord> {
        return learningRecords.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    fun addPromptAddition(addition: String) {
        dynamicPromptAdditions.add(addition)
        if (dynamicPromptAdditions.size > 20) {
            dynamicPromptAdditions.removeAt(0)
        }
        logger.info("Added prompt addition: ${addition.take(50)}...")
    }
    
    fun getDynamicPromptSection(): String {
        if (dynamicPromptAdditions.isEmpty() && behaviorRules.isEmpty()) {
            return ""
        }
        
        return buildString {
            if (behaviorRules.isNotEmpty()) {
                appendLine("<self_learned_rules>")
                getActiveRules().take(10).forEach { rule ->
                    val effectiveness = (rule.effectiveness * 100).toInt()
                    appendLine("- ${rule.rule} [$effectiveness%]")
                }
                appendLine("</self_learned_rules>")
            }
            
            if (dynamicPromptAdditions.isNotEmpty()) {
                appendLine("<dynamic_context>")
                dynamicPromptAdditions.takeLast(5).forEach { addition ->
                    appendLine(addition)
                }
                appendLine("</dynamic_context>")
            }
        }
    }
    
    fun proposeModification(type: String, change: String, reason: String): String {
        val modId = "mod_${System.currentTimeMillis()}_${modCounter.incrementAndGet()}"
        
        val modification = SelfModification(
            id = modId,
            type = type,
            change = change,
            reason = reason,
            appliedAt = System.currentTimeMillis()
        )
        
        modifications[modId] = modification
        logger.info("Proposed modification: $type - $change")
        
        return modId
    }
    
    fun applyModification(modId: String): Boolean {
        val mod = modifications[modId] ?: return false
        
        when (mod.type) {
            "rule_add" -> addRule(mod.change, "self_modified", 6, "self_modification")
            "rule_remove" -> {
                behaviorRules.entries.removeIf { it.value.rule.contains(mod.change, ignoreCase = true) }
            }
            "prompt_add" -> addPromptAddition(mod.change)
            "weight_adjust" -> adjustRuleWeights(mod.change)
            "context_update" -> updateContextRequirements(mod.change)
        }
        
        modifications[modId] = mod.copy(isActive = true)
        logger.info("Applied modification: $modId")
        return true
    }
    
    private fun adjustRuleWeights(spec: String) {
        val parts = spec.split(":")
        if (parts.size != 2) return
        
        val rulePattern = parts[0]
        val delta = parts[1].toDoubleOrNull() ?: return
        
        behaviorRules.values
            .filter { it.rule.contains(rulePattern, ignoreCase = true) }
            .forEach { rule ->
                behaviorRules[rule.id] = rule.copy(
                    weight = (rule.weight + delta).coerceIn(0.1, 1.0)
                )
            }
    }
    
    private fun updateContextRequirements(spec: String) {
        val parts = spec.split("->")
        if (parts.size != 2) return
        
        val rulePattern = parts[0]
        val requirements = parts[1].split(",").associate {
            val kv = it.split("=")
            if (kv.size == 2) kv[0].trim() to (kv[1].trim().toDoubleOrNull() ?: 0.5)
            else it to 0.5
        }
        
        behaviorRules.values
            .filter { it.rule.contains(rulePattern, ignoreCase = true) }
            .forEach { rule ->
                behaviorRules[rule.id] = rule.copy(contextRequirements = requirements)
            }
    }
    
    fun getModificationHistory(): List<SelfModification> {
        return modifications.values.sortedByDescending { it.appliedAt }
    }
    
    fun getRuleConflicts(): List<RuleConflict> = ruleConflicts.toList()
    
    fun getLearningTransfers(): List<LearningTransfer> = learningTransfers.toList()
    
    fun getLearningAnalytics(): Map<String, Any> {
        val totalRules = behaviorRules.size
        val effectiveRules = behaviorRules.values.count { it.effectiveness > 0.6 }
        val totalActivations = behaviorRules.values.sumOf { it.activationCount }
        val successRate = if (totalActivations > 0) {
            behaviorRules.values.sumOf { it.successCount }.toDouble() / totalActivations
        } else 0.0
        
        return mapOf(
            "total_rules" to totalRules,
            "effective_rules" to effectiveRules,
            "total_activations" to totalActivations,
            "success_rate" to successRate,
            "total_learnings" to learningRecords.size,
            "total_transfers" to learningTransfers.size,
            "active_conflicts" to ruleConflicts.size
        )
    }
    
    fun formatRules(context: Map<String, Double> = emptyMap()): String {
        val rules = getActiveRules(context)
        if (rules.isEmpty()) return "No learned rules yet."
        
        return buildString {
            appendLine("[Learned Behavior Rules]")
            appendLine("─".repeat(40))
            rules.forEach { rule ->
                val effectiveness = (rule.effectiveness * 100).toInt()
                val activations = rule.activationCount
                appendLine("• [${rule.category}] ${rule.rule}")
                appendLine("  Effectiveness: $effectiveness% | Activations: $activations | Weight: ${"%.2f".format(rule.weight)}")
            }
        }
    }
    
    fun formatLearnings(): String {
        val learnings = getLearnings(10)
        if (learnings.isEmpty()) return "No learnings recorded yet."
        
        return buildString {
            appendLine("[Recent Learnings]")
            appendLine("─".repeat(40))
            learnings.forEach { learning ->
                val reinforcement = if (learning.reinforcement != 0.0) 
                    "[${if (learning.reinforcement > 0) "+" else ""}${learning.reinforcement}] " else ""
                appendLine("• $reinforcement${learning.lesson}")
                appendLine("  Context: ${learning.observation.take(50)}...")
                if (learning.transferable) {
                    appendLine("  ↳ Transferable to: ${learning.targetDomains.joinToString(", ")}")
                }
            }
        }
    }
    
    fun getStats(): String {
        val analytics = getLearningAnalytics()
        
        return buildString {
            appendLine("[Self-Modification Statistics]")
            appendLine("─".repeat(30))
            appendLine("Active rules: ${analytics["total_rules"]}")
            appendLine("Effective rules (>60%): ${analytics["effective_rules"]}")
            appendLine("Total activations: ${analytics["total_activations"]}")
            appendLine("Success rate: ${"%.1f".format((analytics["success_rate"] as Double) * 100)}%")
            appendLine("Learning records: ${analytics["total_learnings"]}")
            appendLine("Transfer learning: ${analytics["total_transfers"]}")
            appendLine("Active conflicts: ${analytics["active_conflicts"]}")
            appendLine("Modifications: ${modifications.size}")
            appendLine("Prompt additions: ${dynamicPromptAdditions.size}")
        }
    }
    
    fun formatConflicts(): String {
        if (ruleConflicts.isEmpty()) return "No rule conflicts detected."
        
        return buildString {
            appendLine("[Rule Conflicts]")
            appendLine("─".repeat(40))
            ruleConflicts.forEach { conflict ->
                val rule1 = behaviorRules[conflict.ruleId1]?.rule?.take(30) ?: "Unknown"
                val rule2 = behaviorRules[conflict.ruleId2]?.rule?.take(30) ?: "Unknown"
                appendLine("• ${conflict.conflictType}: ${"%.0f".format(conflict.severity * 100)}%")
                appendLine("  $rule1")
                appendLine("  vs $rule2")
                if (conflict.resolution != null) {
                    appendLine("  Resolution: ${conflict.resolution}")
                }
            }
        }
    }
}
