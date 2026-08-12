package com.tabletdisplay.ui

import android.content.Context
import android.graphics.Color
import android.view.SurfaceView

class DisplaySurface(context: Context) : SurfaceView(context) {
    init {
        setBackgroundColor(Color.BLACK)
    }
}
