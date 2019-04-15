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
package io.gravitee.policy.quota;

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
import io.gravitee.policy.quota.utils.DateUtils;
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
 * The quota policy, also known as throttling insure that a user (given its api key or IP address) is allowed
 * to make x requests per y time period.
 *
 * Useful when you want to ensure that your APIs does not get flooded with requests.
 *
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@SuppressWarnings("unused")
public class QuotaPolicy {

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaPolicy.class);

    private static final String QUOTA_TOO_MANY_REQUESTS = "QUOTA_TOO_MANY_REQUESTS";

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

    private static char KEY_SEPARATOR = ':';

    private static char RATE_LIMIT_TYPE = 'q';

    /**
     * Rate limit policy configuration
     */
    private final QuotaPolicyConfiguration quotaPolicyConfiguration;

    public QuotaPolicy(QuotaPolicyConfiguration quotaPolicyConfiguration) {
        this.quotaPolicyConfiguration = quotaPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        RateLimitService rateLimitService = executionContext.getComponent(RateLimitService.class);

        if (rateLimitService == null) {
            policyChain.failWith(PolicyResult.failure("No rate-limit service has been installed."));

            return;
        }

        QuotaConfiguration quotaConfiguration = quotaPolicyConfiguration.getQuota();
        String key = createRateLimitKey(request, executionContext);

        Context context = Vertx.currentContext();

        rateLimitService.incrementAndGet(key, quotaPolicyConfiguration.isAsync(), new Supplier<RateLimit>() {
            @Override
            public RateLimit get() {
                // Set the time at which the current rate limit window resets in UTC epoch seconds.
                long resetTimeMillis = DateUtils.getEndOfPeriod(
                        request.timestamp(),
                        quotaConfiguration.getPeriodTime(),
                        quotaConfiguration.getPeriodTimeUnit());

                RateLimit rate = new RateLimit(key);
                rate.setCounter(0);
                rate.setLimit(quotaConfiguration.getLimit());
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
                        if (quotaPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_QUOTA_LIMIT, Long.toString(quotaConfiguration.getLimit()));
                            response.headers().set(X_QUOTA_REMAINING, Long.toString(Math.max(0, quotaConfiguration.getLimit() - rateLimit.getCounter())));
                            response.headers().set(X_QUOTA_RESET, Long.toString(rateLimit.getResetTime()));
                        }

                        if (rateLimit.getCounter() <= quotaConfiguration.getLimit()) {
                            policyChain.doNext(request, response);
                        } else {
                            policyChain.failWith(createLimitExceeded(quotaConfiguration));
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        // Set Rate Limit headers on response
                        if (quotaPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_QUOTA_LIMIT, Long.toString(quotaConfiguration.getLimit()));
                            // We don't know about the remaining calls, let's assume it is the same as the limit
                            response.headers().set(X_QUOTA_REMAINING, Long.toString(quotaConfiguration.getLimit()));
                            response.headers().set(X_QUOTA_RESET, Long.toString(-1));
                        }

                        // If an errors occurs at the repository level, we accept the call
                        policyChain.doNext(request, response);
                    }
                });
    }

    private String createRateLimitKey(Request request, ExecutionContext executionContext) {
        // Rate limit key must contain :
        // 1_ PLAN_ID
        // 1_ SUBSCRIPTION_ID
        // 2_ Rate Type (rate-limit / quota)
        // 3_ RESOLVED_PATH (policy attached to a path rather than a plan)
        String resolvedPath = (String) executionContext.getAttribute(ExecutionContext.ATTR_RESOLVED_PATH);

        StringBuilder key = new StringBuilder((String) executionContext.getAttribute(ExecutionContext.ATTR_PLAN))
                .append(KEY_SEPARATOR)
                .append((String) executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID))
                .append(KEY_SEPARATOR)
                .append(RATE_LIMIT_TYPE);

        if (resolvedPath != null) {
            key.append(KEY_SEPARATOR).append(resolvedPath.hashCode());
        }

        return key.toString();
    }

    private PolicyResult createLimitExceeded(QuotaConfiguration quotaConfiguration) {
        return PolicyResult.failure(
                QUOTA_TOO_MANY_REQUESTS,
                HttpStatusCode.TOO_MANY_REQUESTS_429,
                "Quota exceeded ! You reach the limit of " + quotaConfiguration.getLimit() +
                        " requests per " + quotaConfiguration.getPeriodTime() + ' ' +
                        quotaConfiguration.getPeriodTimeUnit().name().toLowerCase(),
                Maps.<String, Object>builder()
                        .put("limit", quotaConfiguration.getLimit())
                        .put("period_time", quotaConfiguration.getPeriodTime())
                        .put("period_unit", quotaConfiguration.getPeriodTimeUnit())
                        .build());
    }
}
