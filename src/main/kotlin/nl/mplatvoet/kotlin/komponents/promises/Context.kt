/*
 * Copyright (c) 2014-2015 Mark Platvoet<mplatvoet@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package nl.mplatvoet.kotlin.komponents.promises

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Created by mplatvoet on 30-5-2014.
 */





public object Promises {
    val configuration: Context
        get() = mutableContext.get()!!

    private var mutableContext = AtomicReference(ThreadSafeContext())

    public fun configure(body: MutableContext.() -> Unit) {
        //a copy-on-write strategy is used, but in order to maintain the lazy loading mechanism
        //keeping track of what the developer actually altered is needed, otherwise
        //everything gets initialized during configuration
        val trackingContext = TrackingContext(mutableContext.get()!!)
        trackingContext.body()

        do {
            val current = mutableContext.get()!!
            val newConfig = current.copy()
            trackingContext.applyChanged(newConfig)
        } while (!mutableContext.compareAndSet(current, newConfig))
    }

    private class ThreadSafeContext() : MutableContext {
        override var fallbackOnCurrentThread: Boolean = true

        private val executionErrorsDelegate = ThreadSafeLazyVar {
            {(e: Exception) -> e.printStackTrace() }
        }
        override var executionErrors: (Exception) -> Unit by executionErrorsDelegate

        private val multipleCompletionDelegate = ThreadSafeLazyVar<(Any, Any) -> Unit> {
            {(curVal:Any, newVal:Any)-> throw IllegalStateException("Value[$curVal] is set, can't override with new value[$newVal]") }
        }
        override var multipleCompletion: (curVal:Any, newVal:Any) -> Unit by multipleCompletionDelegate


        private val executorDelegate: ThreadSafeLazyVar<Executor> = ThreadSafeLazyVar {
            val count = AtomicInteger(0)
            val executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), {
                val thread = Thread(it)
                thread.setDaemon(true)
                thread.setName("komponents-promises-${count.incrementAndGet()}")
                thread
            })

            Runtime.getRuntime().addShutdownHook(Thread() {
                executorService.shutdown()
                executorService.awaitTermination(60, TimeUnit.SECONDS)
            })
            executorService
        }
        //TODO Make these distinct
        override var dispatchExecutor: Executor by executorDelegate
        override var workExecutor: Executor by executorDelegate

        fun copy(): ThreadSafeContext {
            val copy = ThreadSafeContext()
            copy.fallbackOnCurrentThread = fallbackOnCurrentThread
            if (executionErrorsDelegate.initialized) copy.executionErrors = executionErrors
            if (executorDelegate.initialized) copy.dispatchExecutor = dispatchExecutor
            if (multipleCompletionDelegate.initialized) copy.multipleCompletion = multipleCompletion
            return copy
        }
    }

    private class TrackingContext(private val currentConfig: Context) : MutableContext {
        private val fallbackOnCurrentThreadDelegate = TrackChangesVar { currentConfig.fallbackOnCurrentThread }
        override var fallbackOnCurrentThread: Boolean by fallbackOnCurrentThreadDelegate

        private val executorDelegate = TrackChangesVar { currentConfig.dispatchExecutor }
        //TODO make these distinct
        override var dispatchExecutor: Executor by executorDelegate
        override var workExecutor: Executor by executorDelegate

        private val executionErrorsDelegate = TrackChangesVar { currentConfig.executionErrors }
        override var executionErrors: (Exception) -> Unit by executionErrorsDelegate

        private val multipleCompletionDelegate = TrackChangesVar { currentConfig.multipleCompletion }
        override var multipleCompletion: (curVal:Any, newVal:Any) -> Unit by multipleCompletionDelegate

        fun applyChanged(config: MutableContext) {
            if (fallbackOnCurrentThreadDelegate.written)
                config.fallbackOnCurrentThread = fallbackOnCurrentThread

            if (executorDelegate.written)
                config.dispatchExecutor = dispatchExecutor

            if (executionErrorsDelegate.written)
                config.executionErrors = executionErrors
        }
    }
}

public trait Context {
    val fallbackOnCurrentThread: Boolean
    val dispatchExecutor: Executor
    val workExecutor: Executor
    val executionErrors: (Exception) -> Unit
    val multipleCompletion: (curVal:Any, newVal:Any) -> Unit
}

public trait MutableContext : Context {
    override var fallbackOnCurrentThread: Boolean
    override var dispatchExecutor: Executor
    override var workExecutor: Executor
    override var executionErrors: (Exception) -> Unit
    override var multipleCompletion: (curVal:Any, newVal:Any) -> Unit
}