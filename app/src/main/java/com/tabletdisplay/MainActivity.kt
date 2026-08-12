package com.tabletdisplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.tabletdisplay.ui.DisplaySurface
import com.tabletdisplay.protocol.AndroidDisplayInfo
import com.tabletdisplay.transport.ServerStatus
import com.tabletdisplay.transport.TabletDisplayServer

@Suppress("DEPRECATION")
class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private var server: TabletDisplayServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            DisplaySurface(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
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

    override fun onStart() {
        super.onStart()
        val displayMode = windowManager.defaultDisplay.mode
        val width = maxOf(displayMode.physicalWidth, displayMode.physicalHeight)
        val height = minOf(displayMode.physicalWidth, displayMode.physicalHeight)
        val displayInfo = AndroidDisplayInfo(
            width = width,
            height = height,
            densityDpi = resources.displayMetrics.densityDpi,
            refreshRateMilliHz = (displayMode.refreshRate * 1_000f).toInt(),
            maxAvcWidth = width,
            maxAvcHeight = height,
            maxAvcFps = 60,
            profileMask = 0x07
        )
        server = TabletDisplayServer(displayInfo, ::showStatus).also { it.start() }
    }

    override fun onStop() {
        server?.close()
        server = null
        super.onStop()
    }

    private fun showStatus(status: ServerStatus, detail: String?) {
        runOnUiThread {
            statusView.text = when (status) {
                ServerStatus.LISTENING -> getString(R.string.status_waiting)
                ServerStatus.CONNECTED -> getString(R.string.status_connected)
                ServerStatus.READY -> getString(R.string.status_ready)
                ServerStatus.ERROR -> getString(R.string.status_error, detail ?: "")
                ServerStatus.STOPPED -> getString(R.string.status_stopped)
            }
        }
    }
}
