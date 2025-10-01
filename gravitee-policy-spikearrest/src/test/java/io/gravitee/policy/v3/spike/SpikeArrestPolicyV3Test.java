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
package io.gravitee.policy.v3.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.spike.configuration.SpikeArrestConfiguration;
import io.gravitee.policy.spike.configuration.SpikeArrestPolicyConfiguration;
import io.gravitee.policy.spike.local.ExecutionContextStub;
import io.gravitee.policy.spike.local.LocalCacheRateLimitProvider;
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
public class SpikeArrestPolicyV3Test {

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
        var policy = new SpikeArrestPolicyV3(
            SpikeArrestPolicyConfiguration.builder()
                .spike(SpikeArrestConfiguration.builder().limit(1).periodTime(1).periodTimeUnit(TimeUnit.SECONDS).build())
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
        var policy = new SpikeArrestPolicyV3(
            SpikeArrestPolicyConfiguration.builder()
                .addHeaders(true)
                .spike(SpikeArrestConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
                .build()
        );

        vertx.runOnContext(event ->
            policy.onRequest(
                request,
                response,
                executionContext,
                chain(
                    (req, res) -> {
                        assertThat(responseHttpHeaders.get(SpikeArrestPolicyV3.X_SPIKE_ARREST_LIMIT)).isEqualTo("1");
                        assertThat(responseHttpHeaders.get(SpikeArrestPolicyV3.X_SPIKE_ARREST_SLICE)).isEqualTo("1000ms");
                        assertThat(responseHttpHeaders.get(SpikeArrestPolicyV3.X_SPIKE_ARREST_RESET)).isEqualTo("1000");
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
        var policy = new SpikeArrestPolicyV3(
            SpikeArrestPolicyConfiguration.builder()
                .addHeaders(false)
                .spike(SpikeArrestConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
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
                            .doesNotContainKey(SpikeArrestPolicyV3.X_SPIKE_ARREST_LIMIT)
                            .doesNotContainKey(SpikeArrestPolicyV3.X_SPIKE_ARREST_SLICE)
                            .doesNotContainKey(SpikeArrestPolicyV3.X_SPIKE_ARREST_RESET);
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
        var latch = new CountDownLatch(2);
        var policy = new SpikeArrestPolicyV3(
            SpikeArrestPolicyConfiguration.builder()
                .spike(
                    SpikeArrestConfiguration.builder()
                        .limit(1)
                        .dynamicLimit("0")
                        .periodTime(100)
                        .periodTimeUnit(TimeUnit.MILLISECONDS)
                        .build()
                )
                .build()
        );
        vertx.runOnContext(event ->
            // Run 1st request
            policy.onRequest(
                request,
                response,
                executionContext,
                chain(
                    (_req, _res) -> {
                        latch.countDown();

                        // Run 2nd request that should fail
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
                                        soft.assertThat(policyResult.key()).isEqualTo("SPIKE_ARREST_TOO_MANY_REQUESTS");
                                        soft
                                            .assertThat(policyResult.message())
                                            .isEqualTo("Spike limit exceeded! You reached the limit of 1 requests per 100 ms.");
                                        soft
                                            .assertThat(policyResult.parameters())
                                            .contains(
                                                Map.entry("slice_limit", 1L),
                                                Map.entry("slice_period_time", 100L),
                                                Map.entry("slice_period_unit", TimeUnit.MILLISECONDS),
                                                Map.entry("limit", 1L),
                                                Map.entry("period_time", 100L),
                                                Map.entry("period_unit", TimeUnit.MILLISECONDS)
                                            );
                                    });
                                    latch.countDown();
                                }
                            )
                        );
                    },
                    policyResult -> {
                        latch.countDown();
                        fail("Unexpected failure: " + policyResult.message());
                    }
                )
            )
        );

        assertThat(latch.await(10000, TimeUnit.MILLISECONDS)).isTrue();
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
            var policy = new SpikeArrestPolicyV3(
                SpikeArrestPolicyConfiguration.builder()
                    .addHeaders(true)
                    .spike(SpikeArrestConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.SECONDS).build())
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
                                Map.entry(SpikeArrestPolicyV3.X_SPIKE_ARREST_LIMIT, "1"),
                                Map.entry(SpikeArrestPolicyV3.X_SPIKE_ARREST_SLICE, "1000ms"),
                                Map.entry(SpikeArrestPolicyV3.X_SPIKE_ARREST_RESET, "-1")
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
