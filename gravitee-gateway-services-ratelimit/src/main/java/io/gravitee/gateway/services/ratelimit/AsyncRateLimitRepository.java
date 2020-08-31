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
package io.gravitee.gateway.services.ratelimit;

import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleSource;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.BiFunction;
import io.reactivex.functions.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AsyncRateLimitRepository implements RateLimitRepository<RateLimit> {

    private final Logger logger = LoggerFactory.getLogger(AsyncRateLimitRepository.class);

    private LocalRateLimitRepository localCacheRateLimitRepository;
    private RateLimitRepository<RateLimit> remoteCacheRateLimitRepository;

    private final Set<String> keys = new CopyOnWriteArraySet<>();

    private final BaseSchedulerProvider schedulerProvider;

    // Get a map of lock for each rate-limit key to ensure data consistency during merge
    private final Map<String, Semaphore> locks = new ConcurrentHashMap<>();

    public AsyncRateLimitRepository(BaseSchedulerProvider schedulerProvider) {
        this.schedulerProvider = schedulerProvider;
    }

    public void initialize() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS)
                .repeat()
                .subscribe(tick -> merge());

        //TODO: dispose subscribe when service is stopped
    }

    @Override
    public Single<RateLimit> incrementAndGet(String key, long weight, Supplier<RateLimit> supplier) {
        return
                isLocked(key)
                        .subscribeOn(schedulerProvider.computation())
                        .andThen(
                                Single.defer(() -> localCacheRateLimitRepository
                                        .incrementAndGet(key, weight, () -> new LocalRateLimit(supplier.get()))
                                        .map(localRateLimit -> {
                                            keys.add(localRateLimit.getKey());
                                            return localRateLimit;
                                        }))
                        );
    }

    void merge() {
        if (!keys.isEmpty()) {
            keys.forEach(new java.util.function.Consumer<String>() {
                @Override
                public void accept(String key) {
                    lock(key)
                            // By default, delay signal are done through the computation scheduler
                            //        .observeOn(Schedulers.computation())
                            .andThen(localCacheRateLimitRepository.get(key)
                                    // Remote rate is incremented by the local counter value
                                    // If the remote does not contains existing value, use the local counter
                                    .flatMapSingle((Function<LocalRateLimit, SingleSource<RateLimit>>) localRateLimit ->
                                            remoteCacheRateLimitRepository.incrementAndGet(key, localRateLimit.getLocal(), () -> localRateLimit))
                                    .zipWith(
                                            localCacheRateLimitRepository.get(key).toSingle(),
                                            new BiFunction<RateLimit, LocalRateLimit, LocalRateLimit>() {
                                                @Override
                                                public LocalRateLimit apply(RateLimit rateLimit, LocalRateLimit localRateLimit) throws Exception {
                                                    // Set the counter with the latest value from the repository
                                                    localRateLimit.setCounter(rateLimit.getCounter());

                                                    // Re-init the local counter
                                                    localRateLimit.setLocal(0L);

                                                    return localRateLimit;
                                                }
                                            })
                                    // And save the new counter value into the local cache
                                    .flatMap((Function<LocalRateLimit, SingleSource<LocalRateLimit>>) rateLimit ->
                                            localCacheRateLimitRepository.save(rateLimit))
                                    .doAfterTerminate(() -> unlock(key))
                                    .doOnError(throwable -> logger.error("An unexpected error occurs while refreshing asynchronous rate-limit", throwable)))
                            .subscribe();
                }
            });

            // Clear keys
            keys.clear();
        }
    }

    private Completable isLocked(String key) {
        return Completable.create(emitter -> {
            Semaphore sem = locks.get(key);

            if (sem == null) {
                emitter.onComplete();
            } else {
                // Wait until unlocked
                boolean acquired = false;
                while(!acquired) {
                    acquired = sem.tryAcquire();
                }

                // Once we get access, release
                sem.release();
            }

            emitter.onComplete();
        });
    }

    private Completable lock(String key) {
        return Completable.create(emitter -> {
            Semaphore sem = locks.computeIfAbsent(key, key1 -> new Semaphore(1));

            boolean acquired = false;
            while(!acquired) {
                acquired = sem.tryAcquire();
            }

            emitter.onComplete();
        });
    }

    private void unlock(String key) {
        Semaphore lock = this.locks.get(key);
        if (lock != null) {
            lock.release();
        }
    }

    public void setLocalCacheRateLimitRepository(LocalRateLimitRepository localCacheRateLimitRepository) {
        this.localCacheRateLimitRepository = localCacheRateLimitRepository;
    }

    public void setRemoteCacheRateLimitRepository(RateLimitRepository<RateLimit> remoteCacheRateLimitRepository) {
        this.remoteCacheRateLimitRepository = remoteCacheRateLimitRepository;
    }
}
