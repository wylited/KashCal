package org.onekash.kashcal.domain.sync

/**
 * Exponential retry backoff shared by the event and task push queues.
 *
 * Retry counts 0 to 9 wait 30s, 1m, 2m and so on up to 256m, so the default 10 retries add up
 * to about 8.5 hours. The 5-hour cap applies from count 10, which only an operation retried
 * past its budget reaches (an unresolved 412).
 */
object RetryDelays {

    const val BASE_DELAY_MS = 30_000L                         // 30 seconds
    const val MAX_BACKOFF_MS = 5L * 60 * 60 * 1000            // 5 hours, WorkManager's cap
    const val OPERATION_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000  // 30 days
    const val AUTO_RESET_FAILED_MS = 24L * 60 * 60 * 1000     // 24 hours

    /**
     * Returns the delay before the next retry: [BASE_DELAY_MS] doubled per retry, capped at
     * [MAX_BACKOFF_MS]. A negative [retryCount] counts as 0.
     */
    fun calculateRetryDelay(retryCount: Int): Long {
        val multiplier = 1L shl retryCount.coerceIn(0, 10) // 2^retryCount, bounded [0, 10]
        val delay = BASE_DELAY_MS * multiplier
        return minOf(delay, MAX_BACKOFF_MS)
    }
}
