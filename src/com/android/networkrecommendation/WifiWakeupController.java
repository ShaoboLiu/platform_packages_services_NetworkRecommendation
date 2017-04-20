/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.networkrecommendation;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles enabling Wi-Fi for the Wi-Fi Wakeup feature.
 *
 * <p>
 * This class enables Wi-Fi when the user is near a network that would would autojoined if Wi-Fi
 * were enabled. When a user disables Wi-Fi, Wi-Fi Wakeup will not enable Wi-Fi until the
 * user's context has changed. For saved networks, this context change is defined by the user
 * leaving the range of the saved SSIDs that were in range when the user disabled Wi-Fi.
 *
 * @hide
 */
public class WifiWakeupController {
    private static final String TAG = "WifiWakeupController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /** Number of scans to ensure that a previously in range AP is now out of range. */
    private static final int NUM_SCANS_TO_CONFIRM_AP_LOSS = 3;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private final WifiManager mWifiManager;
    private final WifiWakeupNetworkSelector mWifiWakeupNetworkSelector;
    private final Handler mHandler;
    private final AtomicBoolean mStarted;
    @VisibleForTesting final ContentObserver mContentObserver;

    private final Map<String, WifiConfiguration> mSavedNetworks = new ArrayMap<>();
    private final Set<String> mSavedSsidsInLastScan = new ArraySet<>();
    private final Set<String> mSavedSsids = new ArraySet<>();
    private final Map<String, Integer> mSavedSsidsOnDisable = new ArrayMap<>();
    private int mWifiState;
    private int mWifiApState;
    private boolean mWifiWakeupEnabled;
    private boolean mAirplaneModeEnabled;

    WifiWakeupController(Context context, ContentResolver contentResolver, Looper looper,
            WifiManager wifiManager, WifiWakeupNetworkSelector wifiWakeupNetworkSelector) {
        mContext = context;
        mContentResolver = contentResolver;
        mHandler = new Handler(looper);
        mStarted = new AtomicBoolean(false);
        mWifiManager = wifiManager;
        mWifiWakeupNetworkSelector = wifiWakeupNetworkSelector;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                mWifiWakeupEnabled = Settings.Global.getInt(mContentResolver,
                        Settings.Global.WIFI_WAKEUP_ENABLED, 0) == 1;
                mAirplaneModeEnabled = Settings.Global.getInt(mContentResolver,
                        Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
            }
        };
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mWifiWakeupEnabled) {
                return;
            }

            if (WifiManager.WIFI_AP_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                handleWifiApStateChanged(intent);
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                handleWifiStateChanged(intent);
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                handleScanResultsAvailable();
            } else if (WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION.equals(intent.getAction())) {
                handleConfiguredNetworksChanged();
            }
        }
    };

    /** Starts {@link WifiWakeupController}. */
    public void start() {
        if (!mStarted.compareAndSet(false, true)) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        // TODO(b/33695273): conditionally register this receiver based on wifi enabled setting
        mContext.registerReceiver(mBroadcastReceiver, filter, null, mHandler);
        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.WIFI_WAKEUP_ENABLED), true, mContentObserver);
        mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.AIRPLANE_MODE_ON), true, mContentObserver);
        mContentObserver.onChange(true);
        handleConfiguredNetworksChanged();
    }

    /** Stops {@link WifiWakeupController}. */
    public void stop() {
        if (!mStarted.compareAndSet(true, false)) {
            return;
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContentResolver.unregisterContentObserver(mContentObserver);
    }

    private void handleWifiApStateChanged(Intent intent) {
        mWifiApState = intent.getIntExtra(WifiManager.EXTRA_WIFI_AP_STATE,
                WifiManager.WIFI_AP_STATE_DISABLED);
    }

    private void handleConfiguredNetworksChanged() {
        List<WifiConfiguration> wifiConfigurations = mWifiManager.getConfiguredNetworks();
        if (wifiConfigurations == null) {
            return;
        }

        mSavedNetworks.clear();
        mSavedSsids.clear();
        for (int i = 0; i < wifiConfigurations.size(); i++) {
            WifiConfiguration wifiConfiguration = wifiConfigurations.get(i);
            if (wifiConfiguration.status != WifiConfiguration.Status.ENABLED
                    || wifiConfiguration.useExternalScores) {
                continue; // Ignore disabled and externally scored networks.
            }
            if (wifiConfiguration.hasNoInternetAccess()
                    || wifiConfiguration.isNoInternetAccessExpected()) {
                continue; // Ignore networks that will likely not have internet access.
            }
            String ssid = WifiConfigurationUtil.removeDoubleQuotes(wifiConfiguration);
            if (TextUtils.isEmpty(ssid)) {
                continue;
            }
            mSavedNetworks.put(ssid, wifiConfiguration);
            mSavedSsids.add(ssid);
        }
        mSavedSsidsInLastScan.retainAll(mSavedSsids);
    }

    private void handleWifiStateChanged(Intent intent) {
        mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
        switch (mWifiState) {
            case WifiManager.WIFI_STATE_ENABLED:
                mSavedSsidsOnDisable.clear();
                break;
            case WifiManager.WIFI_STATE_DISABLED:
                for (String ssid : mSavedSsidsInLastScan) {
                    mSavedSsidsOnDisable.put(ssid, NUM_SCANS_TO_CONFIRM_AP_LOSS);
                }
                break;
        }
    }

    private void handleScanResultsAvailable() {
        List<ScanResult> scanResults = mWifiManager.getScanResults();
        mSavedSsidsInLastScan.clear();
        for (int i = 0; i < scanResults.size(); i++) {
            String ssid = scanResults.get(i).SSID;
            if (mSavedSsids.contains(ssid)) {
                mSavedSsidsInLastScan.add(ssid);
            }
        }

        if (mAirplaneModeEnabled
                || mWifiState != WifiManager.WIFI_STATE_DISABLED
                || mWifiApState != WifiManager.WIFI_AP_STATE_DISABLED) {
            return;
        }

        // Update mSavedSsidsOnDisable to remove ssids that the user has moved away from.
        for (Map.Entry<String, Integer> entry : mSavedSsidsOnDisable.entrySet()) {
            if (mSavedSsidsInLastScan.contains(entry.getKey())) {
                mSavedSsidsOnDisable.put(entry.getKey(), NUM_SCANS_TO_CONFIRM_AP_LOSS);
            } else {
                if (entry.getValue() > 1) {
                    mSavedSsidsOnDisable.put(entry.getKey(), entry.getValue() - 1);
                } else {
                    mSavedSsidsOnDisable.remove(entry.getKey());
                }
            }
        }

        if (!mSavedSsidsOnDisable.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "Latest scan result contains ssids from the disabled set: "
                        + mSavedSsidsOnDisable);
            }
            return;
        }

        WifiConfiguration selectedNetwork = mWifiWakeupNetworkSelector.selectNetwork(mSavedNetworks,
                scanResults);
        if (selectedNetwork != null) {
            // TODO(b/33677088): show notification for wifi enablement
            if (DEBUG) {
                Log.d(TAG, "Enabling wifi for ssid: " + selectedNetwork.SSID);
            }
            mWifiManager.setWifiEnabled(true /* enabled */);
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mStarted " + mStarted.get());
        pw.println("mWifiWakeupEnabled: " + mWifiWakeupEnabled);
        pw.println("mSavedSsids: " + mSavedSsids);
        pw.println("mSavedSsidsInLastScan: " + mSavedSsidsInLastScan);
        pw.println("mSavedSsidsOnDisable: " + mSavedSsidsOnDisable);
    }
}
