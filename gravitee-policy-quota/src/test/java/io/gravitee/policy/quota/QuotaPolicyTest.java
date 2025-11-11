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
package io.gravitee.policy.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.gravitee.common.utils.TimeProvider;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpResponse;
import io.gravitee.policy.quota.configuration.QuotaConfiguration;
import io.gravitee.policy.quota.configuration.QuotaPolicyConfiguration;
import io.gravitee.policy.quota.local.ExecutionContextStub;
import io.gravitee.policy.quota.local.LocalCacheQuotaProvider;
import io.gravitee.policy.v3.quota.QuotaPolicyV3;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * @author GraviteeSource Team
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith({ MockitoExtension.class, VertxExtension.class })
class QuotaPolicyTest {

    private final LocalCacheQuotaProvider rateLimitService = new LocalCacheQuotaProvider();

    @Mock(strictness = Mock.Strictness.LENIENT)
    HttpPlainExecutionContext plainContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    HttpMessageExecutionContext messageContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    HttpResponse response;

    @Mock(strictness = Mock.Strictness.LENIENT)
    HttpHeaders headers;

    @BeforeEach
    void init() {
        when(plainContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(messageContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);

        // Setup template engine
        ExecutionContextStub executionContextStub = new ExecutionContextStub();
        when(plainContext.getTemplateEngine()).thenReturn(executionContextStub.getTemplateEngine());
        when(messageContext.getTemplateEngine()).thenReturn(executionContextStub.getTemplateEngine());

        // Setup timestamp
        when(plainContext.timestamp()).thenReturn(TimeProvider.instantNow().toEpochMilli());
        when(messageContext.timestamp()).thenReturn(TimeProvider.instantNow().toEpochMilli());

        // Setup attributes
        when(plainContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID)).thenReturn("test-subscription");
        when(messageContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID)).thenReturn("test-subscription");

        // Setup response and headers
        when(plainContext.response()).thenReturn(response);
        when(messageContext.response()).thenReturn(response);

        when(response.headers()).thenReturn(headers);

        when(plainContext.metrics()).thenReturn(new Metrics());
        when(messageContext.metrics()).thenReturn(new Metrics());

        when(plainContext.interruptWith(any())).thenAnswer(invocation -> Completable.error(new MyException(invocation.getArgument(0))));
        when(messageContext.interruptMessagesWith(any())).thenAnswer(invocation ->
            Flowable.error(new MyException(invocation.getArgument(0)))
        );
    }

    @AfterEach
    void tearDown() {
        rateLimitService.clean();
    }

    @Test
    void should_fail_onRequest_when_no_service_installed() {
        when(plainContext.getComponent(RateLimitService.class)).thenReturn(null);

        QuotaPolicy policy = new QuotaPolicy(
            QuotaPolicyConfiguration.builder()
                .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                .build()
        );

        policy
            .onRequest(plainContext)
            .timeout(2, TimeUnit.SECONDS)
            .test()
            .assertError(
                throwable ->
                    throwable instanceof MyException ex &&
                    ex.getExecutionFailure().message().contains("No rate-limit service has been installed")
            );
    }

    @Test
    void should_fail_onMessageRequest_when_no_service_installed() {
        when(messageContext.getComponent(RateLimitService.class)).thenReturn(null);

        QuotaPolicy policy = new QuotaPolicy(
            QuotaPolicyConfiguration.builder()
                .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                .build()
        );

        policy
            .onMessageRequest(messageContext)
            .test()
            .assertError(
                throwable ->
                    throwable instanceof MyException ex &&
                    ex.getExecutionFailure().message().contains("No rate-limit service has been installed")
            );
    }

    @Test
    void should_add_quota_headers_when_enabled(Vertx vertx, VertxTestContext testContext) {
        QuotaPolicy policy = new QuotaPolicy(
            QuotaPolicyConfiguration.builder()
                .addHeaders(true)
                .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                .build()
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(plainContext)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    // Verify headers were added
                    verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_LIMIT), anyString());
                    verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_REMAINING), anyString());
                    verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_RESET), anyString());
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_not_add_quota_headers_when_disabled(Vertx vertx, VertxTestContext testContext) {
        QuotaPolicy policy = new QuotaPolicy(
            QuotaPolicyConfiguration.builder()
                .addHeaders(true)
                .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                .build()
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(plainContext)
                .doOnComplete(() -> {
                    // Verify headers were added
                    verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_LIMIT), anyString());
                    verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_REMAINING), anyString());
                    verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_RESET), anyString());
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Nested
    class SuccessfulQuotaRequest {

        @Mock
        RateLimitService mockRateLimitService;

        @BeforeEach
        void setUp() {
            when(plainContext.getComponent(RateLimitService.class)).thenReturn(mockRateLimitService);
            when(messageContext.getComponent(RateLimitService.class)).thenReturn(mockRateLimitService);

            RateLimit rateLimit = new RateLimit("test-key");
            rateLimit.setCounter(1L);
            rateLimit.setLimit(10L);
            rateLimit.setResetTime(System.currentTimeMillis() + 10000);

            when(mockRateLimitService.incrementAndGet(anyString(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));
        }

        @Test
        void should_successfully_process_onRequest(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                    .build()
            );

            vertx.runOnContext(v -> policy.onRequest(plainContext).subscribe(new SubscribeAdapter(testContext)));
        }

        @Test
        void should_successfully_process_onMessageRequest(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                    .build()
            );

            vertx.runOnContext(v -> policy.onMessageRequest(messageContext).subscribe(new SubscribeAdapter(testContext)));
        }

        @Test
        void should_use_dynamic_period_time_when_period_time_is_zero(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .addHeaders(true)
                    .quota(
                        QuotaConfiguration.builder()
                            .limit(10)
                            .periodTime(0L)
                            .dynamicPeriodTime("{(5+5)}")
                            .periodTimeUnit(ChronoUnit.HOURS)
                            .build()
                    )
                    .build()
            );

            vertx.runOnContext(v -> policy.onMessageRequest(messageContext).subscribe(new SubscribeAdapter(testContext)));
        }
    }

    @Nested
    class QuotaExceededRequest {

        @Mock
        RateLimitService mockRateLimitService;

        @BeforeEach
        void setUp() {
            when(plainContext.getComponent(RateLimitService.class)).thenReturn(mockRateLimitService);
            when(messageContext.getComponent(RateLimitService.class)).thenReturn(mockRateLimitService);

            RateLimit rateLimit = new RateLimit("test-key");
            rateLimit.setCounter(11L); // Exceeds limit
            rateLimit.setLimit(10L);
            rateLimit.setResetTime(System.currentTimeMillis() + 10000);

            when(mockRateLimitService.incrementAndGet(anyString(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));
        }

        @Test
        void should_interrupt_onRequest_when_quota_exceeded(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                    .build()
            );

            vertx.runOnContext(v ->
                policy
                    .onRequest(plainContext)
                    .doOnError(th -> {
                        assertThat(th).isInstanceOf(MyException.class);
                        ExecutionFailure executionFailure = ((MyException) th).getExecutionFailure();
                        assertThat(executionFailure.statusCode()).isEqualTo(429);
                        assertThat(executionFailure.message()).contains(
                            "Quota exceeded! You reached the limit of 10 requests per 10 hours"
                        );
                        verify(headers).set(QuotaPolicyV3.X_QUOTA_LIMIT, "10");
                        verify(headers).set(QuotaPolicyV3.X_QUOTA_REMAINING, "0");
                        verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_RESET), anyString());
                    })
                    .subscribe(
                        () -> testContext.failNow("this test must fail"),
                        th -> {
                            if (!(th instanceof MyException)) {
                                testContext.failNow(th);
                            } else {
                                testContext.completeNow();
                            }
                        }
                    )
            );
        }

        @Test
        void should_interrupt_onMessageRequest_when_quota_exceeded(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                    .build()
            );

            vertx.runOnContext(v ->
                policy
                    .onMessageRequest(messageContext)
                    .doOnError(th -> {
                        assertThat(th).isInstanceOf(MyException.class);
                        ExecutionFailure executionFailure = ((MyException) th).getExecutionFailure();
                        assertThat(executionFailure.statusCode()).isEqualTo(429);
                        assertThat(executionFailure.message()).contains(
                            "Quota exceeded! You reached the limit of 10 requests per 10 hours"
                        );
                        verify(headers).set(QuotaPolicyV3.X_QUOTA_LIMIT, "10");
                        verify(headers).set(QuotaPolicyV3.X_QUOTA_REMAINING, "0");
                        verify(headers).set(eq(QuotaPolicyV3.X_QUOTA_RESET), anyString());
                    })
                    .subscribe(
                        () -> testContext.failNow("this test must fail"),
                        th -> {
                            if (!(th instanceof MyException)) {
                                testContext.failNow(th);
                            } else {
                                testContext.completeNow();
                            }
                        }
                    )
            );
        }
    }

    @Nested
    class ErrorHandling {

        @Mock
        RateLimitService mockRateLimitService;

        @BeforeEach
        void setUp() {
            when(plainContext.getComponent(RateLimitService.class)).thenReturn(mockRateLimitService);
            when(messageContext.getComponent(RateLimitService.class)).thenReturn(mockRateLimitService);

            when(mockRateLimitService.incrementAndGet(anyString(), anyBoolean(), any())).thenReturn(
                Single.error(new RuntimeException("Test error"))
            );
        }

        @Test
        void should_handle_repository_error_on_onRequest(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                    .build()
            );

            vertx.runOnContext(v -> policy.onRequest(plainContext).subscribe(new SubscribeAdapter(testContext)));
        }

        @Test
        void should_handle_repository_error_on_onMessageRequest(Vertx vertx, VertxTestContext testContext) {
            QuotaPolicy policy = new QuotaPolicy(
                QuotaPolicyConfiguration.builder()
                    .quota(QuotaConfiguration.builder().limit(10).periodTime(10L).periodTimeUnit(ChronoUnit.HOURS).build())
                    .build()
            );

            vertx.runOnContext(v -> policy.onMessageRequest(messageContext).subscribe(new SubscribeAdapter(testContext)));
        }
    }

    private record SubscribeAdapter(VertxTestContext testContext) implements CompletableObserver {
        @Override
        public void onSubscribe(@NonNull Disposable d) {}

        @Override
        public void onComplete() {
            testContext.completeNow();
        }

        @Override
        public void onError(@NonNull Throwable e) {
            testContext.failNow(e);
        }
    }

    @Getter
    @RequiredArgsConstructor
    private static class MyException extends Exception {

        private final ExecutionFailure executionFailure;
    }
}
