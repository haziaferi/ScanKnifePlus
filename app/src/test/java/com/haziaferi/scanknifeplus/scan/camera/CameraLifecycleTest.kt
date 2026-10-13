package com.haziaferi.scanknifeplus.scan.camera

import androidx.camera.core.CameraState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The camera's lifecycle, which closes the camera at a pause, against a hand-driven timer; Robolectric for the main thread. */
@RunWith(RobolectricTestRunner::class)
class CameraLifecycleTest {
    private val timer = FakeTimer()
    private val timeout = 1_000_000L
    private val camera = CameraLifecycle(timer, timeout)
    private val states = MutableLiveData(CameraState.create(CameraState.Type.OPEN))
    private val events = ArrayList<Lifecycle.Event>()

    init {
        camera.lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event })
    }

    private fun post(type: CameraState.Type) {
        states.value = CameraState.create(type)
    }

    @Test
    fun `follows the screen but stops while it is paused`() {
        camera.follow(Lifecycle.State.CREATED, null)
        camera.follow(Lifecycle.State.STARTED, null)
        assertEquals(Lifecycle.State.CREATED, camera.lifecycle.currentState)
        camera.follow(Lifecycle.State.RESUMED, null)
        assertEquals(listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME), events)
        events.clear()
        camera.follow(Lifecycle.State.STARTED, null)
        assertEquals("a pause stops the camera", listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP), events)
        events.clear()
        camera.follow(Lifecycle.State.CREATED, null)
        camera.follow(Lifecycle.State.STARTED, null)
        assertEquals(emptyList<Lifecycle.Event>(), events)
        camera.destroy()
        assertEquals(listOf(Lifecycle.Event.ON_DESTROY), events)
    }

    @Test
    fun `a resume waits until the main thread has seen the pause close the camera`() {
        camera.follow(Lifecycle.State.RESUMED, null)
        camera.follow(Lifecycle.State.STARTED, states)
        camera.follow(Lifecycle.State.RESUMED, states)
        assertEquals("the camera still reads OPEN", Lifecycle.State.CREATED, camera.lifecycle.currentState)
        timer.advance(20_000)
        post(CameraState.Type.CLOSING)
        assertEquals(Lifecycle.State.RESUMED, camera.lifecycle.currentState)
        assertFalse(states.hasObservers())
        timer.advance(10 * timeout)
        assertEquals(Lifecycle.State.RESUMED, camera.lifecycle.currentState)
    }

    @Test
    fun `a resume after the camera closed is not held`() {
        camera.follow(Lifecycle.State.RESUMED, null)
        camera.follow(Lifecycle.State.STARTED, states)
        post(CameraState.Type.CLOSED)
        camera.follow(Lifecycle.State.RESUMED, states)
        assertEquals(Lifecycle.State.RESUMED, camera.lifecycle.currentState)
    }

    @Test
    fun `a camera that stays OPEN resumes anyway after the timeout`() {
        camera.follow(Lifecycle.State.RESUMED, null)
        camera.follow(Lifecycle.State.STARTED, states)
        camera.follow(Lifecycle.State.RESUMED, states)
        timer.advance(timeout - 1)
        assertEquals(Lifecycle.State.CREATED, camera.lifecycle.currentState)
        timer.advance(1)
        assertEquals(Lifecycle.State.RESUMED, camera.lifecycle.currentState)
        assertFalse(states.hasObservers())
    }

    @Test
    fun `a pause or destroy during the wait cancels the resume`() {
        camera.follow(Lifecycle.State.RESUMED, null)
        camera.follow(Lifecycle.State.STARTED, states)
        camera.follow(Lifecycle.State.RESUMED, states)
        camera.follow(Lifecycle.State.STARTED, states)
        post(CameraState.Type.CLOSING)
        timer.advance(10 * timeout)
        assertEquals(Lifecycle.State.CREATED, camera.lifecycle.currentState)

        post(CameraState.Type.OPEN)
        camera.follow(Lifecycle.State.RESUMED, states)
        camera.destroy()
        post(CameraState.Type.CLOSING)
        timer.advance(10 * timeout)
        assertEquals(Lifecycle.State.DESTROYED, camera.lifecycle.currentState)
        assertFalse(states.hasObservers())
    }

    @Test
    fun `a camera lifecycle that never started can be destroyed`() {
        camera.destroy()
        assertEquals(Lifecycle.State.DESTROYED, camera.lifecycle.currentState)
    }
}
