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
package io.gravitee.gateway.services.ratelimit;

import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public class LocalRateLimitRepository implements RateLimitRepository<LocalRateLimit> {

    private final ConcurrentMap<String, LocalRateLimit> rateLimits = new ConcurrentHashMap<>();

    public LocalRateLimitRepository() {}

    @Override
    public Single<LocalRateLimit> incrementAndGet(String key, long weight, Supplier<LocalRateLimit> supplier) {
        return Single
            .fromCallable(() ->
                rateLimits.compute(
                    key,
                    (key1, rateLimit) -> {
                        // No local counter or existing one is expired
                        if (rateLimit == null || rateLimit.getResetTime() <= System.currentTimeMillis()) {
                            rateLimit = supplier.get();
                        }

                        // Increment local counter
                        rateLimit.setLocal(rateLimit.getLocal() + weight);

                        // We have to update the counter because the policy is based on this one
                        rateLimit.setCounter(rateLimit.getCounter() + weight);
                        return rateLimit;
                    }
                )
            )
            .subscribeOn(Schedulers.computation());
    }

    Maybe<LocalRateLimit> get(String key) {
        return Maybe.fromCallable(() -> rateLimits.get(key)).subscribeOn(Schedulers.computation());
    }

    Single<LocalRateLimit> save(LocalRateLimit rate) {
        return Single
            .fromCallable(() -> {
                rateLimits.put(rate.getKey(), rate);
                return rate;
            })
            .subscribeOn(Schedulers.computation());
    }
}
