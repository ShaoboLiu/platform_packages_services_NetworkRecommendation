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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiSsid;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Unit tests for {@link WifiWakeupNetworkSelector}
 */
@RunWith(AndroidJUnit4.class)
public class WifiWakeupNetworkSelectorTest {
    private static final String SSID = "ssid";
    private static final String SSID2 = "ssid2";

    private static final WifiConfiguration WIFI_CONFIGURATION_PSK = new WifiConfiguration();
    private static final WifiConfiguration WIFI_CONFIGURATION_NONE = new WifiConfiguration();
    private static final ArrayMap<String, WifiConfiguration> SAVED_WIFI_CONFIGURATION_MAP =
            new ArrayMap<>();

    static {
        WIFI_CONFIGURATION_PSK.SSID = "\"" + SSID + "\"";
        WIFI_CONFIGURATION_PSK.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        SAVED_WIFI_CONFIGURATION_MAP.put(SSID, WIFI_CONFIGURATION_PSK);

        WIFI_CONFIGURATION_NONE.SSID = "\"" + SSID2 + "\"";
        WIFI_CONFIGURATION_NONE.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        SAVED_WIFI_CONFIGURATION_MAP.put(SSID2, WIFI_CONFIGURATION_NONE);
    }

    private static ScanResult buildScanResult(String ssid, int level, int frequency, String caps) {
        return new ScanResult(WifiSsid.createFromAsciiEncoded(ssid),
                "00:00:00:00:00:00", 0L, -1, null, caps, level, frequency, 0);
    }

    private static final int MIN_QUALIFIED_24 = -73;
    private static final int MIN_QUALIFIED_5 = -70;
    private static final int FREQUENCY_24 = 2450;
    private static final int FREQUENCY_5 = 5000;
    private static final String CAPABILITIES_NONE = "";
    private static final String CAPABILITIES_PSK = "PSK";

    @Mock private Resources mResources;

    private WifiWakeupNetworkSelector mWifiWakeupNetworkSelector;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mResources.getInteger(
                R.integer.config_netrec_wifi_score_low_rssi_threshold_24GHz))
                .thenReturn(MIN_QUALIFIED_24);
        when(mResources.getInteger(
                R.integer.config_netrec_wifi_score_low_rssi_threshold_5GHz))
                .thenReturn(MIN_QUALIFIED_5);
        when(mResources.getInteger(
                R.integer.config_netrec_5GHz_preference_boost_factor))
                .thenReturn(100);

        mWifiWakeupNetworkSelector = new WifiWakeupNetworkSelector(mResources);
    }

    @Test
    public void testSelectNetwork_noSavedNetworksInScanResults() {
        List<ScanResult> scanResults = Lists.newArrayList(
                buildScanResult("blah", MIN_QUALIFIED_5 + 1, FREQUENCY_5, CAPABILITIES_NONE),
                buildScanResult("blahtoo", MIN_QUALIFIED_24 + 1, FREQUENCY_24, CAPABILITIES_NONE));

        WifiConfiguration selectedNetwork = mWifiWakeupNetworkSelector
                .selectNetwork(SAVED_WIFI_CONFIGURATION_MAP, scanResults);

        assertNull(selectedNetwork);
    }

    @Test
    public void testSelectNetwork_noQualifiedSavedNetworks() {
        List<ScanResult> scanResults = Lists.newArrayList(
                buildScanResult(SSID2, MIN_QUALIFIED_5 - 1, FREQUENCY_5, CAPABILITIES_NONE),
                buildScanResult(SSID2, MIN_QUALIFIED_24 - 1, FREQUENCY_24, CAPABILITIES_NONE));

        WifiConfiguration selectedNetwork = mWifiWakeupNetworkSelector
                .selectNetwork(SAVED_WIFI_CONFIGURATION_MAP, scanResults);

        assertNull(selectedNetwork);
    }

    @Test
    public void testSelectNetwork_noMatchingScanResults() {
        List<ScanResult> scanResults = Lists.newArrayList(
                buildScanResult(SSID, MIN_QUALIFIED_5 + 1, FREQUENCY_5, CAPABILITIES_NONE),
                buildScanResult(SSID, MIN_QUALIFIED_24 + 1, FREQUENCY_24, CAPABILITIES_NONE));

        WifiConfiguration selectedNetwork = mWifiWakeupNetworkSelector
                .selectNetwork(SAVED_WIFI_CONFIGURATION_MAP, scanResults);

        assertNull(selectedNetwork);
    }

    @Test
    public void testSelectNetwork_secureNetworkOverUnsecure() {
        List<ScanResult> scanResults = Lists.newArrayList(
                buildScanResult(SSID, MIN_QUALIFIED_5 + 1, FREQUENCY_5, CAPABILITIES_PSK),
                buildScanResult(SSID2, MIN_QUALIFIED_5 + 1, FREQUENCY_5, CAPABILITIES_NONE));

        WifiConfiguration selectedNetwork = mWifiWakeupNetworkSelector
                .selectNetwork(SAVED_WIFI_CONFIGURATION_MAP, scanResults);

        assertEquals(WIFI_CONFIGURATION_PSK, selectedNetwork);
    }
}
