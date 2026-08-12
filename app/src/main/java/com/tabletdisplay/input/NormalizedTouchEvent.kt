package com.tabletdisplay.input

enum class TouchAction {
    DOWN,
    MOVE,
    UP,
    CANCEL
}

data class NormalizedTouchEvent(
    val action: TouchAction,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val timestampNanos: Long
) {
    init {
        require(x in 0f..1f)
        require(y in 0f..1f)
    }
}
