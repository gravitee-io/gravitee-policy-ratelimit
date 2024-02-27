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
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        RateLimitConfiguration rateLimitConfiguration = rateLimitPolicyConfiguration.getRate();

        if (rateLimitService == null) {
            policyChain.failWith(PolicyResult.failure("No rate-limit service has been installed."));
            return;
        }

        String key = RateLimitKeyFactory.createRateLimitKey(executionContext, rateLimitConfiguration);
        long limit = (rateLimitConfiguration.getLimit() > 0)
            ? rateLimitConfiguration.getLimit()
            : executionContext.getTemplateEngine().getValue(rateLimitConfiguration.getDynamicLimit(), Long.class);

        Context context = Vertx.currentContext();

        rateLimitService
            .incrementAndGet(
                key,
                rateLimitPolicyConfiguration.isAsync(),
                () -> {
                    // Set the time at which the current rate limit window resets in UTC epoch seconds.
                    long resetTimeMillis = DateUtils.getEndOfPeriod(
                        request.timestamp(),
                        rateLimitConfiguration.getPeriodTime(),
                        rateLimitConfiguration.getPeriodTimeUnit()
                    );

                    RateLimit rate = new RateLimit(key);
                    rate.setCounter(0);
                    rate.setLimit(limit);
                    rate.setResetTime(resetTimeMillis);
                    rate.setSubscription((String) executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
                    return rate;
                }
            )
            .observeOn(RxHelper.scheduler(context))
            .subscribe(
                new SingleObserver<>() {
                    @Override
                    public void onSubscribe(Disposable d) {}

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
                }
            );
    }

    private PolicyResult createLimitExceeded(RateLimitConfiguration rateLimitConfiguration, long actualLimit) {
        return PolicyResult.failure(
            RATE_LIMIT_TOO_MANY_REQUESTS,
            HttpStatusCode.TOO_MANY_REQUESTS_429,
            "Rate limit exceeded ! You reach the limit of " +
            actualLimit +
            " requests per " +
            rateLimitConfiguration.getPeriodTime() +
            ' ' +
            rateLimitConfiguration.getPeriodTimeUnit().name().toLowerCase(),
            Maps
                .<String, Object>builder()
                .put("limit", actualLimit)
                .put("period_time", rateLimitConfiguration.getPeriodTime())
                .put("period_unit", rateLimitConfiguration.getPeriodTimeUnit())
                .build()
        );
    }
}
