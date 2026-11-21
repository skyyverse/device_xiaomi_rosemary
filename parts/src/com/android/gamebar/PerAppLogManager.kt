/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import org.lineageos.settings.R
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PerAppLogManager private constructor() {

    interface PerAppStateListener {
        fun onAppLoggingStarted(packageName: String)
        fun onAppLoggingStopped(packageName: String)
    }

    companion object {
        @Volatile
        private var INSTANCE: PerAppLogManager? = null
        private const val TAG = "PerAppLogManager"
        private const val PREF_PER_APP_ENABLED_APPS = "per_app_logging_enabled_apps"
        private const val MAX_ROWS_PER_APP = 5000

        @JvmStatic
        @Synchronized
        fun getInstance(): PerAppLogManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PerAppLogManager().also { INSTANCE = it }
            }
        }

        private val CSV_HEADER = arrayOf(
            "DateTime",
            "PackageName", 
            "FPS",
            "Frame_Time",
            "Battery_Temp",
            "CPU_Usage",
            "CPU_Clock",
            "CPU_Temp",
            "RAM_Usage",
            "RAM_Speed",
            "RAM_Temp",
            "GPU_Usage",
            "GPU_Clock",
            "GPU_Temp",
            "Battery_Level",
            "Power_W",
            "App_RAM_Usage"
        )
    }

    private data class LogSession(
        val resolution: Deferred<String>,
        val rows: ArrayDeque<Array<String>> = ArrayDeque()
    )

    // Session state is owned by the main thread; the worker receives detached sessions.
    private val activeLogSessions = mutableMapOf<String, LogSession>()
    private val resolutionScope = CoroutineScope(Dispatchers.IO)
    private val exportScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val handler = Handler(Looper.getMainLooper())
    private var listener: PerAppStateListener? = null
    private var currentForegroundApp: String? = null

    fun setPerAppStateListener(listener: PerAppStateListener?) {
        this.listener = listener
    }

    fun getEnabledApps(context: Context): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_PER_APP_ENABLED_APPS, emptySet()) ?: emptySet()
    }

    fun setAppLoggingEnabled(context: Context, packageName: String, enabled: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getStringSet(PREF_PER_APP_ENABLED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        
        if (enabled) {
            current.add(packageName)
        } else {
            current.remove(packageName)
            // Stop logging for this app if it's currently active
            stopLoggingForApp(packageName)
        }
        
        prefs.edit().putStringSet(PREF_PER_APP_ENABLED_APPS, current).apply()
    }

    fun isAppLoggingEnabled(context: Context, packageName: String): Boolean {
        return getEnabledApps(context).contains(packageName)
    }

    fun onAppBecameForeground(context: Context, packageName: String) {
        if (currentForegroundApp == packageName) return
        
        // Stop logging for previous app if it was being logged
        currentForegroundApp?.let { previousApp ->
            if (isAppLoggingActive(previousApp)) {
                stopLoggingForApp(previousApp)
            }
        }

        currentForegroundApp = packageName
        
        // Start logging for new app if enabled AND per-app logging mode is active
        // Note: Per-app logging works independently of GameBar overlay visibility
        val gameDataExport = GameDataExport.getInstance()
        if (isAppLoggingEnabled(context, packageName) && 
            gameDataExport.getLoggingMode() == GameDataExport.LoggingMode.PER_APP &&
            gameDataExport.isCapturing()) {
            startLoggingForApp(packageName)
        }
    }

    fun onAppWentToBackground(context: Context, packageName: String) {
        if (currentForegroundApp == packageName) {
            currentForegroundApp = null
            if (isAppLoggingActive(packageName)) {
                stopLoggingForApp(packageName)
            }
        }
    }

    private fun startLoggingForApp(packageName: String) {
        if (activeLogSessions.containsKey(packageName)) return

        Log.d(TAG, "Starting logging for app: $packageName")
        activeLogSessions[packageName] = createSession(packageName)

        listener?.onAppLoggingStarted(packageName)
        
        // Show toast notification
        handler.post {
            try {
                val context = android.app.ActivityThread.currentApplication()
                context?.let {
                    val pm = it.packageManager
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        android.widget.Toast.makeText(it, "$appName GameBar log started", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(it, "$packageName GameBar log started", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show start toast for $packageName", e)
            }
        }
    }

    private fun stopLoggingForApp(packageName: String) {
        val session = activeLogSessions.remove(packageName) ?: return
        if (session.rows.isEmpty()) {
            session.resolution.cancel()
            return
        }
        exportScope.launch {
            val resolution = session.resolution.await()
            exportPerAppDataToCsv(packageName, resolution, session.rows)
        }

        listener?.onAppLoggingStopped(packageName)

        // Show toast notification
        handler.post {
            try {
                val context = android.app.ActivityThread.currentApplication()
                context?.let {
                    val pm = it.packageManager
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        android.widget.Toast.makeText(it, "$appName GameBar log ended", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(it, "$packageName GameBar log ended", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show stop toast for $packageName", e)
            }
        }
    }

    fun isAppLoggingActive(packageName: String): Boolean {
        return activeLogSessions.containsKey(packageName)
    }

    fun addPerAppData(
        packageName: String,
        dateTime: String,
        fps: String,
        frameTime: String,
        batteryTemp: String,
        cpuUsage: String,
        cpuClock: String,
        cpuTemp: String,
        ramUsage: String,
        ramSpeed: String,
        ramTemp: String,
        gpuUsage: String,
        gpuClock: String,
        gpuTemp: String,
        batteryLevel: String,
        powerWatt: String,
        appRamUsage: String
    ) {
        val rows = activeLogSessions[packageName]?.rows ?: return
        if (rows.size >= MAX_ROWS_PER_APP) rows.removeFirst()

        val row = arrayOf(
            dateTime,
            packageName,
            fps,
            frameTime,
            batteryTemp,
            cpuUsage,
            cpuClock,
            cpuTemp,
            ramUsage,
            ramSpeed,
            ramTemp,
            gpuUsage,
            gpuClock,
            gpuTemp,
            batteryLevel,
            powerWatt,
            appRamUsage
        )
        rows.addLast(row)
    }

    private fun createSession(packageName: String): LogSession {
        return LogSession(resolutionScope.async {
            val context = android.app.ActivityThread.currentApplication()
            if (context == null) "Unknown"
            else GameResolutionDetector.detectGameRenderResolution(context, packageName)
        })
    }

    private fun exportPerAppDataToCsv(
        packageName: String,
        resolution: String,
        rows: List<Array<String>>
    ) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val name = "${packageName}_GameBar_log_${resolution}"
        val directory = getLogsDirectory()
        var outFile = File(directory, "${name}_$timestamp.csv")
        var duplicate = 1
        while (outFile.exists()) {
            outFile = File(directory, "${name}_${duplicate++}_$timestamp.csv")
        }

        val file = AtomicFile(outFile)
        try {
            val stream = file.startWrite()
            try {
                val writer = stream.bufferedWriter()
                writer.appendLine(CSV_HEADER.joinToString(","))
                rows.forEach { writer.appendLine(it.joinToString(",")) }
                writer.flush()
                file.finishWrite(stream)
            } catch (e: IOException) {
                file.failWrite(stream)
                throw e
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to export per-app log for $packageName", e)
        }
    }

    fun getPerAppLogFiles(packageName: String): List<File> {
        val files = getLogsDirectory().listFiles { file ->
            file.name.startsWith("${packageName}_GameBar_log_") && file.name.endsWith(".csv")
        }
        return files?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun getAllPerAppLogFiles(): Map<String, List<File>> {
        val files = getLogsDirectory().listFiles { file ->
            file.name.contains("_GameBar_log_") && 
            file.name.endsWith(".csv") && 
            !file.name.startsWith("GameBar_log_") // Exclude global logs
        } ?: emptyArray()

        return files.groupBy { file ->
            // Extract package name from filename: packageName_GameBar_log_timestamp.csv
            val fileName = file.name
            val endIndex = fileName.indexOf("_GameBar_log_")
            if (endIndex > 0) fileName.substring(0, endIndex) else "unknown"
        }.mapValues { (_, fileList) ->
            fileList.sortedByDescending { it.lastModified() }
        }
    }

    fun getCurrentlyLoggingApps(): Set<String> {
        return activeLogSessions.keys.toSet()
    }

    private fun getLogsDirectory(): File {
        val context = android.app.ActivityThread.currentApplication()
        val baseDir = context?.filesDir ?: File(".")
        val dir = File(baseDir, "fps_records")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Start manual logging for an app via double-tap (even if not in enabled apps list)
     */
    fun startManualLoggingForApp(packageName: String) {
        if (activeLogSessions.containsKey(packageName)) {
            // Already logging, ignore
            return
        }

        Log.d(TAG, "Starting manual logging for app: $packageName")
        activeLogSessions[packageName] = createSession(packageName)

        listener?.onAppLoggingStarted(packageName)
        
        // Show toast notification
        handler.post {
            try {
                val context = android.app.ActivityThread.currentApplication()
                context?.let {
                    val pm = it.packageManager
                    try {
                        val appInfo = pm.getApplicationInfo(packageName, 0)
                        val appName = pm.getApplicationLabel(appInfo).toString()
                        android.widget.Toast.makeText(it, it.getString(R.string.toast_manual_logging_started, appName), android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(it, it.getString(R.string.toast_manual_logging_started_pkg, packageName), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show start toast for $packageName", e)
            }
        }
    }
    
    /**
     * Stop manual logging for an app via double-tap
     */
    fun stopManualLoggingForApp(packageName: String) {
        stopLoggingForApp(packageName)
    }

    fun stopAllPerAppLogging() {
        val appsToStop = activeLogSessions.keys.toList()
        appsToStop.forEach { packageName ->
            stopLoggingForApp(packageName)
        }
    }
    
    fun handlePerAppLoggingStarted(context: Context) {
        // When per-app logging is started, check if current foreground app should be logged
        val foregroundApp = ForegroundAppDetector.getForegroundPackageName()
        if (foregroundApp != "Unknown" && foregroundApp.isNotEmpty()) {
            currentForegroundApp = foregroundApp
            if (isAppLoggingEnabled(context, foregroundApp)) {
                startLoggingForApp(foregroundApp)
            }
        }
    }
}
