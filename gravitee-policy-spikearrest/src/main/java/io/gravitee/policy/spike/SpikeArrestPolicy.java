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
package io.gravitee.policy.spike;

import static io.gravitee.policy.spike.utils.LimitUtils.SliceLimit;
import static io.gravitee.policy.spike.utils.LimitUtils.computeSliceLimit;
import static io.gravitee.policy.spike.utils.LimitUtils.getEndOfPeriod;

import io.gravitee.common.http.HttpStatusCode;
import io.gravitee.common.util.Maps;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.policy.api.PolicyChain;
import io.gravitee.policy.api.PolicyResult;
import io.gravitee.policy.api.annotations.OnRequest;
import io.gravitee.policy.spike.configuration.SpikeArrestConfiguration;
import io.gravitee.policy.spike.configuration.SpikeArrestPolicyConfiguration;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.SingleObserver;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The spike arrest policy insures that the amount of requests is limited and smoothed to x requests per y time period.
 *
 * Useful when you want to protected your backends to get flooded with requests.
 *
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@SuppressWarnings("unused")
public class SpikeArrestPolicy {

    /**
     * LOGGER
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(SpikeArrestPolicy.class);

    private static final String SPIKE_ARREST_TOO_MANY_REQUESTS = "SPIKE_ARREST_TOO_MANY_REQUESTS";

    /**
     * The maximum number of requests that the backend is allowed to receive per time unit.
     */
    public static final String X_SPIKE_ARREST_LIMIT = "X-Spike-Arrest-Limit";
    /**
     * The time window applied to reach the limit.
     */
    public static final String X_SPIKE_ARREST_SLICE = "X-Spike-Arrest-Slice-Period";

    /**
     * The time at which the current spike limit window resets in UTC epoch seconds.
     */
    public static final String X_SPIKE_ARREST_RESET = "X-Spike-Arrest-Reset";

    public static final String ATTR_OAUTH_CLIENT_ID = "oauth.client_id";

    /**
     * Spike arrest policy configuration
     */
    private final SpikeArrestPolicyConfiguration spikeArrestPolicyConfiguration;

    public SpikeArrestPolicy(SpikeArrestPolicyConfiguration spikeArrestPolicyConfiguration) {
        this.spikeArrestPolicyConfiguration = spikeArrestPolicyConfiguration;
    }

    @OnRequest
    public void onRequest(Request request, Response response, ExecutionContext executionContext, PolicyChain policyChain) {
        RateLimitService rateLimitService = executionContext.getComponent(RateLimitService.class);
        SpikeArrestConfiguration spikeArrestConfiguration = spikeArrestPolicyConfiguration.getSpike();

        if (rateLimitService == null) {
            policyChain.failWith(PolicyResult.failure("No rate-limit service has been installed."));
            return;
        }

        String key = RateLimitKeyFactory.createRateLimitKey(executionContext, spikeArrestConfiguration);
        final long limit = (spikeArrestConfiguration.getLimit() > 0)
            ? spikeArrestConfiguration.getLimit()
            : executionContext.getTemplateEngine().getValue(spikeArrestConfiguration.getDynamicLimit(), Long.class);

        SliceLimit slice = computeSliceLimit(limit, spikeArrestConfiguration.getPeriodTime(), spikeArrestConfiguration.getPeriodTimeUnit());

        Context context = Vertx.currentContext();

        rateLimitService
            .incrementAndGet(
                key,
                spikeArrestPolicyConfiguration.isAsync(),
                () -> {
                    // Set the time at which the current rate limit window resets in UTC epoch seconds.
                    long resetTimeMillis = getEndOfPeriod(request.timestamp(), slice.getPeriod(), TimeUnit.MILLISECONDS);

                    RateLimit rate = new RateLimit(key);
                    rate.setCounter(0);
                    rate.setLimit(slice.getLimit());
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
                        if (spikeArrestPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_SPIKE_ARREST_LIMIT, Long.toString(slice.getLimit()));
                            response.headers().set(X_SPIKE_ARREST_SLICE, slice.getPeriod() + "ms");
                            response.headers().set(X_SPIKE_ARREST_RESET, Long.toString(rateLimit.getResetTime()));
                        }

                        if (rateLimit.getCounter() <= slice.getLimit()) {
                            policyChain.doNext(request, response);
                        } else {
                            policyChain.failWith(createLimitExceeded(spikeArrestConfiguration, slice, limit));
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        // Set Spike Arrest Limit headers on response
                        if (spikeArrestPolicyConfiguration.isAddHeaders()) {
                            response.headers().set(X_SPIKE_ARREST_LIMIT, Long.toString(slice.getLimit()));
                            response.headers().set(X_SPIKE_ARREST_SLICE, slice.getPeriod() + "ms");
                            response.headers().set(X_SPIKE_ARREST_RESET, Long.toString(-1));
                        }

                        // If an errors occurs at the repository level, we accept the call
                        policyChain.doNext(request, response);
                    }
                }
            );
    }

    private PolicyResult createLimitExceeded(SpikeArrestConfiguration spikeArrestConfiguration, SliceLimit actualLimit, long limit) {
        return PolicyResult.failure(
            SPIKE_ARREST_TOO_MANY_REQUESTS,
            HttpStatusCode.TOO_MANY_REQUESTS_429,
            "Spike limit exceeded ! You reach the limit of " + actualLimit.getLimit() + " requests per " + actualLimit.getPeriod() + " ms.",
            Maps
                .<String, Object>builder()
                .put("slice_limit", actualLimit.getLimit())
                .put("slice_period_time", actualLimit.getPeriod())
                .put("slice_period_unit", TimeUnit.MILLISECONDS)
                .put("limit", limit)
                .put("period_time", spikeArrestConfiguration.getPeriodTime())
                .put("period_unit", spikeArrestConfiguration.getPeriodTimeUnit())
                .build()
        );
    }
}
