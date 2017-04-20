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

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.NetworkScoreManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Provides network recommendations for the platform.
 */
public class DefaultNetworkRecommendationService extends Service {

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private DefaultNetworkRecommendationProvider mProvider;
    private WifiNotificationController mWifiNotificationController;
    private WifiWakeupController mWifiWakeupController;

    @Override
    public void onCreate() {
        mHandlerThread = new HandlerThread("RecommendationProvider");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        NetworkScoreManager networkScoreManager = getSystemService(NetworkScoreManager.class);
        mProvider = new DefaultNetworkRecommendationProvider(mHandler,
                networkScoreManager, new DefaultNetworkRecommendationProvider.ScoreStorage());
        mWifiNotificationController = new WifiNotificationController(
                this, getContentResolver(), mHandler, mProvider,
                getSystemService(WifiManager.class), getSystemService(NotificationManager.class),
                new WifiNotificationHelper(this, mProvider));
        mWifiWakeupController = new WifiWakeupController(this, getContentResolver(),
                mHandlerThread.getLooper(), getSystemService(WifiManager.class),
                new WifiWakeupNetworkSelector(getResources()));
    }

    @Override
    public IBinder onBind(Intent intent) {
        mWifiWakeupController.start();
        mWifiNotificationController.start();
        return mProvider.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mWifiWakeupController.stop();
        mWifiNotificationController.stop();
        return super.onUnbind(intent);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        mProvider.dump(fd, writer, args);
        mWifiNotificationController.dump(fd, writer, args);
        mWifiWakeupController.dump(fd, writer, args);
    }
}
