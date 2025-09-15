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
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.shareddata.SharedData;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Setter
@Slf4j
public class AsyncRateLimitRepository implements RateLimitRepository<RateLimit> {

    private static final Long LOCK_TIMEOUT_MILLIS = 250L;

    private final Set<String> keys = new CopyOnWriteArraySet<>();
    private final SharedData sharedData;
    private LocalRateLimitRepository localCacheRateLimitRepository;
    private RateLimitRepository<RateLimit> remoteCacheRateLimitRepository;
    private Disposable mergeSubscription;

    public AsyncRateLimitRepository(final Vertx vertx) {
        this.sharedData = vertx.sharedData();
    }

    public void initialize() {
        mergeSubscription = Flowable.<Long, Long>generate(
            () -> 0L,
            (state, emitter) -> {
                emitter.onNext(state);
                return state + 1;
            }
        )
            .delay(5000, TimeUnit.MILLISECONDS)
            .rebatchRequests(1)
            .filter(interval -> !keys.isEmpty())
            .concatMapCompletable(interval ->
                Flowable.fromIterable(keys).flatMapCompletable(key ->
                    sharedData
                        .getLocalLock(key)
                        .flatMapCompletable(lock ->
                            localCacheRateLimitRepository
                                .get(key)
                                // Remote rate is incremented by the local counter value
                                // If the remote does not contain existing value, use the local counter
                                .flatMapSingle(localRateLimit ->
                                    remoteCacheRateLimitRepository
                                        .incrementAndGet(key, localRateLimit.getLocal(), () -> localRateLimit)
                                        .map(rateLimit -> {
                                            // Set the counter with the latest value from the repository
                                            localRateLimit.setCounter(rateLimit.getCounter());

                                            // Re-init the local counter
                                            localRateLimit.setLocal(0L);

                                            return localRateLimit;
                                        })
                                        .flatMap(rateLimit -> localCacheRateLimitRepository.save(rateLimit))
                                )
                                .doFinally(lock::release)
                                // And save the new counter value into the local cache
                                .doOnSuccess(localRateLimit -> keys.remove(key))
                                .doOnError(throwable ->
                                    log.error("An unexpected error occurs while refreshing asynchronous rate-limit", throwable)
                                )
                                .onErrorComplete()
                                .ignoreElement()
                        )
                )
            )
            .onErrorComplete()
            .subscribe();
    }

    public void clean() {
        if (mergeSubscription != null) {
            mergeSubscription.dispose();
        }
    }

    @Override
    public Single<RateLimit> incrementAndGet(String key, long weight, Supplier<RateLimit> supplier) {
        return sharedData
            .getLocalLockWithTimeout(key, LOCK_TIMEOUT_MILLIS)
            .flatMap(lock ->
                Single.defer(() ->
                    localCacheRateLimitRepository
                        .incrementAndGet(key, weight, () -> new LocalRateLimit(supplier.get()))
                        .doOnSuccess(localRateLimit -> keys.add(localRateLimit.getKey()))
                ).doFinally(lock::release)
            );
    }
}
