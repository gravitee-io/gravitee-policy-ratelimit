/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.ratelimit;

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.local.LocalCacheRateLimitProvider;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static io.gravitee.common.http.GraviteeHttpHeader.X_GRAVITEE_API_KEY;
import static io.gravitee.common.http.GraviteeHttpHeader.X_GRAVITEE_API_NAME;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
@Ignore
public class RateLimitPolicyTest {

    private static final String API_KEY_HEADER_VALUE = "fbc40d50-5746-40af-b283-d7e99c1775c7";
    private static final String API_NAME_HEADER_VALUE = "my-api";

    private RateLimitService rateLimitService;

    @Mock
    private Request request;

    @Mock
    private Response response;

    private PolicyChain policyChain;

    @Mock
    private ExecutionContext executionContext;

    @Before
    public void init() {
        rateLimitService = new LocalCacheRateLimitProvider();
        ((LocalCacheRateLimitProvider)rateLimitService).clean();

        when(executionContext.getAttribute(ExecutionContext.ATTR_PLAN)).thenReturn("my-plan");
        when(executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID)).thenReturn("my-subscription");
    }

    @Test
    public void rateLimit_noRepository() {
        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        RateLimitConfiguration rateLimitConfiguration = new RateLimitConfiguration();

        policyChain = spy(PolicyChain.class);

        rateLimitConfiguration.setLimit(1);
        rateLimitConfiguration.setPeriodTime(1);
        rateLimitConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);
        policyConfiguration.setRate(rateLimitConfiguration);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        rateLimitPolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain).failWith(any(PolicyResult.class));
    }

    @Test
    public void singleRequest() throws InterruptedException {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(X_GRAVITEE_API_KEY, API_KEY_HEADER_VALUE);
                put(X_GRAVITEE_API_NAME, API_NAME_HEADER_VALUE);
            }
        });

        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        RateLimitConfiguration rateLimitConfiguration = new RateLimitConfiguration();

        rateLimitConfiguration.setLimit(10);
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);
        policyConfiguration.setRate(rateLimitConfiguration);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        final CountDownLatch latch = new CountDownLatch(1);

        rateLimitPolicy.onRequest(request, response, executionContext, policyChain = spy(new PolicyChain() {
            @Override
            public void doNext(Request request, Response response) {
                latch.countDown();
            }

            @Override
            public void failWith(PolicyResult policyResult) {
                latch.countDown();
            }

            @Override
            public void streamFailWith(PolicyResult policyResult) {

            }
        }));

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        verify(policyChain).doNext(request, response);
    }

    @Test
    public void multipleRequestsLegacy() throws InterruptedException {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(X_GRAVITEE_API_KEY, API_KEY_HEADER_VALUE);
                put(X_GRAVITEE_API_NAME, API_NAME_HEADER_VALUE);
            }
        });

        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        RateLimitConfiguration rateLimitConfiguration = new RateLimitConfiguration();

        int limit = 10;

        rateLimitConfiguration.setLimit(limit);
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);
        policyConfiguration.setRate(rateLimitConfiguration);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        int calls = 15;
        int exceedCalls = calls - limit;

        final CountDownLatch latch = new CountDownLatch(calls);

        policyChain = spy(new PolicyChain() {
            @Override
            public void doNext(Request request, Response response) {
                latch.countDown();
            }

            @Override
            public void failWith(PolicyResult policyResult) {
                latch.countDown();
            }

            @Override
            public void streamFailWith(PolicyResult policyResult) {

            }
        });

        InOrder inOrder = inOrder(policyChain);

        for (int i = 0 ; i < calls ; i++) {
            rateLimitPolicy.onRequest(request, response, executionContext, policyChain);
        }

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        inOrder.verify(policyChain, times(limit)).doNext(request, response);
        inOrder.verify(policyChain, times(exceedCalls)).failWith(any(PolicyResult.class));
    }

    
    @Test
    public void multipleRequestsTemplatableLimitFixed() throws InterruptedException {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(X_GRAVITEE_API_KEY, API_KEY_HEADER_VALUE);
                put(X_GRAVITEE_API_NAME, API_NAME_HEADER_VALUE);
            }
        });

        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        RateLimitConfiguration rateLimitConfiguration = new RateLimitConfiguration();

        int limit = 10;

        rateLimitConfiguration.setLimit(15);
        rateLimitConfiguration.setTemplatableLimit(Long.toString(limit));
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);
        policyConfiguration.setRate(rateLimitConfiguration);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        int calls = 15;
        int exceedCalls = calls - limit;

        final CountDownLatch latch = new CountDownLatch(calls);

        policyChain = spy(new PolicyChain() {
            @Override
            public void doNext(Request request, Response response) {
                latch.countDown();
            }

            @Override
            public void failWith(PolicyResult policyResult) {
                latch.countDown();
            }

            @Override
            public void streamFailWith(PolicyResult policyResult) {

            }
        });

        InOrder inOrder = inOrder(policyChain);

        for (int i = 0 ; i < calls ; i++) {
            rateLimitPolicy.onRequest(request, response, executionContext, policyChain);
        }

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        inOrder.verify(policyChain, times(limit)).doNext(request, response);
        inOrder.verify(policyChain, times(exceedCalls)).failWith(any(PolicyResult.class));
    }


    @Test
    public void multipleRequestsTemplatableLimitExpression() throws InterruptedException {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(X_GRAVITEE_API_KEY, API_KEY_HEADER_VALUE);
                put(X_GRAVITEE_API_NAME, API_NAME_HEADER_VALUE);
            }
        });

        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        RateLimitConfiguration rateLimitConfiguration = new RateLimitConfiguration();

        int limit = 10;

        rateLimitConfiguration.setLimit(15);
        rateLimitConfiguration.setTemplatableLimit("{(2*5)}");
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);
        policyConfiguration.setRate(rateLimitConfiguration);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        int calls = 15;
        int exceedCalls = calls - limit;

        final CountDownLatch latch = new CountDownLatch(calls);

        policyChain = spy(new PolicyChain() {
            @Override
            public void doNext(Request request, Response response) {
                latch.countDown();
            }

            @Override
            public void failWith(PolicyResult policyResult) {
                latch.countDown();
            }

            @Override
            public void streamFailWith(PolicyResult policyResult) {

            }
        });

        InOrder inOrder = inOrder(policyChain);

        for (int i = 0 ; i < calls ; i++) {
            rateLimitPolicy.onRequest(request, response, executionContext, policyChain);
        }

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        inOrder.verify(policyChain, times(limit)).doNext(request, response);
        inOrder.verify(policyChain, times(exceedCalls)).failWith(any(PolicyResult.class));
    }

}
