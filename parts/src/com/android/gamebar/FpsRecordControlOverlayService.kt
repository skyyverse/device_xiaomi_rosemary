/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import org.lineageos.settings.R
import android.app.ActivityTaskManager
import android.app.Service
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.preference.PreferenceManager
import kotlin.math.abs

class FpsRecordControlOverlayService : Service() {

    companion object {
        const val ACTION_SET_ENABLED = "com.android.gamebar.action.SET_FPS_RECORD_CONTROL_ENABLED"
        const val ACTION_TOGGLE_RECORDING = "com.android.gamebar.action.TOGGLE_FPS_RECORDING"
        const val ACTION_SYNC_RECORDING_STATE = "com.android.gamebar.action.SYNC_FPS_RECORDING_STATE"
        const val EXTRA_ENABLED = "enabled"

        private const val PREF_ENABLED = "game_bar_fps_record_control_enabled"
        private const val PREF_X = "game_bar_fps_record_control_x"
        private const val PREF_Y = "game_bar_fps_record_control_y"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private var samplingRecording = false

        fun isSamplingRecording(): Boolean = samplingRecording
    }

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val perAppLogManager = PerAppLogManager.getInstance()

    private var bubbleView: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isRecording = false
    private var recordingPackage = ""
    private var samplingThread: HandlerThread? = null
    private var samplingHandler: Handler? = null
    private var session = 0
    private var taskListenerRegistered = false

    private val taskManager = ActivityTaskManager.getService()
    private val sampleRunnable = Runnable { sampleRecording() }
    private val checkForeground = Runnable {
        if (isRecording && ForegroundAppDetector.getForegroundPackageName() != recordingPackage) {
            stopRecording(autoStop = true)
        }
    }
    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            handler.removeCallbacks(checkForeground)
            handler.post(checkForeground)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            stopRecording(autoStop = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun sampleRecording() {
        val worker = samplingHandler ?: return
        val activeSession = session
        val packageName = recordingPackage
        val logging = GameBarStatsSampler.loggingMetrics(this)
        worker.post {
            val snapshot = try {
                GameBarStatsSampler.capture(this, packageName, logging)
            } catch (e: RuntimeException) {
                Log.w("FpsRecordControl", "Cannot sample recording", e)
                null
            }
            handler.post deliver@{
                if (activeSession != session || !isRecording) return@deliver
                if (snapshot != null) GameBarStatsSampler.addToExport(snapshot, logging)
                handler.postDelayed(sampleRunnable, SAMPLE_INTERVAL_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_RECORDING -> {
                toggleRecordingFromBubble()
                if (prefs.getBoolean(PREF_ENABLED, false) && bubbleView == null && Settings.canDrawOverlays(this)) {
                    showBubble()
                } else {
                    updateBubbleAppearance()
                }
                return START_STICKY
            }
            ACTION_SYNC_RECORDING_STATE -> {
                syncRecordingStateFromManager()
                if (prefs.getBoolean(PREF_ENABLED, false) && bubbleView == null && Settings.canDrawOverlays(this)) {
                    showBubble()
                } else {
                    updateBubbleAppearance()
                }
                return START_STICKY
            }
        }

        val enabled = when (intent?.action) {
            ACTION_SET_ENABLED -> intent.getBooleanExtra(EXTRA_ENABLED, false)
            else -> prefs.getBoolean(PREF_ENABLED, false)
        }

        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply()

        if (!enabled) {
            removeBubble()
            stopRecording(autoStop = false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            prefs.edit().putBoolean(PREF_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            showBubble()
        }
        return START_STICKY
    }

    private fun showBubble() {
        val view = ImageView(this).apply {
            val size = (48 * resources.displayMetrics.density).toInt()
            layoutParams = WindowManager.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(14, 14, 14, 14)
            setOnTouchListener(BubbleTouchListener())
        }
        bubbleView = view
        updateBubbleAppearance()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(PREF_X, 24)
            y = prefs.getInt(PREF_Y, 220)
        }
        bubbleParams = params

        try {
            windowManager.addView(view, params)
        } catch (_: Exception) {
            bubbleView = null
            bubbleParams = null
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubbleView = null
        bubbleParams = null
    }

    private inner class BubbleTouchListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val params = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                    if (!moved) {
                        toggleRecordingFromBubble()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun toggleRecordingFromBubble() {
        if (isRecording) stopRecording(autoStop = false)
        else startRecording()
    }

    private fun syncRecordingStateFromManager() {
        if (isRecording && !perAppLogManager.isAppLoggingActive(recordingPackage)) {
            stopRecording(autoStop = false)
        }
    }

    private fun startRecording() {
        val currentPackage = ForegroundAppDetector.getForegroundPackageName()
        if (currentPackage.isBlank() || currentPackage == "Unknown" || currentPackage == packageName) {
            Toast.makeText(this, "Open your game first, then start recording", Toast.LENGTH_SHORT).show()
            return
        }

        val dataExport = GameDataExport.getInstance()
        dataExport.setLoggingMode(GameDataExport.LoggingMode.PER_APP)
        if (!dataExport.isCapturing()) {
            dataExport.startCapture()
        }

        perAppLogManager.startManualLoggingForApp(currentPackage)

        if (!perAppLogManager.isAppLoggingActive(currentPackage)) {
            return
        }

        recordingPackage = currentPackage
        isRecording = true
        samplingRecording = true
        GameBarFpsMeter.getInstance(this).start()
        val thread = HandlerThread("GameBarFpsRecording").apply { start() }
        samplingThread = thread
        samplingHandler = Handler(thread.looper)
        try {
            taskManager.registerTaskStackListener(taskListener)
            taskListenerRegistered = true
        } catch (e: Exception) {
            Log.w("FpsRecordControl", "Cannot monitor recording task", e)
        }
        updateBubbleAppearance()
        handler.post(sampleRunnable)
    }

    private fun stopRecording(autoStop: Boolean) {
        if (!isRecording) return
        session++
        handler.removeCallbacks(sampleRunnable)
        handler.removeCallbacks(checkForeground)
        samplingHandler?.removeCallbacksAndMessages(null)
        samplingThread?.quitSafely()
        samplingHandler = null
        samplingThread = null
        if (taskListenerRegistered) {
            try {
                taskManager.unregisterTaskStackListener(taskListener)
            } catch (e: Exception) {
                Log.w("FpsRecordControl", "Cannot unregister recording task listener", e)
            }
            taskListenerRegistered = false
        }

        perAppLogManager.stopManualLoggingForApp(recordingPackage)
        recordingPackage = ""
        isRecording = false
        samplingRecording = false
        GameBarFpsMeter.getInstance(this).stop()
        if (perAppLogManager.getCurrentlyLoggingApps().isEmpty()) {
            GameDataExport.getInstance().stopCapture()
        }

        if (autoStop) {
            Toast.makeText(this, "Recording stopped (app minimized/switched)", Toast.LENGTH_SHORT).show()
        }
        updateBubbleAppearance()
        if (bubbleView == null) {
            stopSelf()
        }
    }

    private fun updateBubbleAppearance() {
        val view = bubbleView ?: return

        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (isRecording) Color.parseColor("#B71C1C") else Color.parseColor("#1E2B36"))
            setStroke(2, Color.parseColor("#80FFFFFF"))
        }
        view.background = bg
        view.setImageResource(if (isRecording) R.drawable.ic_fps_record_bubble_record else R.drawable.ic_fps_record_bubble_play)
        view.setColorFilter(Color.WHITE)

    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        handler.removeCallbacksAndMessages(null)
        removeBubble()
        stopRecording(autoStop = false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

