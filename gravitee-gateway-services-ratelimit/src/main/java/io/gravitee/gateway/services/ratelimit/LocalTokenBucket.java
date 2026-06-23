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

import io.gravitee.repository.ratelimit.model.TokenBucket;

/**
 * A node-local token bucket. It is an ordinary {@link TokenBucket} (so the shared
 * {@code TokenBucketCalculator} can refill and consume it like any other) plus {@code localConsumed}:
 * the number of tokens consumed on this node since the last reconcile, waiting to be pushed to the
 * store. Mirrors {@code LocalRateLimit}, where {@code local} is the pending delta.
 *
 * <p>Because it {@code is-a} {@link TokenBucket}, an instance must never be handed to the backing store
 * (a distributed store serialises it, and this gateway-services class is absent from the store plugin's
 * classloader). The single place that could leak it — the reconcile seed — copies into a plain
 * {@link TokenBucket} on purpose; see {@code AsyncTokenBucketRateLimitRepository#reconcile}.
 */
class LocalTokenBucket extends TokenBucket {

    private long localConsumed;

    // The bucket parameters from the most recent request, replayed to the store at reconcile time
    // (refill/consume needs them, but they are not part of the persisted TokenBucket model).
    private long refillRate;
    private long refillPeriodMillis;
    private long capacity;

    LocalTokenBucket(TokenBucket seed) {
        super(seed);
    }

    long getLocalConsumed() {
        return localConsumed;
    }

    void setLocalConsumed(long localConsumed) {
        this.localConsumed = localConsumed;
    }

    void addLocalConsumed(long delta) {
        this.localConsumed += delta;
    }

    long getRefillRate() {
        return refillRate;
    }

    long getRefillPeriodMillis() {
        return refillPeriodMillis;
    }

    long getCapacity() {
        return capacity;
    }

    void rememberConfig(long refillRate, long refillPeriodMillis, long capacity) {
        this.refillRate = refillRate;
        this.refillPeriodMillis = refillPeriodMillis;
        this.capacity = capacity;
    }
}
