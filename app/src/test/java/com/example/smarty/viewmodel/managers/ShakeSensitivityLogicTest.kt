package com.example.smarty.core.domain.model

import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.features.settings.domain.SettingsFeatureManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ShakeSensitivityLogicTest {
    private lateinit var securePreferences: SecurePreferences
    private lateinit var settingsManager: SettingsFeatureManager
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Before
    fun setup() {
        securePreferences = mockk(relaxed = true)

        // Mock default behavior
        every { securePreferences.getShakeSensitivity() } returns 2.75f // Default mid value (logic)

        settingsManager = SettingsFeatureManager(securePreferences, scope)
    }

    @Test
    fun `mapUiToLogic converts High UI (1_0) to Low Logic (0_5)`() {
        // We can't access private methods directly, so we test via public setShakeSensitivity
        // UI = 1.0f (High Sensitivity) -> Logic should be 0.5f (Low Threshold)

        settingsManager.setShakeSensitivity(1.0f)

        verify {
            securePreferences.setShakeSensitivity(
                withArg { logicValue ->
                    // Allow for small float point differences
                    assertEquals(0.5f, logicValue, 0.01f)
                },
            )
        }
    }

    @Test
    fun `mapUiToLogic converts Low UI (0_0) to High Logic (5_0)`() {
        // UI = 0.0f (Stable/Low Sensitivity) -> Logic should be 5.0f (High Threshold)

        settingsManager.setShakeSensitivity(0.0f)

        verify {
            securePreferences.setShakeSensitivity(
                withArg { logicValue ->
                    assertEquals(5.0f, logicValue, 0.01f)
                },
            )
        }
    }

    @Test
    fun `mapLogicToUi correctly initializes state flow`() {
        // If logic is 0.5 (High Sensitivity), UI should be 1.0
        every { securePreferences.getShakeSensitivity() } returns 0.5f

        // Re-init manager to trigger init block
        val newManager =
            SettingsFeatureManager(
                securePreferences,
                scope,
            )

        assertEquals(1.0f, newManager.shakeSensitivity.value, 0.01f)
    }
}
