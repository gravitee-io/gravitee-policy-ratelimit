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
import io.gravitee.node.api.Node;
import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Iterator;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
class RateLimitPoller implements Runnable {

    private final Logger LOGGER = LoggerFactory.getLogger(RateLimitPoller.class);

    private RateLimitRepository delegateRateLimitRepository;
    private RateLimitRepository aggregateCacheRateLimitRepository;

    private long lastCheck;

    @Autowired
    private Node node;

    @Override
    public void run() {
        try {
            LOGGER.debug("Refresh rate-limits from repository");
            Iterator<RateLimit> rateLimitIterator = delegateRateLimitRepository.findAsyncAfter(lastCheck);

            rateLimitIterator.forEachRemaining(rateLimit -> {
                String [] parts = KeySplitter.split(rateLimit.getKey());

                // Handle the rate-limit only if it's not a "local" rate-limit
                // (ie. not stored by this gateway instance)
                if (!node.id().equals(parts[0])) {
                    String rateLimitKey = parts[1];

                    long hits = rateLimit.getCounter();

                    RateLimit oldRateLimit = aggregateCacheRateLimitRepository.get(rateLimit.getKey());
                    if (oldRateLimit != null) {
                        // Already known, what is the diff ?
                        // Is it a new value for the same rate-limit key ?
                        if (rateLimit.getCreatedAt() > oldRateLimit.getCreatedAt()) {
                            hits = rateLimit.getCounter();
                        } else {
                            hits = rateLimit.getCounter() - oldRateLimit.getCounter();
                        }
                    }

                    updateAggregateRateLimit(rateLimitKey, rateLimit, hits);
                }
            });

            lastCheck = System.currentTimeMillis();
        } catch (Exception ex) {
            LOGGER.error("Unable to retrieve latest values of rate-limit from repository", ex);
        }
    }

    private void updateAggregateRateLimit(String rateLimitKey, RateLimit rateLimit, long hits) {
        RateLimit aggRateLimit = aggregateCacheRateLimitRepository.get(rateLimitKey);

        if (aggRateLimit == null) {
            aggRateLimit = new RateLimit(rateLimitKey, rateLimit);
            aggRateLimit.setCounter(hits);
        } else {
            // Do we really have to update it at any time ?
            aggRateLimit.setCounter(aggRateLimit.getCounter() + hits);
            aggRateLimit.setLastRequest(rateLimit.getLastRequest());
            aggRateLimit.setResetTime(rateLimit.getResetTime());
            aggRateLimit.setCreatedAt(rateLimit.getCreatedAt());
            aggRateLimit.setUpdatedAt(rateLimit.getUpdatedAt());
        }

        aggregateCacheRateLimitRepository.save(aggRateLimit);
        aggregateCacheRateLimitRepository.save(rateLimit);
    }

    public void setRateLimitRepository(RateLimitRepository delegateRateLimitRepository) {
        this.delegateRateLimitRepository = delegateRateLimitRepository;
    }

    public void setAggregateCacheRateLimitRepository(RateLimitRepository aggregateCacheRateLimitRepository) {
        this.aggregateCacheRateLimitRepository = aggregateCacheRateLimitRepository;
    }
}
