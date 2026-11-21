/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import android.app.ActivityTaskManager
import android.app.TaskStackListener
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.window.TaskFpsCallback
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException

class GameBarFpsMeter private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GameBarFpsMeter"
        private const val STALENESS_THRESHOLD_MS = 2000L
        private const val HISTORY_SIZE = 100
        private const val FPS_METHOD = "game_bar_fps_method"
        private val WHITESPACE = Regex("\\s+")

        private var instance: GameBarFpsMeter? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): GameBarFpsMeter =
            instance ?: GameBarFpsMeter(context.applicationContext).also { instance = it }
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val taskManager = ActivityTaskManager.getService()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val handler = Handler(Looper.getMainLooper())

    private var clients = 0 // Main thread only.
    private var listening = false
    private var taskId = -1
    private var fpsCallback: TaskFpsCallback? = null
    private val history = ArrayDeque<Float>(HISTORY_SIZE)
    private var currentFps = -1f
    private var lastUpdate = 0L

    private val updateTask = Runnable { updateFocusedTask() }
    private val stackListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            handler.removeCallbacks(updateTask)
            handler.post(updateTask)
        }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == FPS_METHOD) updateRegistration()
    }

    // Each consumer acquires once and releases once, on the main thread.
    fun start() {
        if (clients++ != 0) return
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        updateRegistration()
    }

    fun stop() {
        if (clients == 0 || --clients != 0) return
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        updateRegistration()
    }

    private fun updateRegistration() {
        val shouldListen = clients > 0 && prefs.getString(FPS_METHOD, "new") == "new"
        if (shouldListen) {
            if (!listening) {
                try {
                    taskManager.registerTaskStackListener(stackListener)
                    listening = true
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot monitor focused task", e)
                }
            }
            updateFocusedTask()
        } else {
            if (listening) {
                try {
                    taskManager.unregisterTaskStackListener(stackListener)
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot unregister task listener", e)
                }
                listening = false
            }
            handler.removeCallbacks(updateTask)
            clearCallback()
        }
    }

    private fun updateFocusedTask() {
        if (clients == 0 || prefs.getString(FPS_METHOD, "new") != "new") return
        try {
            val focusedTask = taskManager.getFocusedRootTaskInfo()
            val id = focusedTask?.childTaskIds?.lastOrNull() ?: focusedTask?.taskId ?: -1
            if (id == taskId && fpsCallback != null) return
            clearCallback()
            if (id < 0) return

            val callback = object : TaskFpsCallback() {
                override fun onFpsReported(fps: Float) {
                    if (fpsCallback !== this || fps <= 0f) return
                    synchronized(history) {
                        currentFps = fps
                        lastUpdate = SystemClock.elapsedRealtime()
                        addToHistory(fps)
                    }
                }
            }
            windowManager.registerTaskFpsCallback(id, context.mainExecutor, callback)
            fpsCallback = callback
            taskId = id
        } catch (e: Exception) {
            Log.w(TAG, "Cannot update FPS callback", e)
        }
    }

    private fun clearCallback() {
        val callback = fpsCallback
        fpsCallback = null
        taskId = -1
        if (callback != null) {
            try {
                windowManager.unregisterTaskFpsCallback(callback)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot unregister FPS callback", e)
            }
        }
        clearHistory()
    }

    fun getFps(): Float {
        if (prefs.getString(FPS_METHOD, "new") == "legacy") {
            val fps = readLegacyFps()
            if (fps > 0) synchronized(history) { addToHistory(fps) }
            return fps
        }
        return synchronized(history) {
            if (SystemClock.elapsedRealtime() - lastUpdate > STALENESS_THRESHOLD_MS) -1f
            else currentFps
        }
    }

    fun getPercentileLows(): Pair<Float, Float> = synchronized(history) {
        if (history.size < HISTORY_SIZE) return@synchronized -1f to -1f
        val sorted = history.sorted()
        sorted[(sorted.size * 0.01).toInt()] to sorted[(sorted.size * 0.001).toInt()]
    }

    fun clearHistory() {
        synchronized(history) {
            history.clear()
            currentFps = -1f
            lastUpdate = 0L
        }
    }

    private fun addToHistory(fps: Float) {
        if (history.size == HISTORY_SIZE) history.removeFirst()
        history.addLast(fps)
    }

    private fun readLegacyFps(): Float {
        val path = GameBarConfig.fpsSysfsPath ?: return -1f
        return try {
            File(path).bufferedReader().use { reader ->
                val line = reader.readLine()
                if (line?.startsWith("fps:") == true) {
                    line.split(WHITESPACE).getOrNull(1)?.toFloatOrNull() ?: -1f
                } else {
                    -1f
                }
            }
        } catch (_: IOException) {
            -1f
        }
    }
}
