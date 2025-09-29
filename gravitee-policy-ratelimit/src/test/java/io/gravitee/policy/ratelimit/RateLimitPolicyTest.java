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
package io.gravitee.policy.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpResponse;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.ratelimit.ErrorStrategy;
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
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith({ MockitoExtension.class, VertxExtension.class })
class RateLimitPolicyTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private RateLimitService rateLimitService;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpMessageExecutionContext messageContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpPlainExecutionContext plainContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpResponse response;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpHeaders headers;

    private RateLimitPolicy policy;
    private RateLimitPolicyConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new RateLimitPolicyConfiguration();
        RateLimitConfiguration rateLimitConfig = new RateLimitConfiguration();
        rateLimitConfig.setLimit(10);
        rateLimitConfig.setPeriodTime(1);
        rateLimitConfig.setPeriodTimeUnit(TimeUnit.MINUTES);
        configuration.setRate(rateLimitConfig);
        configuration.setAddHeaders(true);

        policy = new RateLimitPolicy(configuration);

        when(messageContext.metrics()).thenReturn(new Metrics());
        when(messageContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(messageContext.response()).thenReturn(response);
        when(response.headers()).thenReturn(headers);
        when(messageContext.interruptMessagesWith(any())).thenAnswer(invocationOnMock ->
            Flowable.error(new MyException(invocationOnMock.getArgument(0)))
        );

        when(plainContext.metrics()).thenReturn(new Metrics());
        when(plainContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(plainContext.response()).thenReturn(response);
        when(plainContext.interruptWith(any())).thenAnswer(invocationOnMock ->
            Completable.error(new MyException(invocationOnMock.getArgument(0)))
        );
        when(response.headers()).thenReturn(headers);
    }

    @Test
    void should_allow_request_when_under_limit(Vertx vertx, VertxTestContext testContext) {
        RateLimit rateLimit = new RateLimit("test-key");
        rateLimit.setCounter(5);
        rateLimit.setLimit(10);
        rateLimit.setResetTime(System.currentTimeMillis() + 60000);

        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));

        vertx.runOnContext(v ->
            policy
                .onMessageRequest(messageContext)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    verify(headers).set("X-Rate-Limit-Limit", "10");
                    verify(headers).set("X-Rate-Limit-Remaining", "5");
                    verify(headers).set(eq("X-Rate-Limit-Reset"), anyString());
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_reject_request_when_over_limit(Vertx vertx, VertxTestContext testContext) {
        RateLimit rateLimit = new RateLimit("test-key");
        rateLimit.setCounter(11);
        rateLimit.setLimit(10);
        rateLimit.setResetTime(System.currentTimeMillis() + 60000);

        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));

        vertx.runOnContext(v ->
            policy
                .onMessageRequest(messageContext)
                .timeout(2, TimeUnit.SECONDS)
                .doOnError(th -> {
                    assertThat(th).isInstanceOf(MyException.class);
                    ExecutionFailure executionFailure = ((MyException) th).getExecutionFailure();
                    assertThat(executionFailure.statusCode()).isEqualTo(429);
                    assertThat(executionFailure.message()).contains(
                        "Rate limit exceeded! You reached the limit of 10 requests per 1 minutes"
                    );
                    verify(headers).set("X-Rate-Limit-Limit", "10");
                    verify(headers).set("X-Rate-Limit-Remaining", "0");
                    verify(headers).set(eq("X-Rate-Limit-Reset"), anyString());
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
    void should_handle_null_rate_limit_service() {
        when(messageContext.getComponent(RateLimitService.class)).thenReturn(null);

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
    void should_handle_service_error_without_given_config(Vertx vertx, VertxTestContext testContext) {
        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.error(new RuntimeException("Service error")));

        vertx.runOnContext(v -> {
            policy
                .onMessageRequest(messageContext)
                .doOnComplete(() -> {
                    verify(headers).set("X-Rate-Limit-Limit", "10");
                    verify(headers).set("X-Rate-Limit-Remaining", "10");
                    verify(headers).set("X-Rate-Limit-Reset", "-1");
                })
                .subscribe(new SubscribeAdapter(testContext)); // Should accept the call on error
        });
    }

    @Test
    void should_handle_service_error(Vertx vertx, VertxTestContext testContext) {
        configuration.setErrorStrategy(ErrorStrategy.FALLBACK_PASS_TROUGH);
        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.error(new RuntimeException("Service error")));

        vertx.runOnContext(v -> {
            policy
                .onMessageRequest(messageContext)
                .doOnComplete(() -> {
                    verify(headers).set("X-Rate-Limit-Limit", "10");
                    verify(headers).set("X-Rate-Limit-Remaining", "10");
                    verify(headers).set("X-Rate-Limit-Reset", "-1");
                })
                .subscribe(new SubscribeAdapter(testContext)); // Should accept the call on error
        });
    }

    @Test
    void should_handle_plain_request(Vertx vertx, VertxTestContext testContext) {
        RateLimit rateLimit = new RateLimit("test-key");
        rateLimit.setCounter(5);
        rateLimit.setLimit(10);
        rateLimit.setResetTime(System.currentTimeMillis() + 60000);

        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));

        vertx.runOnContext(v ->
            policy
                .onRequest(plainContext)
                .doOnComplete(() -> {
                    verify(headers).set("X-Rate-Limit-Limit", "10");
                    verify(headers).set("X-Rate-Limit-Remaining", "5");
                    verify(headers).set(eq("X-Rate-Limit-Reset"), anyString());
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
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
