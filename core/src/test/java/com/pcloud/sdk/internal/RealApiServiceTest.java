package com.pcloud.sdk.internal;

import com.pcloud.sdk.ApiService;
import com.pcloud.sdk.api.ApiServiceTest;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RealApiServiceTest extends ApiServiceTest<RealApiService> {

    @Before
    public void setUp() throws Exception {
        testInstance(new RealApiService());
    }

    @Override
    @After
    public void tearDown() throws Exception {
        super.tearDown();
        testInstance().shutdown();
    }

    @Test
    public void newBuilder_ReturnsBuilderWithSameConfiguration() throws Exception {
        ApiService.Builder builder = testInstance().newBuilder();
        RealApiService newService = (RealApiService) builder.create();

        assertEquals(testInstance().callbackExecutor(), newService.callbackExecutor());
        assertEquals(testInstance().progressCallbackThresholdBytes(), newService.progressCallbackThresholdBytes());

        OkHttpClient originalClient = testInstance().httpClient(), newClient = newService.httpClient();
        assertEquals(originalClient.connectionPool(), newClient.connectionPool());
        assertEquals(originalClient.dispatcher(), newClient.dispatcher());
        assertEquals(originalClient.readTimeoutMillis(), newClient.readTimeoutMillis());
        assertEquals(originalClient.writeTimeoutMillis(), newClient.writeTimeoutMillis());
        assertEquals(originalClient.connectTimeoutMillis(), newClient.connectTimeoutMillis());
        assertEquals(originalClient.protocols(), newClient.protocols());
        assertEquals(originalClient.interceptors(), newClient.interceptors());
        assertEquals(originalClient.cache(), newClient.cache());
    }
}