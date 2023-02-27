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
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.Single;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class DefaultRateLimitService implements RateLimitService {

    private RateLimitRepository<RateLimit> rateLimitRepository;
    private RateLimitRepository<RateLimit> asyncRateLimitRepository;
    private long timeout;

    public DefaultRateLimitService(
        RateLimitRepository<RateLimit> rateLimitRepository,
        RateLimitRepository<RateLimit> asyncRateLimitRepository,
        long timeout
    ) {
        this.rateLimitRepository = rateLimitRepository;
        this.asyncRateLimitRepository = asyncRateLimitRepository;
        this.timeout = timeout;
    }

    private RateLimitRepository<RateLimit> getRateLimitRepository(boolean async) {
        return (async) ? asyncRateLimitRepository : rateLimitRepository;
    }

    public RateLimitRepository<RateLimit> getAsyncRateLimitRepository() {
        return asyncRateLimitRepository;
    }

    public RateLimitRepository getRateLimitRepository() {
        return rateLimitRepository;
    }

    @Override
    public Single<RateLimit> incrementAndGet(String key, long weight, boolean async, Supplier<RateLimit> supplier) {
        try {
            return getRateLimitRepository(async)
                .incrementAndGet(key, weight, supplier)
                .timeout(timeout, TimeUnit.MILLISECONDS)
                .map(rateLimit -> rateLimit);
        } catch (Exception ex) {
            return Single.error(ex);
        }
    }
}
