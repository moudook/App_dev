package com.example.smarty.util

/**
 * Interface for providing security-related messages.
 * Decouples PrivacyGuard from Android resources.
 */
interface SecurityMessageProvider {
    fun getViolationDetail(operation: String): String
    fun getViolationIds(operation: String): String
}
