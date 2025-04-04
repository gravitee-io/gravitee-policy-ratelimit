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
package io.gravitee.policy.spike;

import static io.gravitee.policy.spike.utils.LimitUtils.computeSliceLimit;
import static io.gravitee.policy.spike.utils.LimitUtils.getEndOfPeriod;

import io.gravitee.common.util.Maps;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpBaseExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpMessageExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpPlainExecutionContext;
import io.gravitee.gateway.reactive.api.policy.http.HttpPolicy;
import io.gravitee.policy.spike.configuration.SpikeArrestConfiguration;
import io.gravitee.policy.spike.configuration.SpikeArrestPolicyConfiguration;
import io.gravitee.policy.spike.utils.LimitUtils;
import io.gravitee.policy.v3.spike.SpikeArrestPolicyV3;
import io.gravitee.ratelimit.Pair;
import io.gravitee.ratelimit.PolicyRateLimitException;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Context;
import io.vertx.rxjava3.core.RxHelper;
import io.vertx.rxjava3.core.Vertx;
import java.util.concurrent.TimeUnit;

public class SpikeArrestPolicy extends SpikeArrestPolicyV3 implements HttpPolicy {

    public SpikeArrestPolicy(SpikeArrestPolicyConfiguration spikeArrestPolicyConfiguration) {
        super(spikeArrestPolicyConfiguration);
    }

    @Override
    public String id() {
        return "spike-arrest";
    }

    @Override
    public Completable onRequest(HttpPlainExecutionContext ctx) {
        return Completable
            .defer(() -> run(ctx))
            .onErrorResumeNext(th -> ctx.interruptWith(PolicyRateLimitException.getExecutionFailure(th)));
    }

    @Override
    public Completable onMessageRequest(HttpMessageExecutionContext ctx) {
        return Completable
            .defer(() -> run(ctx))
            .onErrorResumeNext(th -> ctx.interruptMessagesWith(PolicyRateLimitException.getExecutionFailure(th)).ignoreElements());
    }

    public Completable run(HttpBaseExecutionContext ctx) {
        RateLimitService rateLimitService = ctx.getComponent(RateLimitService.class);
        SpikeArrestPolicyConfiguration policyConfiguration = getSpikeArrestPolicyConfiguration();
        SpikeArrestConfiguration spikeArrestConfiguration = policyConfiguration.getSpike();

        if (rateLimitService == null) {
            String message = "No rate-limit service has been installed.";
            ctx.metrics().setErrorMessage(message);
            return Completable.error(PolicyRateLimitException.serverError(message));
        }

        var k = KEY_FACTORY.createRateLimitKey(ctx, spikeArrestConfiguration);
        var l = (spikeArrestConfiguration.getLimit() > 0)
            ? Maybe.just(spikeArrestConfiguration.getLimit())
            : ctx.getTemplateEngine().eval(spikeArrestConfiguration.getDynamicLimit(), Long.class);

        Context context = Vertx.currentContext();

        return Single
            .zip(k, l.defaultIfEmpty(0L), Pair::new)
            .flatMapCompletable(entry -> {
                long limit = entry.limit();

                var slice = computeSliceLimit(
                    limit,
                    spikeArrestConfiguration.getPeriodTime(),
                    spikeArrestConfiguration.getPeriodTimeUnit()
                );

                return rateLimitService
                    .incrementAndGet(
                        entry.key(),
                        policyConfiguration.isAsync(),
                        () -> {
                            // Set the time at which the current rate limit window resets in UTC epoch seconds.
                            long resetTimeMillis = getEndOfPeriod(ctx.timestamp(), slice.period(), TimeUnit.MILLISECONDS);

                            RateLimit rate = new RateLimit(entry.key());
                            rate.setCounter(0);
                            rate.setLimit(slice.limit());
                            rate.setResetTime(resetTimeMillis);
                            rate.setSubscription(ctx.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
                            return rate;
                        }
                    )
                    .observeOn(RxHelper.scheduler(context))
                    .flatMapCompletable(rateLimit -> {
                        // Set Rate Limit headers on response
                        if (policyConfiguration.isAddHeaders()) {
                            ctx.response().headers().set(X_SPIKE_ARREST_LIMIT, Long.toString(slice.limit()));
                            ctx.response().headers().set(X_SPIKE_ARREST_SLICE, slice.period() + "ms");
                            ctx.response().headers().set(X_SPIKE_ARREST_RESET, Long.toString(rateLimit.getResetTime()));
                        }

                        if (rateLimit.getCounter() <= slice.limit()) {
                            return Completable.complete();
                        } else {
                            String message = String.format(
                                "Spike limit exceeded! You reached the limit of %d requests per %d ms.",
                                slice.limit(),
                                slice.period()
                            );
                            ctx.metrics().setErrorKey(SpikeArrestPolicyV3.SPIKE_ARREST_TOO_MANY_REQUESTS);
                            ctx.metrics().setErrorMessage(message);
                            return Completable.error(
                                PolicyRateLimitException.overflow(
                                    SpikeArrestPolicyV3.SPIKE_ARREST_TOO_MANY_REQUESTS,
                                    message,
                                    Maps
                                        .<String, Object>builder()
                                        .put("slice_limit", slice.limit())
                                        .put("slice_period_time", slice.period())
                                        .put("slice_period_unit", TimeUnit.MILLISECONDS)
                                        .put("limit", limit)
                                        .put("period_time", spikeArrestConfiguration.getPeriodTime())
                                        .put("period_unit", spikeArrestConfiguration.getPeriodTimeUnit())
                                        .build()
                                )
                            );
                        }
                    })
                    .onErrorResumeNext(throwable -> errorManagement(ctx, throwable, policyConfiguration, slice));
            });
    }

    private static Completable errorManagement(
        HttpBaseExecutionContext ctx,
        Throwable throwable,
        SpikeArrestPolicyConfiguration policyConfiguration,
        LimitUtils.SliceLimit slice
    ) {
        if (throwable instanceof PolicyRateLimitException ex) {
            return Completable.error(ex);
        }
        // Set Spike Arrest Limit headers on response
        if (policyConfiguration.isAddHeaders()) {
            ctx.response().headers().set(X_SPIKE_ARREST_LIMIT, Long.toString(slice.limit()));
            ctx.response().headers().set(X_SPIKE_ARREST_SLICE, slice.period() + "ms");
            ctx.response().headers().set(X_SPIKE_ARREST_RESET, Long.toString(-1));
        }

        // If an errors occurs at the repository level, we accept the call
        return Completable.complete();
    }
}
