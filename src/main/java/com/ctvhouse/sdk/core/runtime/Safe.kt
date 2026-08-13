package com.ctvhouse.sdk.core.runtime

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Failure barrier between library work and the host process.
 *
 * Every task that runs on a library thread, or that the library posts to the main looper,
 * goes through here: a defect inside the library must never reach the host uncaught handler.
 */
internal object Safe {

    fun run(tag: String, what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            Log.e(tag, "$what failed", t)
        }
    }

    /** [Executor.execute] that tolerates an already released pool. */
    fun execute(executor: Executor, tag: String, what: String, block: () -> Unit) {
        try {
            executor.execute { run(tag, what, block) }
        } catch (_: RejectedExecutionException) {
            Log.w(tag, "$what dropped: pool released")
        } catch (t: Throwable) {
            Log.e(tag, "$what not scheduled", t)
        }
    }

    fun thread(name: String, runnable: Runnable): Thread =
        Thread(runnable, name).apply {
            isDaemon = true
            setUncaughtExceptionHandler { thread, t -> Log.e(TAG, "uncaught on ${thread.name}", t) }
        }

    private const val TAG = "Safe"
}
