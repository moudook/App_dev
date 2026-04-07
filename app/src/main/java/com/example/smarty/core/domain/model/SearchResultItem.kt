package com.example.smarty.core.domain.model

data class SearchResultItem(
    val note: Note,
    val score: Float,
    val highlight: String? = null,
)
