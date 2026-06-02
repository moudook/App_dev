package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CategoryStatInfo(
    val name: String,
    val count: Int,
)
