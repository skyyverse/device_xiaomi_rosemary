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

import android.app.ActivityTaskManager;
import android.app.IActivityTaskManager;
import android.app.Service;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThermalService extends Service {

    private static final String TAG = "ThermalService";

    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private volatile boolean mDestroyed;
    private boolean mTaskListenerRegistered;
    private int mLastWrittenState = -1; // Worker thread only.
    private ThermalUtils mThermalUtils;
    private SharedPreferences mPrefs;
    private PowerManager mPowerManager;
    private IActivityTaskManager mActivityTaskManager;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateThermalProfile();
        }
    };

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            getMainExecutor().execute(ThermalService.this::updateThermalProfile);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (prefs, key) -> {
                if (ThermalUtils.THERMAL_CONTROL.equals(key)) updateThermalProfile();
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mThermalUtils = new ThermalUtils(this);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
        mPowerManager = getSystemService(PowerManager.class);
        mActivityTaskManager = ActivityTaskManager.getService();

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        try {
            mActivityTaskManager.registerTaskStackListener(mTaskListener);
            mTaskListenerRegistered = true;
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Cannot register task listener", e);
        }
        updateThermalProfile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void updateThermalProfile() {
        if (mDestroyed) return;
        mWorker.execute(() -> {
            int state = ThermalUtils.STATE_DEFAULT;
            try {
                if (mPowerManager.isInteractive()) {
                    ActivityTaskManager.RootTaskInfo task =
                            mActivityTaskManager.getFocusedRootTaskInfo();
                    if (task != null && task.topActivity != null) {
                        state = mThermalUtils.getStateForPackage(task.topActivity.getPackageName());
                    }
                }
                if (!mDestroyed && state != mLastWrittenState) {
                    ThermalUtils.applyThermalProfile(state);
                    mLastWrittenState = state;
                }
            } catch (RemoteException | RuntimeException e) {
                Log.w(TAG, "Cannot apply thermal profile", e);
            }
        });
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        unregisterReceiver(mScreenReceiver);
        mPrefs.unregisterOnSharedPreferenceChangeListener(mPrefsListener);
        if (mTaskListenerRegistered) {
            try {
                mActivityTaskManager.unregisterTaskStackListener(mTaskListener);
            } catch (RemoteException e) {
                Log.w(TAG, "Cannot unregister task listener", e);
            }
        }
        mWorker.shutdownNow();
        super.onDestroy();
    }
}
