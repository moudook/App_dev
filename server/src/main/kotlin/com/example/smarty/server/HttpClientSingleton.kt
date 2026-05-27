package com.example.smarty.server

import com.example.smarty.server.factory.HttpClientFactory
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory

/**
 * Single shared HTTP client instance for all services and agents.
 * Replaces 4 separate client instances that were previously created.
 */
object HttpClientSingleton {
    private val logger = LoggerFactory.getLogger(HttpClientSingleton::class.java)

    val client: HttpClient by lazy {
        logger.info("Initializing shared HTTP client instance")
        HttpClientFactory.createDefault()
    }

    fun close() {
        client.close()
        logger.info("Shared HTTP client closed")
    }
}
