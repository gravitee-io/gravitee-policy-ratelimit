/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.policy.ratelimit;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.Maps;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.utils.DateUtils;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.SingleObserver;
import io.reactivex.disposables.Disposable;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.reactivex.RxHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The rate limit policy, also known as throttling insure that a user (given its api key or IP address) is allowed
 * to make x requests per y time period.
 *
 * Useful when you want to ensure that your APIs does not get flooded with requests.
 *
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@SuppressWarnings("unused")
public class RateLimitPolicy {

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitPolicy.class);

    private static final String RATE_LIMIT_TOO_MANY_REQUESTS = "RATE_LIMIT_TOO_MANY_REQUESTS";

    /**
     * The maximum number of requests that the consumer is permitted to make per time unit.
     */
    public static final String X_RATE_LIMIT_LIMIT = "X-Rate-Limit-Limit";

    /**
     * The number of requests remaining in the current rate limit window.
     */
    public static final String X_RATE_LIMIT_REMAINING = "X-Rate-Limit-Remaining";

    /**
     * The time at which the current rate limit window resets in UTC epoch seconds.
     */
    public static final String X_RATE_LIMIT_RESET = "X-Rate-Limit-Reset";

    private static char KEY_SEPARATOR = ':';

    private static String RATE_LIMIT_TYPE = "rl";

    /**
     * Rate limit policy configuration
     */
    private final RateLimitPolicyConfiguration rateLimitPolicyConfiguration;

    public RateLimitPolicy(RateLimitPolicyConfiguration rateLimitPolicyConfiguration) {
        this.rateLimitPolicyConfiguration = rateLimitPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        RateLimitService rateLimitService = executionContext.getComponent(RateLimitService.class);

        if (rateLimitService == null) {
            policyChain.failWith(PolicyResult.failure("No rate-limit service has been installed."));
            return;
        }

        String key = createRateLimitKey(request, executionContext, rateLimitPolicyConfiguration);

        RateLimitConfiguration rateLimitConfiguration = rateLimitPolicyConfiguration.getRate();
        Long limit = evaluateActualLimit(executionContext, rateLimitConfiguration);

        Context context = Vertx.currentContext();

        rateLimitService
                .incrementAndGet(key, rateLimitPolicyConfiguration.isAsync(), new Supplier<RateLimit>() {
                    @Override
                    public RateLimit get() {
                        // Set the time at which the current rate limit window resets in UTC epoch seconds.
                        long resetTimeMillis = DateUtils.getEndOfPeriod(
                                request.timestamp(),
                                rateLimitConfiguration.getPeriodTime(),
                                rateLimitConfiguration.getPeriodTimeUnit());

                        RateLimit rate = new RateLimit(key);
                        rate.setCounter(0);
                        rate.setLimit(limit);
                        rate.setResetTime(resetTimeMillis);
                        rate.setSubscription((String) executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
                        return rate;
                    }
                })
                .observeOn(RxHelper.scheduler(context))
                .subscribe(new SingleObserver<RateLimit>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(RateLimit rateLimit) {
                        // Set Rate Limit headers on response
                        if (rateLimitPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_RATE_LIMIT_LIMIT, Long.toString(limit));
                            response.headers().set(X_RATE_LIMIT_REMAINING, Long.toString(Math.max(0, limit - rateLimit.getCounter())));
                            response.headers().set(X_RATE_LIMIT_RESET, Long.toString(rateLimit.getResetTime()));
                        }

                        if (rateLimit.getCounter() <= limit) {
                            policyChain.doNext(request, response);
                        } else {
                            policyChain.failWith(createLimitExceeded(rateLimitConfiguration, limit));
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        // Set Rate Limit headers on response
                        if (rateLimitPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_RATE_LIMIT_LIMIT, Long.toString(limit));
                            // We don't know about the remaining calls, let's assume it is the same as the limit
                            response.headers().set(X_RATE_LIMIT_REMAINING, Long.toString(limit));
                            response.headers().set(X_RATE_LIMIT_RESET, Long.toString(-1));
                        }

                        // If an errors occurs at the repository level, we accept the call
                        policyChain.doNext(request, response);
                    }
                });
    }

    private long evaluateActualLimit(ExecutionContext executionContext, RateLimitConfiguration rateLimitConfiguration) {
        if (rateLimitConfiguration.getTemplatableLimit() == null) {
            return rateLimitConfiguration.getLimit();
        } else {
            try {
                return Long.parseLong(rateLimitConfiguration.getTemplatableLimit());
            } catch (NumberFormatException nfe) {
                return executionContext.getTemplateEngine().getValue(rateLimitConfiguration.getTemplatableLimit(), Long.class);
            }
        }
    }

    private String createRateLimitKey(Request request, ExecutionContext executionContext, RateLimitPolicyConfiguration rateLimitPolicyConfiguration) {
        // Rate limit key must contain :
        // 1_ User-defined key, or (PLAN_ID, SUBSCRIPTION_ID) by default
        // 2_ Rate Type (rate-limit / quota)
        // 3_ RESOLVED_PATH (policy attached to a path rather than a plan)
        String resolvedPath = (String) executionContext.getAttribute(ExecutionContext.ATTR_RESOLVED_PATH);

        StringBuilder key = new StringBuilder();

        if (rateLimitPolicyConfiguration.getKey() == null || rateLimitPolicyConfiguration.getKey().isEmpty()) {
            key.append(executionContext.getAttribute(ExecutionContext.ATTR_PLAN))
                .append(KEY_SEPARATOR)
                .append(executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
        } else {
            key.append(executionContext.getTemplateEngine().getValue(rateLimitPolicyConfiguration.getKey(), String.class));
        }

        key.append(KEY_SEPARATOR)
            .append(RATE_LIMIT_TYPE);

        if (resolvedPath != null) {
            key.append(KEY_SEPARATOR).append(resolvedPath.hashCode());
        }

        return key.toString();
    }

    private PolicyResult createLimitExceeded(RateLimitConfiguration rateLimitConfiguration, long actualLimit) {
        return PolicyResult.failure(
                RATE_LIMIT_TOO_MANY_REQUESTS,
                HttpStatusCode.TOO_MANY_REQUESTS_429,
                "Rate limit exceeded ! You reach the limit of " + actualLimit +
                        " requests per " + rateLimitConfiguration.getPeriodTime() + ' ' +
                        rateLimitConfiguration.getPeriodTimeUnit().name().toLowerCase(),
                Maps.<String, Object>builder()
                        .put("limit", actualLimit)
                        .put("period_time", rateLimitConfiguration.getPeriodTime())
                        .put("period_unit", rateLimitConfiguration.getPeriodTimeUnit())
                        .build());
    }
}
