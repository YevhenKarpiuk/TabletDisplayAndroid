package com.tabletdisplay.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionControllerTest {
    @Test
    fun happyPathReachesStreaming() {
        val controller = SessionController()

        controller.transitionTo(SessionState.LISTENING)
        controller.transitionTo(SessionState.CONNECTED)
        controller.transitionTo(SessionState.STREAMING)

        assertEquals(SessionState.STREAMING, controller.state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun streamingCannotStartFromIdle() {
        SessionController().transitionTo(SessionState.STREAMING)
    }
}
