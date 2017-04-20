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

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.NetworkInfo;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.ActionListener;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.networkrecommendation.WifiNotificationController}.
 */
@RunWith(AndroidJUnit4.class)
public class WifiNotificationControllerTest {
    @Mock private Context mContext;
    @Mock private WifiManager mWifiManager;
    @Mock private NotificationManager mNotificationManager;
    @Mock private WifiNotificationHelper mWifiNotificationHelper;
    @Mock private SynchronousNetworkRecommendationProvider mNetworkRecommendationProvider;
    private ContentResolver mContentResolver;
    private Handler mHandler;
    private WifiNotificationController mWifiNotificationController;
    private int mNotificationsEnabledOriginalValue;

    /**
     * Internal BroadcastReceiver that WifiNotificationController uses to listen for broadcasts
     * this is initialized by calling startServiceAndLoadDriver
     */
    private BroadcastReceiver mBroadcastReceiver;

    /** Initialize objects before each test run. */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Needed for the NotificationEnabledSettingObserver.
        mContentResolver = InstrumentationRegistry.getTargetContext().getContentResolver();
        mNotificationsEnabledOriginalValue =
                Settings.Global.getInt(mContentResolver,
                        Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 0);
        Settings.Global.putInt(mContentResolver,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1);
        mHandler = new Handler(Looper.getMainLooper());

        mWifiNotificationController = new WifiNotificationController(
                mContext, mContentResolver, mHandler, mNetworkRecommendationProvider,
                mWifiManager, mNotificationManager, mWifiNotificationHelper);
        mWifiNotificationController.start();

        ArgumentCaptor<BroadcastReceiver> broadcastReceiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        verify(mContext)
                .registerReceiver(broadcastReceiverCaptor.capture(), any(IntentFilter.class),
                        anyString(), any(Handler.class));
        mBroadcastReceiver = broadcastReceiverCaptor.getValue();
    }

    @After
    public void tearDown() throws Exception {
        Settings.Global.putInt(mContentResolver,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON,
                mNotificationsEnabledOriginalValue);
    }

    private void setOpenAccessPoints(int numAccessPoints) {
        List<ScanResult> scanResults = new ArrayList<>();
        for (int i = 0; i < numAccessPoints; i++) {
            ScanResult scanResult = createScanResult("testSSID" + i, "00:00:00:00:00:00");
            scanResults.add(scanResult);
        }
        when(mWifiManager.getScanResults()).thenReturn(scanResults);
    }

    private ScanResult createScanResult(String ssid, String bssid) {
        ScanResult scanResult = new ScanResult();
        scanResult.capabilities = "[ESS]";
        scanResult.SSID = ssid;
        scanResult.BSSID = bssid;
        return scanResult;
    }

    private WifiConfiguration createFakeConfig() {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"TestSsid\"";
        config.BSSID = "00:00:00:00:00:00";
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        return config;
    }

    private void createFakeBitmap() {
        when(mWifiNotificationHelper.createNotificationBadgeBitmap(any(), any()))
                .thenReturn(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
    }

    /** Verifies that a notification is displayed (and retracted) given system events. */
    @Test
    public void verifyNotificationDisplayedWhenNetworkRecommended() throws Exception {
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext, WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        setOpenAccessPoints(3);
        createFakeBitmap();

        when(mNetworkRecommendationProvider.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(
                        RecommendationResult.createConnectRecommendation(createFakeConfig()));

        // The notification should not be displayed after only two scan results.
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // Changing to and from "SCANNING" state should not affect the counter.
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.SCANNING);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
        // The third scan result notification will trigger the notification.
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mWifiNotificationHelper).createMainNotification(any(WifiConfiguration.class),
                any(Bitmap.class));
        verify(mNotificationManager)
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
        verify(mNotificationManager, never())
                .cancelAsUser(any(String.class), anyInt(), any(UserHandle.class));
    }

    /** Verifies that a notification is not displayed for bad networks. */
    @Test
    public void verifyNotificationNotDisplayedWhenNoNetworkRecommended() throws Exception {
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext, WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        setOpenAccessPoints(3);
        createFakeBitmap();

        // Recommendation result with no WifiConfiguration returned.
        when(mNetworkRecommendationProvider.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // DoNotConnect Recommendation result.
        when(mNetworkRecommendationProvider.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(RecommendationResult.createDoNotConnectRecommendation());
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mNotificationManager, never())
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
    }

    /**
     * Verifies the notifications flow (Connect -> connecting -> connected) when user clicks
     * on Connect button.
     */
    @Test
    public void verifyNotificationsFlowOnConnectToNetwork() {
        TestUtil.sendWifiStateChanged(mBroadcastReceiver, mContext, WifiManager.WIFI_STATE_ENABLED);
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.DISCONNECTED);
        setOpenAccessPoints(3);
        createFakeBitmap();
        when(mNetworkRecommendationProvider.requestRecommendation(any(RecommendationRequest.class)))
                .thenReturn(
                        RecommendationResult.createConnectRecommendation(createFakeConfig()));

        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        TestUtil.sendScanResultsAvailable(mBroadcastReceiver, mContext);
        verify(mWifiNotificationHelper).createMainNotification(any(WifiConfiguration.class),
                any(Bitmap.class));
        verify(mNotificationManager)
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // Send connect intent, should attempt to connect to Wi-Fi
        Intent intent = new Intent(
                WifiNotificationController.ACTION_CONNECT_TO_RECOMMENDED_NETWORK);
        mBroadcastReceiver.onReceive(mContext, intent);
        verify(mWifiManager).connect(any(WifiConfiguration.class), any(ActionListener.class));
        verify(mWifiNotificationHelper).createConnectingNotification(any(WifiConfiguration.class),
                any(Bitmap.class));

        // Show connecting notification.
        verify(mNotificationManager, times(2))
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));
        // Verify callback to dismiss connecting notification exists.
        assertTrue(mHandler.hasCallbacks(
                mWifiNotificationController.mShowFailedToConnectNotificationRunnable));

        // Verify show connected notification.
        TestUtil.sendNetworkStateChanged(mBroadcastReceiver, mContext,
                NetworkInfo.DetailedState.CONNECTED);
        verify(mWifiNotificationHelper).createConnectedNotification(any(WifiConfiguration.class),
                any(Bitmap.class));
        verify(mNotificationManager, times(3))
                .notifyAsUser(any(String.class), anyInt(), any(Notification.class),
                        any(UserHandle.class));

        // Verify callback to dismiss connected notification exists.
        assertTrue(mHandler.hasCallbacks(
                mWifiNotificationController.mDismissNotificationRunnable));
    }
}
