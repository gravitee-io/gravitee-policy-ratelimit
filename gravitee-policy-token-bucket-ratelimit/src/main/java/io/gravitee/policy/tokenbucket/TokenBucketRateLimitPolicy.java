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

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.reactive.api.ExecutionWarn;
import io.gravitee.gateway.reactive.api.context.http.HttpBaseExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.tokenbucket.configuration.TokenBucketRateLimitPolicyConfiguration;
import io.gravitee.ratelimit.KeyFactory;
import io.gravitee.ratelimit.PolicyRateLimitException;
import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitService;
import io.gravitee.repository.ratelimit.model.TokenBucket;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in token-bucket (burst) rate-limit policy: a steady refill rate accumulates tokens up to a
 * burst capacity, each request consumes one token, and requests are rejected with {@code 429} when
 * the bucket is empty. Enforcement runs against the configured rate-limit store, but the default
 * {@code errorStrategy} is {@code FALLBACK_PASS_TROUGH} (fail open): while the store is unavailable
 * requests pass through and throttling is disabled. Set {@code BLOCK_ON_INTERNAL_ERROR} to fail closed.
 *
 * <p>Works on HTTP proxy APIs ({@code onRequest}) and on v4 message APIs ({@code onMessageRequest}).
 * As with the {@code rate-limit}/{@code quota}/{@code spike-arrest} policies, the message phase reuses
 * the same per-invocation consume and interrupts the message stream (rather than the HTTP request) when
 * the bucket is empty.
 */
public class TokenBucketRateLimitPolicy implements HttpPolicy {

    static final String X_RATE_LIMIT_LIMIT = "X-Rate-Limit-Limit";
    static final String X_RATE_LIMIT_REMAINING = "X-Rate-Limit-Remaining";
    static final String X_RATE_LIMIT_RESET = "X-Rate-Limit-Reset";
    static final String RETRY_AFTER = "Retry-After";

    static final String TOKEN_BUCKET_TOO_MANY_REQUESTS = "TOKEN_BUCKET_RATE_LIMIT_TOO_MANY_REQUESTS";
    static final String TOKEN_BUCKET_SERVER_ERROR = "TOKEN_BUCKET_RATE_LIMIT_SERVER_ERROR";
    static final String TOKEN_BUCKET_BLOCK_ON_INTERNAL_ERROR = "TOKEN_BUCKET_RATE_LIMIT_BLOCK_ON_INTERNAL_ERROR";
    static final String TOKEN_BUCKET_NOT_APPLIED = "TOKEN_BUCKET_RATE_LIMIT_NOT_APPLIED";

    private static final KeyFactory KEY_FACTORY = new KeyFactory("tb");

    private final TokenBucketRateLimitPolicyConfiguration configuration;

    public TokenBucketRateLimitPolicy(TokenBucketRateLimitPolicyConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String id() {
        return "token-bucket-rate-limit";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return Completable.defer(() -> run(ctx)).onErrorResumeNext(th ->
            ctx.interruptWith(PolicyRateLimitException.getExecutionFailure(TOKEN_BUCKET_SERVER_ERROR, th))
        );
    }

    @Override
    public Completable onMessageRequest(HttpMessageExecutionContext ctx) {
        // Same per-invocation consume as the HTTP path; on failure interrupt the message stream rather than
        // the HTTP request. Mirrors rate-limit / quota / spike-arrest.
        return Completable.defer(() -> run(ctx)).onErrorResumeNext(th ->
            ctx.interruptMessagesWith(PolicyRateLimitException.getExecutionFailure(TOKEN_BUCKET_SERVER_ERROR, th)).ignoreElements()
        );
    }

    private Completable run(HttpBaseExecutionContext ctx) {
        // Captured on the event loop (policy entry) so we can resume on it after the store call.
        Context context = Vertx.currentContext();
        if (configuration == null) {
            String message = "No token-bucket rate-limit config has been installed.";
            ctx.metrics().setErrorMessage(message);
            return Completable.error(PolicyRateLimitException.serverError(TOKEN_BUCKET_SERVER_ERROR, message));
        }

        // The service bridge (registered by the rate-limit gateway service) selects the strict or async
        // path from the configured flag — the policy itself stays mode-agnostic.
        TokenBucketRateLimitService service = ctx.getComponent(TokenBucketRateLimitService.class);
        if (service == null) {
            String message = "No token-bucket rate-limit service has been installed";
            ctx.metrics().setErrorMessage(message);
            return Completable.error(PolicyRateLimitException.serverError(TOKEN_BUCKET_SERVER_ERROR, message));
        }

        long now = ctx.timestamp();
        long refillPeriodMillis = resolvePeriodMillis();

        // Resolve refill rate and burst capacity asynchronously: a static value wins, otherwise the EL
        // expression is evaluated with eval() (NOT evalNow). eval() supports deferred variables; evalNow
        // returns null for those, which would collapse to 0 and silently disable enforcement. Mirrors
        // RateLimitPolicy. Period has no EL form, so it stays synchronous.
        Single<String> keySingle = KEY_FACTORY.createRateLimitKey(ctx, configuration);
        Single<Long> refillRateSingle = configuration.getRefillRate() > 0
            ? Single.just(configuration.getRefillRate())
            : evalLong(ctx, configuration.getDynamicRefillRate());
        Single<Long> capacitySingle = configuration.getBurstCapacity() > 0
            ? Single.just(configuration.getBurstCapacity())
            : evalLong(ctx, configuration.getDynamicBurstCapacity());

        return Single.zip(keySingle, refillRateSingle, capacitySingle, Resolved::new).flatMapCompletable(resolved -> {
            long capacity = resolved.capacity();
            // Neither the static value nor a dynamic EL expression resolved to a positive number, so the
            // bucket would have zero capacity / refill and silently reject (or never hold) every request.
            // Reject the request with a 500 so the misconfiguration is visible rather than enforced silently.
            if (resolved.refillRate() <= 0 || capacity <= 0) {
                String message =
                    "Token-bucket rate-limit misconfigured: refillRate and burstCapacity must resolve to a positive value " +
                    "(set the static value or a dynamic EL expression).";
                ctx.metrics().setErrorMessage(message);
                return Completable.error(PolicyRateLimitException.serverError(TOKEN_BUCKET_SERVER_ERROR, message));
            }
            Single<TokenBucketConsumeResult> consume = service.refillAndTryConsume(
                resolved.key(),
                1,
                resolved.refillRate(),
                refillPeriodMillis,
                capacity,
                now,
                configuration.isAsync(),
                () -> {
                    TokenBucket bucket = new TokenBucket(resolved.key());
                    bucket.setSubscription(ctx.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
                    return bucket;
                }
            );
            if (consume == null) {
                // No-op store (rate limiting disabled): pass through.
                return Completable.complete();
            }
            // The store may emit on a non-event-loop thread (e.g. the reactive MongoDB driver). Resume on the
            // request's Vert.x context so downstream components still see one — notably the v2-emulation
            // endpoint invoker, which calls Vertx.currentContext(). Same pattern as rate-limit/quota/spike-arrest.
            return consume
                .observeOn(RxHelper.scheduler(context))
                .flatMapCompletable(result -> applyResult(ctx, result, capacity, now))
                .onErrorResumeNext(throwable -> errorManagement(ctx, throwable, capacity));
        });
    }

    private long resolvePeriodMillis() {
        TimeUnit unit = configuration.getRefillPeriodTimeUnit() != null ? configuration.getRefillPeriodTimeUnit() : TimeUnit.SECONDS;
        long periodTime = configuration.getRefillPeriodTime() > 0 ? configuration.getRefillPeriodTime() : 1;
        return unit.toMillis(periodTime);
    }

    // Evaluate an EL expression to a long via async eval() (which supports deferred variables), defaulting
    // to 0 when the expression is unset or yields nothing. A resulting 0 is not applied silently: the
    // zero-config guard in run() rejects it with a 500 (TOKEN_BUCKET_RATE_LIMIT_SERVER_ERROR).
    private static Single<Long> evalLong(HttpBaseExecutionContext ctx, String expression) {
        if (expression == null || expression.isBlank()) {
            return Single.just(0L);
        }
        return ctx.getTemplateEngine().eval(expression, Long.class).defaultIfEmpty(0L);
    }

    private record Resolved(String key, long refillRate, long capacity) {}

    private Completable applyResult(HttpBaseExecutionContext ctx, TokenBucketConsumeResult result, long capacity, long now) {
        if (configuration.isAddHeaders()) {
            var headers = ctx.response().headers();
            headers.set(X_RATE_LIMIT_LIMIT, Long.toString(capacity));
            headers.set(X_RATE_LIMIT_REMAINING, Long.toString(Math.max(0, result.remainingTokens())));
            headers.set(X_RATE_LIMIT_RESET, Long.toString(result.nextAvailableAtMillis()));
        }

        if (result.allowed()) {
            return Completable.complete();
        }

        if (configuration.isAddHeaders()) {
            long retryAfterSeconds = Math.max(0, (long) Math.ceil((result.nextAvailableAtMillis() - now) / 1000.0));
            ctx.response().headers().set(RETRY_AFTER, Long.toString(retryAfterSeconds));
        }

        String message = "Rate limit exceeded! No tokens available in the bucket.";
        ctx.metrics().setErrorKey(TOKEN_BUCKET_TOO_MANY_REQUESTS);
        ctx.metrics().setErrorMessage(message);
        return Completable.error(
            PolicyRateLimitException.overflow(TOKEN_BUCKET_TOO_MANY_REQUESTS, message, Map.<String, Object>of("burst_capacity", capacity))
        );
    }

    private Completable errorManagement(HttpBaseExecutionContext ctx, Throwable throwable, long capacity) {
        if (throwable instanceof PolicyRateLimitException ex) {
            return Completable.error(ex);
        }
        return switch (configuration.getErrorStrategy()) {
            case BLOCK_ON_INTERNAL_ERROR -> {
                var message = "Token-bucket rate limit blocked the query due to internal error";
                yield Completable.error(PolicyRateLimitException.overflow(TOKEN_BUCKET_BLOCK_ON_INTERNAL_ERROR, message, throwable));
            }
            case FALLBACK_PASS_TROUGH -> {
                if (configuration.isAddHeaders()) {
                    var headers = ctx.response().headers();
                    headers.set(X_RATE_LIMIT_LIMIT, Long.toString(capacity));
                    // We don't know the remaining tokens on internal error; assume the full capacity.
                    headers.set(X_RATE_LIMIT_REMAINING, Long.toString(capacity));
                    headers.set(X_RATE_LIMIT_RESET, Long.toString(-1));
                }
                ctx.warnWith(
                    new ExecutionWarn(TOKEN_BUCKET_NOT_APPLIED)
                        .message("Request bypassed token-bucket rate limit policy due to internal error")
                        .cause(throwable)
                );
                yield Completable.complete();
            }
        };
    }
}
