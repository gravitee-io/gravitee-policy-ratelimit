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

import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitRepository;
import io.gravitee.repository.ratelimit.model.TokenBucket;
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
 * Non-strict token-bucket repository. Every request refills and consumes against a node-local bucket
 * ({@link LocalTokenBucketRateLimitRepository}) with no store round-trip; a scheduler periodically
 * reconciles each touched key's locally-consumed delta to the backing store. The token-bucket
 * analogue of {@code AsyncRateLimitRepository}.
 */
@Setter
@Slf4j
public class AsyncTokenBucketRateLimitRepository implements TokenBucketRateLimitRepository<TokenBucket> {

    private static final Long LOCK_TIMEOUT_MILLIS = 250L;

    private final Set<String> keys = new CopyOnWriteArraySet<>();
    private final SharedData sharedData;
    private LocalTokenBucketRateLimitRepository localCacheTokenBucketRepository;
    private TokenBucketRateLimitRepository<TokenBucket> remoteCacheTokenBucketRepository;
    private Disposable mergeSubscription;

    public AsyncTokenBucketRateLimitRepository(final Vertx vertx) {
        this.sharedData = vertx.sharedData();
    }

    @Override
    public Single<TokenBucketConsumeResult> refillAndTryConsume(
        String key,
        long tokensRequested,
        long refillRate,
        long refillPeriodMillis,
        long capacity,
        long nowMillis,
        Supplier<TokenBucket> supplier
    ) {
        return sharedData
            .getLocalLockWithTimeout(key, LOCK_TIMEOUT_MILLIS)
            .flatMap(lock ->
                Single.defer(() ->
                    localCacheTokenBucketRepository
                        .refillAndTryConsume(key, tokensRequested, refillRate, refillPeriodMillis, capacity, nowMillis, () ->
                            new LocalTokenBucket(supplier.get())
                        )
                        .doOnSuccess(result -> keys.add(key))
                ).doFinally(lock::release)
            );
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
                            localCacheTokenBucketRepository
                                .get(key)
                                .flatMapSingle(this::reconcile)
                                .doFinally(lock::release)
                                .doOnSuccess(local -> keys.remove(key))
                                // Outer level so a get(key) failure is logged too, not only a reconcile() error
                                // (matches AsyncRateLimitRepository). The key is removed only on success, so a
                                // transient store error leaves it tracked and is retried next cycle.
                                .doOnError(throwable ->
                                    log.error("Failed to reconcile asynchronous token-bucket for key '{}'", key, throwable)
                                )
                                .onErrorComplete()
                                .ignoreElement()
                        )
                )
            )
            .onErrorComplete()
            .subscribe();
    }

    /**
     * Push the node's locally-consumed delta to the store as a single batched consume, then re-anchor the
     * local bucket from the store's verdict. The local view stays approximate between reconciles (other
     * nodes consume too), which is the non-strict trade-off.
     *
     * <p>The batched consume is all-or-nothing: if the store has fewer tokens than the delta it debits
     * nothing and returns {@code allowed=false}. That outcome means the global bucket is depleted — the
     * cluster has hit the limit — so this node is treated as out of tokens and throttles back to its local
     * refill rate, rather than re-arming to the store's still-positive remaining and over-serving again.
     * The over-served delta cannot be un-served; it is dropped (a bounded, one-time overshoot) rather than
     * carried forward, which would balloon the debt under sustained overload.
     */
    private Single<LocalTokenBucket> reconcile(LocalTokenBucket local) {
        long now = System.currentTimeMillis();
        Single<TokenBucketConsumeResult> remoteCall = remoteCacheTokenBucketRepository.refillAndTryConsume(
            local.getKey(),
            local.getLocalConsumed(),
            local.getRefillRate(),
            local.getRefillPeriodMillis(),
            local.getCapacity(),
            now,
            // Seed the store with a PLAIN TokenBucket, never the LocalTokenBucket subclass: a distributed
            // store (e.g. Hazelcast) serialises the seed across the cluster, and the gateway-services class
            // is not on the store plugin's classloader (ClassNotFoundException). The store only needs the
            // key + subscription, which the copy carries.
            () -> new TokenBucket(local)
        );

        // A null Single means rate limiting is disabled (no-op store): nothing to reconcile, just clear the delta.
        if (remoteCall == null) {
            local.setLocalConsumed(0L);
            return localCacheTokenBucketRepository.save(local);
        }

        return remoteCall
            .map(result -> {
                // allowed: the store accepted the batch; adopt its authoritative remaining.
                // rejected: the store is depleted; throttle this node (0 tokens) so it does not over-serve again.
                local.setTokens(result.allowed() ? result.remainingTokens() : 0L);
                local.setLastRefillTime(now);
                local.setLocalConsumed(0L);
                return local;
            })
            .flatMap(localCacheTokenBucketRepository::save);
    }

    public void clean() {
        if (mergeSubscription != null) {
            mergeSubscription.dispose();
        }
    }
}
