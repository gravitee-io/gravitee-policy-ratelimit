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
package io.gravitee.policy.spike;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpResponse;
import io.gravitee.policy.spike.configuration.SpikeArrestConfiguration;
import io.gravitee.policy.spike.configuration.SpikeArrestPolicyConfiguration;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.CompletableObserver;
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
@ExtendWith({ VertxExtension.class, MockitoExtension.class })
class SpikeArrestPolicyTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpPlainExecutionContext executionContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private RateLimitService rateLimitService;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpResponse response;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpHeaders httpHeaders;

    private SpikeArrestPolicy policy;
    private SpikeArrestPolicyConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = new SpikeArrestPolicyConfiguration();
        SpikeArrestConfiguration spikeConfig = new SpikeArrestConfiguration();
        spikeConfig.setLimit(100);
        spikeConfig.setPeriodTime(1L);
        spikeConfig.setPeriodTimeUnit(TimeUnit.SECONDS);
        configuration.setSpike(spikeConfig);
        configuration.setAddHeaders(true);

        policy = new SpikeArrestPolicy(configuration);

        when(executionContext.getComponent(RateLimitService.class)).thenReturn(rateLimitService);
        when(executionContext.response()).thenReturn(response);
        when(response.headers()).thenReturn(httpHeaders);

        when(executionContext.metrics()).thenReturn(new Metrics());
        when(executionContext.interruptWith(any())).thenAnswer(invocationOnMock ->
            Completable.error(new MyException(invocationOnMock.getArgument(0)))
        );
    }

    @Test
    void should_allow_request_when_under_limit(Vertx vertx, VertxTestContext testContext) {
        RateLimit rateLimit = new RateLimit("test-key");
        rateLimit.setCounter(5);
        rateLimit.setLimit(100);
        rateLimit.setResetTime(System.currentTimeMillis() + 1000);

        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));

        vertx.runOnContext(v -> policy.onRequest(executionContext).subscribe(new SubscribeAdapter(testContext)));
    }

    @Test
    void should_reject_request_when_over_limit(VertxTestContext testContext) {
        RateLimit rateLimit = new RateLimit("test-key");
        rateLimit.setCounter(11);
        rateLimit.setLimit(10);
        rateLimit.setResetTime(System.currentTimeMillis() + 1000);

        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));

        policy
            .onRequest(executionContext)
            .subscribe(
                () -> testContext.failNow("Should have failed"),
                error -> {
                    if (error instanceof MyException) {
                        testContext.completeNow();
                    } else {
                        testContext.failNow("Wrong exception type");
                    }
                }
            );
    }

    @Test
    void should_handle_rate_limit_service_error(Vertx vertx, VertxTestContext testContext) {
        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.error(new RuntimeException("Service error")));

        vertx.runOnContext(v -> policy.onRequest(executionContext).subscribe(new SubscribeAdapter(testContext)));
    }

    @Test
    void shouldFailWhenNoRateLimitService() {
        when(executionContext.getComponent(RateLimitService.class)).thenReturn(null);

        policy
            .onRequest(executionContext)
            .test()
            .assertError(
                throwable ->
                    throwable instanceof MyException ex &&
                    ex.getExecutionFailure().message().contains("No rate-limit service has been installed")
            );
    }

    @Test
    void should_set_response_headers_when_configured(Vertx vertx, VertxTestContext testContext) {
        RateLimit rateLimit = new RateLimit("test-key");
        rateLimit.setCounter(5);
        rateLimit.setLimit(10);
        rateLimit.setResetTime(System.currentTimeMillis() + 1000);

        when(rateLimitService.incrementAndGet(any(), anyBoolean(), any())).thenReturn(Single.just(rateLimit));

        vertx.runOnContext(v ->
            policy
                .onRequest(executionContext)
                .doOnComplete(() -> {
                    verify(httpHeaders).set(eq("X-Spike-Arrest-Limit"), anyString());
                    verify(httpHeaders).set(eq("X-Spike-Arrest-Slice-Period"), anyString());
                    verify(httpHeaders).set(eq("X-Spike-Arrest-Reset"), anyString());
                })
                .subscribe(
                    () -> {
                        testContext.completeNow();
                    },
                    testContext::failNow
                )
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
