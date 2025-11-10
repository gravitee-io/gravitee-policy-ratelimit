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
package io.gravitee.policy.v3.quota;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.Maps;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.quota.configuration.QuotaConfiguration;
import io.gravitee.policy.quota.configuration.QuotaPolicyConfiguration;
import io.gravitee.ratelimit.DateUtils;
import io.gravitee.ratelimit.KeyFactory;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The quota policy, also known as throttling insure that a user (given its api key or IP address) is allowed
 * to make x requests per y time period.
 *
 * Useful when you want to ensure that your APIs does not get flooded with requests.
 *
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@SuppressWarnings("unused")
public class QuotaPolicyV3 {

    private static final KeyFactory KEY_FACTORY = new KeyFactory("q");

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaPolicyV3.class);

    protected static final String QUOTA_TOO_MANY_REQUESTS = "QUOTA_TOO_MANY_REQUESTS";
    protected static final String QUOTA_SERVER_ERROR = "QUOTA_SERVER_ERROR";
    protected static final String QUOTA_NOT_APPLIED = "QUOTA_NOT_APPLIED";
    protected static final String QUOTA_BLOCK_ON_INTERNAL_ERROR = "QUOTA_BLOCK_ON_INTERNAL_ERROR";

    /**
     * The maximum number of requests that the consumer is permitted to make per time unit.
     */
    public static final String X_QUOTA_LIMIT = "X-Quota-Limit";

    /**
     * The number of requests remaining in the current rate limit window.
     */
    public static final String X_QUOTA_REMAINING = "X-Quota-Remaining";

    /**
     * The time at which the current rate limit window resets in UTC epoch seconds.
     */
    public static final String X_QUOTA_RESET = "X-Quota-Reset";

    /**
     * Rate limit policy configuration
     */
    private final QuotaPolicyConfiguration quotaPolicyConfiguration;

    public QuotaPolicyV3(QuotaPolicyConfiguration quotaPolicyConfiguration) {
        this.quotaPolicyConfiguration = quotaPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        RateLimitService rateLimitService = executionContext.getComponent(RateLimitService.class);
        QuotaConfiguration quotaConfiguration = quotaPolicyConfiguration.getQuota();

        if (rateLimitService == null) {
            policyChain.failWith(PolicyResult.failure("No rate-limit service has been installed."));
            return;
        }

        String key = KEY_FACTORY.createRateLimitKey(
            executionContext.getAttributes(),
            executionContext::getTemplateEngine,
            quotaConfiguration
        ).blockingGet();
        long limit = (quotaConfiguration.getLimit() > 0)
            ? quotaConfiguration.getLimit()
            : executionContext.getTemplateEngine().evalNow(quotaConfiguration.getDynamicLimit(), Long.class);
        var timeDuration = (quotaConfiguration.getPeriodTime() > 1)
            ? Single.just(quotaConfiguration.getPeriodTime())
            : executionContext.getTemplateEngine().eval(quotaConfiguration.getPeriodTimeExpression(), Long.class).defaultIfEmpty(1L);

        Context context = Vertx.currentContext();

        rateLimitService
            .incrementAndGet(key, quotaPolicyConfiguration.isAsync(), () -> {
                // Set the time at which the current rate limit window resets in UTC epoch seconds.
                long resetTimeMillis = DateUtils.getEndOfPeriod(
                    request.timestamp(),
                    timeDuration.blockingGet(),
                    quotaConfiguration.getPeriodTimeUnit()
                );

                RateLimit rate = new RateLimit(key);
                rate.setCounter(0);
                rate.setLimit(limit);
                rate.setResetTime(resetTimeMillis);
                rate.setSubscription((String) executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
                return rate;
            })
            .observeOn(RxHelper.scheduler(context))
            .subscribe(
                new SingleObserver<>() {
                    @Override
                    public void onSubscribe(Disposable d) {}

                    @Override
                    public void onSuccess(RateLimit rateLimit) {
                        // Set Rate Limit headers on response
                        long remaining = Math.max(0, limit - rateLimit.getCounter());
                        if (quotaPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_QUOTA_LIMIT, Long.toString(limit));
                            response.headers().set(X_QUOTA_REMAINING, Long.toString(remaining));
                            response.headers().set(X_QUOTA_RESET, Long.toString(rateLimit.getResetTime()));
                        }

                        executionContext.setAttribute(ExecutionContext.ATTR_QUOTA_COUNT, rateLimit.getCounter());
                        executionContext.setAttribute(ExecutionContext.ATTR_QUOTA_REMAINING, remaining);
                        executionContext.setAttribute(ExecutionContext.ATTR_QUOTA_LIMIT, quotaConfiguration.getLimit());
                        executionContext.setAttribute(ExecutionContext.ATTR_QUOTA_RESET_TIME, rateLimit.getResetTime());

                        if (rateLimit.getCounter() <= limit) {
                            policyChain.doNext(request, response);
                        } else {
                            policyChain.failWith(createLimitExceeded(quotaConfiguration, limit, timeDuration.blockingGet()));
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        // Set Rate Limit headers on response
                        if (quotaPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_QUOTA_LIMIT, Long.toString(limit));
                            // We don't know about the remaining calls, let's assume it is the same as the limit
                            response.headers().set(X_QUOTA_REMAINING, Long.toString(limit));
                            response.headers().set(X_QUOTA_RESET, Long.toString(-1));
                        }

                        executionContext.setAttribute(ExecutionContext.ATTR_QUOTA_REMAINING, limit);
                        executionContext.setAttribute(ExecutionContext.ATTR_QUOTA_LIMIT, limit);

                        // If an errors occurs at the repository level, we accept the call
                        policyChain.doNext(request, response);
                    }
                }
            );
    }

    private PolicyResult createLimitExceeded(QuotaConfiguration quotaConfiguration, long actualLimit, long periodTime) {
        return PolicyResult.failure(
            QUOTA_TOO_MANY_REQUESTS,
            HttpStatusCode.TOO_MANY_REQUESTS_429,
            "Quota exceeded! You reached the limit of " +
                actualLimit +
                " requests per " +
                periodTime +
                ' ' +
                quotaConfiguration.getPeriodTimeUnit().name().toLowerCase(),
            Maps.<String, Object>builder()
                .put("limit", actualLimit)
                .put("period_time", periodTime)
                .put("period_unit", quotaConfiguration.getPeriodTimeUnit())
                .build()
        );
    }

    protected QuotaPolicyConfiguration getQuotaConfiguration() {
        return quotaPolicyConfiguration;
    }
}
