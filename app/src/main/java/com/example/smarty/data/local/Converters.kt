package com.example.smarty.data.local

import androidx.room.TypeConverter
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus

class Converters {
    @TypeConverter
    fun fromNoteType(type: NoteType): String = type.name

    @TypeConverter
    fun toNoteType(value: String?): NoteType {
        return try {
            value?.let { NoteType.valueOf(it) } ?: NoteType.BRAIN_DUMP
        } catch (e: IllegalArgumentException) {
            NoteType.BRAIN_DUMP  // Fallback to default
        }
    }

    @TypeConverter
    fun fromProcessingStatus(status: ProcessingStatus): String = status.name

    @TypeConverter
    fun toProcessingStatus(value: String?): ProcessingStatus {
        return try {
            value?.let { ProcessingStatus.valueOf(it) } ?: ProcessingStatus.PENDING
        } catch (e: IllegalArgumentException) {
            ProcessingStatus.PENDING  // Fallback to default
        }
    }

    @TypeConverter
    fun fromMemoryType(type: com.example.smarty.data.model.MemoryType): String = type.name

    @TypeConverter
    fun toMemoryType(value: String?): com.example.smarty.data.model.MemoryType {
        return try {
            value?.let { com.example.smarty.data.model.MemoryType.valueOf(it) } ?: com.example.smarty.data.model.MemoryType.OTHER
        } catch (e: IllegalArgumentException) {
            com.example.smarty.data.model.MemoryType.OTHER  // Fallback to default
        }
    }
}
