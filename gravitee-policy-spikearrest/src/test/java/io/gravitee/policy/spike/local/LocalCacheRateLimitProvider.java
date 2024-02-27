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
package io.gravitee.policy.spike.local;

import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Single;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class LocalCacheRateLimitProvider implements RateLimitService {

    private ConcurrentMap<Serializable, RateLimit> rateLimits = new ConcurrentHashMap<>();

    public void clean() {
        rateLimits.clear();
    }

    @Override
    public Single<RateLimit> incrementAndGet(String key, long weight, boolean async, Supplier<RateLimit> supplier) {
        RateLimit rateLimit = rateLimits.computeIfAbsent(key, serializable -> supplier.get());
        rateLimit.setCounter(rateLimit.getCounter() + weight);
        return Single.just(rateLimit);
    }
}
