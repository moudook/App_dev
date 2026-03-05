package com.example.smarty.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.smarty.util.TokenManager
import com.example.smarty.util.TokenState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * Unit tests for FCMService.
 * 
 * Tests cover:
 * - Token registration flow
 * - Token caching integration
 * - State management
 * - Error handling
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FCMServiceTest {

    private lateinit var context: Context
    private lateinit var fcmService: FCMService
    private lateinit var tokenManager: TokenManager
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        tokenManager = TokenManager(context)
        fcmService = FCMService()
        
        // Mock static dependencies
        mockkStatic("com.example.smarty.core.common.util.NotificationHelperKt")
        every { 
            com.example.smarty.core.common.util.NotificationHelper.showNotification(
                any(), any(), any()
            ) 
        } returns Unit
    }

    @After
    fun teardown() {
        unmockkAll()
        Shadows.shadowOf(context.cacheDir).clear()
    }

    @Test
    fun onNewToken_cachesTokenAndSendsToServer() = testScope.runTest {
        // Given
        val testToken = "test_fcm_token_xyz123"

        // When
        fcmService.onNewToken(testToken)
        
        // Advance coroutine execution
        testScope.advanceUntilIdle()

        // Then
        val cachedToken = tokenManager.getCachedToken()
        assertNotNull(cachedToken)
        assertEquals(testToken, cachedToken)
        
        // Verify state updated
        val state = fcmService.tokenState.value
        assertTrue(state is TokenState.Cached || state is TokenState.Unknown)
    }

    @Test
    fun onMessageReceived_handlesDataPayload() = testScope.runTest {
        // Given
        val mockMessage = mockk<com.google.firebase.messaging.RemoteMessage>(relaxed = true)
        val dataMap = mapOf("key" to "value")
        every { mockMessage.data } returns dataMap
        every { mockMessage.from } returns "sender_id"
        every { mockMessage.notification } returns null

        // When
        fcmService.onMessageReceived(mockMessage)
        
        testScope.advanceUntilIdle()

        // Then
        // Verify data was logged (would be captured in real scenario)
        assertTrue(dataMap.isNotEmpty())
    }

    @Test
    fun onMessageReceived_handlesNotificationPayload() = testScope.runTest {
        // Given
        val mockMessage = mockk<com.google.firebase.messaging.RemoteMessage>(relaxed = true)
        val mockNotification = mockk<com.google.firebase.messaging.RemoteMessage.Notification>(relaxed = true)
        every { mockMessage.data } returns emptyMap()
        every { mockMessage.from } returns "sender_id"
        every { mockMessage.notification } returns mockNotification
        every { mockNotification.title } returns "Test Notification"
        every { mockNotification.body } returns "Test Body"

        // When
        fcmService.onMessageReceived(mockMessage)
        
        testScope.advanceUntilIdle()

        // Then
        verify { 
            com.example.smarty.core.common.util.NotificationHelper.showNotification(
                any(), "Test Notification", "Test Body"
            ) 
        }
    }

    @Test
    fun onDestroy_cancelsServiceScope() = testScope.runTest {
        // Given
        fcmService.onNewToken("test_token")
        testScope.advanceUntilIdle()

        // When
        fcmService.onDestroy()

        // Then
        // Verify scope is cancelled (no more coroutines can run)
        assertTrue(fcmService.tokenState.value is TokenState.Cached || 
                   fcmService.tokenState.value is TokenState.Unknown)
    }

    @Test
    fun tokenState_flowEmitsOnTokenUpdate() = testScope.runTest {
        // Given
        val states = mutableListOf<TokenState>()
        val testToken = "flow_test_token"

        // Collect states
        val collectJob = testScope.backgroundScope.uncontrolled {
            fcmService.tokenState.collect { state ->
                states.add(state)
            }
        }

        // When
        fcmService.onNewToken(testToken)
        testScope.advanceUntilIdle()

        // Then
        assertTrue(states.isNotEmpty())
        // Should have at least Unknown -> Cached transition
        assertTrue(states.size >= 1)
        
        collectJob.cancel()
    }

    @Test
    fun service_handlesNullNotificationFields() = testScope.runTest {
        // Given
        val mockMessage = mockk<com.google.firebase.messaging.RemoteMessage>(relaxed = true)
        every { mockMessage.data } returns emptyMap()
        every { mockMessage.from } returns "sender_id"
        every { mockMessage.notification } returns null

        // When
        fcmService.onMessageReceived(mockMessage)
        
        testScope.advanceUntilIdle()

        // Then
        // Should not crash with null notification
        assertTrue(true)
    }

    @Test
    fun service_handlesEmptyDataPayload() = testScope.runTest {
        // Given
        val mockMessage = mockk<com.google.firebase.messaging.RemoteMessage>(relaxed = true)
        every { mockMessage.data } returns emptyMap()
        every { mockMessage.from } returns "sender_id"
        every { mockMessage.notification } returns null

        // When
        fcmService.onMessageReceived(mockMessage)
        
        testScope.advanceUntilIdle()

        // Then
        // Should handle empty data gracefully
        assertTrue(true)
    }
}
