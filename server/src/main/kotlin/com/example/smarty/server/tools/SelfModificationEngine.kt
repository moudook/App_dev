package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SelfModification(
    val id: String,
    val type: String,
    val change: String,
    val reason: String,
    val appliedAt: Long,
    val isActive: Boolean = true
)

@Serializable
data class BehaviorRule(
    val id: String,
    val rule: String,
    val category: String,
    val priority: Int = 5,
    val addedAt: Long,
    val source: String = "self"
)

@Serializable
data class LearningRecord(
    val id: String,
    val observation: String,
    val lesson: String,
    val context: String,
    val timestamp: Long
)

class SelfModificationEngine {
    private val logger = LoggerFactory.getLogger(SelfModificationEngine::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val modifications = ConcurrentHashMap<String, SelfModification>()
    private val behaviorRules = ConcurrentHashMap<String, BehaviorRule>()
    private val learningRecords = ConcurrentHashMap<String, LearningRecord>()
    private val dynamicPromptAdditions = mutableListOf<String>()
    
    fun addRule(
        rule: String,
        category: String = "general",
        priority: Int = 5,
        source: String = "self"
    ): String {
        val ruleId = "rule_${System.currentTimeMillis()}_${rule.hashCode()}"
        
        val behaviorRule = BehaviorRule(
            id = ruleId,
            rule = rule,
            category = category,
            priority = priority,
            addedAt = System.currentTimeMillis(),
            source = source
        )
        
        behaviorRules[ruleId] = behaviorRule
        logger.info("Added behavior rule: $rule")
        
        return ruleId
    }
    
    fun removeRule(ruleId: String): Boolean {
        return behaviorRules.remove(ruleId) != null
    }
    
    fun learn(observation: String, lesson: String, context: String = ""): String {
        val recordId = "learn_${System.currentTimeMillis()}"
        
        val record = LearningRecord(
            id = recordId,
            observation = observation,
            lesson = lesson,
            context = context,
            timestamp = System.currentTimeMillis()
        )
        
        learningRecords[recordId] = record
        
        val existingRule = behaviorRules.values.any { it.rule.contains(lesson, ignoreCase = true) }
        if (!existingRule && lesson.isNotBlank()) {
            addRule(lesson, "learned", 7, "self_learning")
        }
        
        logger.info("Recorded learning: $observation -> $lesson")
        return recordId
    }
    
    fun learnFromError(error: String, correction: String): String {
        return learn(
            observation = "Error occurred: $error",
            lesson = "In the future, $correction",
            context = "error_correction"
        )
    }
    
    fun learnFromSuccess(success: String, pattern: String): String {
        return learn(
            observation = "Success: $success",
            lesson = "Pattern that worked: $pattern",
            context = "success_pattern"
        )
    }
    
    fun getActiveRules(): List<BehaviorRule> {
        return behaviorRules.values
            .sortedByDescending { it.priority }
            .filter { true }
    }
    
    fun getRulesByCategory(category: String): List<BehaviorRule> {
        return behaviorRules.values
            .filter { it.category == category }
            .sortedByDescending { it.priority }
    }
    
    fun getLearnings(limit: Int = 20): List<LearningRecord> {
        return learningRecords.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    }
    
    fun addPromptAddition(addition: String) {
        dynamicPromptAdditions.add(addition)
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
                    appendLine("- ${rule.rule}")
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
        val modId = "mod_${System.currentTimeMillis()}"
        
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
        }
        
        modifications[modId] = mod.copy(isActive = true)
        logger.info("Applied modification: $modId")
        return true
    }
    
    fun getModificationHistory(): List<SelfModification> {
        return modifications.values.sortedByDescending { it.appliedAt }
    }
    
    fun formatRules(): String {
        val rules = getActiveRules()
        if (rules.isEmpty()) return "No learned rules yet."
        
        return buildString {
            appendLine("[Learned Behavior Rules]")
            appendLine("─".repeat(40))
            rules.forEach { rule ->
                appendLine("• [${rule.category}] ${rule.rule}")
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
                appendLine("• ${learning.lesson}")
                appendLine("  Context: ${learning.observation.take(50)}...")
            }
        }
    }
    
    fun getStats(): String {
        return buildString {
            appendLine("[Self-Modification Statistics]")
            appendLine("─".repeat(30))
            appendLine("Active rules: ${behaviorRules.size}")
            appendLine("Learning records: ${learningRecords.size}")
            appendLine("Modifications: ${modifications.size}")
            appendLine("Prompt additions: ${dynamicPromptAdditions.size}")
        }
    }
}
