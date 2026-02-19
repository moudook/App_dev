package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class MonitorTarget(
    val id: String,
    val userId: String,
    val type: String,
    val target: String,
    val checkInterval: String,
    val lastChecked: Long? = null,
    val lastValue: String? = null,
    val alertOn: String? = null,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class MonitorCheck(
    val monitorId: String,
    val timestamp: Long,
    val value: String,
    val changed: Boolean,
    val previousValue: String? = null
)

class MonitoringTool(private val client: HttpClient) {
    private val logger = LoggerFactory.getLogger(MonitoringTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val monitors = ConcurrentHashMap<String, MonitorTarget>()
    private val checkHistory = ConcurrentHashMap<String, MutableList<MonitorCheck>>()
    private val webFetchTool = WebFetchTool(client)
    
    fun createMonitor(
        userId: String,
        type: String,
        target: String,
        checkInterval: String,
        alertOn: String? = null
    ): String {
        val monitorId = "mon_${type}_${System.currentTimeMillis()}"
        
        val monitor = MonitorTarget(
            id = monitorId,
            userId = userId,
            type = type,
            target = target,
            checkInterval = checkInterval,
            alertOn = alertOn
        )
        
        monitors[monitorId] = monitor
        checkHistory[monitorId] = mutableListOf()
        
        logger.info("Created monitor $monitorId for $target (type: $type)")
        return monitorId
    }
    
    fun listMonitors(userId: String): List<MonitorTarget> {
        return monitors.values.filter { it.userId == userId && it.isActive }
    }
    
    fun deleteMonitor(userId: String, monitorId: String): Boolean {
        val monitor = monitors[monitorId]
        return if (monitor != null && monitor.userId == userId) {
            monitors.remove(monitorId)
            checkHistory.remove(monitorId)
            true
        } else false
    }
    
    suspend fun checkMonitor(monitorId: String): MonitorCheck? {
        val monitor = monitors[monitorId] ?: return null
        
        return try {
            val value = when (monitor.type) {
                "url" -> checkUrl(monitor.target)
                "price" -> checkPrice(monitor.target)
                "availability" -> checkAvailability(monitor.target)
                "text_change" -> checkTextChange(monitor.target)
                else -> "Unknown monitor type"
            }
            
            val previousValue = monitor.lastValue
            val changed = previousValue != null && previousValue != value
            
            val check = MonitorCheck(
                monitorId = monitorId,
                timestamp = System.currentTimeMillis(),
                value = value,
                changed = changed,
                previousValue = previousValue
            )
            
            checkHistory[monitorId]?.add(check)
            
            monitors[monitorId] = monitor.copy(
                lastChecked = check.timestamp,
                lastValue = value
            )
            
            check
        } catch (e: Exception) {
            logger.error("Monitor check failed for $monitorId", e)
            null
        }
    }
    
    private suspend fun checkUrl(url: String): String {
        val content = webFetchTool.fetch(url, "readable")
        val hash = content.hashCode().toString()
        return "hash:$hash:length:${content.length}"
    }
    
    private suspend fun checkPrice(url: String): String {
        val content = webFetchTool.fetch(url, "raw")
        
        val pricePatterns = listOf(
            Regex("""\$(\d+\.?\d*)"""),
            Regex("""€(\d+\.?\d*)"""),
            Regex("""£(\d+\.?\d*)"""),
            Regex("""(\d+\.?\d*)\s*(?:USD|EUR|GBP)""")
        )
        
        for (pattern in pricePatterns) {
            val match = pattern.find(content)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return "no_price_found"
    }
    
    private suspend fun checkAvailability(url: String): String {
        val content = webFetchTool.fetch(url, "raw").lowercase()
        
        val unavailablePatterns = listOf(
            "out of stock", "unavailable", "sold out",
            "not available", "coming soon", "waitlist"
        )
        
        val availablePatterns = listOf(
            "add to cart", "buy now", "in stock",
            "available", "add to bag"
        )
        
        for (pattern in unavailablePatterns) {
            if (content.contains(pattern)) return "unavailable"
        }
        
        for (pattern in availablePatterns) {
            if (content.contains(pattern)) return "available"
        }
        
        return "unknown"
    }
    
    private suspend fun checkTextChange(url: String): String {
        val content = webFetchTool.fetch(url, "readable")
        return content.take(500).hashCode().toString()
    }
    
    fun getHistory(monitorId: String, limit: Int = 10): List<MonitorCheck> {
        return checkHistory[monitorId]?.takeLast(limit) ?: emptyList()
    }
    
    fun formatMonitorList(monitors: List<MonitorTarget>): String {
        if (monitors.isEmpty()) return "No active monitors."
        
        return monitors.joinToString("\n\n") { m ->
            val lastCheck = m.lastChecked?.let {
                java.time.Instant.ofEpochMilli(it).toString()
            } ?: "Never"
            
            buildString {
                appendLine("[Monitor] ${m.id}")
                appendLine("   Type: ${m.type}")
                appendLine("   Target: ${m.target}")
                appendLine("   Check interval: ${m.checkInterval}")
                appendLine("   Last checked: $lastCheck")
                appendLine("   Last value: ${m.lastValue ?: "N/A"}")
                if (m.alertOn != null) appendLine("   Alert on: ${m.alertOn}")
            }
        }
    }
    
    fun formatCheckResult(check: MonitorCheck, monitor: MonitorTarget): String {
        return buildString {
            appendLine("[Monitor Check Result]")
            appendLine("─".repeat(40))
            appendLine("Monitor: ${monitor.id}")
            appendLine("Target: ${monitor.target}")
            appendLine("Type: ${monitor.type}")
            appendLine("Timestamp: ${java.time.Instant.ofEpochMilli(check.timestamp)}")
            
            if (check.changed) {
                appendLine("\n[!] CHANGE DETECTED!")
                appendLine("Previous: ${check.previousValue}")
                appendLine("Current: ${check.value}")
            } else {
                appendLine("\n[*] No change detected")
                appendLine("Current value: ${check.value}")
            }
        }
    }
}
