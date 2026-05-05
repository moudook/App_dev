# BATCH-07: Storage Management Analysis Report

**Analysis Date:** 2025-12-31
**Focus Areas:** SharedPreferences, Cache Management, Temporary Files, Database Optimization
**Target Directories:** data/local/*.kt, data/cache/*.kt, util/*.kt, data/backup/*.kt

---

## Executive Summary

The Smarty Android app demonstrates generally good storage management practices with several well-implemented patterns including lazy initialization for EncryptedSharedPreferences, LRU cache eviction, and proper database transaction handling. However, there are **12 storage issues** identified ranging from main thread I/O to missing cleanup mechanisms.

**Critical Issues:** 2
**High Priority Issues:** 5
**Medium Priority Issues:** 3
**Low Priority Issues:** 2

---

## Storage Issues Analysis

### 1. SharedPreferences Synchronous Access Issues

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **CRITICAL: SharedPreferences getString() on main thread during init** | `SearchHistoryManager.kt:34-42` | Blocks UI thread during class init with synchronous `loadSearches()` call that reads from prefs | Move `loadSearches()` to coroutine scope, use lazy init pattern like SecurePreferences |
| **Synchronous prefs access in loadState()** | `RateLimiter.kt:250-254` | Called in init block, reads 2 values synchronously | Use suspend function with Dispatchers.IO or lazy initialization |
| **Synchronous prefs read in constructor** | `ApiMetrics.kt:48-60` | `loadFromPrefs()` called during init reads 6 SharedPreferences values | Defer loading to background or use lazy properties |
| **Multiple prefs.edit().apply() per operation** | `ApiMetrics.kt:63-72, 91-109` | Each API call triggers saveToPrefs() which writes 6 values - excessive I/O | Batch writes, debounce saves, or save only on app background |

**Code Example - SearchHistoryManager Issue:**
```kotlin
// PROBLEM: Synchronous init blocking main thread
init {
    loadSearches()  // <-- Synchronous disk I/O
}

private fun loadSearches() {
    synchronized(lock) {
        val stored = prefs.getString(KEY_SEARCHES, "") ?: ""  // Disk I/O
        _recentSearches.value = parseJsonArray(stored)
    }
}
```

**Recommended Fix:**
```kotlin
init {
    // Load asynchronously
    CoroutineScope(Dispatchers.IO).launch {
        loadSearches()
    }
}

private suspend fun loadSearches() = withContext(Dispatchers.IO) {
    synchronized(lock) {
        val stored = prefs.getString(KEY_SEARCHES, "") ?: ""
        _recentSearches.value = parseJsonArray(stored)
    }
}
```

---

### 2. Large Objects in Preferences

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **JSON array stored in preferences** | `SearchHistoryManager.kt:47-51` | Up to 20 search strings serialized as JSON in single pref key | Acceptable for small data, but consider Room if grows |
| **Provider priority list as JSON** | `SecurePreferences.kt:349-360, 364-369` | List<AIProvider> stored as JSON - could grow with new providers | Low impact currently, monitor size |
| **Dynamic models list as JSON** | `SecurePreferences.kt:915-939` | Groq dynamic models stored as JSON pairs - potentially large | Consider dedicated database table if list exceeds 50 items |

**Good Practice Noted:**
SecurePreferences correctly uses lazy initialization for EncryptedSharedPreferences (lines 284-297), avoiding main thread blocking during ViewModel creation.

---

### 3. Missing Cache Size Limits

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **AIResponseCache has small fixed limit (50 entries)** | `AIResponseCache.kt:19` | May be insufficient for heavy usage; could benefit from memory-based limit | Add memory-aware sizing: `max(50, availableMemoryMB / 2)` |
| **HashBasedCache limited to 100 entries** | `HashBasedCache.kt:27` | Fixed size may not adapt to device capabilities | Consider dynamic sizing based on available RAM |
| **ToolResultCache fixed at 50 entries** | `ToolResultCache.kt:10` | Adequate for short TTL (30s), but no memory pressure handling | Add trimToSize() callback for low memory situations |

**Good Practice Noted:**
CacheManager has proper 100MB limit with LRU eviction (lines 17-18, 86-113).

---

### 4. Temporary Files Not Cleaned Up

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **CRITICAL: Backup temp directory cleanup not guaranteed** | `BackupManager.kt:108-109, 248-251` | If exception occurs between temp creation and finally block, orphan dirs remain | Use try-with-resources pattern or register cleanup in finally immediately |
| **Decompression cache not auto-cleaned** | `FileStorageHelper.kt:252-258` | `getDecompressionCacheDir()` creates cache but no automatic cleanup | Add TTL-based cleanup or clear on app startup |
| **Restore extract directory left on failure** | `BackupManager.kt:297-298, 416-418` | Finally block deletes, but crash between creation and finally leaves orphans | Add startup cleanup for known temp patterns |

**Code Example - Backup Temp Issue:**
```kotlin
// PROBLEM: Temp dir created early, deleted late
val tempDir = File(context.cacheDir, "backup_temp_${System.currentTimeMillis()}")
tempDir.mkdirs()  // Created here

try {
    // ... lots of operations that could fail ...
} finally {
    tempDir.deleteRecursively()  // May never reach here on crash
}
```

**Recommended Fix:**
```kotlin
// Add startup cleanup for orphaned temp directories
class BackupManager {
    init {
        cleanupOrphanedTempDirs()
    }

    private fun cleanupOrphanedTempDirs() {
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("backup_temp_") ||
            it.name.startsWith("local_backup_temp_") ||
            it.name.startsWith("restore_")
        }?.forEach {
            it.deleteRecursively()
        }
    }
}
```

---

### 5. Attachment Files Not Deleted When Notes Deleted

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **HIGH: deleteNote() does not delete attachment files** | `CogniRepository.kt:259-265` | Note deletion removes DB record but imageUri/fileUri files remain on disk | Call FileStorageHelper.deleteFile() for imageUri and fileUri |
| **HIGH: deleteNotes() batch does not clean attachments** | `CogniRepository.kt:305-314` | Bulk delete leaves all attachment files orphaned | Iterate notes, collect URIs, delete files before DB delete |
| **Archived notes retain file references** | `NoteDao.kt:73-78` | Archived notes keep file URIs - not an issue, but restored notes may have stale URIs | Consider URI validation on unarchive |

**Code Example - Missing Cleanup:**
```kotlin
// CURRENT: Only deletes DB record
@Transaction
suspend fun deleteNote(note: Note) {
    noteDao.deleteNote(note)
    note.categoryId?.let { categoryDao.decrementNoteCount(it) }
    calendarDao.clearNoteLinkForNote(note.id)
    // Missing: file cleanup!
}
```

**Recommended Fix:**
```kotlin
@Transaction
suspend fun deleteNote(note: Note) {
    // Clean up attachment files BEFORE deleting DB record
    note.imageUri?.let { FileStorageHelper.deleteFile(context, it) }
    note.fileUri?.let { FileStorageHelper.deleteFile(context, it) }

    noteDao.deleteNote(note)
    note.categoryId?.let { categoryDao.decrementNoteCount(it) }
    calendarDao.clearNoteLinkForNote(note.id)
}
```

---

### 6. Backup Files Accumulating Without Cleanup

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **HIGH: LocalBackupManager has no automatic cleanup** | `LocalBackupManager.kt` | Local backups accumulate indefinitely in `local_backups` directory | Add max backup count or size limit with automatic oldest deletion |
| **Drive backups have cleanup but local don't** | `BackupManager.kt:221` | `driveService.deleteOldBackups()` called but no equivalent for local | Implement similar cleanup in LocalBackupManager |
| **No size limit for local backups directory** | `LocalBackupManager.kt:56-57` | Could consume significant storage over time | Add configurable max storage (e.g., 1GB) with LRU cleanup |

**Recommended Fix:**
```kotlin
class LocalBackupManager {
    companion object {
        private const val MAX_LOCAL_BACKUPS = 5
        private const val MAX_LOCAL_BACKUP_SIZE_BYTES = 500L * 1024 * 1024  // 500MB
    }

    suspend fun cleanupOldBackups() = withContext(Dispatchers.IO) {
        val backups = listLocalBackups().sortedByDescending { it.createdAt }

        // Keep only MAX_LOCAL_BACKUPS
        if (backups.size > MAX_LOCAL_BACKUPS) {
            backups.drop(MAX_LOCAL_BACKUPS).forEach { deleteLocalBackup(it) }
        }

        // Enforce size limit
        var totalSize = getTotalLocalBackupSize()
        val remaining = backups.take(MAX_LOCAL_BACKUPS).sortedBy { it.createdAt }
        for (backup in remaining) {
            if (totalSize <= MAX_LOCAL_BACKUP_SIZE_BYTES) break
            deleteLocalBackup(backup)
            totalSize -= backup.fileSize
        }
    }
}
```

---

### 7. Database WAL Mode Optimization

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **WAL mode not explicitly enabled** | `CogniDatabase.kt:239-275` | Room uses WAL by default on API 16+, but not explicitly configured | Add `.setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)` for clarity and control |
| **Missing WAL checkpoint scheduling** | `CogniDatabase.kt` | WAL file can grow large without periodic checkpointing | Add periodic `PRAGMA wal_checkpoint(TRUNCATE)` call |

**Recommended Fix:**
```kotlin
val instance = Room.databaseBuilder(
    context.applicationContext,
    CogniDatabase::class.java,
    "cogni_database"
)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)  // Explicit
    .addCallback(databaseCallback)
    // ... migrations ...
    .build()
```

---

### 8. Missing VACUUM Scheduling

| Issue | Location | Storage Impact | Fix |
|-------|----------|----------------|-----|
| **No database VACUUM scheduling** | `CogniDatabase.kt`, `NoteDao.kt` | Database file grows over time with deletions; never compacted | Add weekly VACUUM via WorkManager |
| **FTS index rebuild exists but not scheduled** | `NoteDao.kt:396-416` | `rebuildFtsIndex()` and `optimizeFtsIndex()` exist but no automatic scheduling | Schedule FTS optimize after bulk deletes, rebuild weekly |
| **FTS maintenance time tracked but not enforced** | `SecurePreferences.kt:826-841` | `getLastFtsMaintenance()` exists but no code calls it periodically | Implement check in app startup or WorkManager |

**Recommended Fix:**
```kotlin
// Add to CogniDatabase or create DatabaseMaintenanceManager
suspend fun performMaintenance() {
    val lastMaintenance = securePreferences.getLastFtsMaintenance()
    val weekInMs = 7 * 24 * 60 * 60 * 1000L

    if (System.currentTimeMillis() - lastMaintenance > weekInMs) {
        withContext(Dispatchers.IO) {
            // Optimize FTS index
            if (CogniDatabase.getFtsVersion() == 5) {
                noteDao.optimizeFtsIndex()
            }

            // VACUUM database (must be outside transaction)
            database.openHelper.writableDatabase.execSQL("VACUUM")

            securePreferences.setLastFtsMaintenance(System.currentTimeMillis())
        }
    }
}
```

---

## Additional Observations

### Good Practices Found

1. **SecurePreferences lazy initialization** (lines 284-297, 305-331): Properly avoids blocking main thread during ViewModel creation.

2. **CacheManager with LRU eviction** (lines 86-113): Well-implemented 100MB limit with oldest-first eviction targeting 80% capacity.

3. **DatabaseWriteBatcher** (entire file): Excellent pattern for batching writes to reduce SQLite transaction overhead.

4. **Backup rollback mechanism** (BackupManager.kt:269-454): Pre-restore backup for rollback on failure shows defensive programming.

5. **Thread-safe cache implementations**: ConcurrentHashMap usage in AIResponseCache and HashBasedCache.

### Storage Statistics Collection

| Component | Max Size | Eviction Policy | TTL |
|-----------|----------|-----------------|-----|
| CacheManager | 100MB | LRU to 80% | None |
| AIResponseCache | 50 entries | LRU | 30 min |
| HashBasedCache | 100 entries | LRU (bottom 10%) | 2 hours |
| ToolResultCache | 50 entries | LRU | 30 sec |
| SearchHistory | 20 entries | FIFO | None |
| LocalBackups | Unlimited | None | None |

---

## Priority Recommendations

### Immediate (P0 - Critical)
1. Add attachment file cleanup on note deletion
2. Implement startup cleanup for orphaned temp directories

### Short-term (P1 - High)
3. Add local backup size/count limits with automatic cleanup
4. Fix synchronous SharedPreferences access in SearchHistoryManager init
5. Debounce ApiMetrics saveToPrefs() calls

### Medium-term (P2 - Medium)
6. Schedule database VACUUM via WorkManager
7. Implement WAL checkpoint scheduling
8. Add memory-pressure aware cache trimming

### Long-term (P3 - Low)
9. Consider database table for dynamic model lists if they grow
10. Add startup URI validation for restored notes

---

## Files Analyzed

- `data/local/SecurePreferences.kt` - 941 lines
- `data/local/CogniDatabase.kt` - 277 lines
- `data/local/NoteDao.kt` - 417 lines
- `data/local/SearchHistoryManager.kt` - 123 lines
- `data/cache/CacheManager.kt` - 411 lines
- `data/cache/ThumbnailCache.kt` - 51 lines
- `data/cache/AIResponseCache.kt` - 124 lines
- `data/cache/HashBasedCache.kt` - 193 lines
- `data/cache/ToolResultCache.kt` - 40 lines
- `data/cache/WaveformCache.kt` - 51 lines
- `data/backup/BackupManager.kt` - 694 lines
- `data/backup/LocalBackupManager.kt` - 490 lines
- `data/repository/CogniRepository.kt` - 557 lines
- `util/FileStorageHelper.kt` - 653 lines
- `util/DatabaseWriteBatcher.kt` - 196 lines
- `util/api/RateLimiter.kt` - 309 lines
- `util/api/ApiMetrics.kt` - 124 lines
- `voice/speaker/SpeakerEmbeddingManager.kt` - 397 lines

---

**Report Generated:** 2025-12-31
**Analyzer:** Storage Management Specialist
