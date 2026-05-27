package com.example.smarty.server.factory

import org.slf4j.LoggerFactory

/**
 * Factory for Supabase configuration.
 * Used for storing generated images in Supabase Storage via REST API.
 */
object SupabaseClientFactory {
    private val logger = LoggerFactory.getLogger(SupabaseClientFactory::class.java)

    /**
     * Returns the Supabase URL.
     * Requires SUPABASE_URL environment variable.
     */
    fun getSupabaseUrl(): String? = System.getenv("SUPABASE_URL")?.trim()?.ifBlank { null }

    /**
     * Returns the Supabase API key.
     * Requires SUPABASE_KEY environment variable.
     */
    fun getSupabaseKey(): String? = System.getenv("SUPABASE_KEY")?.trim()?.ifBlank { null }

    /**
     * Returns the storage bucket name for generated images.
     * Can be overridden via SUPABASE_IMAGE_BUCKET environment variable.
     */
    fun getImageBucketName(): String = System.getenv("SUPABASE_IMAGE_BUCKET")?.trim()?.ifBlank { null } ?: "generated-images"

    /**
     * Checks if Supabase is configured.
     */
    fun isConfigured(): Boolean {
        val supabaseUrl = getSupabaseUrl()
        val supabaseKey = getSupabaseKey()
        return !supabaseUrl.isNullOrBlank() && !supabaseKey.isNullOrBlank()
    }
}
