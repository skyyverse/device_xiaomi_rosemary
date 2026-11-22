/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import android.content.Context
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GameBarStatsSampler {

    enum class Metric(val logPreference: String?) {
        FPS(GameBarLoggingPrefs.PREF_LOG_FPS),
        FRAME_TIME(GameBarLoggingPrefs.PREF_LOG_FRAME_TIME),
        BATTERY_TEMP(GameBarLoggingPrefs.PREF_LOG_BATTERY_TEMP),
        CPU_USAGE(GameBarLoggingPrefs.PREF_LOG_CPU_USAGE),
        CPU_CLOCK(GameBarLoggingPrefs.PREF_LOG_CPU_CLOCK),
        CPU_TEMP(GameBarLoggingPrefs.PREF_LOG_CPU_TEMP),
        RAM(GameBarLoggingPrefs.PREF_LOG_RAM),
        RAM_SPEED(GameBarLoggingPrefs.PREF_LOG_RAM_SPEED),
        RAM_TEMP(GameBarLoggingPrefs.PREF_LOG_RAM_TEMP),
        GPU_USAGE(GameBarLoggingPrefs.PREF_LOG_GPU_USAGE),
        GPU_CLOCK(GameBarLoggingPrefs.PREF_LOG_GPU_CLOCK),
        GPU_TEMP(GameBarLoggingPrefs.PREF_LOG_GPU_TEMP),
        BATTERY_LEVEL(null),
        POWER_WATT(null),
        APP_RAM(GameBarLoggingPrefs.PREF_LOG_RAM)
    }

    data class Snapshot(
        val dateTime: String,
        val packageName: String,
        val values: Map<Metric, String>,
        val cpuFrequencies: List<String>,
        val fpsLows: Pair<Float, Float>
    ) {
        operator fun get(metric: Metric): String = values[metric] ?: "N/A"
    }

    fun loggingMetrics(context: Context): Set<Metric> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return Metric.entries.filterTo(mutableSetOf()) { metric ->
            metric.logPreference?.let { prefs.getBoolean(it, true) } ?: true
        }
    }

    fun capture(
        context: Context,
        packageName: String,
        metrics: Set<Metric>,
        advancedFps: Boolean = false
    ): Snapshot {
        GameBarConfig.init(context)
        val values = mutableMapOf<Metric, String>()
        fun sample(metric: Metric, read: () -> String) {
            if (metric in metrics) values[metric] = read()
        }

        val fpsMeter = GameBarFpsMeter.getInstance(context)
        val fps = if (Metric.FPS in metrics || Metric.FRAME_TIME in metrics) {
            fpsMeter.getFps()
        } else {
            -1f
        }
        sample(Metric.FPS) { if (fps >= 0f) String.format(Locale.US, "%.0f", fps) else "N/A" }
        sample(Metric.FRAME_TIME) {
            if (fps > 0f) String.format(Locale.US, "%.2f", 1000f / fps) else "N/A"
        }

        val battery = if (Metric.BATTERY_TEMP in metrics || Metric.BATTERY_LEVEL in metrics ||
            Metric.POWER_WATT in metrics) GameBarBatteryInfo.getBatterySnapshot(context) else null
        sample(Metric.BATTERY_TEMP) { GameBarBatteryInfo.getBatteryTempC(battery) }
        sample(Metric.BATTERY_LEVEL) { GameBarBatteryInfo.getBatteryLevelPercent(battery) }
        sample(Metric.POWER_WATT) { GameBarBatteryInfo.getBatteryPowerWatt(context, battery) }

        val (gpuUsage, gpuClock) =
            GameBarGpuInfo.sample(Metric.GPU_USAGE in metrics, Metric.GPU_CLOCK in metrics)
        sample(Metric.GPU_USAGE) { gpuUsage }
        sample(Metric.GPU_CLOCK) { gpuClock }
        sample(Metric.GPU_TEMP) { GameBarGpuInfo.getGpuTemp() }

        val cpuFrequencies =
            if (Metric.CPU_CLOCK in metrics) GameBarCpuInfo.getCpuFrequencies() else emptyList()
        sample(Metric.CPU_CLOCK) { cpuFrequencies.joinToString("; ").ifEmpty { "N/A" } }
        sample(Metric.CPU_USAGE) { GameBarCpuInfo.getCpuUsage() }
        sample(Metric.CPU_TEMP) { GameBarCpuInfo.getCpuTemp() }
        sample(Metric.RAM) { GameBarMemInfo.getRamUsage() }
        sample(Metric.RAM_SPEED) { GameBarMemInfo.getRamSpeed() }
        sample(Metric.RAM_TEMP) { GameBarMemInfo.getRamTemp() }
        sample(Metric.APP_RAM) { GameBarMemInfo.getAppRamUsage(context, packageName) }
        val dateTime = if (packageName.isNotEmpty()) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        } else {
            ""
        }
        val fpsLows = if (advancedFps) fpsMeter.getPercentileLows() else -1f to -1f
        return Snapshot(dateTime, packageName, values, cpuFrequencies, fpsLows)
    }

    fun addToExport(snapshot: Snapshot, logging: Set<Metric>) {
        fun logged(metric: Metric) = if (metric in logging) snapshot[metric] else "N/A"
        GameDataExport.getInstance().addOverlayData(
            snapshot.dateTime,
            snapshot.packageName,
            logged(Metric.FPS),
            logged(Metric.FRAME_TIME),
            logged(Metric.BATTERY_TEMP),
            logged(Metric.CPU_USAGE),
            logged(Metric.CPU_CLOCK),
            logged(Metric.CPU_TEMP),
            logged(Metric.RAM),
            logged(Metric.RAM_SPEED),
            logged(Metric.RAM_TEMP),
            logged(Metric.GPU_USAGE),
            logged(Metric.GPU_CLOCK),
            logged(Metric.GPU_TEMP),
            logged(Metric.BATTERY_LEVEL),
            logged(Metric.POWER_WATT),
            logged(Metric.APP_RAM)
        )
    }
}
