/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import android.app.ActivityTaskManager
import android.app.Service
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.preference.PreferenceManager

class GameBarMonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val perAppLogManager = PerAppLogManager.getInstance()
    private val taskManager = ActivityTaskManager.getService()
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private var listening = false
    private var lastForegroundApp = "Unknown"

    private val refresh = Runnable { if (listening) updateForegroundApp() }
    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            handler.removeCallbacks(refresh)
            handler.post(refresh)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateMonitoring()
        }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "game_bar_enable" || key == "game_bar_auto_enable" ||
            key == GameBarPerAppConfigFragment.PREF_AUTO_APPS) {
            updateMonitoring()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateMonitoring()
        return if (prefs.getBoolean("game_bar_auto_enable", false)) START_STICKY else START_NOT_STICKY
    }

    private fun updateMonitoring() {
        val autoEnabled = prefs.getBoolean("game_bar_auto_enable", false)
        if (!autoEnabled || !powerManager.isInteractive) {
            stopMonitoring()
            if (!prefs.getBoolean("game_bar_enable", false) && GameBar.isShowing()) {
                GameBar.getInstance(this).hide()
            }
            if (!autoEnabled) stopSelf()
            return
        }

        if (!listening) {
            try {
                taskManager.registerTaskStackListener(taskListener)
                listening = true
            } catch (e: Exception) {
                Log.w("GameBarMonitorService", "Cannot register task listener", e)
            }
        }
        updateForegroundApp()
    }

    private fun updateForegroundApp() {
        val foreground = ForegroundAppDetector.getForegroundPackageName()
        val masterEnabled = prefs.getBoolean("game_bar_enable", false)
        val autoApps = prefs.getStringSet(GameBarPerAppConfigFragment.PREF_AUTO_APPS, emptySet())
        val shouldShow = masterEnabled || autoApps?.contains(foreground) == true
        if (shouldShow && !GameBar.isShowing()) {
            GameBar.getInstance(this).apply {
                applyPreferences()
                show()
            }
        } else if (!shouldShow && GameBar.isShowing()) {
            GameBar.getInstance(this).hide()
        }

        if (foreground != lastForegroundApp) {
            clearForegroundApp()
            if (foreground != "Unknown") {
                perAppLogManager.onAppBecameForeground(this, foreground)
                lastForegroundApp = foreground
            }
        }
    }

    private fun clearForegroundApp() {
        if (lastForegroundApp != "Unknown") {
            perAppLogManager.onAppWentToBackground(this, lastForegroundApp)
            lastForegroundApp = "Unknown"
        }
    }

    private fun stopMonitoring() {
        if (listening) {
            listening = false
            try {
                taskManager.unregisterTaskStackListener(taskListener)
            } catch (e: Exception) {
                Log.w("GameBarMonitorService", "Cannot unregister task listener", e)
            }
        }
        handler.removeCallbacks(refresh)
        clearForegroundApp()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMonitoring()
        unregisterReceiver(screenReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (!prefs.getBoolean("game_bar_enable", false)) {
            GameBar.destroyInstance()
        }
        super.onDestroy()
    }
}
