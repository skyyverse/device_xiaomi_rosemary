/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException

import java.util.Locale

object GameBarGpuInfo {

    // MediaTek Mali (rosemary): /proc/mtk_mali/utilization
    // contains a line such as "ACTIVE=<percent> ... OPP_IDX=<index> ..."
    private val mtkActiveRegex = Regex("""\bACTIVE=(\d+)""")
    private val mtkOppIdxRegex = Regex("""\bOPP_IDX=(\d+)""")

    // OPP index -> frequency (kHz), parsed from gpufreq_opp_dump. The table is static.
    @Volatile
    private var mtkOppFreqKhz: Map<Int, Long>? = null

    fun getGpuUsage(): String {
        val line = readLine(GameBarConfig.gpuUsagePath) ?: return "N/A"

        // MediaTek Mali
        if (line.contains("ACTIVE=")) {
            return mtkActiveRegex.find(line)?.groupValues?.get(1)?.toIntOrNull()?.toString()
                ?: "N/A"
        }

        // Qualcomm KGSL (default)
        val cleanLine = line.replace("%", "").trim()
        return try {
            val value = cleanLine.toInt()
            value.toString()
        } catch (e: NumberFormatException) {
            "N/A"
        }
    }

    fun getGpuClock(): String {
        // MediaTek Mali: the current OPP index is reported by the utilization node
        // and is mapped to a frequency through the GPU OPP table.
        val utilLine = readLine(GameBarConfig.gpuUsagePath)
        if (utilLine != null && utilLine.contains("OPP_IDX=")) {
            val oppIdx = mtkOppIdxRegex.find(utilLine)?.groupValues?.get(1)?.toIntOrNull()
                ?: return "N/A"
            val freqKhz = getMtkOppFreqKhz(oppIdx) ?: return "N/A"
            return (freqKhz / 1000).toString()
        }

        // Qualcomm KGSL (default)
        val line = readLine(GameBarConfig.gpuClockPath) ?: return "N/A"
        val cleanLine = line.trim()
        try {
            val hz = line.trim().toLong()
            val mhz = hz / GameBarConfig.gpuClockDivider
            return "$mhz".toString()
        } catch (e: NumberFormatException) {
            return "N/A"
        }
    }

    fun getGpuTemp(): String {
        val line = readLine(GameBarConfig.gpuTempPath) ?: return "N/A"
        val cleanLine = line.trim()
        try {
            val raw = line.trim().toInt()
            val celsius = raw / GameBarConfig.gpuTempDivider.toFloat()
            return String.format(Locale.getDefault(), "%.1f", celsius)
        } catch (e: NumberFormatException) {
            return "N/A"
        }
    }

    private fun getMtkOppFreqKhz(oppIdx: Int): Long? {
        mtkOppFreqKhz?.get(oppIdx)?.let { return it }

        // Not cached yet (or index not in the cached table): (re)parse the OPP table.
        val table = parseMtkOppDump(GameBarConfig.gpuClockPath) ?: return null
        mtkOppFreqKhz = table
        return table[oppIdx]
    }

    // Parses lines such as "[00] freq = 900000, volt = ..." into {0 -> 900000}
    private fun parseMtkOppDump(path: String): Map<Int, Long>? {
        val table = HashMap<Int, Long>()
        try {
            BufferedReader(FileReader(path)).use { br ->
                var line = br.readLine()
                while (line != null) {
                    if (line.contains("freq =")) {
                        try {
                            val idx = line.substring(1, line.indexOf("]")).trim().toInt()
                            val freqStart = line.indexOf("freq = ") + 7
                            var freqEnd = line.indexOf(",", freqStart)
                            if (freqEnd < 0) freqEnd = line.length
                            table[idx] = line.substring(freqStart, freqEnd).trim().toLong()
                        } catch (e: Exception) {
                            // Skip malformed line
                        }
                    }
                    line = br.readLine()
                }
            }
        } catch (e: IOException) {
            return null
        }
        return if (table.isEmpty()) null else table
    }

    private fun readLine(path: String): String? {
        return try {
            BufferedReader(FileReader(path)).use { it.readLine() }
        } catch (e: IOException) {
            null
        }
    }
}
