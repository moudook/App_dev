package com.example.smarty.testing

import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Get the value from a LiveData object synchronously.
 *
 * This helper method observes the LiveData and waits for a value to be emitted,
 * making it easy to test LiveData in unit tests.
 *
 * USAGE:
 * ```kotlin
 * @Test
 * fun testLiveData() {
 *     val value = liveData.getOrAwaitValue()
 *     assertEquals(expected, value)
 * }
 * ```
 *
 * @param time Maximum time to wait for a value (default: 2 seconds)
 * @param timeUnit Time unit for the timeout (default: seconds)
 * @param afterObserve Optional lambda to execute after observing (e.g., trigger emission)
 * @return The value emitted by the LiveData
 * @throws TimeoutException if no value is emitted within the timeout
 */
fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 2,
    timeUnit: TimeUnit = TimeUnit.SECONDS,
    afterObserve: () -> Unit = {},
): T {
    var data: T? = null
    val latch = CountDownLatch(1)

    val observer =
        object : Observer<T> {
            override fun onChanged(value: T) {
                data = value
                latch.countDown()
                removeObserver(this)
            }
        }

    observeForever(observer)

    try {
        // Execute optional lambda (e.g., to trigger emission)
        afterObserve.invoke()

        // Wait for value
        if (!latch.await(time, timeUnit)) {
            throw TimeoutException("LiveData value was never set.")
        }
    } finally {
        removeObserver(observer)
    }

    @Suppress("UNCHECKED_CAST")
    return data as T
}

/**
 * Extension function to get the first value emitted by a Flow.
 *
 * This helper method collects the first value from a Flow,
 * making it easy to test StateFlow and SharedFlow in unit tests.
 *
 * USAGE:
 * ```kotlin
 * @Test
 * fun testFlow() = runTest {
 *     val value = flow.firstValue()
 *     assertEquals(expected, value)
 * }
 * ```
 *
 * @param time Maximum time to wait for a value (default: 2 seconds)
 * @param timeUnit Time unit for the timeout (default: seconds)
 * @return The first value emitted by the Flow
 * @throws TimeoutException if no value is emitted within the timeout
 */
suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(
    time: Long = 2,
    timeUnit: TimeUnit = TimeUnit.SECONDS,
): T =
    kotlinx.coroutines.withTimeout(timeUnit.toMillis(time)) {
        first()
    }

/**
 * Collect all values emitted by a Flow during a test.
 *
 * USAGE:
 * ```kotlin
 * @Test
 * fun testFlowEmissions() = runTest {
 *     val values = flow.collectAllValues()
 *     assertEquals(listOf(1, 2, 3), values)
 * }
 * ```
 *
 * @param time Maximum time to collect values (default: 2 seconds)
 * @return List of all values emitted
 */
suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectAllValues(
    time: Long = 2,
    timeUnit: TimeUnit = TimeUnit.SECONDS,
): List<T> {
    val values = mutableListOf<T>()
    val job =
        kotlinx.coroutines.launch {
            collect { values.add(it) }
        }

    kotlinx.coroutines.delay(timeUnit.toMillis(time))
    job.cancel()

    return values
}
