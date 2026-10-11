package com.paperknifeplus.app.data.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PdDocumentPoolTest {
    private class FakeDoc(val key: String) {
        val closes = AtomicInteger()
    }

    private val closed: MutableList<FakeDoc> = Collections.synchronizedList(mutableListOf())
    private fun pool(max: Int) = DocumentPool<String, FakeDoc>(max) { it.closes.incrementAndGet(); closed += it }

    @Test
    fun `two holders never use one document at the same time`() = runBlocking {
        val pool = pool(3)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        withTimeout(10_000) {
            (1..8).map {
                async(Dispatchers.Default) {
                    pool.withDocument("a", { FakeDoc("a") }) {
                        maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                        delay(20)
                        active.decrementAndGet()
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, maxActive.get())
    }

    @Test
    fun `an evicted document stays open until its holder is done`() = runBlocking {
        val pool = pool(1)
        val holding = CompletableDeferred<FakeDoc>()
        val done = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            pool.withDocument("a", { FakeDoc("a") }) { doc ->
                holding.complete(doc)
                done.await()
            }
        }
        val a = withTimeout(10_000) { holding.await() }

        pool.withDocument("b", { FakeDoc("b") }) {} // Evicts "a" while it is in use.
        assertEquals(0, a.closes.get())

        done.complete(Unit)
        holder.join()
        assertEquals(1, a.closes.get())
        assertEquals(listOf("a"), closed.map { it.key })
    }

    @Test
    fun `an idle evicted document is closed once, the least recently used first`() = runBlocking {
        val pool = pool(2)
        val docs = mutableMapOf<String, FakeDoc>()
        suspend fun use(key: String) = pool.withDocument(key, { FakeDoc(key).also { docs[key] = it } }) {}
        use("a")
        use("b")
        use("a") // "b" is now the least recently used.
        use("c")
        use("d")
        assertEquals(listOf("b", "a"), closed.map { it.key })
        assertTrue(docs.values.all { it.closes.get() <= 1 })
        assertEquals(2, pool.size())
    }

    @Test
    fun `a failed load gives null and caches nothing`() = runBlocking {
        val pool = pool(3)
        assertEquals(null, pool.withDocument("a", { null }) { "used" })
        assertEquals(0, pool.size())
    }

    @Test
    fun `concurrent first loads keep one document and close the other`() = runBlocking {
        val pool = pool(3)
        val bothLoading = CountDownLatch(2)
        val loads = AtomicInteger()
        val seen = withTimeout(10_000) {
            (1..2).map {
                async(Dispatchers.IO) {
                    pool.withDocument("a", {
                        val doc = FakeDoc("a")
                        loads.incrementAndGet()
                        bothLoading.countDown()
                        bothLoading.await(5, TimeUnit.SECONDS)
                        doc
                    }) { it }
                }
            }.awaitAll()
        }
        assertEquals(2, loads.get())
        assertSame(seen[0], seen[1])
        assertEquals(1, closed.size)
        assertFalse(closed.single() === seen[0])
        assertEquals(0, seen[0]!!.closes.get())
    }
}
