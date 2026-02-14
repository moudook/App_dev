package com.example.smarty.core.domain.model

data class RecallResult(
    val id: String,
    val title: String,
    val content: String,
    val score: Double,
    val reason: String
)
