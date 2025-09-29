/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.v3.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.local.ExecutionContextStub;
import io.gravitee.policy.ratelimit.local.LocalCacheRateLimitProvider;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(MockitoExtension.class)
public class RateLimitPolicyV3Test {

    private final LocalCacheRateLimitProvider rateLimitService = new LocalCacheRateLimitProvider();

    private Vertx vertx;

    @Captor
    ArgumentCaptor<PolicyResult> policyResultCaptor;

    @Mock
    private Request request;

    @Mock
    private Response response;

    private ExecutionContext executionContext;
    HttpHeaders responseHttpHeaders;

    @BeforeEach
    public void init() {
        vertx = Vertx.vertx();

        executionContext = spy(new ExecutionContextStub());
        executionContext.setAttribute(ExecutionContext.ATTR_PLAN, "my-plan");
        executionContext.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "my-subscription");

        lenient().when(executionContext.getComponent(Vertx.class)).thenReturn(vertx);
        lenient().when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        responseHttpHeaders = HttpHeaders.create();
        lenient().when(response.headers()).thenReturn(responseHttpHeaders);
    }

    @AfterEach
    void tearDown() {
        rateLimitService.clean();
        vertx.close().blockingAwait();
    }

    @Test
    public void should_fail_when_no_service_installed() {
        var policy = new RateLimitPolicyV3(
            RateLimitPolicyConfiguration.builder()
                .rate(RateLimitConfiguration.builder().limit(1).periodTime(1).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );

        vertx.runOnContext(event -> {
            // Given
            var policyChain = mock(PolicyChain.class);
            when(executionContext.getComponent(RateLimitService.class)).thenReturn(null);

            // When
            policy.onRequest(request, response, executionContext, policyChain);

            // Then
            verify(policyChain).failWith(policyResultCaptor.capture());
            SoftAssertions.assertSoftly(soft -> {
                var result = policyResultCaptor.getValue();
                soft.assertThat(result.statusCode()).isEqualTo(500);
                soft.assertThat(result.message()).isEqualTo("No rate-limit service has been installed.");
            });
        });
    }

    @Test
    public void should_add_headers_when_enabled() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var policy = new RateLimitPolicyV3(
            RateLimitPolicyConfiguration.builder()
                .addHeaders(true)
                .rate(RateLimitConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );

        vertx.runOnContext(event ->
            policy.onRequest(
                request,
                response,
                executionContext,
                chain(
                    (req, res) -> {
                        assertThat(responseHttpHeaders.get(RateLimitPolicyV3.X_RATE_LIMIT_LIMIT)).isEqualTo("10");
                        assertThat(responseHttpHeaders.get(RateLimitPolicyV3.X_RATE_LIMIT_REMAINING)).isEqualTo("9");
                        assertThat(responseHttpHeaders.get(RateLimitPolicyV3.X_RATE_LIMIT_RESET)).isEqualTo("10000");
                        latch.countDown();
                    },
                    policyResult -> {
                        fail("Unexpected failure: " + policyResult.message());
                        latch.countDown();
                    }
                )
            )
        );

        assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    public void should_not_add_headers_when_disabled() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var policy = new RateLimitPolicyV3(
            RateLimitPolicyConfiguration.builder()
                .addHeaders(false)
                .rate(RateLimitConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );

        vertx.runOnContext(event ->
            policy.onRequest(
                request,
                response,
                executionContext,
                chain(
                    (req, res) -> {
                        assertThat(responseHttpHeaders.toSingleValueMap())
                            .doesNotContainKey(RateLimitPolicyV3.X_RATE_LIMIT_LIMIT)
                            .doesNotContainKey(RateLimitPolicyV3.X_RATE_LIMIT_REMAINING)
                            .doesNotContainKey(RateLimitPolicyV3.X_RATE_LIMIT_RESET);
                        latch.countDown();
                    },
                    policyResult -> {
                        fail("Unexpected failure: " + policyResult.message());
                        latch.countDown();
                    }
                )
            )
        );

        assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    public void should_provide_info_when_limit_exceeded() throws InterruptedException {
        var latch = new CountDownLatch(1);
        var policy = new RateLimitPolicyV3(
            RateLimitPolicyConfiguration.builder()
                .rate(RateLimitConfiguration.builder().limit(0).dynamicLimit("0").periodTime(1).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );
        vertx.runOnContext(event ->
            policy.onRequest(
                request,
                response,
                executionContext,
                chain(
                    (req, res) -> {
                        fail("Should fail");
                        latch.countDown();
                    },
                    policyResult -> {
                        SoftAssertions.assertSoftly(soft -> {
                            soft.assertThat(policyResult.statusCode()).isEqualTo(429);
                            soft.assertThat(policyResult.key()).isEqualTo("RATE_LIMIT_TOO_MANY_REQUESTS");
                            soft
                                .assertThat(policyResult.message())
                                .isEqualTo("Rate limit exceeded! You reached the limit of 0 requests per 1 seconds");
                            soft
                                .assertThat(policyResult.parameters())
                                .contains(Map.entry("limit", 0L), Map.entry("period_time", 1L), Map.entry("period_unit", TimeUnit.SECONDS));
                        });
                        latch.countDown();
                    }
                )
            )
        );

        assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();
    }

    @Test
    public void should_fail_once_static_limit_is_reached() throws InterruptedException {
        int calls = 15;
        var latch = new CountDownLatch(calls);
        var policy = new RateLimitPolicyV3(
            RateLimitPolicyConfiguration.builder()
                .addHeaders(true)
                .rate(RateLimitConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );
        responseHttpHeaders = mock(HttpHeaders.class);
        lenient().when(response.headers()).thenReturn(responseHttpHeaders);

        vertx.runOnContext(event -> runMultipleRequests(policy, latch));

        assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();

        // 15 calls where the header is set
        verify(responseHttpHeaders, times(calls)).set(RateLimitPolicyV3.X_RATE_LIMIT_LIMIT, "10");
        // 6 calls when the limit is exceeded (10th + 5 exceed calls)
        verify(responseHttpHeaders, times(6)).set(RateLimitPolicyV3.X_RATE_LIMIT_REMAINING, "0");
    }

    @Test
    public void should_fail_once_dynamic_limit_is_reached() throws InterruptedException {
        int calls = 15;
        var latch = new CountDownLatch(calls);
        var policy = new RateLimitPolicyV3(
            RateLimitPolicyConfiguration.builder()
                .addHeaders(true)
                .rate(RateLimitConfiguration.builder().dynamicLimit("{(2*5)}").periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );
        responseHttpHeaders = mock(HttpHeaders.class);
        lenient().when(response.headers()).thenReturn(responseHttpHeaders);

        vertx.runOnContext(event -> runMultipleRequests(policy, latch));

        assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();

        // 15 calls where the header is set
        verify(responseHttpHeaders, times(calls)).set(RateLimitPolicyV3.X_RATE_LIMIT_LIMIT, "10");
        // 6 calls when the limit is exceeded (10th + 5 exceed calls)
        verify(responseHttpHeaders, times(6)).set(RateLimitPolicyV3.X_RATE_LIMIT_REMAINING, "0");
    }

    @Nested
    class WhenErrorsOccursAtRepositoryLevel {

        @BeforeEach
        void setUp() {
            var mockedRateLimitService = mock(RateLimitService.class);
            when(mockedRateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(
                Single.error(new RuntimeException("Error"))
            );
            lenient().when(executionContext.getComponent(RateLimitService.class)).thenReturn(mockedRateLimitService);
        }

        @Test
        public void should_add_headers_when_enabled() throws InterruptedException {
            var latch = new CountDownLatch(1);
            var policy = new RateLimitPolicyV3(
                RateLimitPolicyConfiguration.builder()
                    .addHeaders(true)
                    .rate(RateLimitConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
                    .build()
            );

            vertx.runOnContext(event ->
                policy.onRequest(
                    request,
                    response,
                    executionContext,
                    chain(
                        (req, res) -> {
                            assertThat(responseHttpHeaders.toSingleValueMap()).contains(
                                Map.entry(RateLimitPolicyV3.X_RATE_LIMIT_LIMIT, "10"),
                                Map.entry(RateLimitPolicyV3.X_RATE_LIMIT_REMAINING, "10"),
                                Map.entry(RateLimitPolicyV3.X_RATE_LIMIT_RESET, "-1")
                            );
                            latch.countDown();
                        },
                        policyResult -> {
                            fail("Unexpected failure: " + policyResult.message());
                            latch.countDown();
                        }
                    )
                )
            );

            assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();
        }
    }

    private void runMultipleRequests(RateLimitPolicyV3 policy, CountDownLatch latch) {
        policy.onRequest(
            request,
            response,
            executionContext,
            chain(
                (req, res) -> {
                    latch.countDown();
                    if (latch.getCount() > 0) {
                        runMultipleRequests(policy, latch);
                    }
                },
                policyResult -> {
                    latch.countDown();
                    if (latch.getCount() > 0) {
                        runMultipleRequests(policy, latch);
                    }
                }
            )
        );
    }

    private PolicyChain chain(BiConsumer<Request, Response> doNext, Consumer<PolicyResult> failWith) {
        return new PolicyChain() {
            @Override
            public void doNext(Request request, Response response) {
                doNext.accept(request, response);
            }

            @Override
            public void failWith(PolicyResult policyResult) {
                failWith.accept(policyResult);
            }

            @Override
            public void streamFailWith(PolicyResult policyResult) {}
        };
    }
}
