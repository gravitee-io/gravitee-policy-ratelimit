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

import io.gravitee.repository.ratelimit.api.TokenBucketCalculator;
import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitRepository;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * In-memory, per-node token-bucket store backing the async path. It refills and consumes against a
 * local copy of the bucket using the same {@link TokenBucketCalculator} as the strict backends, with
 * no store round-trip, and tracks the locally-consumed delta so the async repository can reconcile it
 * to the real store later. The async analogue of {@code LocalRateLimitRepository}.
 */
public class LocalTokenBucketRateLimitRepository implements TokenBucketRateLimitRepository<LocalTokenBucket> {

    private final ConcurrentMap<String, LocalTokenBucket> buckets = new ConcurrentHashMap<>();

    public LocalTokenBucketRateLimitRepository() {}

    @Override
    public Single<TokenBucketConsumeResult> refillAndTryConsume(
        String key,
        long tokensRequested,
        long refillRate,
        long refillPeriodMillis,
        long capacity,
        long nowMillis,
        Supplier<LocalTokenBucket> supplier
    ) {
        return Single.fromCallable(() -> {
            AtomicReference<TokenBucketConsumeResult> resultRef = new AtomicReference<>();
            buckets.compute(key, (k, bucket) -> {
                // First touch on this node: seed a full bucket, just like the strict backends.
                if (bucket == null) {
                    bucket = supplier.get();
                    TokenBucketCalculator.newFullBucket(bucket, capacity, nowMillis);
                }
                TokenBucketConsumeResult result = TokenBucketCalculator.refillAndTryConsume(
                    bucket,
                    tokensRequested,
                    refillRate,
                    refillPeriodMillis,
                    capacity,
                    nowMillis
                );
                // Only consumed tokens count toward the delta we owe the store.
                if (result.allowed()) {
                    bucket.addLocalConsumed(tokensRequested);
                }
                // Remember the parameters so the reconcile can replay them to the store.
                bucket.rememberConfig(refillRate, refillPeriodMillis, capacity);
                resultRef.set(result);
                return bucket;
            });
            return resultRef.get();
        }).subscribeOn(Schedulers.computation());
    }

    Maybe<LocalTokenBucket> get(String key) {
        return Maybe.fromCallable(() -> buckets.get(key)).subscribeOn(Schedulers.computation());
    }

    Single<LocalTokenBucket> save(LocalTokenBucket bucket) {
        return Single.fromCallable(() -> {
            buckets.put(bucket.getKey(), bucket);
            return bucket;
        }).subscribeOn(Schedulers.computation());
    }
}
