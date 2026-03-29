/*
 * Copyright (C) 2025 kenway214
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.gamebar;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class GameBarGpuInfo {

    private static final String GPU_UTIL_PATH = "/proc/mtk_mali/utilization";
    private static final String GPU_OPP_DUMP_PATH = "/proc/gpufreq/gpufreq_opp_dump";

    public static String getGpuUsage() {
        String line = readLine(GPU_UTIL_PATH);
        if (line == null) {
            return "N/A";
        }
        
        try {
            if (line.contains("ACTIVE=")) {
                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith("ACTIVE=")) {
                        String activeStr = part.substring(7).trim();
                        int active = Integer.parseInt(activeStr);
                        return String.valueOf(active);
                    }
                }
            }
            return "N/A";
        } catch (NumberFormatException e) {
            return "N/A";
        }
    }

    public static String getGpuClock() {
        String utilLine = readLine(GPU_UTIL_PATH);
        if (utilLine == null) {
            return "N/A";
        }

        int oppIdx = -1;
        try {
            if (utilLine.contains("OPP_IDX=")) {
                String[] parts = utilLine.split("\\s+");
                for (String part : parts) {
                    if (part.startsWith("OPP_IDX=")) {
                        oppIdx = Integer.parseInt(part.substring(8).trim());
                        break;
                    }
                }
            }
        } catch (NumberFormatException e) {
            return "N/A";
        }

        if (oppIdx < 0) {
            return "N/A";
        }

        Map<Integer, Long> oppFreqMap = parseOppDump();
        if (oppFreqMap == null || !oppFreqMap.containsKey(oppIdx)) {
            return "N/A";
        }

        long freqKhz = oppFreqMap.get(oppIdx);
        long mhz = freqKhz / 1_000;
        return String.valueOf(mhz);
    }

    private static Map<Integer, Long> parseOppDump() {
        Map<Integer, Long> oppMap = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(GPU_OPP_DUMP_PATH))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("freq =")) {
                    try {
                        int idx = Integer.parseInt(line.substring(1, line.indexOf("]")));
                        int freqStart = line.indexOf("freq = ") + 7;
                        int freqEnd = line.indexOf(",", freqStart);
                        long freq = Long.parseLong(line.substring(freqStart, freqEnd).trim());
                        oppMap.put(idx, freq);
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return oppMap.isEmpty() ? null : oppMap;
    }

    private static String readLine(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
