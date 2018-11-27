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

import io.gravitee.gateway.services.ratelimit.util.KeySplitter;
import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AsyncRateLimitRepository implements RateLimitRepository {

    private RateLimitRepository localCacheRateLimitRepository;
    private RateLimitRepository aggregateCacheRateLimitRepository;
    private BlockingQueue<RateLimit> rateLimitsQueue;

    @Override
    public RateLimit get(String rateLimitKey) {
        // Get data from local cache
        RateLimit cachedRateLimit = localCacheRateLimitRepository.get(rateLimitKey);
        cachedRateLimit = (cachedRateLimit != null) ? cachedRateLimit : new RateLimit(rateLimitKey);

        // Aggregate counter with data from aggregate cache
        // Split the key to remove the gateway_id and get only the needed part
        String [] parts = KeySplitter.split(rateLimitKey);
        RateLimit aggregateRateLimit = aggregateCacheRateLimitRepository.get(parts[1]);
        if (aggregateRateLimit != null) {
            cachedRateLimit.setCounter(cachedRateLimit.getCounter() + aggregateRateLimit.getCounter());
            AggregateRateLimit extendRateLimit = new AggregateRateLimit(cachedRateLimit);
            extendRateLimit.setAggregateCounter(aggregateRateLimit.getCounter());
            return extendRateLimit;
        }

        return cachedRateLimit;
    }

    @Override
    public void save(RateLimit rateLimit) {
        if (rateLimit instanceof AggregateRateLimit) {
            AggregateRateLimit aggregateRateLimit = (AggregateRateLimit) rateLimit;
            aggregateRateLimit.setCounter(aggregateRateLimit.getCounter() - aggregateRateLimit.getAggregateCounter());
            aggregateRateLimit.setAggregateCounter(0L);
        }

        // Push data in local cache
        localCacheRateLimitRepository.save(rateLimit);

        // Push data in queue to store rate-limit asynchronously
        rateLimitsQueue.offer(rateLimit);
    }

    @Override
    public Iterator<RateLimit> findAsyncAfter(long timestamp) {
        throw new IllegalStateException();
    }

    public void setLocalCacheRateLimitRepository(RateLimitRepository localCacheRateLimitRepository) {
        this.localCacheRateLimitRepository = localCacheRateLimitRepository;
    }

    public void setAggregateCacheRateLimitRepository(RateLimitRepository aggregateCacheRateLimitRepository) {
        this.aggregateCacheRateLimitRepository = aggregateCacheRateLimitRepository;
    }

    public void setRateLimitsQueue(BlockingQueue<RateLimit> rateLimitsQueue) {
        this.rateLimitsQueue = rateLimitsQueue;
    }
}
