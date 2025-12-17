package com.example.smarty.data.local

import androidx.room.TypeConverter
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.model.ProcessingStatus

class Converters {
    @TypeConverter
    fun fromNoteType(type: NoteType): String = type.name

    @TypeConverter
    fun toNoteType(value: String): NoteType = NoteType.valueOf(value)

    @TypeConverter
    fun fromProcessingStatus(status: ProcessingStatus): String = status.name

    @TypeConverter
    fun toProcessingStatus(value: String): ProcessingStatus = ProcessingStatus.valueOf(value)
}
