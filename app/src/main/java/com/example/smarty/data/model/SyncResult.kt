package com.example.smarty.data.model

data class SyncResult(
    val notesProcessed: Int,
    val memoriesCreated: Int,
    val success: Boolean,
    val message: String? = null,
)
