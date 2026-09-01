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
package io.gravitee.policy.tokenbucket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.http.HttpHeaders;
import io.gravitee.gateway.reactive.api.ExecutionFailure;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpResponse;
import io.gravitee.policy.tokenbucket.configuration.TokenBucketRateLimitPolicyConfiguration;
import io.gravitee.ratelimit.ErrorStrategy;
import io.gravitee.reporter.api.v4.metric.Metrics;
import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitService;
import io.reactivex.rxjava3.annotations.NonNull;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.rxjava3.core.Vertx;
import java.util.Map;
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
class TokenBucketRateLimitPolicyTest {

    @Mock(strictness = Mock.Strictness.LENIENT)
    private TokenBucketRateLimitService service;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpPlainExecutionContext ctx;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpMessageExecutionContext messageContext;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpResponse response;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private HttpHeaders headers;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private TemplateEngine templateEngine;

    private TokenBucketRateLimitPolicy policy;
    private TokenBucketRateLimitPolicyConfiguration configuration;

    @BeforeEach
    void setUp() {
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .addHeaders(true)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);

        when(ctx.metrics()).thenReturn(new Metrics());
        when(ctx.getComponent(TokenBucketRateLimitService.class)).thenReturn(service);
        when(ctx.response()).thenReturn(response);
        when(response.headers()).thenReturn(headers);
        when(ctx.timestamp()).thenReturn(1_000L);
        when(ctx.getTemplateEngine()).thenReturn(templateEngine);
        when(templateEngine.eval("test-key", String.class)).thenReturn(Maybe.just("test-key"));
        when(ctx.interruptWith(any())).thenAnswer(invocation -> Completable.error(new MyException(invocation.getArgument(0))));

        // Message context mirrors the plain one; on a message API the policy interrupts the message stream
        // (interruptMessagesWith) instead of the HTTP request.
        when(messageContext.metrics()).thenReturn(new Metrics());
        when(messageContext.getComponent(TokenBucketRateLimitService.class)).thenReturn(service);
        when(messageContext.response()).thenReturn(response);
        when(messageContext.timestamp()).thenReturn(1_000L);
        when(messageContext.getTemplateEngine()).thenReturn(templateEngine);
        when(messageContext.interruptMessagesWith(any())).thenAnswer(invocation ->
            Flowable.error(new MyException(invocation.getArgument(0)))
        );
    }

    @Test
    void should_allow_message_when_token_available(Vertx vertx, VertxTestContext testContext) {
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy.onMessageRequest(messageContext).timeout(2, TimeUnit.SECONDS).subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_interrupt_message_stream_when_bucket_empty(Vertx vertx, VertxTestContext testContext) {
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(false, 0, 1_500L))
        );

        vertx.runOnContext(v ->
            policy
                .onMessageRequest(messageContext)
                .timeout(2, TimeUnit.SECONDS)
                .doOnError(th -> {
                    assertThat(th).isInstanceOf(MyException.class);
                    ExecutionFailure failure = ((MyException) th).getExecutionFailure();
                    assertThat(failure.statusCode()).isEqualTo(429); // interrupts the message stream, not an HTTP 429 response
                    assertThat(failure.message()).contains("Rate limit exceeded");
                })
                .subscribe(
                    () -> testContext.failNow("must interrupt the message stream when the bucket is empty"),
                    th -> {
                        if (th instanceof MyException) {
                            testContext.completeNow();
                        } else {
                            testContext.failNow(th);
                        }
                    }
                )
        );
    }

    @Test
    void should_interrupt_message_stream_with_500_when_no_service_installed(Vertx vertx, VertxTestContext testContext) {
        when(messageContext.getComponent(TokenBucketRateLimitService.class)).thenReturn(null);

        vertx.runOnContext(v ->
            policy
                .onMessageRequest(messageContext)
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(
                    () -> testContext.failNow("infra failure must interrupt the message stream"),
                    th -> {
                        if (
                            th instanceof MyException ex &&
                            ex.getExecutionFailure().statusCode() == 500 &&
                            ex.getExecutionFailure().message().contains("No token-bucket rate-limit service")
                        ) {
                            testContext.completeNow();
                        } else {
                            testContext.failNow(th);
                        }
                    }
                )
        );
    }

    @Test
    void should_allow_request_when_token_available(Vertx vertx, VertxTestContext testContext) {
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    // useKeyOnly=true + key="test-key" resolves to "test-key:tb" (the :tb type marker keeps
                    // token-bucket keys from clashing with rate-limit / quota / spike-arrest).
                    verify(service).refillAndTryConsume(
                        eq("test-key:tb"),
                        eq(1L),
                        eq(3L),
                        eq(1_000L),
                        eq(300L),
                        eq(1_000L),
                        eq(false),
                        any()
                    );
                    verify(headers).set("X-Rate-Limit-Limit", "300");
                    verify(headers).set("X-Rate-Limit-Remaining", "299");
                    verify(headers).set(eq("X-Rate-Limit-Reset"), anyString());
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_pass_async_true_to_the_service_when_configured(Vertx vertx, VertxTestContext testContext) {
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .async(true)
            .addHeaders(true)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(true), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    // The flag is threaded through to the service...
                    verify(service).refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(true), any());
                    // ...and the request takes the allowed path (headers are only written on a successful consume,
                    // and the chain completes without a rejection).
                    verify(headers).set("X-Rate-Limit-Remaining", "299");
                    verify(ctx, never()).interruptWith(any());
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_resolve_burst_and_rate_from_el(Vertx vertx, VertxTestContext testContext) {
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .dynamicBurstCapacity("{(7)}")
            .dynamicRefillRate("{(2)}")
            .addHeaders(true)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        // Dynamic values resolve via async eval() (supports deferred variables), not evalNow().
        when(templateEngine.eval("{(7)}", Long.class)).thenReturn(Maybe.just(7L));
        when(templateEngine.eval("{(2)}", Long.class)).thenReturn(Maybe.just(2L));
        when(service.refillAndTryConsume(any(), eq(1L), eq(2L), eq(1_000L), eq(7L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 6, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    verify(service).refillAndTryConsume(any(), eq(1L), eq(2L), eq(1_000L), eq(7L), eq(1_000L), eq(false), any());
                    verify(headers).set("X-Rate-Limit-Limit", "7");
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_reject_request_when_bucket_empty(Vertx vertx, VertxTestContext testContext) {
        // empty bucket; next token due 500ms later -> Retry-After = ceil(500/1000) = 1
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(false, 0, 1_500L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnError(th -> {
                    assertThat(th).isInstanceOf(MyException.class);
                    ExecutionFailure failure = ((MyException) th).getExecutionFailure();
                    assertThat(failure.statusCode()).isEqualTo(429);
                    assertThat(failure.key()).isEqualTo("TOKEN_BUCKET_RATE_LIMIT_TOO_MANY_REQUESTS");
                    assertThat(failure.message()).contains("Rate limit exceeded");
                    verify(headers).set("X-Rate-Limit-Remaining", "0");
                    verify(headers).set("Retry-After", "1");
                })
                .subscribe(
                    () -> testContext.failNow("this test must fail"),
                    th -> {
                        if (th instanceof MyException) {
                            testContext.completeNow();
                        } else {
                            testContext.failNow(th);
                        }
                    }
                )
        );
    }

    @Test
    void should_pass_through_on_internal_error_with_fallback_strategy(Vertx vertx, VertxTestContext testContext) {
        configuration.setErrorStrategy(ErrorStrategy.FALLBACK_PASS_TROUGH);
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.error(new RuntimeException("store down"))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> {
                    verify(headers).set("X-Rate-Limit-Limit", "300");
                    verify(headers).set("X-Rate-Limit-Remaining", "300");
                    verify(headers).set("X-Rate-Limit-Reset", "-1");
                })
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_block_on_internal_error_with_block_strategy(Vertx vertx, VertxTestContext testContext) {
        configuration.setErrorStrategy(ErrorStrategy.BLOCK_ON_INTERNAL_ERROR);
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.error(new RuntimeException("store down"))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(
                    () -> testContext.failNow("this test must fail"),
                    th -> {
                        if (th instanceof MyException) {
                            testContext.completeNow();
                        } else {
                            testContext.failNow(th);
                        }
                    }
                )
        );
    }

    @Test
    void should_pass_through_when_service_returns_null(Vertx vertx, VertxTestContext testContext) {
        // NoOp store (rate limiting disabled): the service propagates a null Single.
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(null);

        vertx.runOnContext(v -> policy.onRequest(ctx).timeout(2, TimeUnit.SECONDS).subscribe(new SubscribeAdapter(testContext)));
    }

    @Test
    void should_error_when_no_service_installed() {
        when(ctx.getComponent(TokenBucketRateLimitService.class)).thenReturn(null);

        policy
            .onRequest(ctx)
            .test()
            .assertError(
                throwable ->
                    throwable instanceof MyException ex &&
                    ex.getExecutionFailure().statusCode() == 500 && // infra failure must be a 500, never masked as a 429
                    ex.getExecutionFailure().message().contains("No token-bucket rate-limit service")
            );
    }

    @Test
    void should_error_with_500_when_no_config_installed() {
        TokenBucketRateLimitPolicy noConfig = new TokenBucketRateLimitPolicy(null);

        noConfig
            .onRequest(ctx)
            .test()
            .assertError(
                throwable ->
                    throwable instanceof MyException ex &&
                    ex.getExecutionFailure().statusCode() == 500 &&
                    ex.getExecutionFailure().message().contains("No token-bucket rate-limit config")
            );
    }

    @Test
    void should_not_write_headers_when_addHeaders_is_false(Vertx vertx, VertxTestContext testContext) {
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() -> verify(headers, never()).set(anyString(), anyString()))
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void defaults_period_to_one_second_when_unit_null_and_period_unset(Vertx vertx, VertxTestContext testContext) {
        // refillPeriodTime <= 0 and a null unit must default to 1 SECOND (1000ms), not crash or pass 0.
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .refillPeriodTime(0)
            .refillPeriodTimeUnit(null)
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() ->
                    verify(service).refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())
                )
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_compose_key_from_subscription_when_useKeyOnly_false(Vertx vertx, VertxTestContext testContext) {
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(false)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        // useKeyOnly=false composes subscription + key + type: "<plan><subscription>:<key>:tb".
        when(ctx.getAttributes()).thenReturn(Map.of(ExecutionContext.ATTR_PLAN, "plan1", ExecutionContext.ATTR_SUBSCRIPTION_ID, "sub1"));
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() ->
                    verify(service).refillAndTryConsume(
                        eq("plan1sub1:test-key:tb"),
                        eq(1L),
                        eq(3L),
                        eq(1_000L),
                        eq(300L),
                        eq(1_000L),
                        eq(false),
                        any()
                    )
                )
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_error_with_500_when_capacity_and_rate_both_unset(Vertx vertx, VertxTestContext testContext) {
        // Neither the static value nor a dynamic EL expression is set. Rather than silently sending a
        // zero-capacity bucket to the store, the policy rejects with a 500 so the misconfiguration is
        // visible to the operator and the store is never touched.
        configuration = TokenBucketRateLimitPolicyConfiguration.builder().addHeaders(false).key("test-key").useKeyOnly(true).build();
        policy = new TokenBucketRateLimitPolicy(configuration);

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(
                    () -> testContext.failNow("this test must fail"),
                    th -> {
                        if (
                            th instanceof MyException ex &&
                            ex.getExecutionFailure().statusCode() == 500 &&
                            "TOKEN_BUCKET_RATE_LIMIT_SERVER_ERROR".equals(ex.getExecutionFailure().key())
                        ) {
                            verify(service, never()).refillAndTryConsume(
                                any(),
                                anyLong(),
                                anyLong(),
                                anyLong(),
                                anyLong(),
                                anyLong(),
                                anyBoolean(),
                                any()
                            );
                            testContext.completeNow();
                        } else {
                            testContext.failNow(th);
                        }
                    }
                )
        );
    }

    @Test
    void should_error_with_500_when_dynamic_rate_resolves_to_empty(Vertx vertx, VertxTestContext testContext) {
        // The dynamic EL expression resolves to no value: eval() returns an empty Maybe, so evalLong falls
        // back to 0L. That 0 is not silently applied as "no limit" — the zero-config guard rejects it with a
        // 500 and the store is never touched.
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .dynamicRefillRate("{#empty}")
            .burstCapacity(300)
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(templateEngine.eval("{#empty}", Long.class)).thenReturn(Maybe.empty());

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .subscribe(
                    () -> testContext.failNow("this test must fail"),
                    th -> {
                        if (
                            th instanceof MyException ex &&
                            ex.getExecutionFailure().statusCode() == 500 &&
                            "TOKEN_BUCKET_RATE_LIMIT_SERVER_ERROR".equals(ex.getExecutionFailure().key())
                        ) {
                            verify(service, never()).refillAndTryConsume(
                                any(),
                                anyLong(),
                                anyLong(),
                                anyLong(),
                                anyLong(),
                                anyLong(),
                                anyBoolean(),
                                any()
                            );
                            testContext.completeNow();
                        } else {
                            testContext.failNow(th);
                        }
                    }
                )
        );
    }

    @Test
    void should_consume_static_weight_when_budget_enabled(Vertx vertx, VertxTestContext testContext) {
        // Budget mode on with a static cost of 10: each request drains 10 tokens from the bucket instead of 1.
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .budget(true)
            .weight(10)
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(service.refillAndTryConsume(any(), eq(10L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 290, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() ->
                    verify(service).refillAndTryConsume(any(), eq(10L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())
                )
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_consume_dynamic_weight_from_el_when_budget_enabled(Vertx vertx, VertxTestContext testContext) {
        // Budget mode on with no static cost: the per-request cost is resolved from the EL expression (e.g. a
        // token-usage attribute set by an upstream policy) via async eval().
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .budget(true)
            .dynamicWeight("{(42)}")
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(templateEngine.eval("{(42)}", Long.class)).thenReturn(Maybe.just(42L));
        when(service.refillAndTryConsume(any(), eq(42L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 258, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() ->
                    verify(service).refillAndTryConsume(any(), eq(42L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())
                )
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_default_dynamic_weight_to_one_when_el_resolves_empty(Vertx vertx, VertxTestContext testContext) {
        // Budget mode on but the cost expression resolves to nothing: rather than silently disabling
        // enforcement (cost 0), the weight defaults to 1 so the request still counts.
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .budget(true)
            .dynamicWeight("{#empty}")
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(templateEngine.eval("{#empty}", Long.class)).thenReturn(Maybe.empty());
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() ->
                    verify(service).refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())
                )
                .subscribe(new SubscribeAdapter(testContext))
        );
    }

    @Test
    void should_consume_one_when_budget_disabled_even_if_weight_set(Vertx vertx, VertxTestContext testContext) {
        // The toggle gates the weight: with budget off, a configured weight is ignored and the request consumes 1.
        configuration = TokenBucketRateLimitPolicyConfiguration.builder()
            .refillRate(3)
            .burstCapacity(300)
            .budget(false)
            .weight(10)
            .addHeaders(false)
            .key("test-key")
            .useKeyOnly(true)
            .build();
        policy = new TokenBucketRateLimitPolicy(configuration);
        when(service.refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 299, 1_000L))
        );

        vertx.runOnContext(v ->
            policy
                .onRequest(ctx)
                .timeout(2, TimeUnit.SECONDS)
                .doOnComplete(() ->
                    verify(service).refillAndTryConsume(any(), eq(1L), eq(3L), eq(1_000L), eq(300L), eq(1_000L), eq(false), any())
                )
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
