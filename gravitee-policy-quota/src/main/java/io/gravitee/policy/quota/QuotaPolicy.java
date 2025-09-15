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

import io.gravitee.common.util.Maps;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.reactive.api.ExecutionWarn;
import io.gravitee.gateway.reactive.api.context.http.HttpBaseExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.quota.configuration.QuotaConfiguration;
import io.gravitee.policy.quota.configuration.QuotaPolicyConfiguration;
import io.gravitee.policy.v3.quota.QuotaPolicyV3;
import io.gravitee.ratelimit.DateUtils;
import io.gravitee.ratelimit.KeyFactory;
import io.gravitee.ratelimit.Pair;
import io.gravitee.ratelimit.PolicyRateLimitException;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.*;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;

public class QuotaPolicy extends QuotaPolicyV3 implements HttpPolicy {

    private static final KeyFactory KEY_FACTORY = new KeyFactory("q");

    public QuotaPolicy(QuotaPolicyConfiguration quotaPolicyConfiguration) {
        super(quotaPolicyConfiguration);
    }

    @Override
    public String id() {
        return "quota";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return Completable.defer(() -> run(ctx)).onErrorResumeNext(th ->
            ctx.interruptWith(PolicyRateLimitException.getExecutionFailure(QUOTA_SERVER_ERROR, th))
        );
    }

    @Override
    public Completable onMessageRequest(HttpMessageExecutionContext ctx) {
        return Completable.defer(() -> run(ctx)).onErrorResumeNext(th ->
            ctx.interruptMessagesWith(PolicyRateLimitException.getExecutionFailure(QUOTA_SERVER_ERROR, th)).ignoreElements()
        );
    }

    private Completable run(HttpBaseExecutionContext ctx) {
        RateLimitService rateLimitService = ctx.getComponent(RateLimitService.class);
        QuotaPolicyConfiguration policyConfig = getQuotaConfiguration();
        QuotaConfiguration quotaConfiguration = policyConfig.getQuota();

        if (rateLimitService == null) {
            String message = "No rate-limit service has been installed.";
            ctx.metrics().setErrorMessage(message);
            return Completable.error(PolicyRateLimitException.serverError(QUOTA_SERVER_ERROR, message));
        }

        var k = KEY_FACTORY.createRateLimitKey(ctx, quotaConfiguration);
        var l = (quotaConfiguration.getLimit() > 0)
            ? Maybe.just(quotaConfiguration.getLimit())
            : ctx.getTemplateEngine().eval(quotaConfiguration.getDynamicLimit(), Long.class);

        Context context = Vertx.currentContext();

        return Single.zip(k, l.defaultIfEmpty(0L), Pair::new).flatMapCompletable(entry -> {
            long limit = entry.limit();

            return rateLimitService
                .incrementAndGet(entry.key(), policyConfig.isAsync(), () -> {
                    // Set the time at which the current rate limit window resets in UTC epoch seconds.
                    long resetTimeMillis = DateUtils.getEndOfPeriod(
                        ctx.timestamp(),
                        quotaConfiguration.getPeriodTime(),
                        quotaConfiguration.getPeriodTimeUnit()
                    );

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
                    long remaining = Math.max(0, limit - rateLimit.getCounter());
                    if (policyConfig.isAddHeaders()) {
                        ctx.response().headers().set(X_QUOTA_LIMIT, Long.toString(limit));
                        ctx.response().headers().set(X_QUOTA_REMAINING, Long.toString(remaining));
                        ctx.response().headers().set(X_QUOTA_RESET, Long.toString(rateLimit.getResetTime()));
                    }

                    ctx.setAttribute(ExecutionContext.ATTR_QUOTA_COUNT, rateLimit.getCounter());
                    ctx.setAttribute(ExecutionContext.ATTR_QUOTA_REMAINING, remaining);
                    ctx.setAttribute(ExecutionContext.ATTR_QUOTA_LIMIT, quotaConfiguration.getLimit());
                    ctx.setAttribute(ExecutionContext.ATTR_QUOTA_RESET_TIME, rateLimit.getResetTime());

                    if (rateLimit.getCounter() <= limit) {
                        return Completable.complete();
                    } else {
                        String message = String.format(
                            "Quota exceeded! You reached the limit of %d requests per %d %s",
                            limit,
                            quotaConfiguration.getPeriodTime(),
                            quotaConfiguration.getPeriodTimeUnit().name().toLowerCase()
                        );
                        ctx.metrics().setErrorKey(QuotaPolicyV3.QUOTA_TOO_MANY_REQUESTS);
                        ctx.metrics().setErrorMessage(message);
                        return Completable.error(
                            PolicyRateLimitException.overflow(
                                QuotaPolicyV3.QUOTA_TOO_MANY_REQUESTS,
                                message,
                                Maps.<String, Object>builder()
                                    .put("limit", limit)
                                    .put("period_time", quotaConfiguration.getPeriodTime())
                                    .put("period_unit", quotaConfiguration.getPeriodTimeUnit())
                                    .build()
                            )
                        );
                    }
                })
                .onErrorResumeNext(th -> errorManagement(ctx, th, policyConfig, limit));
        });
    }

    private static Completable errorManagement(
        HttpBaseExecutionContext ctx,
        Throwable throwable,
        QuotaPolicyConfiguration policyConfig,
        long limit
    ) {
        if (throwable instanceof PolicyRateLimitException ex) {
            return Completable.error(ex);
        }
        // Set Rate Limit headers on response
        if (policyConfig.isAddHeaders()) {
            ctx.response().headers().set(X_QUOTA_LIMIT, Long.toString(limit));
            // We don't know about the remaining calls, let's assume it is the same as the limit
            ctx.response().headers().set(X_QUOTA_REMAINING, Long.toString(limit));
            ctx.response().headers().set(X_QUOTA_RESET, Long.toString(-1));
        }

        ctx.setAttribute(ExecutionContext.ATTR_QUOTA_REMAINING, limit);
        ctx.setAttribute(ExecutionContext.ATTR_QUOTA_LIMIT, limit);

        ctx.warnWith(new ExecutionWarn(QUOTA_NOT_APPLIED).message("Request bypassed quota policy due to internal error").cause(throwable));
        // If an errors occurs at the repository level, we accept the call
        return Completable.complete();
    }
}
