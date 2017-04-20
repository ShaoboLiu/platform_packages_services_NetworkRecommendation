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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkRecommendationProvider;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.RssiCurve;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiSsid;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Tests the recommendation provider directly, to test internals of the provider rather than the
 * service's API.
 */
@RunWith(AndroidJUnit4.class)
public class DefaultNetworkRecommendationProviderTest {

    private static final String GOOD_METERED_NETWORK_STRING_UNQUOTED = "Metered";
    private static final String GOOD_METERED_NETWORK_STRING = "\"Metered\",aa:bb:cc:dd:ee:ff" +
            "|10,-128,-128,-128,-128,-128,-128,-128,-128,20,20,20,20,-128|1|0|4K";
    private static final RssiCurve GOOD_METERED_NETWORK_CURVE = new RssiCurve(
            DefaultNetworkRecommendationProvider.CONSTANT_CURVE_START, 10 /* bucketWidth */,
            new byte[]{-128, -128, -128, -128, -128, -128, -128, -128, 20, 20, 20, 20, -128},
            0 /* defaultActiveNetworkBoost */);
    private static final ScoredNetwork GOOD_METERED_NETWORK = new ScoredNetwork(
            new NetworkKey(new WifiKey("\"Metered\"", "aa:bb:cc:dd:ee:ff")),
            GOOD_METERED_NETWORK_CURVE, true /* meteredHint */, new Bundle());

    private static final String GOOD_CAPTIVE_NETWORK_STRING_UNQUOTED = "Captive";
    private static final String GOOD_CAPTIVE_NETWORK_STRING =
            "\"Captive\",ff:ee:dd:cc:bb:aa"
                    + "|18,-128,-128,-128,-128,-128,-128,21,21,21,-128|0|1|HD";
    private static final RssiCurve GOOD_CAPTIVE_NETWORK_CURVE = new RssiCurve(
            DefaultNetworkRecommendationProvider.CONSTANT_CURVE_START, 18 /* bucketWidth */,
            new byte[]{-128, -128, -128, -128, -128, -128, 21, 21, 21, -128},
            0 /* defaultActiveNetworkBoost */);
    private static final ScoredNetwork GOOD_CAPTIVE_NETWORK;
    static {
        Bundle attributes = new Bundle();
        attributes.putBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL, true);
        GOOD_CAPTIVE_NETWORK = new ScoredNetwork(
                new NetworkKey(new WifiKey("\"Captive\"", "ff:ee:dd:cc:bb:aa")),
                GOOD_CAPTIVE_NETWORK_CURVE, false /* meteredHint */, attributes);
    }

    private static final String ANY_NETWORK_STRING_UNQUOTED = "AnySsid";
    private static final String ANY_NETWORK_STRING =
            "\"AnySsid\",00:00:00:00:00:00"
                    + "|18,-128,-128,-128,-128,-128,-128,22,22,22,-128|0|0|NONE";
    private static final RssiCurve ANY_NETWORK_CURVE = new RssiCurve(
            DefaultNetworkRecommendationProvider.CONSTANT_CURVE_START, 18 /* bucketWidth */,
            new byte[]{-128, -128, -128, -128, -128, -128, 22, 22, 22, -128},
            0 /* defaultActiveNetworkBoost */);
    private static final ScoredNetwork ANY_NETWORK = new ScoredNetwork(
            new NetworkKey(new WifiKey("\"AnySsid\"", "ee:ee:ee:ee:ee:ee")),
            ANY_NETWORK_CURVE, false /* meteredHint */, new Bundle());

    private static final String ANY_NETWORK_SPECIFIC_STRING_UNQUOTED = "AnySsid";
    private static final String ANY_NETWORK_SPECIFIC_STRING =
            "\"AnySsid\",ee:ee:ee:ee:ee:ee"
                    + "|18,-128,-128,-128,-128,-128,-128,23,23,23,-128|0|0|NONE";
    private static final RssiCurve ANY_NETWORK_SPECIFIC_CURVE = new RssiCurve(
            DefaultNetworkRecommendationProvider.CONSTANT_CURVE_START, 18 /* bucketWidth */,
            new byte[]{-128, -128, -128, -128, -128, -128, 23, 23, 23, -128},
            0 /* defaultActiveNetworkBoost */);
    private static final ScoredNetwork ANY_NETWORK_SPECIFIC = new ScoredNetwork(
            new NetworkKey(new WifiKey("\"AnySsid\"", "ee:ee:ee:ee:ee:ee")),
            ANY_NETWORK_SPECIFIC_CURVE, false /* meteredHint */, new Bundle());

    private static final String BAD_NETWORK_STRING_UNQUOTED = "Bad";
    private static final String BAD_NETWORK_STRING =
            "\"Bad\",aa:bb:cc:dd:ee:ff"
                    + "|10,-128,-128,-128,-128,-128,-128,-128,-128,-128,-128,-128,-128,-128"
                    + "|1|0|SD";
    private static final RssiCurve BAD_NETWORK_CURVE =
            new RssiCurve(
                    DefaultNetworkRecommendationProvider.CONSTANT_CURVE_START,
                    10 /* bucketWidth */,
                    new byte[] {-128, -128, -128, -128, -128, -128,
                            -128, -128, -128, -128, -128, -128, -128},
                    0 /* defaultActiveNetworkBoost */);
    private static final ScoredNetwork BAD_NETWORK =
            new ScoredNetwork(
                    new NetworkKey(new WifiKey("\"Bad\"", "aa:bb:cc:dd:ee:ff")),
                    BAD_NETWORK_CURVE,
                    true /* meteredHint */,
                    new Bundle());

    @Mock
    private NetworkRecommendationProvider.ResultCallback mCallback;

    @Mock
    private NetworkScoreManager mNetworkScoreManager;

    private DefaultNetworkRecommendationProvider.ScoreStorage mStorage;
    private DefaultNetworkRecommendationProvider mProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStorage = new DefaultNetworkRecommendationProvider.ScoreStorage();
        mProvider = new DefaultNetworkRecommendationProvider(
                new Handler(Looper.getMainLooper()), mNetworkScoreManager, mStorage);
    }

    @Test
    public void basicRecommendation() throws Exception {

        ScanResult[] scanResults = new ScanResult[6];
        for (int i = 0; i < 3; i++) {
            scanResults[i] = TestUtil.createMockScanResult(i);
        }

        // For now we add directly to storage, but when we start calling
        // networkScoreManager.updateScores, we'll have to adjust this test.
        mProvider.addScoreForTest(GOOD_METERED_NETWORK);
        {
            ScanResult scanResult = new ScanResult();
            scanResult.level = 115;
            scanResult.SSID = GOOD_METERED_NETWORK_STRING_UNQUOTED;
            scanResult.wifiSsid = WifiSsid.createFromAsciiEncoded(
                    GOOD_METERED_NETWORK_STRING_UNQUOTED);
            scanResult.BSSID = GOOD_METERED_NETWORK.networkKey.wifiKey.bssid;
            scanResult.capabilities = "[ESS]";
            scanResult.timestamp = SystemClock.elapsedRealtime() * 1000;
            scanResults[3] = scanResult;
        }

        for (int i = 4; i < 6; i++) {
            scanResults[i] = TestUtil.createMockScanResult(i);
        }

        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(scanResults)
                .setNetworkCapabilities(new NetworkCapabilities().removeCapability(
                        NetworkCapabilities.NET_CAPABILITY_TRUSTED))
                .build();

        RecommendationResult result = verifyAndCaptureResult(request);
        assertEquals(GOOD_METERED_NETWORK.networkKey.wifiKey.ssid,
                result.getWifiConfiguration().SSID);
    }

    @Test
    public void recommendation_noScans_returnsCurrentConfig() throws Exception {
        ScanResult[] scanResults = new ScanResult[0];

        WifiConfiguration expectedConfig = new WifiConfiguration();
        expectedConfig.SSID = "ssid";
        expectedConfig.BSSID = "bssid";
        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(scanResults)
                .setNetworkCapabilities(new NetworkCapabilities().removeCapability(
                        NetworkCapabilities.NET_CAPABILITY_TRUSTED))
                .setCurrentRecommendedWifiConfig(expectedConfig)
                .build();

        RecommendationResult result = verifyAndCaptureResult(request);
        assertEquals(expectedConfig, result.getWifiConfiguration());
    }

    @Test
    public void recommendation_noScans_noCurrentConfig_returnsEmpty() throws Exception {
        ScanResult[] scanResults = new ScanResult[0];

        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(scanResults)
                .setNetworkCapabilities(new NetworkCapabilities().removeCapability(
                        NetworkCapabilities.NET_CAPABILITY_TRUSTED))
                .build();

        RecommendationResult result = verifyAndCaptureResult(request);
        assertNull(result.getWifiConfiguration());
    }

    @Test
    public void scoreNetworks() throws Exception {
        NetworkKey[] keys =
                new NetworkKey[]{GOOD_METERED_NETWORK.networkKey, GOOD_CAPTIVE_NETWORK.networkKey};
        mProvider.onRequestScores(keys);

        verify(mNetworkScoreManager).updateScores(Mockito.any());
    }

    @Test
    public void scoreNetworks_empty() throws Exception {
        NetworkKey[] keys = new NetworkKey[]{};
        mProvider.onRequestScores(keys);

        verify(mNetworkScoreManager, times(0)).updateScores(Mockito.any());
    }

    @Test
    public void dumpAddScores_goodMetered() {
        String[] args = {"netrec", "addScore", GOOD_METERED_NETWORK_STRING};
        mProvider.dump(null /* fd */, new PrintWriter(new StringWriter()), args);

        ScoredNetwork[] scoredNetworks = verifyAndCaptureScoredNetworks();
        assertEquals(1, scoredNetworks.length);
        ScoredNetwork score = scoredNetworks[0];

        assertEquals(GOOD_METERED_NETWORK.networkKey.wifiKey.ssid, score.networkKey.wifiKey.ssid);
        assertEquals(GOOD_METERED_NETWORK.networkKey.wifiKey.bssid, score.networkKey.wifiKey.bssid);

        assertEquals(GOOD_METERED_NETWORK.meteredHint, score.meteredHint);
        assertEquals(
                GOOD_METERED_NETWORK.attributes.getBoolean(
                        ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL),
                score.attributes.getBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL));

        assertEquals("Network curve does not match", GOOD_METERED_NETWORK_CURVE, score.rssiCurve);
        assertEquals(
                "Badge curve does not match",
                DefaultNetworkRecommendationProvider.BADGE_CURVE_4K,
                (RssiCurve) score.attributes.getParcelable(
                        ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE));
    }

    @Test
    public void dumpAddScores_bad() {
        String[] args = {"netrec", "addScore", BAD_NETWORK_STRING};
        mProvider.dump(null /* fd */, new PrintWriter(new StringWriter()), args);

        ScoredNetwork[] scoredNetworks = verifyAndCaptureScoredNetworks();
        assertEquals(1, scoredNetworks.length);
        ScoredNetwork score = scoredNetworks[0];

        assertEquals(BAD_NETWORK.networkKey.wifiKey.ssid, score.networkKey.wifiKey.ssid);
        assertEquals(BAD_NETWORK.networkKey.wifiKey.bssid, score.networkKey.wifiKey.bssid);

        assertEquals(BAD_NETWORK.meteredHint, score.meteredHint);
        assertEquals(
                BAD_NETWORK.attributes.getBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL),
                score.attributes.getBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL));

        assertEquals("Network curve does not match", BAD_NETWORK_CURVE, score.rssiCurve);
        assertEquals(
                "Badge curve does not match",
                DefaultNetworkRecommendationProvider.BADGE_CURVE_SD,
                (RssiCurve) score.attributes.getParcelable(
                        ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE));
    }

    @Test
    public void dumpAddScores_goodCaptivePortal() {
        String[] args = {"addScore", GOOD_CAPTIVE_NETWORK_STRING};
        mProvider.dump(null /* fd */, new PrintWriter(new StringWriter()), args);

        ScoredNetwork[] scoredNetworks = verifyAndCaptureScoredNetworks();
        assertEquals(1, scoredNetworks.length);
        ScoredNetwork score = scoredNetworks[0];

        assertEquals(GOOD_CAPTIVE_NETWORK.networkKey.wifiKey.ssid, score.networkKey.wifiKey.ssid);
        assertEquals(GOOD_CAPTIVE_NETWORK.networkKey.wifiKey.bssid, score.networkKey.wifiKey.bssid);

        assertEquals(GOOD_CAPTIVE_NETWORK.meteredHint, score.meteredHint);

        assertEquals(
                GOOD_CAPTIVE_NETWORK.attributes.getBoolean(
                        ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL),
                score.attributes.getBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL));
        assertEquals("Network curve does not match.", GOOD_CAPTIVE_NETWORK_CURVE, score.rssiCurve);
        assertEquals(
                "Badge curve does not match",
                DefaultNetworkRecommendationProvider.BADGE_CURVE_HD,
                (RssiCurve) score.attributes.getParcelable(
                        ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE));
    }

    @Test
    public void dumpAddScores_anySsid() {
        String[] args = {"addScore", ANY_NETWORK_STRING};
        mProvider.dump(null /* fd */, new PrintWriter(new StringWriter()), args);

        // We don't update the platform with the any bssid score, but we do store it.
        verify(mNetworkScoreManager, times(0)).updateScores(Mockito.any());

        // We do store and serve the score, though:
        ScoredNetwork score = mStorage.get(ANY_NETWORK.networkKey);
        assertNotNull(score);

        assertEquals(ANY_NETWORK.networkKey, score.networkKey);
        assertEquals(ANY_NETWORK.meteredHint, score.meteredHint);
        assertEquals(
                ANY_NETWORK.attributes.getBoolean(
                    ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL),
                score.attributes.getBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL));
        assertEquals("Network curve does not match", ANY_NETWORK_CURVE, score.rssiCurve);
        assertNull(
                "Badge curve should not be set.",
                (RssiCurve) score.attributes.getParcelable(
                        ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE));
    }

    @Test
    public void dumpAddScores_anySsid_useMoreSpecific() {
        mProvider.dump(null /* fd */, new PrintWriter(new StringWriter()),
                new String[] {"addScore", ANY_NETWORK_STRING});
        verify(mNetworkScoreManager, times(0)).updateScores(Mockito.any());

        mProvider.dump(null /* fd */, new PrintWriter(new StringWriter()),
                new String[] {"addScore", ANY_NETWORK_SPECIFIC_STRING});
        verify(mNetworkScoreManager).updateScores(Mockito.any());

        // We don't update the platform with the any bssid score, but we do store it.
        ScoredNetwork score = mStorage.get(ANY_NETWORK.networkKey);
        assertNotNull(score);

        assertEquals(ANY_NETWORK_SPECIFIC.networkKey, score.networkKey);
        assertEquals(ANY_NETWORK_SPECIFIC.meteredHint, score.meteredHint);
        assertEquals(
                ANY_NETWORK_SPECIFIC.attributes.getBoolean(
                    ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL),
                score.attributes.getBoolean(ScoredNetwork.ATTRIBUTES_KEY_HAS_CAPTIVE_PORTAL));
        assertEquals("Network curve does not match", ANY_NETWORK_SPECIFIC_CURVE, score.rssiCurve);
        assertNull(
                "Badge curve should not be set.",
                (RssiCurve) score.attributes.getParcelable(
                        ScoredNetwork.ATTRIBUTES_KEY_BADGING_CURVE));

    }

    private RecommendationResult verifyAndCaptureResult(
            RecommendationRequest request) {
        mProvider.onRequestRecommendation(request, mCallback);

        ArgumentCaptor<RecommendationResult> resultCaptor =
                ArgumentCaptor.forClass(RecommendationResult.class);
        verify(mCallback).onResult(resultCaptor.capture());

        return resultCaptor.getValue();
    }

    private ScoredNetwork[] verifyAndCaptureScoredNetworks() {
        ArgumentCaptor<ScoredNetwork[]> resultCaptor = ArgumentCaptor.forClass(
                ScoredNetwork[].class);
        verify(mNetworkScoreManager).updateScores(resultCaptor.capture());
        return resultCaptor.getValue();
    }
}
