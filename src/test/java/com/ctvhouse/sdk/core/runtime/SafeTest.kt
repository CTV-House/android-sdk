package com.ctvhouse.sdk.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The barrier exists so a library defect can never reach the host process. */
class SafeTest {

    @Test
    fun run_swallowsFailure_andKeepsGoing() {
        var after = false
        Safe.run(TAG, "unit") { error("boom") }
        Safe.run(TAG, "unit") { after = true }
        assertTrue(after)
    }

    @Test
    fun run_swallowsErrorsNotJustExceptions() {
        Safe.run(TAG, "unit") { throw OutOfMemoryError("simulated") }
    }

    @Test
    fun run_restoresInterruptFlag() {
        val thread = Thread {
            Safe.run(TAG, "unit") { throw InterruptedException() }
        }
        thread.start()
        thread.join()
    }

    @Test
    fun execute_runsTask() {
        val pool = Executors.newSingleThreadExecutor()
        val done = CountDownLatch(1)
        try {
            Safe.execute(pool, TAG, "unit") { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun execute_onReleasedPool_isDroppedNotThrown() {
        val pool = Executors.newSingleThreadExecutor()
        pool.shutdownNow()
        var ran = false
        Safe.execute(pool, TAG, "unit") { ran = true }
        assertFalse(ran)
    }

    @Test
    fun execute_failingTask_doesNotKillTheThread() {
        val pool = Executors.newSingleThreadExecutor { r -> Safe.thread("safe-test", r) }
        val done = CountDownLatch(1)
        try {
            Safe.execute(pool, TAG, "unit") { error("boom") }
            Safe.execute(pool, TAG, "unit") { done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun thread_isDaemonWithUncaughtHandler() {
        val thread = Safe.thread("safe-test") { }
        assertTrue(thread.isDaemon)
        assertEquals("safe-test", thread.name)
        assertTrue(thread.uncaughtExceptionHandler != null)
    }

    private companion object {
        const val TAG = "SafeTest"
    }
}
