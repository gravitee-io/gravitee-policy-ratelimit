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

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.reactive.api.ExecutionWarn;
import io.gravitee.gateway.reactive.api.context.http.HttpBaseExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.ratelimit.configuration.PeriodCalculation;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.v3.ratelimit.RateLimitPolicyV3;
import io.gravitee.ratelimit.DateUtils;
import io.gravitee.ratelimit.KeyFactory;
import io.gravitee.ratelimit.PolicyRateLimitException;
import io.gravitee.ratelimit.SharedPolicy;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RateLimitPolicy extends RateLimitPolicyV3 implements HttpPolicy {

    private static final KeyFactory KEY_FACTORY = new KeyFactory("rl");

    public RateLimitPolicy(RateLimitPolicyConfiguration rateLimitPolicyConfiguration) {
        super(rateLimitPolicyConfiguration);
    }

    @Override
    public String id() {
        return "rate-limit";
    }

    @Override
    public Completable onMessageRequest(HttpMessageExecutionContext ctx) {
        return Completable.defer(() -> run(ctx)).onErrorResumeNext(th ->
            ctx.interruptMessagesWith(PolicyRateLimitException.getExecutionFailure(RATE_LIMIT_SERVER_ERROR, th)).ignoreElements()
        );
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return Completable.defer(() -> run(ctx)).onErrorResumeNext(th ->
            ctx.interruptWith(PolicyRateLimitException.getExecutionFailure(RATE_LIMIT_SERVER_ERROR, th))
        );
    }

    @OnRequest
    public Completable run(HttpBaseExecutionContext ctx) {
        RateLimitService rateLimitService = ctx.getComponent(RateLimitService.class);
        RateLimitPolicyConfiguration rateConfig = getRateLimitPolicyConfiguration();
        if (rateConfig == null) {
            String message = "No rate-limit config has been installed.";
            ctx.metrics().setErrorMessage(message);
            return Completable.error(PolicyRateLimitException.serverError(RATE_LIMIT_SERVER_ERROR, message));
        }
        RateLimitConfiguration rateLimitConfiguration = rateConfig.getRate();

        if (rateLimitService == null) {
            String errorMessage = "No rate-limit service has been installed";
            ctx.metrics().setErrorMessage(errorMessage);
            return Completable.error(PolicyRateLimitException.serverError(RATE_LIMIT_SERVER_ERROR, errorMessage));
        }

        var k = KEY_FACTORY.createRateLimitKey(ctx, rateLimitConfiguration);
        var l = (rateLimitConfiguration.getLimit() > 0)
            ? Single.just(rateLimitConfiguration.getLimit())
            : ctx.getTemplateEngine().eval(rateLimitConfiguration.getDynamicLimit(), Long.class).defaultIfEmpty(0L);
        var timeDuration = switch (rateLimitConfiguration.periodTime()) {
            case PeriodCalculation.Static(long value) -> Single.just(value);
            case PeriodCalculation.ExpressionLanguage(String expression) -> ctx
                .getTemplateEngine()
                .eval(expression, Long.class)
                .defaultIfEmpty(1L);
        };
        var timeUnit = rateLimitConfiguration.validPeriodTime();

        Context context = Vertx.currentContext();

        return Single.zip(k, l, timeDuration, SharedPolicy::new).flatMapCompletable(entry -> {
            long limit = entry.limit();

            return rateLimitService
                .incrementAndGet(entry.key(), rateConfig.isAsync(), () -> {
                    // Set the time at which the current rate limit window resets in UTC epoch seconds.
                    long resetTimeMillis = DateUtils.getEndOfPeriod(ctx.timestamp(), entry.period(), timeUnit);

                    RateLimit rate = new RateLimit(entry.key());
                    rate.setCounter(0);
                    rate.setLimit(limit);
                    rate.setResetTime(resetTimeMillis);
                    rate.setSubscription(ctx.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
                    return rate;
                })
                .observeOn(RxHelper.scheduler(context))
                .flatMapCompletable(rateLimit -> {
                    // Set Rate Limit headers on response
                    if (rateConfig.isAddHeaders()) {
                        ctx.response().headers().set(X_RATE_LIMIT_LIMIT, Long.toString(limit));
                        ctx.response().headers().set(X_RATE_LIMIT_REMAINING, Long.toString(Math.max(0, limit - rateLimit.getCounter())));
                        ctx.response().headers().set(X_RATE_LIMIT_RESET, Long.toString(rateLimit.getResetTime()));
                    }

                    if (rateLimit.getCounter() <= limit) {
                        return Completable.complete();
                    } else {
                        String message = String.format(
                            "Rate limit exceeded! You reached the limit of %d requests per %d %s",
                            limit,
                            entry.period(),
                            timeUnit.name().toLowerCase()
                        );
                        ctx.metrics().setErrorKey(RateLimitPolicyV3.RATE_LIMIT_TOO_MANY_REQUESTS);
                        ctx.metrics().setErrorMessage(message);
                        return Completable.error(
                            PolicyRateLimitException.overflow(
                                RateLimitPolicyV3.RATE_LIMIT_TOO_MANY_REQUESTS,
                                message,
                                Map.of("limit", limit, "period_time", entry.period(), "period_unit", timeUnit.name().toLowerCase())
                            )
                        );
                    }
                })
                .onErrorResumeNext(throwable -> errorManagement(ctx, throwable, rateConfig, limit));
        });
    }

    private Completable errorManagement(
        HttpBaseExecutionContext ctx,
        Throwable throwable,
        RateLimitPolicyConfiguration rateConfig,
        long limit
    ) {
        if (throwable instanceof PolicyRateLimitException ex) {
            return Completable.error(ex);
        }
        return switch (getRateLimitPolicyConfiguration().getErrorStrategy()) {
            case BLOCK_ON_INTERNAL_ERROR -> {
                var msg = "Rate limit blocked the query due to internal error";
                yield Completable.error(PolicyRateLimitException.overflow(RATE_LIMIT_BLOCK_ON_INTERNAL_ERROR, msg, throwable));
            }
            case FALLBACK_PASS_TROUGH -> {
                // Set Rate Limit headers on response
                if (rateConfig.isAddHeaders()) {
                    ctx.response().headers().set(X_RATE_LIMIT_LIMIT, Long.toString(limit));
                    // We don't know about the remaining calls, let's assume it is the same as the limit
                    ctx.response().headers().set(X_RATE_LIMIT_REMAINING, Long.toString(limit));
                    ctx.response().headers().set(X_RATE_LIMIT_RESET, Long.toString(-1));
                }
                ctx.warnWith(
                    new ExecutionWarn(RATE_LIMIT_NOT_APPLIED)
                        .message("Request bypassed rate limit policy due to internal error")
                        .cause(throwable)
                );
                yield Completable.complete();
            }
        };
    }
}
