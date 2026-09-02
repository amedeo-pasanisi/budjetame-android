package com.budjetame.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Pins Dispatchers.Main for ViewModel tests. Unconfined: ViewModel coroutines
 * run eagerly on the test thread, and the tests await outcomes on the
 * StateFlows with real-time timeouts (the HTTP seam runs on OkHttp's threads,
 * outside any virtual scheduler).
 *
 * The dispatcher is installed once per JVM and never swapped or reset.
 * ViewModel tests drive real OkHttp threads, and a response that lands after
 * its test body has returned must still find a valid Main dispatcher — a
 * per-test setMain/resetMain races with exactly those late dispatches
 * ("Dispatchers.Main is used concurrently with setting it"), which this
 * device hits on the dashboard suite. Late responses only ever update the
 * finished test's own ViewModel, so sharing one dispatcher is harmless.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule : TestWatcher() {

    override fun starting(description: Description) {
        ensureInstalled()
    }

    /** The JVM-wide test Main dispatcher; its scheduler drives the
     * TransactionsViewModel debounce tests (virtual time). */
    val dispatcher: TestDispatcher get() = shared

    companion object {
        private val shared: TestDispatcher = UnconfinedTestDispatcher()
        private var installed = false

        @Synchronized
        private fun ensureInstalled() {
            if (!installed) {
                Dispatchers.setMain(shared)
                installed = true
            }
        }
    }
}
