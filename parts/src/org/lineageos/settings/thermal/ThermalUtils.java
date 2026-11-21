/*
 * Copyright (C) 2020 The LineageOS Project
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

package org.lineageos.settings.thermal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemProperties;
import android.os.UserHandle;

import androidx.preference.PreferenceManager;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

public final class ThermalUtils {

    static final String THERMAL_CONTROL = "thermal_control";
    private static final String THERMAL_PROFILE_PROPERTY = "sys.rosemary.thermal.profile";

    protected static final int STATE_DEFAULT = 0;
    protected static final int STATE_BENCHMARK = 1;
    protected static final int STATE_BROWSER = 2;
    protected static final int STATE_CAMERA = 3;
    protected static final int STATE_DIALER = 4;
    protected static final int STATE_GAMING = 5;
    protected static final int STATE_NAVIGATION = 6;
    protected static final int STATE_STREAMING = 7;
    protected static final int STATE_VIDEO = 8;

    private static final String[] PROFILE_NAMES = {
            "", "thermal.benchmark", "thermal.browser", "thermal.camera", "thermal.dialer",
            "thermal.gaming", "thermal.navigation", "thermal.streaming", "thermal.video"
    };
    private static final String[] PROFILE_VALUES = {
            "0", "10", "11", "12", "8", "9", "19", "14", "21"
    };

    private final SharedPreferences mSharedPrefs;
    private String mCachedValue;
    private ConcurrentHashMap<String, Integer> mPackageStates = new ConcurrentHashMap<>();

    protected ThermalUtils(Context context) {
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static void startService(Context context) {
        context.startServiceAsUser(new Intent(context, ThermalService.class), UserHandle.CURRENT);
    }

    private static Set<String> packagesForState(String[] profiles, int state) {
        String name = PROFILE_NAMES[state] + "=";
        Set<String> packages = new LinkedHashSet<>();
        for (String profile : profiles) {
            if (!profile.startsWith(name)) continue;
            for (String packageName : profile.substring(name.length()).split(",")) {
                if (!packageName.isEmpty()) packages.add(packageName);
            }
        }
        return packages;
    }

    protected void writePackage(String packageName, int mode) {
        String[] profiles = mSharedPrefs.getString(THERMAL_CONTROL, "").split(":");
        StringJoiner updated = new StringJoiner(":");
        for (int state = STATE_BENCHMARK; state < PROFILE_NAMES.length; state++) {
            Set<String> packages = packagesForState(profiles, state);
            packages.remove(packageName);
            if (state == mode) packages.add(packageName);
            StringBuilder profile = new StringBuilder(PROFILE_NAMES[state]).append('=');
            for (String name : packages) profile.append(name).append(',');
            updated.add(profile);
        }
        mSharedPrefs.edit().putString(THERMAL_CONTROL, updated.toString()).apply();
    }

    protected synchronized int getStateForPackage(String packageName) {
        if (packageName == null) return STATE_DEFAULT;
        String value = mSharedPrefs.getString(THERMAL_CONTROL, "");
        if (!value.equals(mCachedValue)) {
            String[] profiles = value.split(":");
            ConcurrentHashMap<String, Integer> states = new ConcurrentHashMap<>();
            for (int state = STATE_BENCHMARK; state < PROFILE_NAMES.length; state++) {
                for (String name : packagesForState(profiles, state)) {
                    states.putIfAbsent(name, state);
                }
            }
            mPackageStates = states;
            mCachedValue = value;
        }
        return mPackageStates.getOrDefault(packageName, STATE_DEFAULT);
    }

    static void applyThermalProfile(int state) {
        SystemProperties.set(THERMAL_PROFILE_PROPERTY, PROFILE_VALUES[state]);
    }
}
