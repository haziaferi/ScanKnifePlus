package com.haziaferi.scanknifeplus.scan.camera

import androidx.camera.core.CameraState
import androidx.lifecycle.MutableLiveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The wait for the camera to leave OPEN before a bind, against a hand-driven timer; Robolectric for LiveData's main thread. */
@RunWith(RobolectricTestRunner::class)
class CloseWaitTest {
    private val timer = FakeTimer()
    private val timeout = 1_000_000L
    private val wait = CloseWait(timer, timeout)
    private val states = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
    private val done = ArrayList<String>()

    private fun start() = wait.start(states) { timedOut -> done += "${if (timedOut) "timeout" else "left"}@${timer.now}" }

    private fun post(type: CameraState.Type) {
        states.value = CameraState.create(type)
    }

    @Test
    fun `ends as soon as the camera leaves OPEN`() {
        start()
        timer.advance(20_000)
        post(CameraState.Type.OPEN) // a repeat of OPEN keeps waiting
        post(CameraState.Type.CLOSING)
        timer.advance(10 * timeout)
        post(CameraState.Type.CLOSED)
        assertEquals(listOf("left@20000"), done)
        assertFalse(states.hasObservers())
    }

    @Test
    fun `a camera that stays OPEN is bound anyway after the timeout`() {
        start()
        timer.advance(timeout - 1)
        assertEquals(emptyList<String>(), done)
        timer.advance(1)
        post(CameraState.Type.CLOSING)
        assertEquals(listOf("timeout@$timeout"), done)
        assertFalse(states.hasObservers())
    }

    @Test
    fun `a cancelled wait never finishes`() {
        start()
        wait.cancel()
        post(CameraState.Type.CLOSING)
        timer.advance(10 * timeout)
        assertEquals(emptyList<String>(), done)
        assertFalse(states.hasObservers())
        wait.cancel() // harmless when not waiting
    }

    @Test
    fun `a new wait replaces the running one`() {
        start()
        timer.advance(500_000)
        start()
        timer.advance(600_000)
        assertEquals(emptyList<String>(), done)
        timer.advance(400_000)
        assertEquals(listOf("timeout@1500000"), done)
    }
}
