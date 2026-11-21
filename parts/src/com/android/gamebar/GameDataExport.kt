/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GameDataExport private constructor() {

    interface CaptureStateListener {
        fun onCaptureStarted()
        fun onCaptureStopped()
    }

    enum class LoggingMode {
        GLOBAL, PER_APP
    }

    companion object {
        @Volatile
        private var INSTANCE: GameDataExport? = null

        @JvmStatic
        @Synchronized
        fun getInstance(): GameDataExport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GameDataExport().also { INSTANCE = it }
            }
        }
        
        private const val MAX_ROWS = 10000 // Prevent unlimited memory growth
        
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

    @Volatile
    private var capturing = false
    private var currentLoggingMode = LoggingMode.PER_APP // Default to per-app logging

    private val statsRows = ArrayDeque<Array<String>>()
    private val exportScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private var listener: CaptureStateListener? = null
    private val perAppLogManager = PerAppLogManager.getInstance()
    
    fun setCaptureStateListener(listener: CaptureStateListener?) {
        this.listener = listener
    }

    fun startCapture() {
        capturing = true
        if (currentLoggingMode == LoggingMode.GLOBAL) {
            statsRows.clear()
        }
        listener?.onCaptureStarted()
    }

    fun stopCapture() {
        capturing = false
        if (currentLoggingMode == LoggingMode.PER_APP) {
            perAppLogManager.stopAllPerAppLogging()
        }
        listener?.onCaptureStopped()
    }
    
    fun clearData() {
        statsRows.clear()
    }
    
    fun getDataSize(): Int {
        return statsRows.size
    }

    fun isCapturing(): Boolean {
        return capturing
    }

    fun addOverlayData(
        dateTime: String,
        packageName: String,
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
        if (!capturing) return

        when (currentLoggingMode) {
            LoggingMode.GLOBAL -> {
                if (statsRows.size >= MAX_ROWS) statsRows.removeFirst()

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
                statsRows.addLast(row)
            }
            LoggingMode.PER_APP -> {
                // Add data to per-app logging system
                perAppLogManager.addPerAppData(
                    packageName,
                    dateTime,
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
            }
        }
    }

    fun exportDataToCsv() {
        if (currentLoggingMode != LoggingMode.GLOBAL || statsRows.isEmpty()) return
        val rows = statsRows.toList()
        exportScope.launch {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
            val outFile = File(Environment.getExternalStorageDirectory(), "GameBar_log_$timeStamp.csv")
            try {
                outFile.bufferedWriter().use { writer ->
                    writer.appendLine(CSV_HEADER.joinToString(","))
                    rows.forEach { writer.appendLine(it.joinToString(",")) }
                }
            } catch (e: IOException) {
                Log.e("GameDataExport", "Cannot export GameBar log", e)
            }
        }
    }

    fun setLoggingMode(mode: LoggingMode) {
        currentLoggingMode = mode
    }

    fun getLoggingMode(): LoggingMode {
        return currentLoggingMode
    }

    fun getPerAppLogManager(): PerAppLogManager {
        return perAppLogManager
    }
}
