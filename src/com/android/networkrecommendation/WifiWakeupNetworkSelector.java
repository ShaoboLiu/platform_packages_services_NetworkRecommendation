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

import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import java.util.List;
import java.util.Map;

/**
 * This class determines which network the framework would connect to if Wi-Fi was enabled.
 */
public class WifiWakeupNetworkSelector {
    private final int mThresholdQualifiedRssi24;
    private final int mThresholdQualifiedRssi5;
    private final int mRssiScoreSlope;
    private final int mRssiScoreOffset;
    private final int mPasspointSecurityAward;
    private final int mSecurityAward;
    private final int mBand5GHzAward;
    private final int mThresholdSaturatedRssi24;

    public WifiWakeupNetworkSelector(Resources resources) {
        mThresholdQualifiedRssi24 = resources.getInteger(
                R.integer.config_netrec_wifi_score_low_rssi_threshold_24GHz);
        mThresholdQualifiedRssi5 = resources.getInteger(
                R.integer.config_netrec_wifi_score_low_rssi_threshold_5GHz);
        mRssiScoreSlope = resources.getInteger(
                R.integer.config_netrec_RSSI_SCORE_SLOPE);
        mRssiScoreOffset = resources.getInteger(
                R.integer.config_netrec_RSSI_SCORE_OFFSET);
        mPasspointSecurityAward = resources.getInteger(
                R.integer.config_netrec_PASSPOINT_SECURITY_AWARD);
        mSecurityAward = resources.getInteger(
                R.integer.config_netrec_SECURITY_AWARD);
        mBand5GHzAward = resources.getInteger(
                R.integer.config_netrec_5GHz_preference_boost_factor);
        mThresholdSaturatedRssi24 = resources.getInteger(
                R.integer.config_netrec_wifi_score_good_rssi_threshold_24GHz);
    }

    /**
     * Returns the network that the framework would most likely connect to if Wi-Fi was enabled.
     */
    public WifiConfiguration selectNetwork(Map<String, WifiConfiguration> savedNetworks,
            List<ScanResult> scanResults) {
        WifiConfiguration candidateWifiConfiguration = null;
        ScanResult candidateScanResult = null;
        int candidateScore = -1;
        for (int i = 0; i < scanResults.size(); i++) {
            ScanResult scanResult = scanResults.get(i);
            WifiConfiguration wifiConfiguration = savedNetworks.get(scanResult.SSID);
            if (wifiConfiguration == null) {
                continue;
            }
            if (ScanResultUtil.is5GHz(scanResult) && scanResult.level < mThresholdQualifiedRssi5
                    || ScanResultUtil.is24GHz(scanResult)
                    && scanResult.level < mThresholdQualifiedRssi24) {
                continue;
            }
            if (!ScanResultUtil.doesScanResultMatchWithNetwork(scanResult, wifiConfiguration)) {
                continue;
            }
            int score = calculateScore(scanResult, wifiConfiguration);
            if (candidateScanResult == null
                    || calculateScore(scanResult, wifiConfiguration) > candidateScore) {
                candidateScanResult = scanResult;
                candidateWifiConfiguration = wifiConfiguration;
                candidateScore = score;
            }
        }
        return candidateWifiConfiguration;
    }

    private int calculateScore(ScanResult scanResult, WifiConfiguration wifiConfiguration) {
        int score = 0;
        // Calculate the RSSI score.
        int rssi = scanResult.level <= mThresholdSaturatedRssi24
                ? scanResult.level : mThresholdSaturatedRssi24;
        score += (rssi + mRssiScoreOffset) * mRssiScoreSlope;

        // 5GHz band bonus.
        if (ScanResultUtil.is5GHz(scanResult)) {
            score += mBand5GHzAward;
        }

        // Security award.
        if (wifiConfiguration.isPasspoint()) {
            score += mPasspointSecurityAward;
        } else if (!WifiConfigurationUtil.isConfigForOpenNetwork(wifiConfiguration)) {
            score += mSecurityAward;
        }

        return score;
    }
}
