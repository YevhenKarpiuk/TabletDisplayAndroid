package com.tabletdisplay.session

enum class SessionState {
    IDLE,
    LISTENING,
    CONNECTED,
    STREAMING,
    ERROR
}

class SessionController {
    @Volatile
    var state: SessionState = SessionState.IDLE
        private set

    @Synchronized
    fun transitionTo(next: SessionState) {
        require(next in allowedTransitions.getValue(state)) {
            "Invalid session transition: $state -> $next"
        }
        state = next
    }

    companion object {
        private val allowedTransitions = mapOf(
            SessionState.IDLE to setOf(SessionState.LISTENING),
            SessionState.LISTENING to setOf(SessionState.CONNECTED, SessionState.ERROR, SessionState.IDLE),
            SessionState.CONNECTED to setOf(SessionState.STREAMING, SessionState.ERROR, SessionState.LISTENING),
            SessionState.STREAMING to setOf(SessionState.ERROR, SessionState.LISTENING),
            SessionState.ERROR to setOf(SessionState.LISTENING, SessionState.IDLE)
        )
    }
}
