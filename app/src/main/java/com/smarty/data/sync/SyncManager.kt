package com.smarty.data.sync

import com.smarty.data.dao.SmartDatabaseDao
import com.smarty.data.entity.ChatMessageEntity
import com.smarty.data.entity.NoteEntity
import com.smarty.data.entity.TagEntity
import com.smarty.data.model.SyncState
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Real-time event streaming from Supabase
 */
class SupabaseEventStreamer {

    private val _events = MutableStateFlow<List<SyncEvent>>(emptyList())
    val events: StateFlow<List<SyncEvent>> = _events

    private val listeners = mutableMapOf<String, MutableList<(SyncEvent) -> Unit>>()

    fun listenToTable(tableName: String, filter: String? = null) {
        println("Listening to table: $tableName")
    }

    fun handleRealtimeEvent(event: RealtimeEvent) {
        val syncEvent = when (event.eventType) {
            "INSERT" -> SyncEvent.Insert(event.table, event.newRecord ?: return)
            "UPDATE" -> SyncEvent.Update(event.table, event.oldRecord ?: return, event.newRecord ?: return)
            "DELETE" -> SyncEvent.Delete(event.table, event.oldRecord ?: return)
            else -> return
        }
        _events.value = _events.value + syncEvent
        notifyListeners(event.table, syncEvent)
    }

    fun addListener(tableName: String, listener: (SyncEvent) -> Unit) {
        listeners.getOrPut(tableName) { mutableListOf() }.add(listener)
    }

    fun removeListener(tableName: String, listener: (SyncEvent) -> Unit) {
        listeners[tableName]?.remove(listener)
    }

    private fun notifyListeners(tableName: String, event: SyncEvent) {
        listeners[tableName]?.forEach { it(event) }
    }

    fun <T> subscribeToEntity(
        entityType: String,
        entityId: Long,
        onChanged: (T) -> Unit,
    ) {
        addListener(entityType) { event ->
            when (event) {
                is SyncEvent.Update -> if (event.getEntityId() == entityId) onChanged(event.newRecord as T)
                is SyncEvent.Insert -> if (event.getEntityId() == entityId) onChanged(event.record as T)
                else -> {}
            }
        }
    }
}

/**
 * Offline-first sync manager with a real HTTP bridge to the Ktor server.
 *
 * ID strategy:
 *  - Android entities use auto-increment Long PKs locally.
 *  - [NoteEntity.supabaseId] holds the server-assigned UUID as a String.
 *  - On INSERT, the server response body must contain `"id": "<uuid>"`.
 *    That UUID is written back to [NoteEntity.supabaseId] so all future
 *    UPDATE/DELETE calls can address the correct server row.
 *
 * @param serverBaseUrl  Ktor server base URL, e.g. "https://api.smarty.app"
 * @param getIdToken     Suspending lambda returning the current Firebase ID token
 * @param dao            Room DAO used to persist sync state changes locally
 * @param scope          Coroutine scope — defaults to Dispatchers.IO
 */
class OfflineFirstSyncManager(
    private val crdtManager: CRDTManager,
    private val eventStreamer: SupabaseEventStreamer,
    private val serverBaseUrl: String,
    private val getIdToken: suspend () -> String?,
    private val dao: SmartDatabaseDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val pendingSyncs = mutableListOf<PendingSync>()
    @Suppress("UnusedPrivateProperty")
    private val syncQueue = MutableStateFlow<List<SyncOperationType>>(emptyList())
    private var isSyncing = false

    // ─── Public API ───────────────────────────────────────────────────────────

    suspend fun queueForSync(entity: EntityWithMetadata, operation: SyncOperationType) {
        pendingSyncs.add(
            PendingSync(entity = entity, operation = operation, timestamp = Instant.now()),
        )
        processSyncQueue()
    }

    suspend fun forceSync() = processSyncQueue()

    fun getSyncStatus(): SyncManagerStatus =
        SyncManagerStatus(pendingCount = pendingSyncs.size, isSyncing = isSyncing, lastSyncTime = Instant.now())

    // ─── Queue Processing ─────────────────────────────────────────────────────

    private suspend fun processSyncQueue() {
        if (isSyncing) return
        isSyncing = true
        while (pendingSyncs.isNotEmpty()) {
            val pending = pendingSyncs.removeAt(0)
            try {
                when (pending.operation) {
                    SyncOperationType.INSERT -> performInsert(pending.entity)
                    SyncOperationType.UPDATE -> performUpdate(pending.entity)
                    SyncOperationType.DELETE -> performDelete(pending.entity)
                }
                pending.status = SyncStatus.SYNCED
            } catch (e: Exception) {
                handleSyncError(pending, e)
            }
        }
        isSyncing = false
    }

    // ─── CRUD Operations ──────────────────────────────────────────────────────

    private suspend fun performInsert(entity: EntityWithMetadata) {
        val responseBody = apiCall("POST", routeFor(entity), bodyFor(entity))
        val serverId = JSONObject(responseBody).optString("id").takeIf { it.isNotBlank() }
        if (serverId != null && entity is NoteEntity) {
            dao.updateNote(entity.copy(supabaseId = serverId, syncState = SyncState.SYNCED))
        } else {
            updateLocalSyncState(entity, SyncState.SYNCED)
        }
    }

    private suspend fun performUpdate(entity: EntityWithMetadata) {
        val remote = fetchRemoteVersion(entity)
        val toSync = if (remote != null) {
            crdtManager.mergeEntities(entity, remote, entity::class.simpleName!!)
        } else {
            entity
        }
        apiCall("PUT", "${routeFor(toSync)}/${serverIdOf(toSync)}", bodyFor(toSync))
        updateLocalSyncState(entity, SyncState.SYNCED)
    }

    private suspend fun performDelete(entity: EntityWithMetadata) {
        val serverId = serverIdOf(entity)
        if (serverId.isNotBlank()) {
            apiCall("DELETE", "${routeFor(entity)}/$serverId", null)
        }
        updateLocalSyncState(entity, SyncState.SYNCED)
    }

    // ─── HTTP Helper ──────────────────────────────────────────────────────────

    private suspend fun apiCall(method: String, path: String, body: String?): String =
        withContext(Dispatchers.IO) {
            val token = getIdToken() ?: throw IllegalStateException("No auth token — cannot sync")
            val conn = (URL("$serverBaseUrl$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("Sync HTTP $code for $method $path")
            conn.inputStream.bufferedReader().readText()
        }

    private suspend fun fetchRemoteVersion(entity: EntityWithMetadata): EntityWithMetadata? {
        val serverId = serverIdOf(entity).takeIf { it.isNotBlank() } ?: return null
        return try {
            val body = apiCall("GET", "${routeFor(entity)}/$serverId", null)
            remoteJsonToEntity(entity, body)
        } catch (e: Exception) {
            null
        }
    }

    // ─── Entity Mapping ───────────────────────────────────────────────────────

    private fun routeFor(entity: EntityWithMetadata): String = when (entity) {
        is NoteEntity -> "/notes"
        is TagEntity -> "/tags"
        is ChatMessageEntity -> "/chat/messages"
        else -> "/entities"
    }

    private fun serverIdOf(entity: EntityWithMetadata): String = when (entity) {
        is NoteEntity -> entity.supabaseId
        is TagEntity -> entity.supabaseId
        is ChatMessageEntity -> entity.supabaseId
        else -> ""
    }

    private fun bodyFor(entity: EntityWithMetadata): String = when (entity) {
        is NoteEntity -> JSONObject().apply {
            put("title", entity.title)
            put("content", entity.content)
            put("category", entity.category)
            put("is_pinned", entity.isPinned)
            put("is_archived", entity.isArchived)
            put("version", entity.version)
        }.toString()
        is TagEntity -> JSONObject().apply {
            put("name", entity.name)
            put("type", entity.type)
            put("color", entity.color)
        }.toString()
        else -> "{}"
    }

    private fun remoteJsonToEntity(local: EntityWithMetadata, json: String): EntityWithMetadata? {
        if (local !is NoteEntity) return null
        return runCatching {
            val j = JSONObject(json)
            local.copy(
                supabaseId = j.optString("id", local.supabaseId),
                title = j.optString("title", local.title),
                content = j.optString("content", local.content),
                version = j.optLong("version", local.version),
                syncState = SyncState.SYNCED,
            )
        }.getOrNull()
    }

    // ─── Local State Update ───────────────────────────────────────────────────

    private suspend fun updateLocalSyncState(entity: EntityWithMetadata, state: String) {
        when (entity) {
            is NoteEntity -> dao.updateNote(entity.copy(syncState = state))
            is TagEntity -> dao.updateTag(entity.copy(syncState = state))
            is ChatMessageEntity -> dao.updateChatMessage(entity.copy(syncState = state))
            else -> {}
        }
    }

    // ─── Remote Update Handler (inbound realtime) ─────────────────────────────

    fun handleRemoteUpdate(tableName: String, record: Map<String, Any>) {
        val entity = convertToEntity(tableName, record)
        val local = getLocalVersion(entity)
        val merged = if (local != null) crdtManager.mergeEntities(local, entity, tableName) else entity
        scope.launch { updateLocal(merged) }
    }

    private fun convertToEntity(tableName: String, record: Map<String, Any>): EntityWithMetadata =
        when (tableName) {
            "notes" -> NoteEntity(
                supabaseId = record["id"] as String,
                userId = (record["user_id"] as Number).toLong(),
                title = record["title"] as String,
                content = record["content"] as String,
                lastModified = Instant.parse(record["updated_at"] as String),
                version = (record["version"] as Number).toLong(),
                syncState = SyncState.SYNCED,
            )
            else -> throw IllegalArgumentException("Unknown table: $tableName")
        }

    private fun getLocalVersion(entity: EntityWithMetadata): EntityWithMetadata? = null

    private suspend fun updateLocal(entity: EntityWithMetadata) = updateLocalSyncState(entity, SyncState.SYNCED)

    // ─── Retry ────────────────────────────────────────────────────────────────

    private fun handleSyncError(pending: PendingSync, error: Exception) {
        pending.retryCount++
        pending.status = SyncStatus.ERROR
        pending.lastError = error.message
        if (pending.retryCount < MAX_RETRIES) {
            val delayMs = (1000 * Math.pow(2.0, pending.retryCount.toDouble())).toLong()
            scheduleRetry(pending, delayMs)
        } else {
            pending.status = SyncStatus.CONFLICT
        }
    }

    private fun scheduleRetry(pending: PendingSync, delayMs: Long) {
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            pendingSyncs.add(0, pending)
            processSyncQueue()
        }
    }
}

// ─── Supporting Types ─────────────────────────────────────────────────────────

sealed class SyncEvent {
    data class Insert(val table: String, val record: Any) : SyncEvent() {
        fun getEntityId(): Long = (record as? Map<*, *>)?.get("id") as? Long ?: 0
    }
    data class Update(val table: String, val oldRecord: Any, val newRecord: Any) : SyncEvent() {
        fun getEntityId(): Long = (newRecord as? Map<*, *>)?.get("id") as? Long ?: 0
    }
    data class Delete(val table: String, val record: Any) : SyncEvent()
}

data class RealtimeEvent(
    val eventType: String,
    val table: String,
    val oldRecord: Map<String, Any>?,
    val newRecord: Map<String, Any>?,
    val timestamp: Instant,
)

data class PendingSync(
    val entity: EntityWithMetadata,
    val operation: SyncOperationType,
    val timestamp: Instant,
    var retryCount: Int = 0,
    var status: SyncStatus = SyncStatus.PENDING,
    var lastError: String? = null,
)

enum class SyncOperationType { INSERT, UPDATE, DELETE }

data class SyncManagerStatus(
    val pendingCount: Int,
    val isSyncing: Boolean,
    val lastSyncTime: Instant,
)

const val MAX_RETRIES = 3
