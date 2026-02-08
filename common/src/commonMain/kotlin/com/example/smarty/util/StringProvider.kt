package com.example.smarty.util

/**
 * Interface for providing localized strings.
 * Abstraction layer to decouple domain logic from Android Context.
 */
interface StringProvider {
    fun getString(resId: Int): String
    fun getString(resId: Int, vararg formatArgs: Any): String
}
