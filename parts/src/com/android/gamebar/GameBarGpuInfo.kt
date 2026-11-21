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

    fun sample(readUsage: Boolean, readClock: Boolean): Pair<String, String> {
        if (!readUsage && !readClock) return "N/A" to "N/A"
        val line = readLine(GameBarConfig.gpuUsagePath) ?: return "N/A" to "N/A"
        val usage = if (readUsage) mtkActiveRegex.find(line)?.groupValues?.get(1) else null
        val opp = if (readClock) mtkOppIdxRegex.find(line)?.groupValues?.get(1)?.toIntOrNull() else null
        val clock = opp?.let(::getMtkOppFreqKhz)?.div(1000)
        return (usage ?: "N/A") to (clock?.toString() ?: "N/A")
    }

    fun getGpuTemp(): String {
        val (path, divider) = GameBarConfig.getGpuTempConfig()
        val raw = path?.let(::readLine)?.trim()?.toIntOrNull() ?: return "N/A"
        return String.format(Locale.getDefault(), "%.1f", raw / divider.toFloat())
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
