
package com.smarty.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smarty.data.dao.SmartDatabaseDao
import com.smarty.data.entity.*
import com.smarty.data.relationship.*
import java.time.Instant

/**
 * Main Room Database with all entities and relationships
 */
@Database(
    entities = [
        UserEntity::class,
        NoteEntity::class,
        TagEntity::class,
        NoteTagEntity::class,
        ChatEntity::class,
        ChatMessageEntity::class,
        ChatMessageNoteEntity::class,
        CalendarEventEntity::class,
        CalendarEventNoteEntity::class,
        TaskEntity::class,
        SharedItemEntity::class,
        ReasoningTraceEntity::class,
        AgentCheckpointEntity::class,
        SearchIndexEntity::class,
        CRDTMetadataEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(
    InstantConverter::class,
    ListConverter::class,
    MapConverter::class
)
abstract class SmartDatabase : RoomDatabase() {

    abstract fun smartDao(): SmartDatabaseDao

    companion object {
        @Volatile
        private var INSTANCE: SmartDatabase? = null

        fun getInstance(context: Context): SmartDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): SmartDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                SmartDatabase::class.java,
                "smart_database"
            )
            .addCallback(DatabaseCallback())
            .enableMultiInstanceInvalidation()
            .fallbackToDestructiveMigration()
            .build()
        }
    }
}

/**
 * Database callback for initialization and migrations
 */
class DatabaseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        // Initialize with system tags and default data
        initializeSystemData()
    }

    override fun onOpen(db: SupportSQLiteDatabase) {
        super.onOpen(db)
        // Enable foreign keys
        db.execSQL("PRAGMA foreign_keys=ON;")
        // Optimize for read performance
        db.execSQL("PRAGMA journal_mode=WAL;")
        db.execSQL("PRAGMA synchronous=NORMAL;")
    }

    private fun initializeSystemData() {
        // Initialize system tags, default categories, etc.
        // This would be done via repository on first launch
    }
}

/**
 * Type converters for complex types
 */
class InstantConverter {
    @TypeConverter
    fun fromInstant(instant: Instant?): String? {
        return instant?.toString()
    }

    @TypeConverter
    fun toInstant(value: String?): Instant? {
        return value?.let { Instant.parse(it) }
    }
}

class ListConverter {
    private val gson = com.google.gson.Gson()

    @TypeConverter
    fun fromList(list: List<*>?): String? {
        return gson.toJson(list)
    }

    @TypeConverter
    fun toList(value: String?): List<*>? {
        return gson.fromJson(value, Array<Any>::class.java)?.toList()
    }
}

class MapConverter {
    private val gson = com.google.gson.Gson()

    @TypeConverter
    fun fromMap(map: Map<String, Any>?): String? {
        return gson.toJson(map)
    }

    @TypeConverter
    fun toMap(value: String?): Map<String, Any>? {
        return gson.fromJson(value, Map::class.java) as? Map<String, Any>
    }
}
