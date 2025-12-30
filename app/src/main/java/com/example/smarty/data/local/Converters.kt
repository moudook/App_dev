package com.example.smarty.data.local

import androidx.room.TypeConverter
import com.example.smarty.data.model.ExecutionStatus
import com.example.smarty.data.model.MemoryType
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus

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
    fun fromMemoryType(type: MemoryType): String = type.name

    @TypeConverter
    fun toMemoryType(value: String?): MemoryType {
        return try {
            value?.let { MemoryType.valueOf(it) } ?: MemoryType.PREFERENCE
        } catch (e: IllegalArgumentException) {
            MemoryType.PREFERENCE  // Fallback to default
        }
    }

    @TypeConverter
    fun fromExecutionStatus(status: ExecutionStatus): String = status.name

    @TypeConverter
    fun toExecutionStatus(value: String?): ExecutionStatus {
        return try {
            value?.let { ExecutionStatus.valueOf(it) } ?: ExecutionStatus.FAILED
        } catch (e: IllegalArgumentException) {
            ExecutionStatus.FAILED  // Fallback to default
        }
    }
}
