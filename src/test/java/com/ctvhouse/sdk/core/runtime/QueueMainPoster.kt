package com.ctvhouse.sdk.core.runtime

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Test poster standing in for the main looper.
 *
 * Work submitted from the thread that created it runs inline; work submitted from anywhere
 * else waits for [runPosted], the way a real looper hop would. Delayed work waits for
 * [runDelayed], so a self-rescheduling ticker advances one round per call.
 */
internal class QueueMainPoster : MainPoster {
    private val mainThread = Thread.currentThread()
    private val delayed = ArrayDeque<() -> Unit>()
    private val offMain = ConcurrentLinkedQueue<() -> Unit>()

    override fun post(block: () -> Unit) {
        if (Thread.currentThread() === mainThread) block() else offMain.add(block)
    }

    override fun postDelayed(delayMs: Long, block: () -> Unit) {
        delayed.addLast(block)
    }

    override fun cancelDelayed() {
        delayed.clear()
    }

    override fun cancelAll() {
        cancelDelayed()
        offMain.clear()
    }

    /** Runs one round of delayed work; blocks re-queued by the round stay pending. */
    fun runDelayed() {
        val round = delayed.toList()
        delayed.clear()
        round.forEach { it.invoke() }
    }

    /** Runs work handed over from other threads. */
    fun runPosted() {
        while (true) {
            val block = offMain.poll() ?: return
            block()
        }
    }

    fun clearDelayed() = delayed.clear()
}
