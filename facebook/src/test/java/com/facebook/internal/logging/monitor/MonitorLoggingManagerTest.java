/**
 * Copyright (c) 2014-present, Facebook, Inc. All rights reserved.
 * <p>
 * You are hereby granted a non-exclusive, worldwide, royalty-free license to use,
 * copy, modify, and distribute this software in source code or binary form for use
 * in connection with the web services and APIs provided by Facebook.
 * <p>
 * As with any software that integrates with the Facebook platform, your use of
 * this software is subject to the Facebook Developer Principles and Policies
 * [http://developers.facebook.com/policy/]. This copyright notice shall be
 * included in all copies or substantial portions of the software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.facebook.internal.logging.monitor;

import com.facebook.FacebookPowerMockTestCase;
import com.facebook.FacebookSdk;
import com.facebook.GraphRequest;
import com.facebook.GraphRequestBatch;
import com.facebook.internal.logging.ExternalLog;
import com.facebook.internal.logging.LoggingCache;
import com.facebook.internal.logging.LoggingStore;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.reflect.Whitebox;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.facebook.internal.logging.monitor.MonitorLoggingTestUtil.TEST_APP_ID;
import static com.facebook.internal.logging.monitor.MonitorLoggingTestUtil.TEST_TIME_START;
import static java.lang.Thread.sleep;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;

@PrepareForTest({
        FacebookSdk.class,
        MonitorLoggingManager.class,
        GraphRequest.class,
})
public class MonitorLoggingManagerTest extends FacebookPowerMockTestCase {

    @Mock
    private LoggingCache mockMonitorLoggingQueue;
    @Mock
    private LoggingStore mockMonitorLoggingStore;
    @Mock
    private MonitorLoggingManager mockMonitorLoggingManager;
    private ScheduledExecutorService mockExecutor;
    private MonitorLog monitorLog;
    private static final int TEST_MAX_LOG_NUMBER_PER_REQUEST = 3;
    private static final int TIMES = 2;

    @Before
    public void init() {
        spy(FacebookSdk.class);
        when(FacebookSdk.isInitialized()).thenReturn(true);
        PowerMockito.when(FacebookSdk.getApplicationContext()).thenReturn(
                RuntimeEnvironment.application);

        MonitorLoggingQueue monitorLoggingQueue = MonitorLoggingQueue.getInstance();
        mockMonitorLoggingQueue = Mockito.spy(monitorLoggingQueue);
        MonitorLoggingManager monitorLoggingManager = MonitorLoggingManager.getInstance(
                mockMonitorLoggingQueue, mockMonitorLoggingStore);
        mockExecutor = Mockito.spy(new FacebookSerialThreadPoolExecutor(1));

        mock(Executors.class);
        Whitebox.setInternalState(monitorLoggingManager, "singleThreadExecutor", mockExecutor);
        Whitebox.setInternalState(monitorLoggingManager, "logQueue", mockMonitorLoggingQueue);
        mockMonitorLoggingManager = Mockito.spy(monitorLoggingManager);
        monitorLog = MonitorLoggingTestUtil.getTestMonitorLog(TEST_TIME_START);
    }

    @Test
    public void testAddLogThenHasNotReachedFlushLimit() throws InterruptedException {
        when(mockMonitorLoggingQueue.addLog(any(ExternalLog.class))).thenReturn(false);
        mockMonitorLoggingManager.addLog(monitorLog);

        // make sure that singleThreadExecutor has been scheduled a future task successfully
        sleep(300);
        verify(mockExecutor).schedule(any(Runnable.class), anyInt(), any(TimeUnit.class));
    }

    @Test
    public void testAddLogThenHasReachedFlushLimit() {
        when(mockMonitorLoggingQueue.addLog(any(ExternalLog.class))).thenReturn(true);
        mockMonitorLoggingManager.addLog(monitorLog);

        verify(mockMonitorLoggingQueue).addLog(monitorLog);
        verify(mockMonitorLoggingManager).flushAndWait();
    }

    @Test
    public void testFlushAndWait() {
        PowerMockito.mockStatic(GraphRequest.class);
        PowerMockito.mockStatic(MonitorLoggingManager.class);
        spy(MonitorLoggingManager.class);
        when(FacebookSdk.getApplicationId()).thenReturn(TEST_APP_ID);

        mockMonitorLoggingManager.flushAndWait();
        PowerMockito.verifyStatic();
        GraphRequest.executeBatchAsync(any(GraphRequestBatch.class));

        PowerMockito.verifyStatic();
        MonitorLoggingManager.buildRequests(any(MonitorLoggingQueue.class));
    }

    @Test
    public void testBuildRequestsWhenAppIDIsNull() {
        when(FacebookSdk.getApplicationId()).thenReturn(null);
        List<GraphRequest> requests = MonitorLoggingManager.buildRequests(mockMonitorLoggingQueue);
        verifyNoMoreInteractions(mockMonitorLoggingQueue);
        Assert.assertEquals(0, requests.size());
    }

    @Test
    public void testBuildRequestsWhenAppIDIsNotNull() {
        when(FacebookSdk.getApplicationId()).thenReturn(TEST_APP_ID);
        ReflectionHelpers.setStaticField(MonitorLoggingManager.class, "MAX_LOG_NUMBER_PER_REQUEST", TEST_MAX_LOG_NUMBER_PER_REQUEST);
        for (int i = 0; i < TEST_MAX_LOG_NUMBER_PER_REQUEST * TIMES; i++) {
            mockMonitorLoggingManager.addLog(monitorLog);
        }

        List<GraphRequest> requests = MonitorLoggingManager.buildRequests(mockMonitorLoggingQueue);
        verify(mockMonitorLoggingQueue, times(TEST_MAX_LOG_NUMBER_PER_REQUEST * TIMES)).fetchLog();
        Assert.assertEquals(TIMES, requests.size());
    }

    @Test
    public void testBuildPostRequestFromLogs() {
        GraphRequest request = MonitorLoggingManager.buildPostRequestFromLogs(Arrays.asList(monitorLog));
        Assert.assertNotNull(request);
    }

    @After
    public void tearDown() {
        mockExecutor.shutdown();
        reset(mockMonitorLoggingQueue);

        // empty mockMonitorLoggingQueue
        while (!mockMonitorLoggingQueue.isEmpty()) {
            mockMonitorLoggingQueue.fetchLog();
        }
    }
}