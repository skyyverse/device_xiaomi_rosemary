/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import android.content.Context
import com.android.gamebar.utils.SysfsDetector
import org.lineageos.settings.R

object GameBarConfig {
    @Volatile
    private lateinit var context: Context

    fun init(ctx: Context) {
        context = ctx.applicationContext
    }

    val fpsSysfsPath: String? get() = SysfsDetector.getFpsPath()
    val cpuBasePath: String? get() = SysfsDetector.getCpuBasePath()
    val gpuUsagePath: String get() = context.getString(R.string.config_gpu_usage_path)
    val gpuClockPath: String get() = context.getString(R.string.config_gpu_clock_path)
    val ramFreqPath: String? by lazy {
        context.getString(R.string.config_ram_freq_path)
            .takeIf { it.isNotBlank() && java.io.File(it).canRead() }
    }
    val procStatPath: String get() = context.getString(R.string.config_proc_stat_path)
    val procMeminfoPath: String get() = context.getString(R.string.config_proc_meminfo_path)

    fun getBatteryTempConfig() = SysfsDetector.getBatteryTempInfo()

    fun getCpuTempConfig() = temperatureConfig(
        R.string.config_cpu_temp_path, R.integer.config_cpu_temp_divider,
        SysfsDetector::getCpuTempInfo
    )

    fun getGpuTempConfig() = temperatureConfig(
        R.string.config_gpu_temp_path, R.integer.config_gpu_temp_divider,
        SysfsDetector::getGpuTempInfo
    )

    fun getRamTempConfig() = temperatureConfig(
        R.string.config_ram_temp_path, R.integer.config_ram_temp_divider,
        SysfsDetector::getRamTempInfo
    )

    private fun temperatureConfig(
        pathResource: Int,
        dividerResource: Int,
        detect: () -> Pair<String?, Int>
    ): Pair<String?, Int> {
        val path = context.getString(pathResource)
        val divider = context.resources.getInteger(dividerResource)
        if (path.isBlank()) return null to 1000
        if (path == "dynamic") {
            val (detectedPath, detectedDivider) = detect()
            return detectedPath to (divider.takeIf { it > 0 } ?: detectedDivider)
        }
        return path to (divider.takeIf { it > 0 } ?: SysfsDetector.detectTemperatureDivider(path))
    }

    fun isSystemSupported(): Boolean =
        SysfsDetector.getBatteryTempPath() != null || SysfsDetector.getFpsPath() != null ||
            SysfsDetector.getCpuTempPath() != null
}
