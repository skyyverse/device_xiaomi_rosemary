/*
 * SPDX-FileCopyrightText: 2026 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.gamebar

import android.content.Context
import kotlin.math.max
import kotlin.math.min

object GameResolutionDetector {
    private val gfxSizePattern = Regex("""(\d+)\s*\(\s*\d+\s*\)\s*x\s*(\d+)""")
    private val surfaceSizePattern = Regex("""w/h:(\d+)x(\d+)""")

    fun detectGameRenderResolution(context: Context, packageName: String): String {
        val metrics = context.resources.displayMetrics
        val fallback = "${metrics.widthPixels}x${metrics.heightPixels}"
        return runCatching {
            val content = readDump("gfxinfo", packageName)

            if (content.isBlank()) {
                return@runCatching detectRenderResolutionFromSurfaceFlinger(packageName) ?: fallback
            }

            val matches = gfxSizePattern.findAll(content)
                .mapNotNull { match ->
                    val w = match.groupValues[1].toIntOrNull()
                    val h = match.groupValues[2].toIntOrNull()
                    if (w == null || h == null || w <= 0 || h <= 0) null else (w to h)
                }
                .toList()
                
            if (matches.isEmpty()) {
                return@runCatching detectRenderResolutionFromSurfaceFlinger(packageName) ?: fallback
            }

            val nativeLong = max(metrics.widthPixels, metrics.heightPixels)
            val nativeShort = min(metrics.widthPixels, metrics.heightPixels)
            val filtered = matches.filterNot { (w, h) ->
                (h == nativeLong && w == nativeShort) || (w == nativeLong && h == nativeShort) || h == nativeLong
            }
            val picked = filtered.firstOrNull() ?: matches.first()
            "${picked.first}x${picked.second}"
        }.getOrDefault(fallback)
    }

    private fun detectRenderResolutionFromSurfaceFlinger(packageName: String): String? {
        return runCatching {
            val out = readDump("SurfaceFlinger", "--layers")

            if (out.isBlank()) return@runCatching null

            val lines = out.lineSequence()
                .filter { line ->
                    line.contains(packageName, ignoreCase = true) &&
                        line.contains("(BLAST Consumer)", ignoreCase = true)
                }
                .toList()
            if (lines.isEmpty()) return@runCatching null

            val match = lines.asSequence()
                .mapNotNull { line -> surfaceSizePattern.find(line) }
                .firstOrNull()
                ?: return@runCatching null
            val w = match.groupValues[1].toIntOrNull() ?: return@runCatching null
            val h = match.groupValues[2].toIntOrNull() ?: return@runCatching null
            if (w <= 0 || h <= 0) return@runCatching null
            "${w}x${h}"
        }.getOrNull()
    }
    private fun readDump(vararg args: String): String {
        val process = ProcessBuilder(listOf("dumpsys") + args)
            .redirectErrorStream(true)
            .start()
        try {
            process.outputStream.close()
            return process.inputStream.bufferedReader().use { reader ->
                val output = reader.readText()
                if (process.waitFor() == 0) output else ""
            }
        } finally {
            process.errorStream.close()
            process.destroy()
        }
    }
}
