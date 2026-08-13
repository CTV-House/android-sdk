package com.ctvhouse.sdk.core.runtime

import android.os.Handler
import android.os.Looper

/** Posts work to the main looper. */
internal interface MainPoster {
    fun post(block: () -> Unit)
    fun postDelayed(delayMs: Long, block: () -> Unit)

    /** Cancel pending delayed posts only (not immediate [post]). */
    fun cancelDelayed() {}

    /** Cancel everything this poster still owes the main looper. */
    fun cancelAll() {}
}

internal class HandlerMainPoster : MainPoster {
    private val handler = Handler(Looper.getMainLooper())
    private val delayedLock = Any()
    private val delayed = ArrayList<Runnable>()

    override fun post(block: () -> Unit) {
        handler.post { Safe.run(TAG, "main task", block) }
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        val wrapper = object : Runnable {
            override fun run() {
                synchronized(delayedLock) { delayed.remove(this) }
                Safe.run(TAG, "delayed main task", block)
            }
        }
        synchronized(delayedLock) { delayed.add(wrapper) }
        handler.postDelayed(wrapper, delayMs)
    }

    override fun cancelDelayed() {
        synchronized(delayedLock) {
            for (r in delayed) {
                handler.removeCallbacks(r)
            }
            delayed.clear()
        }
    }

    override fun cancelAll() {
        cancelDelayed()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val TAG = "MainPoster"
    }
}