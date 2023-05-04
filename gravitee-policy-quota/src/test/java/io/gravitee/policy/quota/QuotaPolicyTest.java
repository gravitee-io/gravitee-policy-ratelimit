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
package io.gravitee.policy.quota;

import static org.mockito.Mockito.*;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.quota.configuration.QuotaConfiguration;
import io.gravitee.policy.quota.configuration.QuotaMode;
import io.gravitee.policy.quota.configuration.QuotaPolicyConfiguration;
import io.gravitee.policy.quota.local.LocalCacheQuotaProvider;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.env.Environment;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class QuotaPolicyTest {

    private RateLimitService rateLimitService;

    @Mock
    protected Request request;

    @Mock
    protected Response response;

    protected PolicyChain policyChain;

    @Mock
    protected ExecutionContext executionContext;

    @Mock
    private Environment environment;

    @Mock
    private HttpHeaders responseHttpHeaders;

    @Before
    public void init() {
        rateLimitService = new LocalCacheQuotaProvider();
        ((LocalCacheQuotaProvider) rateLimitService).clean();

        when(request.timestamp()).thenReturn(System.currentTimeMillis());
        when(executionContext.getAttribute(ExecutionContext.ATTR_PLAN)).thenReturn("my-plan");
        when(executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID)).thenReturn("my-subscription");

        when(executionContext.getComponent(Environment.class)).thenReturn(environment);
        when(executionContext.getTemplateEngine()).thenReturn(TemplateEngine.templateEngine());
    }

    @Test
    public void rateLimit_noRepository() {
        QuotaPolicyConfiguration policyConfiguration = new QuotaPolicyConfiguration();
        QuotaConfiguration rateLimitConfiguration = new QuotaConfiguration();

        policyChain = spy(PolicyChain.class);

        rateLimitConfiguration.setLimit(1);
        rateLimitConfiguration.setPeriodTime(1);
        rateLimitConfiguration.setPeriodTimeUnit(ChronoUnit.SECONDS);
        policyConfiguration.setQuota(rateLimitConfiguration);

        QuotaPolicy rateLimitPolicy = new QuotaPolicy(policyConfiguration);

        rateLimitPolicy.onRequest(request, response, executionContext, policyChain);

        verify(policyChain).failWith(any(PolicyResult.class));
    }

    @Test
    public void singleRequest_withQuotaHeaders() throws InterruptedException {
        QuotaPolicyConfiguration policyConfiguration = new QuotaPolicyConfiguration();
        QuotaConfiguration rateLimitConfiguration = new QuotaConfiguration();

        rateLimitConfiguration.setLimit(10);
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(ChronoUnit.SECONDS);
        policyConfiguration.setQuota(rateLimitConfiguration);

        QuotaPolicy rateLimitPolicy = new QuotaPolicy(policyConfiguration);

        lenient().when(environment.getProperty(eq(QuotaPolicy.QUOTA_MODE_PROPERTY), anyString())).thenReturn(QuotaMode.STRICT.name());
        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(response.headers()).thenReturn(responseHttpHeaders);

        final CountDownLatch latch = new CountDownLatch(1);

        rateLimitPolicy.onRequest(
            request,
            response,
            executionContext,
            policyChain =
                spy(
                    new PolicyChain() {
                        @Override
                        public void doNext(Request request, Response response) {
                            latch.countDown();
                        }

                        @Override
                        public void failWith(PolicyResult policyResult) {
                            latch.countDown();
                        }

                        @Override
                        public void streamFailWith(PolicyResult policyResult) {}
                    }
                )
        );

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        verify(responseHttpHeaders).set(QuotaPolicy.X_QUOTA_LIMIT, "10");
        verify(responseHttpHeaders).set(QuotaPolicy.X_QUOTA_REMAINING, "9");
        verify(policyChain).doNext(request, response);
    }

    @Test
    public void singleRequest_withoutQuotaHeaders() throws InterruptedException {
        QuotaPolicyConfiguration policyConfiguration = new QuotaPolicyConfiguration();
        QuotaConfiguration rateLimitConfiguration = new QuotaConfiguration();

        rateLimitConfiguration.setLimit(10);
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(ChronoUnit.SECONDS);
        policyConfiguration.setQuota(rateLimitConfiguration);
        policyConfiguration.setAddHeaders(false);

        QuotaPolicy rateLimitPolicy = new QuotaPolicy(policyConfiguration);

        lenient().when(environment.getProperty(eq(QuotaPolicy.QUOTA_MODE_PROPERTY), anyString())).thenReturn(QuotaMode.STRICT.name());
        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        final CountDownLatch latch = new CountDownLatch(1);

        rateLimitPolicy.onRequest(
            request,
            response,
            executionContext,
            policyChain =
                spy(
                    new PolicyChain() {
                        @Override
                        public void doNext(Request request, Response response) {
                            latch.countDown();
                        }

                        @Override
                        public void failWith(PolicyResult policyResult) {
                            latch.countDown();
                        }

                        @Override
                        public void streamFailWith(PolicyResult policyResult) {}
                    }
                )
        );

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        verify(responseHttpHeaders, never()).set(QuotaPolicy.X_QUOTA_LIMIT, "10");
        verify(responseHttpHeaders, never()).set(QuotaPolicy.X_QUOTA_REMAINING, "9");
        verify(policyChain).doNext(request, response);
    }

    @Test
    public void singleRequest_withRoundMode() throws InterruptedException {
        QuotaPolicyConfiguration policyConfiguration = new QuotaPolicyConfiguration();
        QuotaConfiguration rateLimitConfiguration = new QuotaConfiguration();

        rateLimitConfiguration.setLimit(10);
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(ChronoUnit.SECONDS);
        policyConfiguration.setQuota(rateLimitConfiguration);
        policyConfiguration.setAddHeaders(false);

        QuotaPolicy rateLimitPolicy = new QuotaPolicy(policyConfiguration);

        lenient().when(environment.getProperty(eq(QuotaPolicy.QUOTA_MODE_PROPERTY), anyString())).thenReturn(QuotaMode.ROUND.name());
        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        final CountDownLatch latch = new CountDownLatch(1);

        rateLimitPolicy.onRequest(
            request,
            response,
            executionContext,
            policyChain =
                spy(
                    new PolicyChain() {
                        @Override
                        public void doNext(Request request, Response response) {
                            latch.countDown();
                        }

                        @Override
                        public void failWith(PolicyResult policyResult) {
                            latch.countDown();
                        }

                        @Override
                        public void streamFailWith(PolicyResult policyResult) {}
                    }
                )
        );

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        verify(responseHttpHeaders, never()).set(QuotaPolicy.X_QUOTA_LIMIT, "10");
        verify(responseHttpHeaders, never()).set(QuotaPolicy.X_QUOTA_REMAINING, "9");
        verify(policyChain).doNext(request, response);
    }

    @Test
    public void multipleRequestsLegacy() throws InterruptedException {
        QuotaPolicyConfiguration policyConfiguration = new QuotaPolicyConfiguration();
        QuotaConfiguration rateLimitConfiguration = new QuotaConfiguration();

        int limit = 10;

        rateLimitConfiguration.setLimit(limit);
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(ChronoUnit.SECONDS);
        policyConfiguration.setQuota(rateLimitConfiguration);

        QuotaPolicy rateLimitPolicy = new QuotaPolicy(policyConfiguration);

        when(environment.getProperty(eq(QuotaPolicy.QUOTA_MODE_PROPERTY), anyString())).thenReturn(QuotaMode.STRICT.name());
        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(response.headers()).thenReturn(responseHttpHeaders);

        int calls = 15;
        int exceedCalls = calls - limit;

        final CountDownLatch latch = new CountDownLatch(calls);

        policyChain =
            spy(
                new PolicyChain() {
                    @Override
                    public void doNext(Request request, Response response) {
                        latch.countDown();
                    }

                    @Override
                    public void failWith(PolicyResult policyResult) {
                        latch.countDown();
                    }

                    @Override
                    public void streamFailWith(PolicyResult policyResult) {}
                }
            );

        InOrder inOrder = inOrder(policyChain);

        for (int i = 0; i < calls; i++) {
            rateLimitPolicy.onRequest(request, response, executionContext, policyChain);
        }

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        inOrder.verify(policyChain, times(limit)).doNext(request, response);
        inOrder.verify(policyChain, times(exceedCalls)).failWith(any(PolicyResult.class));

        verify(responseHttpHeaders, times(calls)).set(QuotaPolicy.X_QUOTA_LIMIT, "10");
        verify(responseHttpHeaders, times(exceedCalls + 1)).set(QuotaPolicy.X_QUOTA_REMAINING, "0");
    }

    @Test
    public void multipleRequestsTemplatableLimitExpression() throws InterruptedException {
        QuotaPolicyConfiguration policyConfiguration = new QuotaPolicyConfiguration();
        QuotaConfiguration rateLimitConfiguration = new QuotaConfiguration();

        int limit = 10;

        rateLimitConfiguration.setLimit(0);
        rateLimitConfiguration.setDynamicLimit("{(2*5)}");
        rateLimitConfiguration.setPeriodTime(10);
        rateLimitConfiguration.setPeriodTimeUnit(ChronoUnit.SECONDS);
        policyConfiguration.setQuota(rateLimitConfiguration);

        QuotaPolicy rateLimitPolicy = new QuotaPolicy(policyConfiguration);

        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(response.headers()).thenReturn(responseHttpHeaders);

        int calls = 15;
        int exceedCalls = calls - limit;

        final CountDownLatch latch = new CountDownLatch(calls);

        policyChain =
            spy(
                new PolicyChain() {
                    @Override
                    public void doNext(Request request, Response response) {
                        latch.countDown();
                    }

                    @Override
                    public void failWith(PolicyResult policyResult) {
                        latch.countDown();
                    }

                    @Override
                    public void streamFailWith(PolicyResult policyResult) {}
                }
            );

        InOrder inOrder = inOrder(policyChain);

        for (int i = 0; i < calls; i++) {
            rateLimitPolicy.onRequest(request, response, executionContext, policyChain);
        }

        Assert.assertTrue(latch.await(10000, TimeUnit.MILLISECONDS));

        inOrder.verify(policyChain, times(limit)).doNext(request, response);
        inOrder.verify(policyChain, times(exceedCalls)).failWith(any(PolicyResult.class));

        verify(responseHttpHeaders, times(calls)).set(QuotaPolicy.X_QUOTA_LIMIT, "10");
        verify(responseHttpHeaders, times(exceedCalls + 1)).set(QuotaPolicy.X_QUOTA_REMAINING, "0");
    }
}
