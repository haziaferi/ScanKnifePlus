package com.paperknifeplus.app.data.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Robolectric for android.util.Log, which the pool writes to when a close fails.
@RunWith(RobolectricTestRunner::class)
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
    fun `two concurrent first loads of one key parse it once and share the document`() = runBlocking {
        val pool = pool(3)
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val loads = AtomicInteger()
        val first = async(Dispatchers.IO) {
            pool.withDocument("a", {
                loads.incrementAndGet()
                loading.countDown()
                finish.await(5, TimeUnit.SECONDS)
                FakeDoc("a")
            }) { it }
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        // Runs on this thread until it waits for the load in progress.
        val second = async(start = CoroutineStart.UNDISPATCHED) { pool.withDocument("a", { loads.incrementAndGet(); FakeDoc("a") }) { it } }
        finish.countDown()
        val seen = withTimeout(10_000) { listOf(first.await(), second.await()) }
        assertEquals(1, loads.get())
        assertSame(seen[0], seen[1])
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a failed load throws to every caller that waited for it and caches nothing`() = runBlocking {
        val pool = pool(3)
        val loading = CountDownLatch(1)
        val fail = CountDownLatch(1)
        val first = async(Dispatchers.IO) {
            runCatching {
                pool.withDocument("a", {
                    loading.countDown()
                    fail.await(5, TimeUnit.SECONDS)
                    throw IOException("damaged")
                }) { "used" }
            }
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { pool.withDocument("a", { FakeDoc("a") }) { "used" } } }
        fail.countDown()
        val results = withTimeout(10_000) { listOf(first.await(), second.await()) }
        assertTrue(results.toString(), results.all { it.exceptionOrNull() is IOException })
        assertEquals(0, pool.size())
        assertEquals("used", pool.withDocument("a", { FakeDoc("a") }) { "used" })
    }

    private class CloseFailed : Error("close failed")

    @Test
    fun `a close that throws an Error still hands the new document to the callers waiting for it`() = runBlocking {
        val pool = DocumentPool<String, FakeDoc>(1) {
            if (it.key == "a") throw CloseFailed()
            closed += it
        }
        pool.withDocument("a", { FakeDoc("a") }) {} // Idle, so the next load evicts and closes it.
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val first = async(Dispatchers.IO) {
            runCatching {
                pool.withDocument("b", {
                    loading.countDown()
                    finish.await(5, TimeUnit.SECONDS)
                    FakeDoc("b")
                }) { it }
            }
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { pool.withDocument("b", { error("loaded twice") }) { it.key } }
        finish.countDown()

        assertEquals("b", withTimeout(10_000) { waiter.await() })
        assertTrue(first.await().exceptionOrNull() is CloseFailed)
        // Neither holds "b" any more, so evicting it closes it.
        pool.withDocument("c", { FakeDoc("c") }) {}
        assertEquals(listOf("b"), closed.map { it.key })
    }

    @Test
    fun `a caller stopped while it waits for a load lets the load finish, and holds nothing`() = runBlocking {
        val pool = pool(1)
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val first = async(Dispatchers.IO) {
            pool.withDocument("a", {
                loading.countDown()
                finish.await(5, TimeUnit.SECONDS)
                FakeDoc("a")
            }) { it }
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { pool.withDocument("a", { error("loaded twice") }) {} }
        waiter.cancel()
        finish.countDown()

        val loaded = withTimeout(10_000) { first.await() }
        assertTrue(waiter.isCancelled)
        assertSame(loaded, pool.withDocument("a", { error("loaded again") }) { it })
        // Nobody holds "a" any more, so evicting it closes it.
        pool.withDocument("b", { FakeDoc("b") }) {}
        assertEquals(listOf("a"), closed.map { it.key })
    }

    @Test
    fun `a caller whose key is evicted before its turn loads it again`() = runBlocking {
        val pool = pool(1)
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val published = CountDownLatch(1)
        val first = async(Dispatchers.IO) {
            pool.withDocument("a", {
                loading.countDown()
                finish.await(5, TimeUnit.SECONDS)
                FakeDoc("a")
            }) {
                published.countDown()
                it
            }
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val loads = AtomicInteger()
        // The waiter's turn comes when this thread next suspends; until then nothing here does.
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { pool.withDocument("a", { loads.incrementAndGet(); FakeDoc("a") }) { it } }
        finish.countDown()
        assertTrue(published.await(5, TimeUnit.SECONDS))
        pool.withDocument("b", { FakeDoc("b") }) {} // Evicts the published "a".

        val again = withTimeout(10_000) { waiter.await() }
        val loaded = first.await()
        assertEquals(1, loads.get())
        assertFalse(again === loaded)
        assertEquals(1, loaded.closes.get())
        assertEquals(0, again.closes.get())
    }

    @Test
    fun `a stopped check leaves the document it loaded to a caller that waits for it`() = runBlocking {
        val pool = pool(3)
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val first = launch(Dispatchers.IO) {
            pool.check("a", {
                loading.countDown()
                finish.await(5, TimeUnit.SECONDS)
                FakeDoc("a")
            }, rejects = { false }) {}
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        first.cancel()
        val second = async(start = CoroutineStart.UNDISPATCHED) { pool.check("a", { error("loaded twice") }, rejects = { false }) { it } }
        finish.countDown()

        val loaded = withTimeout(10_000) { second.await() }
        withTimeout(10_000) { first.join() }
        assertSame(loaded, pool.withDocument("a", { error("loaded again") }) { it })
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a check stopped while it waits no longer counts, so the stopped check that loaded the document closes it`() = runBlocking {
        val pool = pool(3)
        val loading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val first = launch(Dispatchers.IO) {
            pool.check("a", {
                loading.countDown()
                finish.await(5, TimeUnit.SECONDS)
                FakeDoc("a")
            }, rejects = { false }) {}
        }
        assertTrue(loading.await(5, TimeUnit.SECONDS))
        val second = launch(start = CoroutineStart.UNDISPATCHED) { pool.check("a", { error("loaded twice") }, rejects = { false }) {} }
        assertEquals(1, pool.waiters("a"))
        second.cancel()
        withTimeout(10_000) { second.join() }
        first.cancel()
        finish.countDown()

        withTimeout(10_000) { first.join() }
        assertEquals(listOf("a"), closed.map { it.key })
        assertEquals(0, pool.size())
    }

    @Test
    fun `a check that rejects a document another caller loaded leaves it pooled`() = runBlocking {
        val pool = pool(3)
        val loaded = pool.withDocument("a", { FakeDoc("a") }) { it }
        assertEquals("rejected", pool.check("a", { error("loaded again") }, rejects = { true }) { "rejected" })
        assertSame(loaded, pool.withDocument("a", { error("loaded again") }) { it })
        assertTrue(closed.isEmpty())
    }

    @Test
    fun `a document a check rejects stays open while the check holds it, then closes once, and the next caller loads it again`() = runBlocking {
        val pool = pool(3)
        val loads = AtomicInteger()
        val rejected = pool.check("a", { loads.incrementAndGet(); FakeDoc("a") }, rejects = { true }) { doc ->
            assertEquals("open while the check reads it", 0, doc.closes.get())
            doc
        }
        assertEquals(1, rejected.closes.get())

        val next = pool.withDocument("a", { loads.incrementAndGet(); FakeDoc("a") }) { it }
        assertEquals(2, loads.get())
        assertFalse(next === rejected)
        assertEquals(1, rejected.closes.get())
        assertEquals(listOf("a"), closed.map { it.key })
        assertEquals(1, pool.size())
    }

    @Test
    fun `a check whose read throws closes the document it loaded`() = runBlocking {
        val pool = pool(3)
        var read: FakeDoc? = null
        val thrown = runCatching {
            pool.check("a", { FakeDoc("a") }, rejects = { false }) { doc ->
                read = doc
                throw IllegalStateException("malformed outline")
            }
        }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is IllegalStateException)
        assertEquals(1, read!!.closes.get())
        assertEquals(0, pool.size())
    }

    @Test
    fun `a stopped check ends with its cancellation even if closing its document throws`() = runBlocking {
        val pool = DocumentPool<String, FakeDoc>(3) { throw CloseFailed() }
        val reading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        // Its own scope, so that a check failing with the Error would not fail the test's.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val check = scope.async {
            pool.check("a", { FakeDoc("a") }, rejects = { false }) {
                reading.countDown()
                finish.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        check.cancel()
        finish.countDown()

        withTimeout(10_000) { check.join() }
        val thrown = runCatching { check.await() }.exceptionOrNull()
        assertTrue(thrown.toString(), thrown is CancellationException)
        assertEquals("taken out of the pool all the same", 0, pool.size())
    }
}
