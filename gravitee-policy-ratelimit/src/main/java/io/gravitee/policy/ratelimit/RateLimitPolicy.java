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
import io.gravitee.common.node.Node;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.ChainScope;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.Category;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.api.annotations.Policy;
import io.gravitee.policy.api.annotations.Scope;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.utils.DateUtils;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
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
@Policy(
        category = @Category(io.gravitee.policy.api.Category.SECURITY),
        scope = @Scope({ChainScope.API, ChainScope.PLAN})
)
public class RateLimitPolicy {

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(RateLimitPolicy.class);

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

        // We prefer currentTimeMillis in place of nanoTime() because nanoTime is relatively
        // expensive call and depends on the underlying architecture.
        long now = System.currentTimeMillis();

        RateLimitConfiguration rateLimitConfiguration = rateLimitPolicyConfiguration.getRate();

        String rateLimitKey = createRateLimitKey(rateLimitPolicyConfiguration.isAsync(), request, executionContext);
        RateLimit rateLimit = rateLimitService.get(rateLimitKey, rateLimitPolicyConfiguration.isAsync());

        boolean rateLimitExceeded = false;

        if (rateLimit.getResetTime() != 0 && now >= rateLimit.getResetTime()) {
            rateLimit.setCounter(0);
            rateLimit.setResetTime(0);
        }

        if (rateLimit.getCounter() >= rateLimitConfiguration.getLimit()) {
            rateLimitExceeded = true;
            rateLimit.setCounter(rateLimitConfiguration.getLimit());
        } else {
            // Update rate limiter
            // By default, weight is 1 (can be configurable in the future)
            rateLimit.setCounter(rateLimit.getCounter() + 1);
            rateLimit.setLastRequest(now);
        }

        // Set the time at which the current rate limit window resets in UTC epoch seconds.
        if (rateLimit.getResetTime() == 0) {
            long resetTimeMillis = DateUtils.getEndOfPeriod(now,
                    rateLimitConfiguration.getPeriodTime(), rateLimitConfiguration.getPeriodTimeUnit());
            rateLimit.setResetTime(resetTimeMillis);
        }

        rateLimit.setAsync(rateLimitPolicyConfiguration.isAsync());
        rateLimit.setUpdatedAt(now);
        if(rateLimit.getCreatedAt() == 0) {
            rateLimit.setCreatedAt(now);
        }

        rateLimitService.save(rateLimit, rateLimitPolicyConfiguration.isAsync());

        long remains = rateLimitConfiguration.getLimit() - rateLimit.getCounter();
        long resetTime = rateLimit.getResetTime() / 1000L;

        // Set Rate Limit headers on response
        if (rateLimitPolicyConfiguration.isAddHeaders()) {
            response.headers().set(X_RATE_LIMIT_LIMIT, Long.toString(rateLimitConfiguration.getLimit()));
            response.headers().set(X_RATE_LIMIT_REMAINING, Long.toString(remains));
            response.headers().set(X_RATE_LIMIT_RESET, Long.toString(resetTime));
        }

        if (rateLimitExceeded) {
            policyChain.failWith(createLimitExceeded(rateLimitConfiguration));
            return;
        }

        policyChain.doNext(request, response);
    }

    private String createRateLimitKey(boolean async, Request request, ExecutionContext executionContext) {
        // Rate limit key must contain :
        // 1_ GATEWAY_ID (async mode only)
        // 2_ Rate Type (throttling / quota)
        // 3_ SUBSCRIPTION_ID
        // 4_ RESOLVED_PATH
        String resolvedPath = (String) executionContext.getAttribute(ExecutionContext.ATTR_RESOLVED_PATH);

        if (async) {
            return executionContext.getComponent(Node.class).id() + KEY_SEPARATOR + RATE_LIMIT_TYPE + KEY_SEPARATOR +
                    executionContext.getAttribute(ExecutionContext.ATTR_USER_ID) + KEY_SEPARATOR +
                    ((resolvedPath != null) ? resolvedPath.hashCode() : "");
        }

        return (String) executionContext.getAttribute(ExecutionContext.ATTR_USER_ID) + KEY_SEPARATOR +
                RATE_LIMIT_TYPE + KEY_SEPARATOR +
                ((resolvedPath != null) ? resolvedPath.hashCode() : "");
    }

    private PolicyResult createLimitExceeded(RateLimitConfiguration rateLimitConfiguration) {
        return PolicyResult.failure(HttpStatusCode.TOO_MANY_REQUESTS_429,
                "Rate limit exceeded ! You reach the limit of " + rateLimitConfiguration.getLimit() +
                        " requests per " + rateLimitConfiguration.getPeriodTime() + ' ' +
                        rateLimitConfiguration.getPeriodTimeUnit().name().toLowerCase());
    }
}
