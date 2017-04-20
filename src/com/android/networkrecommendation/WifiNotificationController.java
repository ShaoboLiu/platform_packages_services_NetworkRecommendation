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

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes care of handling the "open wi-fi network available" notification
 * @hide
 */
public class WifiNotificationController {
    /**
     * The icon to show in the 'available networks' notification. This will also
     * be the ID of the Notification given to the NotificationManager.
     */
    private static final int ICON_NETWORKS_AVAILABLE = R.drawable.stat_notify_wifi_in_range;
    /**
     * When a notification is shown, we wait this amount before possibly showing it again.
     */
    private final long mNotificationRepeatDelayMs;
    /**
     * Whether the user has set the setting to show the 'available networks' notification.
     */
    private boolean mNotificationEnabled;
    /**
     * Observes the user setting to keep {@link #mNotificationEnabled} in sync.
     */
    private final NotificationEnabledSettingObserver mNotificationEnabledSettingObserver;
    /**
     * The {@link System#currentTimeMillis()} must be at least this value for us
     * to show the notification again.
     */
    private long mNotificationRepeatTime;
    /**
     * Whether the notification is being shown.
     */
    private boolean mNotificationShown;
    /**
     * The number of continuous scans that must occur before consider the
     * supplicant in a scanning state. This allows supplicant to associate with
     * remembered networks that are in the scan results.
     */
    private static final int NUM_SCANS_BEFORE_ACTUALLY_SCANNING = 3;
    /**
     * The number of scans since the last network state change. When this
     * exceeds {@link #NUM_SCANS_BEFORE_ACTUALLY_SCANNING}, we consider the
     * supplicant to actually be scanning. When the network state changes to
     * something other than scanning, we reset this to 0.
     */
    private int mNumScansSinceNetworkStateChange;
    /**
     * Time in milliseconds to display the Connecting notification.
     */
    private static final int TIME_TO_SHOW_CONNECTING_MILLIS = 10000;
    /**
     * Time in milliseconds to display the Connected notification.
     */
    private static final int TIME_TO_SHOW_CONNECTED_MILLIS = 5000;
    /**
     * Time in milliseconds to display the Failed To Connect notification.
     */
    private static final int TIME_TO_SHOW_FAILED_MILLIS = 5000;
    /**
     * Try to connect to provided WifiConfiguration since user wants to
     * connect to the recommended open access point.
     */
    static final String ACTION_CONNECT_TO_RECOMMENDED_NETWORK =
            "com.android.networkrecommendation.CONNECT_TO_RECOMMENDED_NETWORK";
    /**
     * Handles behavior when notification is deleted.
     */
    static final String ACTION_NOTIFICATION_DELETED =
            "com.android.networkrecommendation.NOTIFICATION_DELETED";
    /**
     * Network recommended by {@link NetworkScoreManager#requestRecommendation}.
     */
    private WifiConfiguration mRecommendedNetwork;
    private Bitmap mNotificationBadgeBitmap;
    /**
     * Whether {@link WifiNotificationController} has been started.
     */
    private final AtomicBoolean mStarted;
    /**
     * Runnable to dismiss notification.
     */
    @VisibleForTesting
    final Runnable mDismissNotificationRunnable = () -> {
        removeNotification();
    };
    /**
     * Runnable to show Failed To Connect notification.
     */
    @VisibleForTesting
    final Runnable mShowFailedToConnectNotificationRunnable = () -> {
        showFailedToConnectNotification();
    };

    private static final String TAG = "WifiNotification";

    private final Context mContext;
    private final Handler mHandler;
    private final ContentResolver mContentResolver;
    private final SynchronousNetworkRecommendationProvider mNetworkRecommendationProvider;
    private final WifiManager mWifiManager;
    private final NotificationManager mNotificationManager;
    private final WifiNotificationHelper mWifiNotificationHelper;
    private NetworkInfo mNetworkInfo;
    private NetworkInfo.DetailedState mDetailedState;
    private volatile int mWifiState;

    WifiNotificationController(Context context, ContentResolver contentResolver, Handler handler,
            SynchronousNetworkRecommendationProvider networkRecommendationProvider,
            WifiManager wifiManager, NotificationManager notificationManager,
            WifiNotificationHelper helper) {
        mContext = context;
        mContentResolver = contentResolver;
        mNetworkRecommendationProvider = networkRecommendationProvider;
        mWifiManager = wifiManager;
        mNotificationManager = notificationManager;
        mHandler = handler;
        mWifiNotificationHelper = helper;
        mStarted = new AtomicBoolean(false);

        // Setting is in seconds
        mNotificationRepeatDelayMs = Settings.Global.getInt(
                contentResolver, Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY, 900) * 1000L;
        mNotificationEnabledSettingObserver = new NotificationEnabledSettingObserver(mHandler);
    }

    /** Starts {@link WifiNotificationController}. */
    public void start() {
        if (!mStarted.compareAndSet(false, true)) {
            return;
        }

        mWifiState = WifiManager.WIFI_STATE_UNKNOWN;
        mDetailedState = NetworkInfo.DetailedState.IDLE;

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(ACTION_CONNECT_TO_RECOMMENDED_NETWORK);
        filter.addAction(ACTION_NOTIFICATION_DELETED);

        mContext.registerReceiver(
                mBroadcastReceiver, filter, null /* broadcastPermission */, mHandler);
        mNotificationEnabledSettingObserver.register();
    }

    /** Stops {@link WifiNotificationController}. */
    public void stop() {
        if (!mStarted.compareAndSet(true, false)) {
            return;
        }
        mContext.unregisterReceiver(mBroadcastReceiver);
        mNotificationEnabledSettingObserver.register();
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                mWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                resetNotification();
            } else if (intent.getAction().equals(
                    WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                mNetworkInfo = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);
                NetworkInfo.DetailedState detailedState =
                        mNetworkInfo.getDetailedState();
                if (detailedState != NetworkInfo.DetailedState.SCANNING
                        && detailedState != mDetailedState) {
                    mDetailedState = detailedState;
                    switch (mDetailedState) {
                        case CONNECTED:
                            updateNotificationOnConnect();
                            break;
                        case DISCONNECTED:
                        case CAPTIVE_PORTAL_CHECK:
                            resetNotification();
                            break;

                        // TODO: figure out if these are failure cases when connecting
                        case IDLE:
                        case SCANNING:
                        case CONNECTING:
                        case AUTHENTICATING:
                        case OBTAINING_IPADDR:
                        case SUSPENDED:
                        case FAILED:
                        case BLOCKED:
                        case VERIFYING_POOR_LINK:
                            break;
                    }
                }
            } else if (intent.getAction().equals(
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                checkAndSetNotification(mNetworkInfo, mWifiManager.getScanResults());
            } else if (intent.getAction().equals(ACTION_CONNECT_TO_RECOMMENDED_NETWORK)) {
                connectToRecommendedNetwork();
            } else if (intent.getAction().equals(ACTION_NOTIFICATION_DELETED)) {
                handleNotificationDeleted();
            }
        }
    };

    private void checkAndSetNotification(NetworkInfo networkInfo,
            List<ScanResult> scanResults) {

        // TODO: unregister broadcast so we do not have to check here
        // If we shouldn't place a notification on available networks, then
        // don't bother doing any of the following
        if (!mNotificationEnabled) return;
        if (mWifiState != WifiManager.WIFI_STATE_ENABLED) return;
        if (scanResults == null || scanResults.isEmpty()) return;

        NetworkInfo.State state = NetworkInfo.State.DISCONNECTED;
        if (networkInfo != null) {
            state = networkInfo.getState();
        }


        if (state == NetworkInfo.State.DISCONNECTED
                || state == NetworkInfo.State.UNKNOWN) {
            RecommendationResult result = getOpenNetworkRecommendation(scanResults);
            if (result != null
                    && result.getWifiConfiguration() != null) {
                mRecommendedNetwork = result.getWifiConfiguration();
                mNotificationBadgeBitmap = mWifiNotificationHelper.createNotificationBadgeBitmap(
                        mRecommendedNetwork, scanResults);
                if (++mNumScansSinceNetworkStateChange >= NUM_SCANS_BEFORE_ACTUALLY_SCANNING
                        && mNotificationBadgeBitmap != null) {
                    /*
                     * We have scanned continuously at least
                     * NUM_SCANS_BEFORE_NOTIFICATION times. The user
                     * probably does not have a remembered network in range,
                     * since otherwise supplicant would have tried to
                     * associate and thus resetting this counter.
                     */
                    displayNotification();
                }
                return;
            }
        }

        // No open networks in range, remove the notification
        removeNotification();
    }

    /**
     * Uses {@link NetworkScoreManager} to choose a qualified network out of the list of
     * {@link ScanResult}s.
     *
     * @return returns the best qualified open networks, if any.
     */
    @Nullable
    private RecommendationResult getOpenNetworkRecommendation(List<ScanResult> scanResults) {
        if (scanResults == null || scanResults.isEmpty()) {
            return null;
        }
        ArrayList<ScanResult> openNetworks = new ArrayList<>();
        for (ScanResult scanResult : scanResults) {
            //A capability of [ESS] represents an open access point
            //that is available for an STA to connect
            if ("[ESS]".equals(scanResult.capabilities)) {
                openNetworks.add(scanResult);
            }
        }
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(openNetworks.toArray(new ScanResult[openNetworks.size()]))
                .build();

        return mNetworkRecommendationProvider.requestRecommendation(request);
    }

    /**
     * Display's a notification that there are open Wi-Fi networks.
     */
    private void displayNotification() {

        // Since we use auto cancel on the notification, when the
        // mNetworksAvailableNotificationShown is true, the notification may
        // have actually been canceled.  However, when it is false we know
        // for sure that it is not being shown (it will not be shown any other
        // place than here)

        // Not enough time has passed to show the notification again

        if (System.currentTimeMillis() < mNotificationRepeatTime) {
            return;
        }
        Notification notification = mWifiNotificationHelper.createMainNotification(
                        mRecommendedNetwork, mNotificationBadgeBitmap);
        mNotificationRepeatTime = System.currentTimeMillis() + mNotificationRepeatDelayMs;

        postNotification(notification);
        mNotificationShown = true;
    }


    /**
     * Attempts to connect to recommended network and updates the notification to
     * show Connecting state.
     * TODO(33668991): work with UX to polish notification UI and figure out failure states
     */
    private void connectToRecommendedNetwork() {
        if (mRecommendedNetwork == null) {
            return;
        }
        // Attempts to connect to recommended network.
        mWifiManager.connect(mRecommendedNetwork, null /* actionListener */);

        // Update notification to connecting status.
        Notification notification = mWifiNotificationHelper.createConnectingNotification(
                        mRecommendedNetwork, mNotificationBadgeBitmap);
        postNotification(notification);
        mHandler.postDelayed(mShowFailedToConnectNotificationRunnable,
                TIME_TO_SHOW_CONNECTING_MILLIS);
    }

    /**
     * When detailed state changes to CONNECTED, show connected notification or
     * reset notification.
     * TODO: determine failure state where main notification shows but connected.
     */
    private void updateNotificationOnConnect() {
        if (!mNotificationShown) {
            return;
        }

        Notification notification = mWifiNotificationHelper.createConnectedNotification(
                mRecommendedNetwork, mNotificationBadgeBitmap);
        postNotification(notification);
        // Remove any previous reset notification callbacks.
        mHandler.removeCallbacks(mShowFailedToConnectNotificationRunnable);
        mHandler.postDelayed(mDismissNotificationRunnable, TIME_TO_SHOW_CONNECTED_MILLIS);
    }

    /**
     * Displays the Failed To Connect notification after the Connecting notification
     * is shown for {@link #TIME_TO_SHOW_CONNECTING_MILLIS} duration.
     */
    private void showFailedToConnectNotification() {
        Notification notification =
                mWifiNotificationHelper.createFailedToConnectNotification(mRecommendedNetwork);
        postNotification(notification);
        mHandler.postDelayed(mDismissNotificationRunnable, TIME_TO_SHOW_FAILED_MILLIS);
    }

    /**
     * Handles behavior when notification is dismissed.
     */
    private void handleNotificationDeleted() {
        mNotificationShown = false;
        mRecommendedNetwork = null;
        mNotificationBadgeBitmap = null;
    }

    private void postNotification(Notification notification) {
        mNotificationManager.notify(null /* tag */, ICON_NETWORKS_AVAILABLE, notification);
    }

    /**
     * Clears variables related to tracking whether a notification has been
     * shown recently and clears the current notification.
     */
    private void resetNotification() {
        mNotificationRepeatTime = 0;
        mNumScansSinceNetworkStateChange = 0;
        if (mNotificationShown) {
            removeNotification();
        }
    }

    private void removeNotification() {
        mNotificationManager.cancel(null /* tag */, ICON_NETWORKS_AVAILABLE);
        mNotificationShown = false;
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mNotificationEnabled " + mNotificationEnabled);
        pw.println("mNotificationRepeatTime " + mNotificationRepeatTime);
        pw.println("mNotificationShown " + mNotificationShown);
        pw.println("mNumScansSinceNetworkStateChange " + mNumScansSinceNetworkStateChange);
    }

    private class NotificationEnabledSettingObserver extends ContentObserver {
        NotificationEnabledSettingObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            mContentResolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON), true, this);
            mNotificationEnabled = getValue();
        }

        public void unregister() {
            mContentResolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            mNotificationEnabled = getValue();
            resetNotification();
        }

        private boolean getValue() {
            return Settings.Global.getInt(
                    mContentResolver,
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 0) == 1;
        }
    }
}
