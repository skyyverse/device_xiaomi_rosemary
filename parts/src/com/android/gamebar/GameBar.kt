/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-FileCopyrightText: 2025 DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.android.gamebar.GameBarStatsSampler.Metric
import org.lineageos.settings.R
import java.util.*

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.UserHandle
import com.android.systemui.screenrecord.IRemoteRecording

class GameBar private constructor(context: Context) {

    private enum class OverlayTouchMode {
        UNDECIDED,
        DRAG,
        HORIZONTAL_SCROLL,
        WIDTH_EDIT
    }

    companion object {
        @Volatile
        private var sInstance: GameBar? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): GameBar {
            return sInstance ?: synchronized(this) {
                sInstance ?: GameBar(context.applicationContext).also { sInstance = it }
            }
        }
        
        @JvmStatic
        @Synchronized
        fun destroyInstance() {
            sInstance?.cleanup()
            sInstance = null
        }
        
        @JvmStatic
        fun isInstanceCreated(): Boolean {
            return sInstance != null
        }
        
        @JvmStatic
        fun isShowing(): Boolean {
            return sInstance?.isShowing == true
        }

        private const val PREF_KEY_X = "game_bar_x"
        private const val PREF_KEY_Y = "game_bar_y"
        private const val TOUCH_SLOP = 30f
    }

    private val context: Context = context.applicationContext
    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler: Handler = Handler(Looper.getMainLooper())

    init {
        // Initialize config with context
        GameBarConfig.init(context)
    }

    private var overlayView: View? = null
    private var rootLayout: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    @Volatile
    private var isShowing = false

    // Style properties
    private var textSizeSp = 15
    private var backgroundAlpha = 255
    private var backgroundColorInt = 0xFF0D1117.toInt()
    private var cornerRadius = 30
    private var paddingDp = 4
    private var titleColorHex = "#9FCEDE"
    private var valueColorHex = "#FFB347"
    private var customTypeface: Typeface? = null
    private var overlayFormat = "full"
    private var position = "draggable"
    private var splitMode = "side_by_side"
    private var overlayWidthDp = 280
    private var overlayWidthEditing = false
    private var updateIntervalMs = 1000
    private var draggable = true

    // Display toggles
    private var showBatteryTemp = false
    private var showCpuUsage = true
    private var showCpuClock = false
    private var showCpuTemp = false
    private var showRam = false
    private var showFps = true
    private var fpsDisplayMode = "basic"
    private var showFrameTime = false
    private var showGpuUsage = true
    private var showGpuClock = false
    private var showGpuTemp = false
    private var showRamSpeed = false
    private var showRamTemp = false

    // Touch handling
    private var longPressEnabled = false
    private var longPressThresholdMs = 500L
    private var pressActive = false
    private var downX = 0f
    private var downY = 0f

    private var gestureDetector: GestureDetector? = null
    private var singleTapEnabled = true
    private var singleTapFunction = "toggle_format"
    private var doubleTapEnabled = true
    private var doubleTapFunction = "toggle_fps_logging"
    private var longPressFunction = "load_preset"
    private var bgDrawable: GradientDrawable? = null
    
    private var isRecorderBound = false
    private var remoteRecording: IRemoteRecording? = null
    private val recorderConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remoteRecording = IRemoteRecording.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remoteRecording = null
        }
    }

    private var itemSpacingDp = 8
    private var renderedStyle: List<Any>? = null
    private var renderedShape = emptyList<Pair<String, Int>>()
    private var renderedValues = emptyList<List<TextView>>()

    private var samplingThread: HandlerThread? = null
    private var samplingHandler: Handler? = null
    private var samplingGeneration = 0
    private var fpsActive = false
    private var screenReceiverRegistered = false
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) startUpdates()
            else stopUpdates()
        }
    }

    // Touch coordinates for dragging
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    init {
        bgDrawable = GradientDrawable()
        applyBackgroundStyle()
        
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (doubleTapEnabled) {
                    executeGestureFunction(doubleTapFunction)
                    return true
                }
                return super.onDoubleTap(e)
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (singleTapEnabled) {
                    executeGestureFunction(singleTapFunction)
                    return true
                }
                return super.onSingleTapConfirmed(e)
            }
        })
    }

    private fun executeGestureFunction(function: String) {
        when (function) {
            "no_action" -> {
                // Do nothing
            }
            "toggle_format" -> {
                overlayFormat = if (overlayFormat == "full") "minimal" else "full"
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit()
                    .putString("game_bar_format", overlayFormat)
                    .apply()
                Toast.makeText(context, context.getString(R.string.toast_overlay_format, overlayFormat), Toast.LENGTH_SHORT).show()
                startUpdates()
            }
            "open_settings" -> {
                openOverlaySettings()
            }
            "take_screenshot" -> {
                takeScreenshot()
            }
            "screen_record" -> {
                toggleScreenRecording()
            }
            "toggle_fps_logging" -> {
                context.startService(
                    Intent(context, FpsRecordControlOverlayService::class.java).apply {
                        action = FpsRecordControlOverlayService.ACTION_TOGGLE_RECORDING
                    }
                )
            }
            "adjust_length" -> {
                overlayWidthEditing = !overlayWidthEditing
                updateWidthEditVisualState()
                Toast.makeText(
                    context,
                    if (overlayWidthEditing) "Length edit mode enabled" else "Length edit mode disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
            "load_preset" -> {
                showPresetQuickLoader()
            }
        }
    }

    // Long press runnable
    private val longPressRunnable = Runnable {
        if (pressActive) {
            executeGestureFunction(longPressFunction)
            pressActive = false
        }
    }

    private val updateRunnable = Runnable { scheduleSample() }

    fun applyPreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        showFps = prefs.getBoolean("game_bar_fps_enable", true)
        fpsDisplayMode = prefs.getString("game_bar_fps_display_mode", "basic") ?: "basic"
        showFrameTime = prefs.getBoolean("game_bar_frame_time_enable", false)
        showBatteryTemp = prefs.getBoolean("game_bar_temp_enable", false)
        showCpuUsage = prefs.getBoolean("game_bar_cpu_usage_enable", true)
        showCpuClock = prefs.getBoolean("game_bar_cpu_clock_enable", false)
        showCpuTemp = prefs.getBoolean("game_bar_cpu_temp_enable", false)
        showRam = prefs.getBoolean("game_bar_ram_enable", false)

        showGpuUsage = prefs.getBoolean("game_bar_gpu_usage_enable", true)
        showGpuClock = prefs.getBoolean("game_bar_gpu_clock_enable", false)
        showGpuTemp = prefs.getBoolean("game_bar_gpu_temp_enable", false)

        showRamSpeed = prefs.getBoolean("game_bar_ram_speed_enable", false)
        showRamTemp = prefs.getBoolean("game_bar_ram_temp_enable", false)

        singleTapEnabled = prefs.getBoolean("game_bar_single_tap_enable", true)
        singleTapFunction = sanitizeGestureFunction(
            prefs = prefs,
            key = "game_bar_single_tap_function",
            defaultValue = "toggle_format"
        )
        doubleTapEnabled = prefs.getBoolean("game_bar_doubletap_enable", true)
        doubleTapFunction = sanitizeGestureFunction(
            prefs = prefs,
            key = "game_bar_doubletap_function",
            defaultValue = "toggle_fps_logging"
        )
        longPressFunction = sanitizeGestureFunction(
            prefs = prefs,
            key = "game_bar_longpress_function",
            defaultValue = "load_preset"
        )

        updateSplitMode(prefs.getString("game_bar_split_mode", "side_by_side") ?: "side_by_side")
        updateTextSize(prefs.getInt("game_bar_text_size", 15))
        updateBackgroundAlpha(prefs.getInt("game_bar_background_alpha", 255))
        updateBackgroundColor(prefs.getInt("game_bar_background_color", 0xFF0D1117.toInt()))
        updateCornerRadius(prefs.getInt("game_bar_corner_radius", 30))
        updatePadding(prefs.getInt("game_bar_padding", 4))
        val titleColorInt = prefs.getInt("game_bar_title_color", 0xFF9FCEDE.toInt())
        val titleColorHex = String.format("#%06X", 0xFFFFFF and titleColorInt)
        updateTitleColor(titleColorHex)
        
        val valueColorInt = prefs.getInt("game_bar_value_color", 0xFFFFB347.toInt())
        val valueColorHex = String.format("#%06X", 0xFFFFFF and valueColorInt)
        updateValueColor(valueColorHex)
        
        // Load custom font
        val fontPath = prefs.getString("game_bar_font_path", "fonts/Circular-Std-Bold.ttf") ?: "fonts/Circular-Std-Bold.ttf"
        loadCustomFont(fontPath)
        
        updateOverlayFormat(prefs.getString("game_bar_format", "full") ?: "full")
        updateUpdateInterval(prefs.getString("game_bar_update_interval", "1000") ?: "1000")
        updatePosition("draggable")
        overlayWidthDp = prefs.getInt("game_bar_overlay_width_dp", 280)
        if (isShowing && layoutParams != null && overlayView != null) {
            applyOverlayWindowWidth(layoutParams!!)
            clampToScreenBounds(layoutParams!!, overlayView)
            windowManager.updateViewLayout(overlayView, layoutParams)
        }

        val spacing = prefs.getInt("game_bar_item_spacing", 8)
        updateItemSpacing(spacing)

        longPressEnabled = prefs.getBoolean("game_bar_longpress_enable", true)
        val lpTimeoutStr = prefs.getString("game_bar_longpress_timeout", "500") ?: "500"
        try {
            val lpt = lpTimeoutStr.toLong()
            setLongPressThresholdMs(lpt)
        } catch (ignored: NumberFormatException) {}
    }

    private fun sanitizeGestureFunction(
        prefs: SharedPreferences,
        key: String,
        defaultValue: String
    ): String {
        val value = prefs.getString(key, defaultValue) ?: defaultValue
        if (value == "capture_logs") {
            prefs.edit().putString(key, "toggle_fps_logging").apply()
            return "toggle_fps_logging"
        }
        return value
    }

    fun show() {
        // Force cleanup any existing overlay before showing new one
        if (isShowing) {
            hide()
        }
        
        // Double check to make sure no overlay view exists
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                // View might already be removed
            }
            overlayView = null
        }

        bindScreenRecorder()

        applyPreferences()

        layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        )

        draggable = true
        loadSavedPosition(layoutParams!!)
        if (layoutParams!!.x == 0 && layoutParams!!.y == 0) {
            layoutParams!!.gravity = Gravity.TOP or Gravity.START
            layoutParams!!.x = 0
            layoutParams!!.y = 100
        }
        applyOverlayWindowWidth(layoutParams!!)

        val contentLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout = contentLayout
        overlayView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                contentLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        applySplitMode()
        applyBackgroundStyle()
        applyPadding()
        updateWidthEditVisualState()

        var touchMode = OverlayTouchMode.UNDECIDED
        var downRawX = 0f
        var downRawY = 0f
        var startScrollX = 0
        var startWindowWidth = 0
        overlayView?.setOnTouchListener { _, event ->
            gestureDetector?.let {
                if (it.onTouchEvent(event)) {
                    return@setOnTouchListener true
                }
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchMode = OverlayTouchMode.UNDECIDED
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startScrollX = (overlayView as? HorizontalScrollView)?.scrollX ?: 0
                    startWindowWidth = layoutParams?.width ?: 0
                    if (overlayWidthEditing) {
                        touchMode = OverlayTouchMode.WIDTH_EDIT
                        pressActive = false
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (draggable) {
                        initialX = layoutParams!!.x
                        initialY = layoutParams!!.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                    }
                    if (longPressEnabled && touchMode != OverlayTouchMode.WIDTH_EDIT) {
                        pressActive = true
                        downX = event.rawX
                        downY = event.rawY
                        handler.postDelayed(longPressRunnable, longPressThresholdMs)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDx = event.rawX - downRawX
                    val totalDy = event.rawY - downRawY
                    val absDx = Math.abs(totalDx)
                    val absDy = Math.abs(totalDy)

                    if (touchMode == OverlayTouchMode.WIDTH_EDIT) {
                        val lp = layoutParams ?: return@setOnTouchListener false
                        val newWidth = (startWindowWidth + totalDx.toInt())
                            .coerceIn(minOverlayWidthPx(), maxOverlayWidthPx())
                        lp.width = newWidth
                        overlayWidthDp = pxToDp(newWidth)
                        windowManager.updateViewLayout(overlayView, lp)
                        return@setOnTouchListener true
                    }

                    if (touchMode == OverlayTouchMode.UNDECIDED && (absDx > TOUCH_SLOP || absDy > TOUCH_SLOP)) {
                        touchMode = if (splitMode == "side_by_side" && absDx > absDy + 8f) {
                            OverlayTouchMode.HORIZONTAL_SCROLL
                        } else {
                            OverlayTouchMode.DRAG
                        }
                        if (touchMode == OverlayTouchMode.HORIZONTAL_SCROLL) {
                            pressActive = false
                            handler.removeCallbacks(longPressRunnable)
                            downRawX = event.rawX
                            startScrollX = (overlayView as? HorizontalScrollView)?.scrollX ?: 0
                        } else if (touchMode == OverlayTouchMode.DRAG) {
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            initialX = layoutParams!!.x
                            initialY = layoutParams!!.y
                        }
                    }

                    if (longPressEnabled && pressActive) {
                        val dx = Math.abs(event.rawX - downX)
                        val dy = Math.abs(event.rawY - downY)
                        if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) {
                            pressActive = false
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }

                    if (touchMode == OverlayTouchMode.HORIZONTAL_SCROLL) {
                        val currentDx = event.rawX - downRawX
                        val hsv = overlayView as? HorizontalScrollView
                        hsv?.scrollTo((startScrollX - currentDx).toInt(), 0)
                        return@setOnTouchListener true
                    }

                    if (draggable && touchMode == OverlayTouchMode.DRAG) {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        layoutParams!!.x = initialX + deltaX
                        layoutParams!!.y = initialY + deltaY
                        clampToScreenBounds(layoutParams!!, overlayView)
                        windowManager.updateViewLayout(overlayView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (longPressEnabled && pressActive) {
                        pressActive = false
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (draggable && touchMode == OverlayTouchMode.DRAG) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit()
                                .putInt(PREF_KEY_X, layoutParams!!.x)
                                .putInt(PREF_KEY_Y, layoutParams!!.y)
                                .apply()
                    }
                    if (touchMode == OverlayTouchMode.WIDTH_EDIT) {
                        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                        prefs.edit().putInt("game_bar_overlay_width_dp", overlayWidthDp).apply()
                        Toast.makeText(context, "Overlay width: ${overlayWidthDp}dp (edit mode active)", Toast.LENGTH_SHORT).show()
                    }
                    touchMode = OverlayTouchMode.UNDECIDED
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, layoutParams)
        isShowing = true
        context.registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        screenReceiverRegistered = true
        startUpdates()
    }

    fun hide() {
        isShowing = false
        if (screenReceiverRegistered) {
            context.unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        stopUpdates()
        handler.removeCallbacks(longPressRunnable)

        // Remove overlay view
        overlayView?.let { view ->
            try {
                if (view.parent != null) {
                    windowManager.removeView(view)
                }
            } catch (e: Exception) {
                // View might already be removed, log but continue cleanup
                android.util.Log.w("GameBar", "Error removing overlay view: ${e.message}")
            }
        }
        
        // Clear all references
        overlayView = null
        rootLayout = null
        layoutParams = null
        renderedStyle = null
        renderedShape = emptyList()
        renderedValues = emptyList()
        
        unbindScreenRecorder()
    }
    
    fun cleanup() {
        hide()
        
        // Remove all handler callbacks and messages
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacksAndMessages(null)
        
        // Clear all object references to prevent memory leaks
        gestureDetector = null
        bgDrawable = null
        
        // Reset all state variables
        pressActive = false
    }
    
    private fun stopUpdates() {
        samplingGeneration++
        handler.removeCallbacks(updateRunnable)
        samplingHandler?.removeCallbacksAndMessages(null)
        samplingThread?.quitSafely()
        samplingThread = null
        samplingHandler = null
        updateFpsMeter(false)
    }

    private fun startUpdates() {
        if (!isShowing || !powerManager.isInteractive) return
        if (samplingThread == null) {
            val thread = HandlerThread("GameBarStats").apply { start() }
            samplingThread = thread
            samplingHandler = Handler(thread.looper)
        }
        samplingGeneration++
        samplingHandler?.removeCallbacksAndMessages(null)
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    private fun updateFpsMeter(enabled: Boolean) {
        if (enabled == fpsActive) return
        fpsActive = enabled
        val meter = GameBarFpsMeter.getInstance(context)
        if (enabled) meter.start() else meter.stop()
    }

    private fun scheduleSample() {
        val worker = samplingHandler ?: return
        val generation = samplingGeneration
        val visible = buildSet {
            if (showFps) add(Metric.FPS)
            if (showFrameTime) add(Metric.FRAME_TIME)
            if (showBatteryTemp) add(Metric.BATTERY_TEMP)
            if (showCpuUsage) add(Metric.CPU_USAGE)
            if (showCpuClock) add(Metric.CPU_CLOCK)
            if (showCpuTemp) add(Metric.CPU_TEMP)
            if (showRam) add(Metric.RAM)
            if (showRamSpeed) add(Metric.RAM_SPEED)
            if (showRamTemp) add(Metric.RAM_TEMP)
            if (showGpuUsage) add(Metric.GPU_USAGE)
            if (showGpuClock) add(Metric.GPU_CLOCK)
            if (showGpuTemp) add(Metric.GPU_TEMP)
        }
        val advanced = showFps && fpsDisplayMode == "advanced"
        val export = GameDataExport.getInstance()
        val recording = export.isCapturing() &&
            !FpsRecordControlOverlayService.isSamplingRecording()
        val logging = if (recording) GameBarStatsSampler.loggingMetrics(context) else emptySet()
        val metrics = visible + logging
        updateFpsMeter(Metric.FPS in metrics || Metric.FRAME_TIME in metrics)
        worker.post {
            val snapshot = try {
                val packageName = if (recording) ForegroundAppDetector.getForegroundPackageName() else ""
                GameBarStatsSampler.capture(context, packageName, metrics, advanced)
            } catch (e: RuntimeException) {
                android.util.Log.w("GameBar", "Cannot sample overlay statistics", e)
                null
            }
            handler.post deliver@{
                if (generation != samplingGeneration || !isShowing) return@deliver
                if (snapshot != null) {
                    renderStats(snapshot, visible, advanced)
                    if (recording && !FpsRecordControlOverlayService.isSamplingRecording()) {
                        GameBarStatsSampler.addToExport(snapshot, logging)
                    }
                }
                handler.postDelayed(updateRunnable, updateIntervalMs.toLong())
            }
        }
    }

    private data class StatRow(val title: String, val values: List<String>, val stacked: Boolean = false)

    private fun renderStats(
        snapshot: GameBarStatsSampler.Snapshot,
        visible: Set<Metric>,
        advanced: Boolean
    ) {
        val layout = rootLayout ?: return
        val rows = buildList {
            fun addRow(metric: Metric, title: String, suffix: String = "") {
                if (metric !in visible) return
                val value = snapshot[metric]
                add(StatRow(title, listOf(if (value == "N/A") value else value + suffix)))
            }

            if (advanced) {
                val (fps1, fps01) = snapshot.fpsLows
                fun formatLow(value: Float) =
                    if (value < 0) "Collecting..." else String.format(Locale.getDefault(), "%.0f", value)
                add(StatRow("FPS Stats", listOf(
                    "FPS: ${snapshot[Metric.FPS]}",
                    "1% Low: ${formatLow(fps1)}",
                    "0.1% Low: ${formatLow(fps01)}"
                ), stacked = true))
            } else {
                addRow(Metric.FPS, "FPS")
            }
            addRow(Metric.FRAME_TIME, "Frame Time", "ms")
            addRow(Metric.BATTERY_TEMP, "Temp", "°C")
            addRow(Metric.CPU_USAGE, "CPU", "%")
            if (Metric.CPU_CLOCK in visible && snapshot.cpuFrequencies.isNotEmpty()) {
                add(StatRow("CPU Freq", snapshot.cpuFrequencies, stacked = true))
            }
            addRow(Metric.CPU_TEMP, "CPU Temp", "°C")
            addRow(Metric.RAM, "RAM", " MB")
            addRow(Metric.RAM_SPEED, "RAM Freq")
            addRow(Metric.RAM_TEMP, "RAM Temp")
            addRow(Metric.GPU_USAGE, "GPU", "%")
            addRow(Metric.GPU_CLOCK, "GPU Freq", "MHz")
            addRow(Metric.GPU_TEMP, "GPU Temp", "°C")
        }
        val shape = rows.map { it.title to it.values.size }
        val style = listOf(overlayFormat, splitMode, textSizeSp, titleColorHex, valueColorHex, itemSpacingDp)
        var changed = shape != renderedShape || style != renderedStyle
        if (changed) {
            renderedShape = shape
            renderedStyle = style
            layout.removeAllViews()
            renderedValues = rows.mapIndexed { index, row ->
                if (index > 0 && splitMode == "side_by_side" && overlayFormat == "minimal") {
                    layout.addView(createTextView(valueColorHex).apply { text = " . " })
                }
                val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                val spacing = dpToPx(context, itemSpacingDp)
                line.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(spacing, spacing / 2, spacing, spacing / 2) }
                layout.addView(line)
                if (overlayFormat == "full") {
                    line.addView(createTextView(titleColorHex).apply { text = "${row.title} " })
                }
                val valueContainer = if (row.stacked) {
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        line.addView(this)
                    }
                } else {
                    line
                }
                row.values.map {
                    createTextView(valueColorHex).apply {
                        if (row.stacked) setPadding(spacing, spacing / 4, spacing, spacing / 4)
                        valueContainer.addView(this)
                    }
                }
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            row.values.forEachIndexed { valueIndex, value ->
                val view = renderedValues[rowIndex][valueIndex]
                if (view.text != value) {
                    view.text = value
                    changed = true
                }
            }
        }
        if (!changed) return

        val params = layoutParams ?: return
        val overlay = overlayView ?: return
        val width = params.width
        val x = params.x
        val y = params.y
        applyAdaptiveOverlayWidth(params, layout)
        clampToScreenBounds(params, overlay)
        if (params.width != width || params.x != x || params.y != y) {
            windowManager.updateViewLayout(overlay, params)
        }
    }

    private fun createTextView(color: String): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
        setTextColor(runCatching { Color.parseColor(color) }.getOrDefault(Color.WHITE))
        setTypeface(this@GameBar.getTypeface(), Typeface.NORMAL)
    }

    // Public setter methods for feature toggles
    fun setShowBatteryTemp(show: Boolean) { showBatteryTemp = show }
    fun setShowCpuUsage(show: Boolean) { showCpuUsage = show }
    fun setShowCpuClock(show: Boolean) { showCpuClock = show }
    fun setShowCpuTemp(show: Boolean) { showCpuTemp = show }
    fun setShowRam(show: Boolean) { showRam = show }
    fun setShowFps(show: Boolean) { showFps = show }
    fun setShowFrameTime(show: Boolean) { showFrameTime = show }
    fun setShowGpuUsage(show: Boolean) { showGpuUsage = show }
    fun setShowGpuClock(show: Boolean) { showGpuClock = show }
    fun setShowGpuTemp(show: Boolean) { showGpuTemp = show }
    fun setShowRamSpeed(show: Boolean) { showRamSpeed = show }
    fun setShowRamTemp(show: Boolean) { showRamTemp = show }

    fun updateTextSize(sp: Int) {
        textSizeSp = sp
    }

    fun updateCornerRadius(radius: Int) {
        cornerRadius = radius
        applyBackgroundStyle()
    }

    fun updateBackgroundAlpha(alpha: Int) {
        backgroundAlpha = alpha
        applyBackgroundStyle()
    }

    fun updatePadding(dp: Int) {
        paddingDp = dp
        applyPadding()
    }

    fun updateTitleColor(hex: String) {
        titleColorHex = hex
    }

    fun updateValueColor(hex: String) {
        valueColorHex = hex
    }

    fun updateBackgroundColor(colorInt: Int) {
        backgroundColorInt = colorInt
        applyBackgroundStyle()
    }

    fun updateFont(fontPath: String) {
        loadCustomFont(fontPath)
        // Apply new typeface in-place to avoid overlay flicker
        if (isShowing) {
            applyTypefaceToOverlay()
        }
    }

    private fun loadCustomFont(fontPath: String) {
        customTypeface = if (fontPath == "default" || fontPath.isEmpty()) {
            null
        } else {
            try {
                Typeface.createFromAsset(context.assets, fontPath)
            } catch (_: RuntimeException) {
                // Presets may refer to fonts removed from the bundled collection.
                null
            }
        }
    }

    private fun getTypeface(): Typeface {
        return customTypeface ?: Typeface.DEFAULT
    }

    private fun applyTypefaceToOverlay() {
        val root = rootLayout ?: return
        val targetTypeface = getTypeface()
        fun traverse(view: View) {
            when (view) {
                is TextView -> view.setTypeface(targetTypeface, Typeface.NORMAL)
                is ViewGroup -> {
                    for (i in 0 until view.childCount) {
                        traverse(view.getChildAt(i))
                    }
                }
            }
        }
        try {
            traverse(root)
            // Ensure layout is refreshed without rebuilding
            layoutParams?.let { lp ->
                overlayView?.let { view ->
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GameBar", "Error applying typeface to overlay: ${e.message}")
        }
    }

    fun updateOverlayFormat(format: String) {
        overlayFormat = format
        if (isShowing) {
            startUpdates()
        }
    }

    fun updateItemSpacing(dp: Int) {
        itemSpacingDp = dp
        if (isShowing) {
            startUpdates()
        }
    }

    fun updatePosition(pos: String) {
        position = "draggable"
        if (isShowing && overlayView != null && layoutParams != null) {
            draggable = true
            loadSavedPosition(layoutParams!!)
            if (layoutParams!!.x == 0 && layoutParams!!.y == 0) {
                layoutParams!!.gravity = Gravity.TOP or Gravity.START
                layoutParams!!.x = 0
                layoutParams!!.y = 100
            }
            clampToScreenBounds(layoutParams!!, overlayView)
            windowManager.updateViewLayout(overlayView, layoutParams)
        }
    }

    fun updateSplitMode(mode: String) {
        splitMode = mode
        if (isShowing && overlayView != null) {
            applySplitMode()
            startUpdates()
        }
    }

    fun updateUpdateInterval(intervalStr: String) {
        try {
            updateIntervalMs = intervalStr.toInt()
        } catch (e: NumberFormatException) {
            updateIntervalMs = 1000
        }
        if (isShowing) {
            startUpdates()
        }
    }

    fun setLongPressEnabled(enabled: Boolean) {
        longPressEnabled = enabled
    }
    
    fun setLongPressThresholdMs(ms: Long) {
        longPressThresholdMs = ms
    }

    
    fun isCurrentlyShowing(): Boolean {
        return isShowing
    }

    private fun applyBackgroundStyle() {
        // Ensure we have a valid bgDrawable
        if (bgDrawable == null) {
            bgDrawable = GradientDrawable()
        }
        
        // Apply background color with proper alpha
        val red = Color.red(backgroundColorInt)
        val green = Color.green(backgroundColorInt)
        val blue = Color.blue(backgroundColorInt)
        
        // Use backgroundAlpha for transparency control
        val color = Color.argb(
            backgroundAlpha,
            red, green, blue
        )
        bgDrawable?.setColor(color)
        bgDrawable?.cornerRadius = cornerRadius.toFloat()

        // Only apply background if overlay view exists
        overlayView?.let { view ->
            view.background = bgDrawable
        }
    }

    private fun applyPadding() {
        rootLayout?.let {
            val px = dpToPx(context, paddingDp)
            it.setPadding(px, px, px, px)
        }
    }

    private fun applySplitMode() {
        rootLayout?.let {
            it.orientation = if (splitMode == "side_by_side") {
                LinearLayout.HORIZONTAL
            } else {
                LinearLayout.VERTICAL
            }
        }
    }

    private fun loadSavedPosition(lp: WindowManager.LayoutParams) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val savedX = prefs.getInt(PREF_KEY_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(PREF_KEY_Y, Int.MIN_VALUE)
        if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = savedX
            lp.y = savedY
        }
    }

    private fun applyPosition(lp: WindowManager.LayoutParams, pos: String) {
        when (pos) {
            "top_left" -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = 0
                lp.y = 100
            }
            "top_center" -> {
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = 100
            }
            "top_right" -> {
                lp.gravity = Gravity.TOP or Gravity.END
                lp.x = 0
                lp.y = 100
            }
            "bottom_left" -> {
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.x = 0
                lp.y = 100
            }
            "bottom_center" -> {
                lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                lp.y = 100
            }
            "bottom_right" -> {
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.x = 0
                lp.y = 100
            }
            else -> {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = 0
                lp.y = 100
            }
        }
    }

    private fun clampToScreenBounds(lp: WindowManager.LayoutParams, view: View?) {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val viewWidth = view?.width?.takeIf { it > 0 } ?: 0
        val viewHeight = view?.height?.takeIf { it > 0 } ?: 0
        val maxX = (screenWidth - viewWidth).coerceAtLeast(0)
        val maxY = (screenHeight - viewHeight).coerceAtLeast(0)
        lp.x = lp.x.coerceIn(0, maxX)
        lp.y = lp.y.coerceIn(0, maxY)
    }

    private fun applyOverlayWindowWidth(lp: WindowManager.LayoutParams) {
        lp.width = overlayWidthPxFromDp(overlayWidthDp)
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private fun applyAdaptiveOverlayWidth(lp: WindowManager.LayoutParams, contentLayout: View) {
        if (overlayWidthEditing) {
            // While editing, keep explicit width for interactive feedback.
            return
        }
        val desiredWidthPx = overlayWidthPxFromDp(overlayWidthDp)
        val measuredContentWidth = measureContentWidthPx(contentLayout)
        lp.width = if (measuredContentWidth in 1 until desiredWidthPx) {
            WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            desiredWidthPx
        }
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
    }

    private fun measureContentWidthPx(contentLayout: View): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        contentLayout.measure(widthSpec, heightSpec)
        return contentLayout.measuredWidth
    }

    private fun updateWidthEditVisualState() {
        val view = overlayView ?: return
        if (overlayWidthEditing) {
            val border = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = cornerRadius.toFloat()
                setStroke(dpToPx(context, 2), 0xFF58A6FF.toInt())
            }
            view.foreground = border
        } else {
            view.foreground = null
        }
    }

    private fun overlayWidthPxFromDp(dp: Int): Int {
        val px = dpToPx(context, dp.coerceAtLeast(160))
        return px.coerceIn(minOverlayWidthPx(), maxOverlayWidthPx())
    }

    private fun minOverlayWidthPx(): Int = dpToPx(context, 160)

    private fun maxOverlayWidthPx(): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return (screenWidth - dpToPx(context, 24)).coerceAtLeast(minOverlayWidthPx())
    }

    private fun pxToDp(px: Int): Int {
        val scale = context.resources.displayMetrics.density
        return (px / scale).toInt().coerceAtLeast(1)
    }

    private fun takeScreenshot() {
        try {
            // Trigger system screenshot using shell command
            Runtime.getRuntime().exec("input keyevent KEYCODE_SYSRQ")
            Toast.makeText(context, R.string.toast_screenshot_taken, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.toast_screenshot_failed, Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun bindScreenRecorder() {
        try {
            isRecorderBound = context.bindServiceAsUser(Intent().apply {
                component = ComponentName(
                    "com.android.systemui",
                    "com.android.systemui.screenrecord.RecordingService"
                )
            }, recorderConnection, Context.BIND_AUTO_CREATE, UserHandle.CURRENT)
        } catch (e: Exception) {
            android.util.Log.e("GameBar", "Failed to bind screen recorder: ${e.message}")
            isRecorderBound = false
        }
    }
    
    private fun unbindScreenRecorder() {
        if (isRecorderBound) {
            try {
                context.unbindService(recorderConnection)
            } catch (e: Exception) {
                android.util.Log.w("GameBar", "Failed to unbind screen recorder: ${e.message}")
            } finally {
                isRecorderBound = false
                remoteRecording = null
            }
        }
    }
    
    private fun toggleScreenRecording() {
        val recorder = remoteRecording
        if (recorder == null) {
            Toast.makeText(context, R.string.toast_screen_recorder_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            val isStarting = try { recorder.isStarting } catch (e: Exception) { false }
            val isRecording = try { recorder.isRecording } catch (e: Exception) { false }
            
            if (!isStarting) {
                if (!isRecording) {
                    recorder.startRecording()
                    Toast.makeText(context, R.string.toast_screen_recording_started, Toast.LENGTH_SHORT).show()
                } else {
                    recorder.stopRecording()
                    Toast.makeText(context, R.string.toast_screen_recording_stopped, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.toast_screen_recording_failed, Toast.LENGTH_SHORT).show()
            android.util.Log.e("GameBar", "Screen recording error: ${e.message}")
        }
    }
    
    private fun showPresetQuickLoader() {
        PresetQuickLoaderOverlay.getInstance(context).show()
    }
    
    private fun openOverlaySettings() {
        try {
            val intent = Intent(context, GameBarSettingsActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Exception ignored
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        val scale = context.resources.displayMetrics.density
        return Math.round(dp * scale)
    }
}
