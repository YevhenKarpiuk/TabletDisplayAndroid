package com.tabletdisplay

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
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
import android.widget.Toast
import com.tabletdisplay.protocol.AndroidDisplayInfo
import com.tabletdisplay.protocol.ProtocolConstants
import com.tabletdisplay.input.NormalizedTouchEvent
import com.tabletdisplay.input.TouchAction
import com.tabletdisplay.transport.ServerStatus
import com.tabletdisplay.transport.TabletDisplayServer
import com.tabletdisplay.transport.WifiOptions
import com.tabletdisplay.ui.DisplaySurface
import com.tabletdisplay.video.H264Decoder
import com.tabletdisplay.video.VideoConfig
import com.tabletdisplay.wifi.PairingConfirmation
import com.tabletdisplay.wifi.WifiAdvertiser
import com.tabletdisplay.wifi.WifiIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
class MainActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var displaySurface: DisplaySurface
    private lateinit var blackoutView: View
    private lateinit var statusView: TextView
    private var server: TabletDisplayServer? = null
    private var activityStarted = false
    private var lastError: String? = null
    private var advertiser: WifiAdvertiser? = null
    private var pairingDialog: AlertDialog? = null

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
        blackoutView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
        }
        root.addView(
            blackoutView,
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
        val rotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Windows derives the virtual monitor modes and their aspect ratio from the panel
        // resolution; physical sizes are given for the natural orientation.
        val sideways = rotation == 90 || rotation == 270
        val width = if (sideways) displayMode.physicalHeight else displayMode.physicalWidth
        val height = if (sideways) displayMode.physicalWidth else displayMode.physicalHeight
        val maximumDimension = maxOf(width, height)
        val decoderLimits = H264Decoder.maximumFrameSize() ?: (maximumDimension to maximumDimension)
        val displayInfo = AndroidDisplayInfo(
            width = width,
            height = height,
            densityDpi = resources.displayMetrics.densityDpi,
            refreshRateMilliHz = (displayMode.refreshRate * 1_000f).toInt(),
            maxAvcWidth = decoderLimits.first,
            maxAvcHeight = decoderLimits.second,
            maxAvcFps = 60,
            profileMask = 0x07,
            rotation = rotation
        )
        val decoder = H264Decoder(displaySurface.holder.surface)
        val identity = WifiIdentity(this)
        val wifiOptions = WifiOptions(
            androidId = identity.id,
            androidName = identity.name,
            store = identity,
            confirmation = PairingConfirmation(::confirmPairing),
            onPaired = { windowsName ->
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.pairing_done, windowsName), Toast.LENGTH_LONG).show()
                }
            }
        )
        server = TabletDisplayServer(displayInfo, decoder, ::showStatus, ::lockOrientation, wifiOptions)
            .also { it.start() }
        advertiser = WifiAdvertiser(this, identity, ProtocolConstants.WIFI_PORT).also { it.start() }
    }

    private fun stopServer() {
        advertiser?.stop()
        advertiser = null
        pairingDialog?.dismiss()
        pairingDialog = null
        server?.close()
        server = null
    }

    // Runs on a Wi-Fi connection thread: shows the comparison code and waits for the user.
    private fun confirmPairing(windowsName: String, code: String): Boolean {
        val decision = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        runOnUiThread {
            if (isFinishing) {
                decision.countDown()
                return@runOnUiThread
            }
            pairingDialog?.dismiss()
            pairingDialog = AlertDialog.Builder(this)
                .setTitle(R.string.pairing_title)
                .setMessage(getString(R.string.pairing_message, windowsName, code))
                .setPositiveButton(R.string.pairing_accept) { _, _ -> accepted.set(true) }
                .setNegativeButton(R.string.pairing_reject, null)
                .setOnDismissListener { decision.countDown() }
                .show()
        }
        val decided = decision.await(PAIRING_DECISION_SECONDS, TimeUnit.SECONDS)
        if (!decided) runOnUiThread { pairingDialog?.dismiss() }
        return decided && accepted.get()
    }

    // The Windows display orientation decides the tablet orientation, so the video is never
    // stretched across a Surface of the other aspect ratio.
    private fun lockOrientation(config: VideoConfig) {
        runOnUiThread {
            val orientation = if (config.width >= config.height) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
            if (requestedOrientation != orientation) requestedOrientation = orientation
        }
    }

    private fun showStatus(status: ServerStatus, detail: String?) {
        runOnUiThread {
            val streaming = status == ServerStatus.STREAMING
            // The screen stays on while Windows streams to it. Windows ends the stream when its
            // displays turn off, the session locks or the connection is lost; then the device
            // may sleep on its own timeout again.
            displaySurface.keepScreenOn = streaming
            blackoutView.visibility = if (streaming) View.GONE else View.VISIBLE
            statusView.visibility = if (streaming) View.GONE else View.VISIBLE
            when (status) {
                ServerStatus.ERROR -> lastError = getString(R.string.status_error, detail ?: "")
                ServerStatus.STREAMING -> lastError = null
                else -> Unit
            }
            val text = when (status) {
                ServerStatus.LISTENING -> getString(R.string.status_waiting)
                ServerStatus.CONNECTED -> getString(R.string.status_connected)
                ServerStatus.READY -> getString(R.string.status_ready)
                ServerStatus.STREAMING -> getString(R.string.status_streaming)
                ServerStatus.ERROR -> null
                ServerStatus.STOPPED -> getString(R.string.status_stopped)
            }
            // Keep the last failure visible while the server waits for the next connection.
            statusView.text = listOfNotNull(text, lastError).joinToString("\n")
        }
    }

    companion object {
        private const val PAIRING_DECISION_SECONDS = 120L
    }
}
