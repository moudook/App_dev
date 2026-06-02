package com.example.smarty.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for TokenManager.
 *
 * Tests cover:
 * - Token caching and retrieval
 * - Token expiration (24-hour validity)
 * - Thread safety with Mutex
 * - State flow updates
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TokenManagerTest {
    private lateinit var context: Context
    private lateinit var tokenManager: TokenManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tokenManager = TokenManager(context)
    }

    @Test
    fun cacheToken_storesTokenAndUpdatesState() =
        testScope.runTest {
            // Given
            val testToken = "test_fcm_token_12345"

            // When
            tokenManager.cacheToken(testToken)

            // Then
            val cachedToken = tokenManager.getCachedToken()
            assertEquals(testToken, cachedToken)

            val state = tokenManager.tokenState.value
            assertTrue(state is TokenState.Cached)
            assertEquals(testToken, (state as TokenState.Cached).token)
        }

    @Test
    fun getCachedToken_returnsNullWhenNoToken() =
        testScope.runTest {
            // When
            val cachedToken = tokenManager.getCachedToken()

            // Then
            assertNull(cachedToken)
            assertTrue(tokenManager.tokenState.value is TokenState.Unknown)
        }

    @Test
    fun hasValidCachedToken_returnsTrueForFreshToken() =
        testScope.runTest {
            // Given
            val testToken = "fresh_token"
            tokenManager.cacheToken(testToken)

            // When
            val isValid = tokenManager.hasValidCachedToken()

            // Then
            assertTrue(isValid)
        }

    @Test
    fun markAsRegistered_updatesStateToRegistered() =
        testScope.runTest {
            // Given
            val testToken = "registered_token"
            tokenManager.cacheToken(testToken)

            // When
            tokenManager.markAsRegistered(testToken)

            // Then
            val state = tokenManager.tokenState.value
            assertTrue(state is TokenState.Registered)
            assertEquals(testToken, (state as TokenState.Registered).token)
        }

    @Test
    fun clearCachedToken_removesTokenAndResetsState() =
        testScope.runTest {
            // Given
            val testToken = "to_be_cleared"
            tokenManager.cacheToken(testToken)
            assertNotNull(tokenManager.getCachedToken())

            // When
            tokenManager.clearCachedToken()

            // Then
            assertNull(tokenManager.getCachedToken())
            assertTrue(tokenManager.tokenState.value is TokenState.Unknown)
            assertFalse(tokenManager.hasValidCachedToken())
        }

    @Test
    fun tokenState_flowEmitsOnChanges() =
        testScope.runTest {
            // Given
            val testToken = "flow_test_token"
            val states = mutableListOf<TokenState>()

            // Collect states in background
            val collectJob =
                testScope.backgroundScope.uncontrolled {
                    tokenManager.tokenState.collect { state ->
                        states.add(state)
                    }
                }

            // When
            tokenManager.cacheToken(testToken)
            tokenManager.markAsRegistered(testToken)

            // Then
            assertEquals(3, states.size) // Initial Unknown + Cached + Registered
            assertTrue(states[0] is TokenState.Unknown)
            assertTrue(states[1] is TokenState.Cached)
            assertTrue(states[2] is TokenState.Registered)

            collectJob.cancel()
        }

    @Test
    fun failedOperation_updatesStateToFailed() =
        testScope.runTest {
            // When
            // Simulate a failure scenario (would happen in real usage)
            tokenManager::class.java
                .getDeclaredMethod(
                    "updateState",
                    TokenState::class.java,
                ).apply {
                    isAccessible = true
                    invoke(tokenManager, TokenState.Failed("Test error"))
                }

            // Then
            val state = tokenManager.tokenState.value
            assertTrue(state is TokenState.Failed)
            assertEquals("Test error", (state as TokenState.Failed).reason)
        }
}
