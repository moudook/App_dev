package com.example.smarty.core.common.util

import com.example.smarty.BuildConfig
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

/**
 * Android client that calls the Ktor server's proxy endpoint
 * instead of calling the Hugging Face Space directly.
 *
 * The server reads the HF token from its own environment variables
 * and attaches Authorization: Bearer <token> before forwarding.
 *
 * Usage:
 *   val response = HfProxyClient.get("v1/models")
 *   val result  = HfProxyClient.post("v1/chat/completions", jsonBody)
 */
object HfProxyClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = HttpClientProvider.default

    private val baseUrl: String
        get() = BuildConfig.SERVER_URL.trimEnd('/') + "/api/v1/proxy"

    /**
     * Perform a GET request through the server-side proxy.
     *
     * @param path  The path to forward to the HF Space (e.g. "v1/models")
     * @return      Raw response body string, or null on failure
     */
    suspend fun get(path: String): String? {
        val url = "$baseUrl/$path"
        return client.executeGet(url)?.readBodySafely()
    }

    /**
     * Perform a POST request through the server-side proxy.
     *
     * @param path  The path to forward to the HF Space (e.g. "v1/chat/completions")
     * @param body  JSON body string
     * @return      Raw response body string, or null on failure
     */
    suspend fun post(path: String, body: String): String? {
        val url = "$baseUrl/$path"
        return client.executePostJson(url, body)?.readBodySafely()
    }

    /**
     * Lower-level access — build your own [Request] and execute it
     * through the proxy. Use this when you need custom headers, streaming, etc.
     *
     * The token is injected server-side; do NOT add it here.
     */
    suspend fun request(request: Request): okhttp3.Response? {
        return client.newCall(request).execute()
    }
}
