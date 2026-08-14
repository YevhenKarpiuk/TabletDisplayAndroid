package com.tabletdisplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.tabletdisplay.protocol.AndroidDisplayInfo
import com.tabletdisplay.input.NormalizedTouchEvent
import com.tabletdisplay.input.TouchAction
import com.tabletdisplay.transport.ServerStatus
import com.tabletdisplay.transport.TabletDisplayServer
import com.tabletdisplay.ui.DisplaySurface
import com.tabletdisplay.video.H264Decoder

@Suppress("DEPRECATION")
class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var displaySurface: DisplaySurface
    private lateinit var statusView: TextView
    private var server: TabletDisplayServer? = null
    private var activityStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        displaySurface = DisplaySurface(this).also {
            it.holder.addCallback(this)
            it.setOnTouchListener { view, event ->
                forwardTouch(view, event)
            }
            root.addView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        statusView = TextView(this).apply {
            text = getString(R.string.status_waiting)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.status_background)
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        setContentView(root)
    }

    private fun forwardTouch(view: View, event: MotionEvent): Boolean {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> return true
            else -> return false
        }
        if (event.pointerCount == 0 || view.width <= 0 || view.height <= 0) return true
        val x = (event.getX(0) / maxOf(1, view.width - 1)).coerceIn(0f, 1f)
        val y = (event.getY(0) / maxOf(1, view.height - 1)).coerceIn(0f, 1f)
        server?.sendTouch(
            NormalizedTouchEvent(
                action = action,
                pointerId = 0,
                x = x,
                y = y,
                timestampNanos = event.eventTime * 1_000_000L,
                pressure = event.getPressure(0).coerceIn(0f, 1f)
            )
        )
        return true
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        startServerIfReady()
    }

    override fun onStop() {
        activityStarted = false
        stopServer()
        super.onStop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        startServerIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopServer()
    }

    private fun startServerIfReady() {
        if (!activityStarted || !displaySurface.holder.surface.isValid || server != null) return
        val displayMode = windowManager.defaultDisplay.mode
        val width = displaySurface.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = displaySurface.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val maximumDimension = maxOf(displayMode.physicalWidth, displayMode.physicalHeight)
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val displayInfo = AndroidDisplayInfo(
            width = width,
            height = height,
            densityDpi = resources.displayMetrics.densityDpi,
            refreshRateMilliHz = (displayMode.refreshRate * 1_000f).toInt(),
            maxAvcWidth = maximumDimension,
            maxAvcHeight = maximumDimension,
            maxAvcFps = 60,
            profileMask = 0x07,
            rotation = rotation
        )
        val decoder = H264Decoder(displaySurface.holder.surface)
        server = TabletDisplayServer(displayInfo, decoder, ::showStatus).also { it.start() }
    }

    private fun stopServer() {
        server?.close()
        server = null
    }

    private fun showStatus(status: ServerStatus, detail: String?) {
        runOnUiThread {
            statusView.visibility = if (status == ServerStatus.STREAMING) View.GONE else View.VISIBLE
            statusView.text = when (status) {
                ServerStatus.LISTENING -> getString(R.string.status_waiting)
                ServerStatus.CONNECTED -> getString(R.string.status_connected)
                ServerStatus.READY -> getString(R.string.status_ready)
                ServerStatus.STREAMING -> getString(R.string.status_streaming)
                ServerStatus.ERROR -> getString(R.string.status_error, detail ?: "")
                ServerStatus.STOPPED -> getString(R.string.status_stopped)
            }
        }
    }
}
