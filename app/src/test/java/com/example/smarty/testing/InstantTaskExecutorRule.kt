package com.example.smarty.testing

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * JUnit rule to make LiveData execute synchronously.
 *
 * This rule replaces the background executor with a direct executor,
 * ensuring that LiveData operations complete immediately in tests.
 *
 * USAGE:
 * ```kotlin
 * class MyViewModelTest {
 *
 *     @get:Rule
 *     val instantTaskExecutorRule = InstantTaskExecutorRule()
 *
 *     @Test
 *     fun testLiveData() {
 *         val value = liveData.getOrAwaitValue()
 *         assertEquals(expected, value)
 *     }
 * }
 * ```
 *
 * BENEFITS:
 * - Synchronous LiveData execution
 * - No need to wait for background threads
 * - Deterministic test behavior
 * - Works with Room, Lifecycle, and other Architecture Components
 */
class InstantTaskExecutorRule : TestWatcher() {
    /**
     * Called before each test. Sets up the synchronous executor.
     */
    override fun starting(description: Description) {
        super.starting(description)
        ArchTaskExecutor.getInstance().setDelegate(
            object : TaskExecutor() {
                override fun executeOnDiskIO(runnable: Runnable) = runnable.run()

                override fun postToMainThread(runnable: Runnable) = runnable.run()

                override fun isMainThread(): Boolean = true
            },
        )
    }

    /**
     * Called after each test. Cleans up the executor.
     */
    override fun finished(description: Description) {
        super.finished(description)
        ArchTaskExecutor.getInstance().setDelegate(null)
    }
}
