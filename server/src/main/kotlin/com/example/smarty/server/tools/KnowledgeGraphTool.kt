package com.example.smarty.server.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class Entity(
    val id: String,
    val name: String,
    val type: String,
    val aliases: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Relationship(
    val id: String,
    val fromEntity: String,
    val toEntity: String,
    val relationType: String,
    val confidence: Double = 1.0,
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class KnowledgeGraphResult(
    val entities: List<Entity>,
    val relationships: List<Relationship>
)

class KnowledgeGraphTool {
    private val logger = LoggerFactory.getLogger(KnowledgeGraphTool::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val entities = mutableMapOf<String, Entity>()
    private val relationships = mutableMapOf<String, Relationship>()
    private val entityNameIndex = mutableMapOf<String, String>()
    
    fun extractEntities(text: String): List<Entity> {
        val found = mutableListOf<Entity>()
        
        val personPattern = Regex("""\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)\b""")
        val organizationPattern = Regex("""\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*(?:\s+(?:Inc|Corp|LLC|Company|Co|Ltd|University|College|Institute))?)\b""")
        val locationPattern = Regex("""\b([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*(?:\s+(?:City|State|Country|Street|Avenue|Road))?)\b""")
        val datePattern = Regex("""\b(\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}|\d{4}[/\-]\d{1,2}[/\-]\d{1,2}|(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{1,2},?\s+\d{4})\b""")
        val emailPattern = Regex("""\b([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})\b""")
        val phonePattern = Regex("""\b(\+?\d{1,3}[-.\s]?\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4})\b""")
        val urlPattern = Regex("""\b(https?://[^\s]+)\b""")
        val moneyPattern = Regex("""\$([\d,]+(?:\.\d{2})?)""")
        val projectPattern = Regex("""\b(?:project|app|system)\s+["']?([A-Za-z][A-Za-z0-9\s]+)["']?\b""", RegexOption.IGNORE_CASE)
        
        organizationPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1].trim()
            if (name.length > 2) {
                val entity = createOrGetEntity(name, "organization")
                found.add(entity)
            }
        }
        
        personPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1].trim()
            val words = name.split(Regex("\\s+"))
            if (words.size >= 2 && name.length > 3) {
                val entity = createOrGetEntity(name, "person")
                found.add(entity)
            }
        }
        
        locationPattern.findAll(text).forEach { match ->
            val name = match.groupValues[1].trim()
            if (name.length > 2) {
                val entity = createOrGetEntity(name, "location")
                found.add(entity)
            }
        }
        
        datePattern.findAll(text).forEach { match ->
            val date = match.groupValues[1].trim()
            val entity = createOrGetEntity(date, "date")
            found.add(entity)
        }
        
        emailPattern.findAll(text).forEach { match ->
            val email = match.groupValues[1].trim()
            val entity = createOrGetEntity(email, "email")
            found.add(entity)
        }
        
        urlPattern.findAll(text).forEach { match ->
            val url = match.groupValues[1].trim()
            val entity = createOrGetEntity(url, "url")
            found.add(entity)
        }
        
        moneyPattern.findAll(text).forEach { match ->
            val amount = match.groupValues[1].trim()
            val entity = createOrGetEntity("\$$amount", "money")
            found.add(entity)
        }
        
        projectPattern.findAll(text).forEach { match ->
            val projectName = match.groupValues[1].trim()
            if (projectName.length > 2) {
                val entity = createOrGetEntity(projectName, "project")
                found.add(entity)
            }
        }
        
        return found.distinctBy { it.id }
    }
    
    fun extractRelationships(text: String, entities: List<Entity>): List<Relationship> {
        val relations = mutableListOf<Relationship>()
        val lower = text.lowercase()
        
        val relationPatterns = listOf(
            Triple("works at", "works_at", "organization"),
            Triple("works for", "works_at", "organization"),
            Triple("employed by", "works_at", "organization"),
            Triple("ceo of", "ceo_of", "organization"),
            Triple("manager of", "manager_of", "project"),
            Triple("member of", "member_of", "organization"),
            Triple("located in", "located_in", "location"),
            Triple("based in", "located_in", "location"),
            Triple("lives in", "lives_in", "location"),
            Triple("from", "from", "location"),
            Triple("met with", "met_with", "person"),
            Triple("reported to", "reports_to", "person"),
            Triple("responsible for", "responsible_for", "project"),
            Triple("owns", "owns", "organization"),
            Triple("created", "created", "project"),
            Triple("founded", "founded", "organization")
        )
        
        for ((pattern, relType, targetType) in relationPatterns) {
            if (lower.contains(pattern)) {
                val sourceEntities = entities.filter { it.type == "person" || it.type == "organization" }
                val targetEntities = entities.filter { it.type == targetType }
                
                for (source in sourceEntities) {
                    for (target in targetEntities) {
                        if (source.id != target.id) {
                            val relId = "${source.id}_${relType}_${target.id}"
                            val existing = relationships[relId]
                            if (existing == null) {
                                val rel = Relationship(
                                    id = relId,
                                    fromEntity = source.id,
                                    toEntity = target.id,
                                    relationType = relType,
                                    source = text.take(200)
                                )
                                relationships[relId] = rel
                                relations.add(rel)
                            }
                        }
                    }
                }
            }
        }
        
        return relations
    }
    
    private fun createOrGetEntity(name: String, type: String): Entity {
        val normalizedName = name.lowercase().trim()
        val existingId = entityNameIndex[normalizedName]
        
        if (existingId != null) {
            return entities[existingId]!!
        }
        
        val id = "ent_${type}_${System.currentTimeMillis()}_${name.hashCode()}"
        val entity = Entity(
            id = id,
            name = name,
            type = type,
            aliases = listOf(name)
        )
        entities[id] = entity
        entityNameIndex[normalizedName] = id
        
        return entity
    }
    
    fun getEntity(id: String): Entity? = entities[id]
    
    fun findEntityByName(name: String): Entity? {
        val normalized = name.lowercase().trim()
        val id = entityNameIndex[normalized] ?: return null
        return entities[id]
    }
    
    fun getRelatedEntities(entityId: String): List<Pair<Relationship, Entity>> {
        val related = mutableListOf<Pair<Relationship, Entity>>()
        
        relationships.values.forEach { rel ->
            if (rel.fromEntity == entityId) {
                entities[rel.toEntity]?.let { target ->
                    related.add(rel to target)
                }
            } else if (rel.toEntity == entityId) {
                entities[rel.fromEntity]?.let { source ->
                    related.add(rel to source)
                }
            }
        }
        
        return related
    }
    
    fun getEntityNetwork(entityId: String, depth: Int = 2): KnowledgeGraphResult {
        val visitedEntities = mutableSetOf<String>()
        val visitedRelations = mutableSetOf<String>()
        val resultEntities = mutableListOf<Entity>()
        val resultRelations = mutableListOf<Relationship>()
        
        fun traverse(id: String, currentDepth: Int) {
            if (currentDepth > depth || visitedEntities.contains(id)) return
            visitedEntities.add(id)
            
            entities[id]?.let { resultEntities.add(it) }
            
            relationships.values.forEach { rel ->
                if (!visitedRelations.contains(rel.id)) {
                    if (rel.fromEntity == id) {
                        visitedRelations.add(rel.id)
                        resultRelations.add(rel)
                        traverse(rel.toEntity, currentDepth + 1)
                    } else if (rel.toEntity == id) {
                        visitedRelations.add(rel.id)
                        resultRelations.add(rel)
                        traverse(rel.fromEntity, currentDepth + 1)
                    }
                }
            }
        }
        
        traverse(entityId, 0)
        
        return KnowledgeGraphResult(resultEntities, resultRelations)
    }
    
    fun analyzeText(text: String): KnowledgeGraphResult {
        val extractedEntities = extractEntities(text)
        val extractedRelations = extractRelationships(text, extractedEntities)
        return KnowledgeGraphResult(extractedEntities, extractedRelations)
    }
    
    fun formatGraph(result: KnowledgeGraphResult): String {
        return buildString {
            appendLine("🕸️ Knowledge Graph Analysis")
            appendLine("━".repeat(50))
            appendLine()
            
            appendLine("📌 Entities Found: ${result.entities.size}")
            val grouped = result.entities.groupBy { it.type }
            grouped.forEach { (type, ents) ->
                appendLine("\n  ${type.uppercase()} (${ents.size}):")
                ents.take(10).forEach { e ->
                    appendLine("    • ${e.name}")
                }
                if (ents.size > 10) appendLine("    ... and ${ents.size - 10} more")
            }
            
            if (result.relationships.isNotEmpty()) {
                appendLine("\n🔗 Relationships Found: ${result.relationships.size}")
                result.relationships.take(10).forEach { r ->
                    val from = entities[r.fromEntity]?.name ?: r.fromEntity
                    val to = entities[r.toEntity]?.name ?: r.toEntity
                    appendLine("    • $from ──[${r.relationType}]──> $to")
                }
                if (result.relationships.size > 10) {
                    appendLine("    ... and ${result.relationships.size - 10} more")
                }
            }
        }
    }
    
    fun visualizeNetwork(entityId: String, depth: Int = 2): String {
        val network = getEntityNetwork(entityId, depth)
        val centerEntity = entities[entityId] ?: return "Entity not found"
        
        return buildString {
            appendLine("🌐 Network for: ${centerEntity.name}")
            appendLine("━".repeat(50))
            
            val related = getRelatedEntities(entityId)
            if (related.isEmpty()) {
                appendLine("\nNo direct connections found.")
                return@buildString
            }
            
            appendLine("\nDirect Connections:")
            related.forEach { (rel, entity) ->
                val arrow = if (rel.fromEntity == entityId) "→" else "←"
                appendLine("  $arrow ${entity.name} (${rel.relationType})")
            }
            
            if (depth > 1 && network.entities.size > 1) {
                appendLine("\nExtended Network (${network.entities.size} entities, ${network.relationships.size} connections):")
                network.entities.filter { it.id != entityId }.take(5).forEach { e ->
                    appendLine("  • ${e.name} [${e.type}]")
                }
            }
        }
    }
    
    fun clear() {
        entities.clear()
        relationships.clear()
        entityNameIndex.clear()
    }
    
    fun stats(): String {
        return buildString {
            appendLine("📊 Knowledge Graph Statistics")
            appendLine("━".repeat(30))
            appendLine("Entities: ${entities.size}")
            appendLine("Relationships: ${relationships.size}")
            val byType = entities.values.groupBy { it.type }
            appendLine("\nBy Type:")
            byType.forEach { (type, list) ->
                appendLine("  $type: ${list.size}")
            }
        }
    }
}
