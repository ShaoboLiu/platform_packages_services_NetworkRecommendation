/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.content.Context;
import android.content.Intent;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.SystemClock;

/**
 * Utils for wifi tests.
 */
public class TestUtil {

    /** Create a scan result with some basic properties. */
    public static ScanResult createMockScanResult(int i) {
        ScanResult scanResult = new ScanResult();
        scanResult.level = i;
        scanResult.SSID = "ssid-" + i;
        scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded("ssid-" + i);
        scanResult.BSSID = "aa:bb:cc:dd:ee:0" + i;
        scanResult.capabilities = "[ESS]";
        scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
        return scanResult;
    }

    /** Send {@link WifiManager#NETWORK_STATE_CHANGED_ACTION} broadcast. */
    public static void sendNetworkStateChanged(BroadcastReceiver broadcastReceiver,
            Context context, NetworkInfo.DetailedState detailedState) {
        Intent intent = new Intent(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        NetworkInfo networkInfo = new NetworkInfo(0, 0, "", "");
        networkInfo.setDetailedState(detailedState, "", "");
        intent.putExtra(WifiManager.EXTRA_NETWORK_INFO, networkInfo);
        broadcastReceiver.onReceive(context, intent);
    }

    /** Send {@link WifiManager#WIFI_AP_STATE_CHANGED_ACTION} broadcast. */
    public static void sendWifiApStateChanged(BroadcastReceiver broadcastReceiver, Context context,
            int wifiApState) {
        Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, wifiApState);
        broadcastReceiver.onReceive(context, intent);
    }

    /** Send {@link WifiManager#SCAN_RESULTS_AVAILABLE_ACTION} broadcast. */
    public static void sendScanResultsAvailable(BroadcastReceiver broadcastReceiver,
            Context context) {
        Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        broadcastReceiver.onReceive(context, intent);
    }

    /** Send {@link WifiManager#CONFIGURED_NETWORKS_CHANGED_ACTION} broadcast. */
    public static void sendConfiguredNetworksChanged(BroadcastReceiver broadcastReceiver,
            Context context) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        broadcastReceiver.onReceive(context, intent);
    }

    /** Send {@link WifiManager#WIFI_STATE_CHANGED_ACTION} broadcast. */
    public static void sendWifiStateChanged(BroadcastReceiver broadcastReceiver,
            Context context, int wifiState) {
        Intent intent = new Intent(WifiManager.WIFI_STATE_CHANGED_ACTION);
        intent.putExtra(WifiManager.EXTRA_WIFI_STATE, wifiState);
        broadcastReceiver.onReceive(context, intent);
    }
}
