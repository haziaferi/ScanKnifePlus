package com.paperknifeplus.app.data.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

class NativeRendererPoolTest {
    private class FakeRenderer(val key: String) {
        val closes = AtomicInteger()
    }

    private val opened = mutableListOf<FakeRenderer>()
    private fun pool(maxKeys: Int, perKey: Int = 4) = RendererPool<String, FakeRenderer>(
        maxKeys, perKey,
        open = { key -> FakeRenderer(key).also { synchronized(opened) { opened += it } } },
        close = { it.closes.incrementAndGet() }
    )
    private fun opened(key: String) = synchronized(opened) { opened.filter { it.key == key } }

    @Test
    fun `evicting a renderer in use closes it only when it is released`() = runBlocking {
        val pool = pool(maxKeys = 1)
        val holding = CompletableDeferred<FakeRenderer>()
        val done = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            pool.use("a") { renderer ->
                holding.complete(renderer)
                done.await()
            }
        }
        val a = withTimeout(10_000) { holding.await() }

        pool.use("b") {} // A new URI evicts "a" while it renders.
        assertEquals(listOf("b"), pool.keys())
        assertEquals(0, a.closes.get())

        done.complete(Unit)
        holder.join()
        assertEquals(1, a.closes.get())
    }

    @Test
    fun `an idle evicted renderer is closed exactly once`() = runBlocking {
        val pool = pool(maxKeys = 1)
        pool.use("a") {}
        pool.use("b") {}
        pool.use("c") {}
        pool.use("b") {}
        assertEquals(1, opened("a").single().closes.get())
        assertEquals(1, opened("c").single().closes.get())
        assertEquals(listOf(1, 0), opened("b").map { it.closes.get() })
    }

    @Test
    fun `the most recently used URI survives eviction`() = runBlocking {
        val pool = pool(maxKeys = 2)
        pool.use("a") {}
        pool.use("b") {}
        pool.use("a") {} // "b" is now the least recently used.
        pool.use("c") {}
        assertEquals(setOf("a", "c"), pool.keys().toSet())
        assertEquals(1, opened("a").size)
        assertEquals(1, opened("b").single().closes.get())
    }

    @Test
    fun `a renderer acquired by a cancelled caller is still released`() = runBlocking {
        val pool = pool(maxKeys = 2, perKey = 1)
        launch(Dispatchers.Default) {
            pool.use("a") {
                coroutineContext.cancel()
                coroutineContext.ensureActive()
            }
        }.join()
        val reused = withTimeout(10_000) { pool.use("a") { it } }
        assertSame(opened("a").single(), reused)
    }

    @Test
    fun `when all renderers of a URI are busy, a caller waits for one instead of failing`() = runBlocking {
        val pool = pool(maxKeys = 2, perKey = 1)
        val holding = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            pool.use("a") {
                holding.complete(Unit)
                done.await()
            }
        }
        holding.await()
        val waiter = launch(Dispatchers.Default) { assertSame(opened("a").single(), pool.use("a") { it }) }
        delay(200) // The waiter is parked by now; it must not have opened a second renderer.
        assertEquals(1, opened("a").size)
        assertFalse(waiter.isCompleted)
        done.complete(Unit)
        withTimeout(10_000) { holder.join(); waiter.join() }
        assertEquals(1, opened("a").size)
        assertEquals(1, pool.openCount())
    }

    @Test
    fun `a file that cannot be opened gives null`() = runBlocking {
        val pool = RendererPool<String, FakeRenderer>(2, 4, open = { null }, close = {})
        assertNull(pool.use("a") { "rendered" })
        assertEquals(0, pool.openCount())
    }
}
