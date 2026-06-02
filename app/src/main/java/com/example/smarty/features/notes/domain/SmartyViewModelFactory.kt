@file:Suppress("DEPRECATION")

package com.example.smarty.features.notes.domain

import android.app.Application
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Factory for SmartyViewModel that provides SavedStateHandle for state preservation
 * across process death (BUG-053 fix).
 *
 * Usage in Activity:
 * ```
 * private val viewModel: SmartyViewModel by viewModels {
 *     SmartyViewModelFactory(application, this)
 * }
 * ```
 */
@Suppress("DEPRECATION")
class SmartyViewModelFactory(
    private val application: Application,
    owner: SavedStateRegistryOwner,
) : AbstractSavedStateViewModelFactory(owner, null) {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle,
    ): T {
        if (modelClass.isAssignableFrom(SmartyViewModel::class.java)) {
            return SmartyViewModel(application, handle) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
