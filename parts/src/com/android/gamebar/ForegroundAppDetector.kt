/*
 * SPDX-FileCopyrightText: 2025 kenway214
 * SPDX-License-Identifier: Apache-2.0
 */


package com.android.gamebar

import android.app.ActivityTaskManager
import android.util.Log

object ForegroundAppDetector {
    fun getForegroundPackageName(): String {
        return try {
            ActivityTaskManager.getService().getFocusedRootTaskInfo()
                ?.topActivity?.packageName ?: "Unknown"
        } catch (e: Exception) {
            Log.w("ForegroundAppDetector", "Cannot query focused task", e)
            "Unknown"
        }
    }
}
