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
    val timestampNanos: Long,
    val pressure: Float = 1f
) {
    init {
        require(x in 0f..1f)
        require(y in 0f..1f)
        require(pressure in 0f..1f)
    }
}
