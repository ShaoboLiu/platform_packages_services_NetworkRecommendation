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
import static org.junit.Assert.fail;

import android.content.Intent;
import android.net.INetworkRecommendationProvider;
import android.net.NetworkCapabilities;
import android.net.NetworkKey;
import android.net.NetworkRecommendationProvider;
import android.net.NetworkScoreManager;
import android.net.RecommendationRequest;
import android.net.RecommendationResult;
import android.net.WifiKey;
import android.net.wifi.ScanResult;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ServiceTestRule;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
public class DefaultNetworkRecommendationServiceTest {

    private static final int RESULT_LATCH_TIMEOUT_MILLIS = 2000;
    private static final int WAIT_FOR_BIND_TIMEOUT_MILLIS = 2000;
    private static final int SEQUENCE_ID = 11;

    @Rule
    public final ServiceTestRule mServiceRule = new ServiceTestRule();

    private INetworkRecommendationProvider bind() throws TimeoutException {
        Intent bindIntent = new Intent(InstrumentationRegistry.getTargetContext(),
                DefaultNetworkRecommendationService.class);
        bindIntent.setAction(NetworkScoreManager.ACTION_RECOMMEND_NETWORKS);

        // https://code.google.com/p/android/issues/detail?id=200071
        // bindService can occasionally returns null.
        IBinder binder = null;
        boolean interrupted = false;
        try {
            long startTime = SystemClock.elapsedRealtime();
            long currentTime = startTime;
            while (currentTime < startTime + WAIT_FOR_BIND_TIMEOUT_MILLIS) {
                binder = mServiceRule.bindService(bindIntent);
                if (binder != null) {
                    return INetworkRecommendationProvider.Stub.asInterface(binder);
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    interrupted = true;
                    currentTime = SystemClock.elapsedRealtime();
                }
            }
            throw new TimeoutException("Unable to bind to service.");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Assert that when we make a request we get a response with the proper sequence.
     * <p />
     * We do not assert the config given to us, because we're simply verifying the behavior of
     * this service interacting with the provider. For recommendation tests, see
     * {@link DefaultNetworkRecommendationProviderTest}.
     */
    @Test
    public void requestRecommendation() throws Exception {
        INetworkRecommendationProvider service = bind();

        ScanResult[] scanResults = new ScanResult[5];
        for (int i = 0; i < 5; i++) {
            scanResults[i] = TestUtil.createMockScanResult(i);
        }

        RecommendationRequest request = new RecommendationRequest.Builder()
                .setScanResults(scanResults)
                .setNetworkCapabilities(new NetworkCapabilities().removeCapability(
                        NetworkCapabilities.NET_CAPABILITY_TRUSTED))
                .build();

        Result result = requestRecommendation(service, request, SEQUENCE_ID);
        synchronized (result) {
            assertEquals(result.sequence, SEQUENCE_ID);
        }
    }

    @Test
    public void scoreNetworks() throws Exception {
        INetworkRecommendationProvider service = bind();
        service.requestScores(new NetworkKey[]{new NetworkKey(new WifiKey("\"ProperlyQuoted\"",
                "aa:bb:cc:dd:ee:ff"))});
    }

    @Test
    public void scoreNetworks_empty() throws Exception {
        INetworkRecommendationProvider service = bind();
        service.requestScores(new NetworkKey[]{});
    }

    @Test
    public void scoreNetworks_invalid() throws Exception {
        INetworkRecommendationProvider service = bind();
        try {
            service.requestScores(new NetworkKey[]{
                    new NetworkKey(new WifiKey("ImproperlyQuoted", "aa:bb:cc:dd:ee:ff"))});
            fail("An invalid SSID should throw an exception.");
        } catch (IllegalArgumentException e) {
            // Expected.
        }
    }

    /**
     * Make a network recommendation request. Be sure to synchronize on the result to access its
     * values properly.
     */
    private Result requestRecommendation(INetworkRecommendationProvider service,
            RecommendationRequest request, int seqId) throws RemoteException, InterruptedException {
        final Result result = new Result();
        final CountDownLatch latch = new CountDownLatch(1);
        IRemoteCallback callback = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                synchronized (result) {
                    result.sequence = data.getInt(NetworkRecommendationProvider.EXTRA_SEQUENCE);
                    result.recommendation = data.getParcelable(
                            NetworkRecommendationProvider.EXTRA_RECOMMENDATION_RESULT);
                    latch.countDown();
                }
            }
        };

        service.requestRecommendation(request, callback, seqId);
        latch.await(RESULT_LATCH_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        return result;
    }

    private static class Result {
        int sequence;
        RecommendationResult recommendation;
    }
}
