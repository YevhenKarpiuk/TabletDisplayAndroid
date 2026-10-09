package com.tabletdisplay.ui

import android.content.Context
import android.view.SurfaceView

// The parent is black; a background on SurfaceView can cover MediaCodec's Surface layer.
class DisplaySurface(context: Context) : SurfaceView(context)
