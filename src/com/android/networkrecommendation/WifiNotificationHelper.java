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

import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.app.Notification;
import android.app.Notification.Action;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.NetworkKey;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;

import java.util.List;

/**
 * Helper class that creates notifications for {@link WifiNotificationController}.
 */
public class WifiNotificationHelper {
    private final Context mContext;
    private final SynchronousNetworkRecommendationProvider mCachedScoredNetworkProvider;

    public WifiNotificationHelper(
            Context context, SynchronousNetworkRecommendationProvider cachedScoredNetworkProvider) {
        mContext = context;
        mCachedScoredNetworkProvider = cachedScoredNetworkProvider;
    }

    /**
     * Creates the main open networks notification with two actions. "Options" link to the
     * Wi-Fi picker activity, and "Connect" prompts {@link WifiNotificationController}
     * to connect to the recommended network.
     */
    public Notification createMainNotification(WifiConfiguration config, Bitmap badge) {
        PendingIntent optionsIntent = PendingIntent.getActivity(
                mContext, 0, new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK), FLAG_UPDATE_CURRENT);
        Action optionsAction = new Action.Builder(
                null /* icon */,
                mContext.getText(R.string.wifi_available_options),
                optionsIntent)
                .build();
        PendingIntent connectIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                new Intent(WifiNotificationController.ACTION_CONNECT_TO_RECOMMENDED_NETWORK),
                FLAG_UPDATE_CURRENT);
        Action connectAction = new Action.Builder(
                null /* icon */,
                mContext.getText(R.string.wifi_available_connect),
                connectIntent)
                .build();
        return createNotificationBuilder(config, badge)
                .addAction(connectAction)
                .addAction(optionsAction)
                .build();
    }

    /**
     * Creates the notification that indicates the controller is attempting to connect
     * to the recommended network.
     */
    public Notification createConnectingNotification(WifiConfiguration config, Bitmap badge) {
        Action connecting = new Action.Builder(
                null /* icon */,
                mContext.getText(R.string.wifi_available_connecting),
                null /* pendingIntent */)
                .build();
        return createNotificationBuilder(config, badge)
                .addAction(connecting)
                .setProgress(0 /* max */, 0 /* progress */, true /* indeterminate */)
                .build();
    }

    /**
     * Creates the notification that indicates the controller successfully connected
     * to the recommended network.
     */
    public Notification createConnectedNotification(WifiConfiguration config, Bitmap badge) {
        Action connected = new Action.Builder(
                null /* icon */,
                mContext.getText(R.string.wifi_available_connected),
                null /* pendingIntent */)
                .build();
        return createNotificationBuilder(config, badge)
                .addAction(connected)
                .build();
    }

    /**
     * Creates the notification that indicates the controller failed to connect to
     * the recommended network.
     */
    public Notification createFailedToConnectNotification(WifiConfiguration config) {
        Spannable failedText =
                new SpannableString(mContext.getText(R.string.wifi_available_failed));
        Resources resources = mContext.getResources();
        Drawable iconDrawable = mContext.getDrawable(R.drawable.ic_signal_wifi_no_network);
        iconDrawable.setTint(mContext.getColor(R.color.color_tint));
        Bitmap icon = ImageUtils.buildScaledBitmap(
                iconDrawable,
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height));
        failedText.setSpan(new ForegroundColorSpan(
                Color.RED), 0, failedText.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return createNotificationBuilder(config, icon)
                .setContentText(failedText)
                .build();
    }

    private Notification.Builder createNotificationBuilder(WifiConfiguration config, Bitmap badge) {
        CharSequence title = mContext.getText(R.string.wifi_available);
        PendingIntent deleteIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                new Intent(WifiNotificationController.ACTION_NOTIFICATION_DELETED),
                FLAG_UPDATE_CURRENT);
        return new Notification.Builder(mContext)
                .setDeleteIntent(deleteIntent)
                .setSmallIcon(R.drawable.stat_notify_wifi_in_range)
                .setLargeIcon(badge)
                .setAutoCancel(true)
                .setTicker(title)
                .setContentTitle(title)
                .setContentText(WifiConfigurationUtil.getPrintableSsid(config))
                .addExtras(getSystemLabelExtras());
    }

    private Bundle getSystemLabelExtras() {
        Bundle extras = new Bundle();
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                mContext.getString(R.string.android_system_label));
        return extras;
    }

    //TODO(34177812): Share this logic between systemUi and Settings.
    static final int[] WIFI_PIE_FOR_BADGING = {
            R.drawable.ic_signal_wifi_badged_0_bars,
            R.drawable.ic_signal_wifi_badged_1_bar,
            R.drawable.ic_signal_wifi_badged_2_bars,
            R.drawable.ic_signal_wifi_badged_3_bars,
            R.drawable.ic_signal_wifi_badged_4_bars
    };

    private int getWifiBadgeResourceForEnum(int badgeEnum) {
        switch (badgeEnum) {
            case ScoredNetwork.BADGING_NONE:
                return 0;
            case ScoredNetwork.BADGING_SD:
                return R.drawable.ic_signal_wifi_badged_sd;
            case ScoredNetwork.BADGING_HD:
                return R.drawable.ic_signal_wifi_badged_hd;
            case ScoredNetwork.BADGING_4K:
                return R.drawable.ic_signal_wifi_badged_4k;
            default:
                throw new IllegalArgumentException("No badge resource for enum :" + badgeEnum);
        }
    }

    /**
     * Creates a Wi-Fi badge for the notification using matching {@link ScanResult}'s RSSI
     * and badging from {@link CachedScoredNetworkProvider}.
     */
    public Bitmap createNotificationBadgeBitmap(
            @NonNull WifiConfiguration config,
            @NonNull List<ScanResult> scanResults) {
        ScanResult matchingScanResult = findMatchingScanResult(scanResults, config);
        if (matchingScanResult == null) {
            return null;
        }
        int rssi = matchingScanResult.level;
        WifiKey wifiKey = new WifiKey(config.SSID, config.BSSID);
        ScoredNetwork scoredNetwork =
                mCachedScoredNetworkProvider.getCachedScoredNetwork(new NetworkKey(wifiKey));
        if (scoredNetwork != null) {
            return getBadgedWifiBitmap(scoredNetwork.calculateBadge(rssi), rssi);
        }
        return null;
    }

    private Bitmap getBadgedWifiBitmap(int badgeEnum, int rssi) {
        int signalLevel = WifiManager.calculateSignalLevel(rssi, 5);
        LayerDrawable layerDrawable = new LayerDrawable(new Drawable[]{
                mContext.getDrawable(WIFI_PIE_FOR_BADGING[signalLevel]),
                mContext.getDrawable(getWifiBadgeResourceForEnum(badgeEnum))});
        layerDrawable.setTint(mContext.getColor(R.color.color_tint));
        Resources resources = mContext.getResources();
        return ImageUtils.buildScaledBitmap(layerDrawable,
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
                resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height));
    }

    private ScanResult findMatchingScanResult(List<ScanResult> scanResults,
            WifiConfiguration wifiConfiguration) {
        String ssid = WifiConfigurationUtil.removeDoubleQuotes(wifiConfiguration);
        String bssid = wifiConfiguration.BSSID;
        for (ScanResult scanResult : scanResults) {
            if (ssid.equals(scanResult.SSID) && bssid.equals(scanResult.BSSID)) {
                return scanResult;
            }
        }
        return null;
    }
}
