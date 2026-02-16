package com.example.smarty.features.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Helper function to create a state that can be used with delegation
@Composable
fun <T> rememberStateFlow(initialValue: T): MutableStateFlow<T> {
    return remember { MutableStateFlow(initialValue) }
}

// Extension function to convert StateFlow to State for delegation
@Composable
fun <T> StateFlow<T>.collectAsDelegatedState(): State<T> {
    return this.collectAsState(this.value)
}