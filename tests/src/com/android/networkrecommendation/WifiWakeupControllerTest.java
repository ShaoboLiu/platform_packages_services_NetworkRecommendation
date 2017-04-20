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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyList;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.support.test.runner.AndroidJUnit4;

import com.google.android.collect.Lists;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Unit tests for {@link WifiWakeupController}.
 */
@RunWith(AndroidJUnit4.class)
public class WifiWakeupControllerTest {
    private static final ScanResult OPEN_SCAN_RESULT = buildScanResult("ssid");
    private static final ScanResult SAVED_SCAN_RESULT = buildScanResult("ssid1");
    private static final ScanResult SAVED_SCAN_RESULT2 = buildScanResult("ssid2");
    private static final ScanResult SAVED_SCAN_RESULT_EXTERNAL = buildScanResult("ssid3");

    private static final WifiConfiguration SAVED_WIFI_CONFIGURATION = new WifiConfiguration();
    private static final WifiConfiguration SAVED_WIFI_CONFIGURATION2 = new WifiConfiguration();
    private static final WifiConfiguration SAVED_WIFI_CONFIGURATION_EXTERNAL =
            new WifiConfiguration();

    static {
        SAVED_WIFI_CONFIGURATION.SSID = "\"" + SAVED_SCAN_RESULT.SSID + "\"";
        makeConnectable(SAVED_WIFI_CONFIGURATION);
        SAVED_WIFI_CONFIGURATION2.SSID = "\"" + SAVED_SCAN_RESULT.SSID + "\"";
        makeConnectable(SAVED_WIFI_CONFIGURATION2);
        SAVED_WIFI_CONFIGURATION_EXTERNAL.SSID = "\"" + SAVED_SCAN_RESULT_EXTERNAL.SSID + "\"";
        SAVED_WIFI_CONFIGURATION_EXTERNAL.useExternalScores = true;
        makeConnectable(SAVED_WIFI_CONFIGURATION_EXTERNAL);
    }

    private static void makeConnectable(WifiConfiguration wifiConfiguration) {
        wifiConfiguration.status = WifiConfiguration.Status.ENABLED;
        wifiConfiguration.validatedInternetAccess = true;
    }

    private static ScanResult buildScanResult(String ssid) {
        ScanResult scanResult = new ScanResult(WifiSsid.createFromAsciiEncoded(ssid),
                "00:00:00:00:00:00", 0L, -1, null, "", 0, 0, 0);
        scanResult.informationElements = new ScanResult.InformationElement[0];
        return scanResult;
    }

    @Mock private Context mContext;
    @Mock private NotificationManager mNotificationManager;
    @Mock private ContentResolver mContentResolver;
    @Mock private WifiWakeupNetworkSelector mWifiWakeupNetworkSelector;
    @Mock private WifiManager mWifiManager;
    @Captor private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;

    private WifiWakeupController mWifiWakeupController;
    private BroadcastReceiver mBroadcastReceiver;

    private int mWifiWakeupEnabledOriginalValue;
    private int mAirplaneModeOriginalValue;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mWifiWakeupEnabledOriginalValue =
                Settings.Global.getInt(mContentResolver, Settings.Global.WIFI_WAKEUP_ENABLED, 0);
        mAirplaneModeOriginalValue =
                Settings.Global.getInt(mContentResolver, Settings.Global.AIRPLANE_MODE_ON, 0);
        Settings.Global.putInt(mContentResolver, Settings.Global.WIFI_WAKEUP_ENABLED, 1);
        Settings.Global.putInt(mContentResolver, Settings.Global.AIRPLANE_MODE_ON, 0);

        mWifiWakeupController = new WifiWakeupController(mContext, mContentResolver,
                Looper.getMainLooper(), mWifiManager, mWifiWakeupNetworkSelector);
        mWifiWakeupController.start();

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(),
                any(IntentFilter.class), anyString(), any(Handler.class));
        mBroadcastReceiver = mBroadcastReceiverCaptor.getValue();
        TestUtil.sendWifiApStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_AP_STATE_DISABLED);
    }

    @After
    public void tearDown() {
        Settings.Global.putInt(mContentResolver, Settings.Global.WIFI_WAKEUP_ENABLED,
                mWifiWakeupEnabledOriginalValue);
        Settings.Global.putInt(mContentResolver, Settings.Global.AIRPLANE_MODE_ON,
                mAirplaneModeOriginalValue);
    }

    /**
     * When the NetworkRecommendationService associated with this WifiWakeupController is unbound,
     * this WifiWakeupController should no longer function.
     */
    @Test
    public void wifiWakeupControllerStopped() {
        mWifiWakeupController.stop();

        verify(mContext).unregisterReceiver(mBroadcastReceiver);
    }

    /**
     * When Wi-Fi is disabled and a saved network is in the scan list, and then this network is not
     * in the scan list 3x, and then it is, Wi-Fi should be enabled.
     */
    @Test
    public void wifiEnabled_userDisabledWifiNearSavedNetwork_thenLeaves_thenMovesBack() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_RESULT),
                Lists.newArrayList(OPEN_SCAN_RESULT),
                Lists.newArrayList(OPEN_SCAN_RESULT),
                Lists.newArrayList(OPEN_SCAN_RESULT),
                Lists.newArrayList(SAVED_SCAN_RESULT));
        when(mWifiWakeupNetworkSelector.selectNetwork(anyMap(), anyList()))
                .thenReturn(null, SAVED_WIFI_CONFIGURATION);

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    /**
     * When Wi-Fi is disabled and a saved network is in the scan list, and then another scan result
     * comes in 3x with only a different saved network, Wi-Fi should be enabled.
     */
    @Test
    public void wifiEnabled_userDisabledWifiNearSavedNetwork_thenMovesToAnotherSavedNetwork() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION2));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_RESULT),
                Lists.newArrayList(SAVED_SCAN_RESULT2));
        when(mWifiWakeupNetworkSelector.selectNetwork(anyMap(), anyList()))
                .thenReturn(SAVED_WIFI_CONFIGURATION2);

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    /**
     * When a user disables Wi-Fi when there is a saved network marked with
     * {@link WifiConfiguration#useExternalScores} in the scan list, Wi-Fi should not be enabled if
     * the {@link WifiWakeupNetworkSelector} does not return an ssid.
     *
     * When {@link WifiWakeupNetworkSelector} does return an ssid, Wi-Fi should
     * be enabled (in absence of a scan list without the saved network) because networks marked
     * with external scores are not tracked by {@link WifiWakeupController}.
     */
    @Test
    public void wifiEnabled_userDisablesWifiNearExternallyScoredNetwork_thenNetworkIsSelected() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT_EXTERNAL));
        when(mWifiWakeupNetworkSelector.selectNetwork(anyMap(), anyList()))
                .thenReturn(null, SAVED_WIFI_CONFIGURATION_EXTERNAL);

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager).setWifiEnabled(true);
    }

    /**
     * When Wi-Fi is enabled and a saved network is in the scan list, Wi-Fi should not be enabled.
     */
    @Test
    public void wifiNotEnabled_wifiAlreadyEnabled() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiWakeupNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * When Wi-Fi is disabled and a saved network is in the scan list, but
     * {@link WifiWakeupNetworkSelector}, does not choose this network, Wi-Fi should not be enabled.
     */
    @Test
    public void wifiNotEnabled_userNearSavedNetworkButNotSelected() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));
        when(mWifiWakeupNetworkSelector.selectNetwork(anyMap(), anyList())).thenReturn(null);

        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled
     * if the user has not enabled the wifi wakeup feature.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiWakeupFeature() {
        Settings.Global.putInt(mContentResolver, Settings.Global.WIFI_WAKEUP_ENABLED, 0);
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));

        mWifiWakeupController.mContentObserver.onChange(true);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiWakeupNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled if
     * the user is in airplane mode.
     */
    @Test
    public void wifiNotEnabled_userIsInAirplaneMode() {
        Settings.Global.putInt(mContentResolver, Settings.Global.AIRPLANE_MODE_ON, 1);
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));

        mWifiWakeupController.mContentObserver.onChange(true);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiWakeupNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled and a saved network is in the scan list, Wi-Fi should not be enabled if
     * the wifi AP state is not disabled.
     */
    @Test
    public void wifiNotEnabled_wifiApStateIsNotDisabled() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));

        mWifiWakeupController.mContentObserver.onChange(true);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendWifiApStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_AP_STATE_ENABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiWakeupNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled when a saved network is the scan list, Wi-Fi should not be enabled no
     * matter how many scans are performed that include the saved network.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiNearSavedNetwork_thenDoesNotLeave() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION_EXTERNAL));
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiWakeupNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled when a saved network is in the scan list, and then that saved network
     * is removed, Wi-Fi is not enabled even if the user leaves range of that network and returns.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiNearSavedNetwork_thenRemovesNetwork_thenStays() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION),
                Lists.<WifiConfiguration>newArrayList());
        when(mWifiManager.getScanResults())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT))
                .thenReturn(Lists.<ScanResult>newArrayList())
                .thenReturn(Lists.newArrayList(SAVED_SCAN_RESULT));
        when(mWifiWakeupNetworkSelector.selectNetwork(anyMap(), anyList())).thenReturn(null);

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /**
     * If Wi-Fi is disabled when 2 saved networks are in the scan list, and then a scan result
     * comes in with only 1 saved network 3x, Wi-Fi should not be enabled.
     */
    @Test
    public void wifiNotEnabled_userDisablesWifiNear2SavedNetworks_thenLeavesRangeOfOneOfThem() {
        when(mWifiManager.getConfiguredNetworks()).thenReturn(
                Lists.newArrayList(SAVED_WIFI_CONFIGURATION, SAVED_WIFI_CONFIGURATION2));
        when(mWifiManager.getScanResults()).thenReturn(
                Lists.newArrayList(SAVED_SCAN_RESULT,
                        SAVED_SCAN_RESULT2),
                Lists.newArrayList(SAVED_SCAN_RESULT));

        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendConfiguredNetworksChanged(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext,
                WifiManager.WIFI_STATE_DISABLED);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);

        verifyZeroInteractions(mWifiWakeupNetworkSelector);
        verify(mWifiManager, never()).setWifiEnabled(true);
    }

    /** Test dump() does not crash. */
    @Test
    public void testDump() {
        StringWriter stringWriter = new StringWriter();
        mWifiWakeupController.dump(
                new FileDescriptor(), new PrintWriter(stringWriter), new String[0]);
    }
}
