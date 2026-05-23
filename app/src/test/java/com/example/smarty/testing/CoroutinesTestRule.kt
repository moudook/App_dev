package com.example.smarty.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule to set up coroutines test dispatcher.
 *
 * This rule replaces the Main dispatcher with a test dispatcher,
 * allowing for deterministic execution of coroutines in tests.
 *
 * USAGE:
 * ```kotlin
 * @OptIn(ExperimentalCoroutinesApi::class)
 * class MyViewModelTest {
 *
 *     @get:Rule
 *     val coroutinesTestRule = CoroutinesTestRule()
 *
 *     @Test
 *     fun testExample() = coroutinesTestRule.testDispatcher.runTest {
 *         // All coroutines will use the test dispatcher
 *         viewModel.loadData()
 *         advanceUntilIdle()
 *         // Assert results
 *     }
 * }
 * ```
 *
 * BENEFITS:
 * - Deterministic test execution
 * - No random test failures due to timing
 * - Fast test execution (no actual delays)
 * - Easy to test time-based operations
 *
 * @param dispatcher The test dispatcher to use (defaults to StandardTestDispatcher)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesTestRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    /**
     * Get the test dispatcher for use in tests.
     */
    val testDispatcher: TestDispatcher get() = dispatcher

    /**
     * Called before each test. Sets the Main dispatcher to our test dispatcher.
     */
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    /**
     * Called after each test. Resets the Main dispatcher.
     */
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
