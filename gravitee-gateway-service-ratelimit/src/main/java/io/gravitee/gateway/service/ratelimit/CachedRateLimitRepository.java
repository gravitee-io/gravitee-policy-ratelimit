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
package io.gravitee.gateway.service.ratelimit;

import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
class CachedRateLimitRepository implements RateLimitRepository {

    private final Logger LOGGER = LoggerFactory.getLogger(CachedRateLimitRepository.class);

    private final Cache cache;

    CachedRateLimitRepository(final Cache cache) {
        this.cache = cache;
    }

    @Override
    public RateLimit get(String rateLimitKey) {
        LOGGER.debug("Retrieve rate-limiting for {} from {}", rateLimitKey, cache.getName());

        Element elt = cache.get(rateLimitKey);
        return (elt != null) ? (RateLimit) elt.getObjectValue() : null;
    }

    @Override
    public void save(RateLimit rateLimit) {
        long ttlInMillis = rateLimit.getResetTime() - System.currentTimeMillis();
        if (ttlInMillis > 0L) {
            int ttl = (int) (ttlInMillis / 1000L);
            LOGGER.debug("Put rate-limiting {} with a TTL {} into {}", rateLimit, ttl, cache.getName());
            cache.put(new Element(rateLimit.getKey(), rateLimit, 0,ttl));
        }
    }

    @Override
    public Iterator<RateLimit> findAsyncAfter(long timestamp) {
        throw new IllegalStateException();
    }
}
