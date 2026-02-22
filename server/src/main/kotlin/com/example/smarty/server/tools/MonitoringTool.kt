package com.example.smarty.server.tools

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*
import kotlin.random.Random

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
    val createdAt: Long = System.currentTimeMillis(),
    val config: MonitorConfig = MonitorConfig()
)

@Serializable
data class MonitorConfig(
    val retryCount: Int = 3,
    val timeout: Long = 30000,
    val threshold: Double = 0.0,
    val smoothingFactor: Double = 0.3,
    val enablePrediction: Boolean = true,
    val enableAnomalyDetection: Boolean = true
)

@Serializable
data class MonitorCheck(
    val monitorId: String,
    val timestamp: Long,
    val value: String,
    val changed: Boolean,
    val previousValue: String? = null,
    val anomalyScore: Double = 0.0,
    val predictedValue: String? = null,
    val confidence: Double = 0.0
)

class MonitoringTool(private val client: HttpClient) {
    private val logger = LoggerFactory.getLogger(MonitoringTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val monitors = ConcurrentHashMap<String, MonitorTarget>()
    private val checkHistory = ConcurrentHashMap<String, MutableList<MonitorCheck>>()
    private val webFetchTool = WebFetchTool(client)
    
    private val anomalyDetector = AnomalyDetector()
    private val trendAnalyzer = TrendAnalyzer()
    private val predictor = ValuePredictor()
    private val alertingEngine = AlertingEngine()
    private val monitorScheduler = MonitorScheduler()
    private val healthTracker = HealthTracker()
    private val correlationEngine = CorrelationEngine()
    
    fun createMonitor(
        userId: String,
        type: String,
        target: String,
        checkInterval: String,
        alertOn: String? = null,
        config: MonitorConfig = MonitorConfig()
    ): String {
        val monitorId = "mon_${type}_${System.currentTimeMillis()}"
        
        val monitor = MonitorTarget(
            id = monitorId,
            userId = userId,
            type = type,
            target = target,
            checkInterval = checkInterval,
            alertOn = alertOn,
            config = config
        )
        
        monitors[monitorId] = monitor
        checkHistory[monitorId] = mutableListOf()
        
        monitorScheduler.schedule(monitorId, checkInterval)
        
        healthTracker.initialize(monitorId)
        
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
            monitorScheduler.cancel(monitorId)
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
                "custom" -> checkCustom(monitor.target, monitor.config)
                else -> "Unknown monitor type"
            }
            
            val previousValue = monitor.lastValue
            val changed = previousValue != null && previousValue != value
            
            val history = checkHistory[monitorId] ?: mutableListOf()
            
            val anomalyScore = if (monitor.config.enableAnomalyDetection && history.size > 5) {
                anomalyDetector.detect(monitorId, value, history)
            } else 0.0
            
            val (predictedValue, confidence) = if (monitor.config.enablePrediction && history.size > 3) {
                predictor.predict(monitorId, history)
            } else (null to 0.0)
            
            val check = MonitorCheck(
                monitorId = monitorId,
                timestamp = System.currentTimeMillis(),
                value = value,
                changed = changed,
                previousValue = previousValue,
                anomalyScore = anomalyScore,
                predictedValue = predictedValue,
                confidence = confidence
            )
            
            checkHistory[monitorId]?.add(check)
            
            monitors[monitorId] = monitor.copy(
                lastChecked = check.timestamp,
                lastValue = value
            )
            
            healthTracker.recordCheck(monitorId, check)
            
            if (check.changed || anomalyScore > 0.7) {
                alertingEngine.evaluate(monitor, check)
            }
            
            correlationEngine.recordObservation(monitorId, check)
            
            check
        } catch (e: Exception) {
            logger.error("Monitor check failed for $monitorId", e)
            healthTracker.recordFailure(monitorId)
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
    
    private suspend fun checkCustom(target: String, config: MonitorConfig): String {
        val content = webFetchTool.fetch(target, "raw")
        
        val hash = content.hashCode().toString()
        val wordCount = content.split("\\s+".toRegex()).size
        val charCount = content.length
        
        return "hash:$hash:words:$wordCount:chars:$charCount"
    }
    
    fun getHistory(monitorId: String, limit: Int = 10): List<MonitorCheck> {
        return checkHistory[monitorId]?.takeLast(limit) ?: emptyList()
    }
    
    fun getAnomalies(monitorId: String): List<MonitorCheck> {
        return checkHistory[monitorId]?.filter { it.anomalyScore > 0.7 } ?: emptyList()
    }
    
    fun getTrends(monitorId: String): TrendAnalysis {
        return trendAnalyzer.analyze(checkHistory[monitorId] ?: emptyList())
    }
    
    fun getCorrelations(): Map<String, Double> {
        return correlationEngine.findCorrelations()
    }
    
    fun getHealth(monitorId: String): HealthStatus {
        return healthTracker.getStatus(monitorId)
    }
    
    fun getAllAlerts(): List<Alert> {
        return alertingEngine.getActiveAlerts()
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
                
                val health = healthTracker.getStatus(m.id)
                appendLine("   Health: ${health.status} (uptime: ${"%.1f".format(health.uptimePercent)}%)")
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
            
            if (check.anomalyScore > 0.7) {
                appendLine("\n[!] ANOMALY DETECTED!")
                appendLine("Anomaly Score: ${"%.2f".format(check.anomalyScore * 100)}%")
            }
            
            if (check.predictedValue != null) {
                appendLine("\n[Prediction]")
                appendLine("Predicted: ${check.predictedValue}")
                appendLine("Confidence: ${"%.1f".format(check.confidence * 100)}%")
            }
            
            if (check.changed) {
                appendLine("\n[!] CHANGE DETECTED!")
                appendLine("Previous: ${check.previousValue}")
                appendLine("Current: ${check.value}")
            } else {
                appendLine("\n[*] No change detected")
                appendLine("Current value: ${check.value}")
            }
            
            val history = checkHistory[monitor.id] ?: emptyList()
            if (history.size > 1) {
                val trend = trendAnalyzer.analyze(history)
                appendLine("\n[Trend Analysis]")
                appendLine("Direction: ${trend.direction}")
                appendLine("Strength: ${"%.2f".format(trend.strength * 100)}%")
            }
        }
    }
}

class AnomalyDetector {
    private val baselines = ConcurrentHashMap<String, AnomalyBaseline>()
    
    fun detect(monitorId: String, currentValue: String, history: List<MonitorCheck>): Double {
        val baseline = baselines.getOrPut(monitorId) {
            AnomalyBaseline()
        }
        
        if (history.size < 3) return 0.0
        
        val numericValues = history.mapNotNull { extractNumeric(it.value) }
        
        if (numericValues.size < 3) {
            return if (currentValue != history.lastOrNull()?.value) 0.5 else 0.0
        }
        
        val mean = numericValues.average()
        val stdDev = sqrt(numericValues.map { (it - mean) * (it - mean) }.average())
        
        val currentNumeric = extractNumeric(currentValue) ?: return 0.0
        
        if (stdDev == 0.0) {
            return if (currentNumeric != numericValues.lastOrNull()) 0.8 else 0.0
        }
        
        val zScore = abs(currentNumeric - mean) / stdDev
        
        baseline.update(currentNumeric, mean, stdDev)
        
        return (zScore / 3.0).coerceIn(0.0, 1.0)
    }
    
    private fun extractNumeric(value: String): Double? {
        val regex = Regex("""\d+\.?\d*""")
        return regex.find(value)?.value?.toDoubleOrNull()
    }
    
    data class AnomalyBaseline(
        var mean: Double = 0.0,
        var stdDev: Double = 0.0,
        var lastValue: Double = 0.0,
        var count: Int = 0
    ) {
        fun update(value: Double, mean: Double, stdDev: Double) {
            this.mean = mean
            this.stdDev = stdDev
            this.lastValue = value
            this.count++
        }
    }
}

class TrendAnalyzer {
    fun analyze(history: List<MonitorCheck>): TrendAnalysis {
        if (history.size < 2) {
            return TrendAnalysis("UNKNOWN", 0.0, 0.0)
        }
        
        val values = history.mapNotNull { extractNumeric(it.value) }
        
        if (values.size < 2) {
            return TrendAnalysis("UNKNOWN", 0.0, 0.0)
        }
        
        val n = values.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        
        values.forEachIndexed { index, value ->
            sumX += index
            sumY += value
            sumXY += index * value
            sumX2 += index * index
        }
        
        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        
        val direction = when {
            slope > 0.1 -> "UPWARD"
            slope < -0.1 -> "DOWNWARD"
            else -> "STABLE"
        }
        
        val meanY = sumY / n
        var ssTot = 0.0
        var ssRes = 0.0
        
        values.forEachIndexed { index, value ->
            val predicted = sumX / n + slope * (index - sumX / n)
            ssTot += (value - meanY) * (value - meanY)
            ssRes += (value - predicted) * (value - predicted)
        }
        
        val rSquared = if (ssTot > 0) 1 - (ssRes / ssTot) else 0.0
        val strength = abs(rSquared)
        
        return TrendAnalysis(direction, slope, strength)
    }
    
    private fun extractNumeric(value: String): Double? {
        val regex = Regex("""\d+\.?\d*""")
        return regex.find(value)?.value?.toDoubleOrNull()
    }
    
    data class TrendAnalysis(
        val direction: String,
        val slope: Double,
        val strength: Double
    )
}

class ValuePredictor {
    private val models = ConcurrentHashMap<String, PredictionModel>()
    
    fun predict(monitorId: String, history: List<MonitorCheck>): Pair<String, Double> {
        val model = models.getOrPut(monitorId) { PredictionModel() }
        
        val values = history.mapNotNull { extractNumeric(it.value) }
        
        if (values.size < 3) {
            return (history.lastOrNull()?.value ?: "unknown") to 0.0
        }
        
        val recent = values.takeLast(5)
        
        val smoothed = exponentialSmooth(recent, 0.3)
        
        val trend = calculateTrend(recent)
        
        val predicted = smoothed + trend * 1.5
        
        model.update(predicted)
        
        val confidence = calculateConfidence(values)
        
        return predicted.toInt().toString() to confidence
    }
    
    private fun extractNumeric(value: String): Double? {
        val regex = Regex("""\d+\.?\d*""")
        return regex.find(value)?.value?.toDoubleOrNull()
    }
    
    private fun exponentialSmooth(values: List<Double>, alpha: Double): Double {
        if (values.isEmpty()) return 0.0
        var result = values.first()
        for (i in 1 until values.size) {
            result = alpha * values[i] + (1 - alpha) * result
        }
        return result
    }
    
    private fun calculateTrend(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        return (values.last() - values.first()) / values.size
    }
    
    private fun calculateConfidence(values: List<Double>): Double {
        if (values.size < 5) return 0.3
        
        val variance = values.map { (it - values.average()) * (it - values.average()) }.average()
        val stdDev = sqrt(variance)
        val mean = values.average()
        
        return if (mean > 0) (1 - stdDev / mean).coerceIn(0.0, 1.0) else 0.5
    }
    
    class PredictionModel {
        var lastPrediction: Double = 0.0
        var accuracy: Double = 0.5
        var samples: Int = 0
        
        fun update(prediction: Double) {
            samples++
            accuracy = accuracy * 0.9 + if (abs(prediction - lastPrediction) < abs(prediction) * 0.1) 0.1 else 0.0
            lastPrediction = prediction
        }
    }
}

class AlertingEngine {
    private val alerts = mutableListOf<Alert>()
    private val alertRules = ConcurrentHashMap<String, AlertRule>()
    
    init {
        registerDefaultRules()
    }
    
    private fun registerDefaultRules() {
        alertRules["price_change"] = AlertRule("price_change", "value_change", 0.0) { check, _ ->
            check.changed
        }
        
        alertRules["anomaly"] = AlertRule("anomaly", "anomaly", 0.7) { check, _ ->
            check.anomalyScore > 0.7
        }
        
        alertRules["availability_change"] = AlertRule("availability_change", "value_change", 0.0) { check, _ ->
            check.changed && (check.value == "available" || check.value == "unavailable")
        }
    }
    
    fun evaluate(monitor: MonitorTarget, check: MonitorCheck) {
        alertRules.values.forEach { rule ->
            if (rule.condition(check, monitor)) {
                val alert = Alert(
                    id = "alert_${System.currentTimeMillis()}",
                    monitorId = monitor.id,
                    type = rule.name,
                    message = generateMessage(monitor, check, rule),
                    severity = when {
                        check.anomalyScore > 0.9 -> AlertSeverity.HIGH
                        check.anomalyScore > 0.7 -> AlertSeverity.MEDIUM
                        else -> AlertSeverity.LOW
                    },
                    timestamp = System.currentTimeMillis(),
                    acknowledged = false
                )
                alerts.add(alert)
            }
        }
    }
    
    private fun generateMessage(monitor: MonitorTarget, check: MonitorCheck, rule: AlertRule): String {
        return when (rule.name) {
            "price_change" -> "Price changed for ${monitor.target}: ${check.previousValue} -> ${check.value}"
            "anomaly" -> "Anomaly detected for ${monitor.target} (score: ${"%.2f".format(check.anomalyScore)})"
            "availability_change" -> "Availability changed for ${monitor.target}: ${check.value}"
            else -> "Alert for ${monitor.target}"
        }
    }
    
    fun getActiveAlerts(): List<Alert> = alerts.filter { !it.acknowledged }
    
    fun acknowledgeAlert(alertId: String): Boolean {
        return alerts.find { it.id == alertId }?.let {
            alerts[alerts.indexOf(it)] = it.copy(acknowledged = true)
            true
        } ?: false
    }
    
    data class AlertRule(
        val name: String,
        val type: String,
        val threshold: Double,
        val condition: (MonitorCheck, MonitorTarget) -> Boolean
    )
    
    data class Alert(
        val id: String,
        val monitorId: String,
        val type: String,
        val message: String,
        val severity: AlertSeverity,
        val timestamp: Long,
        val acknowledged: Boolean
    )
    
    enum class AlertSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}

class MonitorScheduler {
    private val schedules = ConcurrentHashMap<String, ScheduledMonitor>()
    
    fun schedule(monitorId: String, interval: String) {
        val intervalMs = parseInterval(interval)
        schedules[monitorId] = ScheduledMonitor(monitorId, intervalMs, System.currentTimeMillis())
    }
    
    fun cancel(monitorId: String) {
        schedules.remove(monitorId)
    }
    
    fun getNextCheck(monitorId: String): Long? {
        return schedules[monitorId]?.nextCheckTime
    }
    
    fun shouldCheck(monitorId: String): Boolean {
        val schedule = schedules[monitorId] ?: return false
        return System.currentTimeMillis() >= schedule.nextCheckTime
    }
    
    fun markChecked(monitorId: String) {
        schedules[monitorId]?.let {
            schedules[monitorId] = it.copy(nextCheckTime = System.currentTimeMillis() + it.intervalMs)
        }
    }
    
    private fun parseInterval(interval: String): Long {
        val value = interval.filter { it.isDigit() }.toLongOrNull() ?: 60
        return when {
            interval.contains("minute", ignoreCase = true) -> value * 60 * 1000
            interval.contains("hour", ignoreCase = true) -> value * 60 * 60 * 1000
            interval.contains("second", ignoreCase = true) -> value * 1000
            else -> value * 60 * 1000
        }
    }
    
    data class ScheduledMonitor(
        val monitorId: String,
        val intervalMs: Long,
        var nextCheckTime: Long
    )
}

class HealthTracker {
    private val healthData = ConcurrentHashMap<String, HealthData>()
    
    fun initialize(monitorId: String) {
        healthData[monitorId] = HealthData()
    }
    
    fun recordCheck(monitorId: String, check: MonitorCheck) {
        healthData[monitorId]?.let { data ->
            healthData[monitorId] = data.copy(
                totalChecks = data.totalChecks + 1,
                successfulChecks = if (check.value != "error") data.successfulChecks + 1 else data.successfulChecks,
                lastCheckTime = check.timestamp,
                lastValue = check.value
            )
        }
    }
    
    fun recordFailure(monitorId: String) {
        healthData[monitorId]?.let { data ->
            healthData[monitorId] = data.copy(
                totalChecks = data.totalChecks + 1,
                consecutiveFailures = data.consecutiveFailures + 1,
                lastFailureTime = System.currentTimeMillis()
            )
        }
    }
    
    fun getStatus(monitorId: String): HealthStatus {
        val data = healthData[monitorId] ?: return HealthStatus("UNKNOWN", 0.0, 0)
        
        val uptime = if (data.totalChecks > 0) {
            data.successfulChecks.toDouble() / data.totalChecks
        } else 0.0
        
        val status = when {
            data.consecutiveFailures >= 3 -> "DOWN"
            uptime > 0.95 -> "HEALTHY"
            uptime > 0.8 -> "DEGRADED"
            else -> "UNKNOWN"
        }
        
        return HealthStatus(status, uptime * 100, data.consecutiveFailures)
    }
    
    data class HealthData(
        var totalChecks: Int = 0,
        var successfulChecks0,
        var consecutiveFailures: Int = 0: Int = ,
        var lastCheckTime: Long = 0,
        var lastFailureTime: Long = 0,
        var lastValue: String = ""
    )
    
    data class HealthStatus(
        val status: String,
        val uptimePercent: Double,
        val consecutiveFailures: Int
    )
}

class CorrelationEngine {
    private val observations = ConcurrentHashMap<String, MutableList<MonitorCheck>>()
    private val correlations = ConcurrentHashMap<String, Double>()
    
    fun recordObservation(monitorId: String, check: MonitorCheck) {
        observations.getOrPut(monitorId) { mutableListOf() }.add(check)
        
        if ((observations[monitorId]?.size ?: 0) > 100) {
            observations[monitorId]?.removeAt(0)
        }
    }
    
    fun findCorrelations(): Map<String, Double> {
        val monitorIds = observations.keys.toList()
        val result = mutableMapOf<String, Double>()
        
        for (i in monitorIds.indices) {
            for (j in i + 1 until monitorIds.size) {
                val id1 = monitorIds[i]
                val id2 = monitorIds[j]
                
                val correlation = calculateCorrelation(
                    observations[id1] ?: emptyList(),
                    observations[id2] ?: emptyList()
                )
                
                if (abs(correlation) > 0.5) {
                    result["$id1:$id2"] = correlation
                }
            }
        }
        
        return result
    }
    
    private fun calculateCorrelation(history1: List<MonitorCheck>, history2: List<MonitorCheck>): Double {
        val minSize = minOf(history1.size, history2.size)
        if (minSize < 3) return 0.0
        
        val values1 = history1.takeLast(minSize).mapNotNull { extractNumeric(it.value) }
        val values2 = history2.takeLast(minSize).mapNotNull { extractNumeric(it.value) }
        
        if (values1.size < 3 || values2.size < 3) return 0.0
        
        val mean1 = values1.average()
        val mean2 = values2.average()
        
        var numerator = 0.0
        var denom1 = 0.0
        var denom2 = 0.0
        
        for (i in values1.indices) {
            val diff1 = values1[i] - mean1
            val diff2 = values2[i] - mean2
            numerator += diff1 * diff2
            denom1 += diff1 * diff1
            denom2 += diff2 * diff2
        }
        
        return if (denom1 > 0 && denom2 > 0) numerator / sqrt(denom1 * denom2) else 0.0
    }
    
    private fun extractNumeric(value: String): Double? {
        val regex = Regex("""\d+\.?\d*""")
        return regex.find(value)?.value?.toDoubleOrNull()
    }
}
