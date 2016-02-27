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
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 * @author GraviteeSource Team
 */
public class DefaultRateLimitService implements RateLimitService {

    private RateLimitRepository rateLimitRepository;
    private RateLimitRepository asyncRateLimitRepository;

    @Override
    public RateLimit get(String rateLimitKey, boolean async) {
        return getRateLimitRepository(async).get(rateLimitKey);
    }

    @Override
    public void save(RateLimit rateLimit, boolean async) {
        getRateLimitRepository(async).save(rateLimit);
    }

    private RateLimitRepository getRateLimitRepository(boolean async) {
        return (async) ? asyncRateLimitRepository : rateLimitRepository;
    }

    public RateLimitRepository getAsyncRateLimitRepository() {
        return asyncRateLimitRepository;
    }

    public void setAsyncRateLimitRepository(RateLimitRepository asyncRateLimitRepository) {
        this.asyncRateLimitRepository = asyncRateLimitRepository;
    }

    public RateLimitRepository getRateLimitRepository() {
        return rateLimitRepository;
    }

    public void setRateLimitRepository(RateLimitRepository rateLimitRepository) {
        this.rateLimitRepository = rateLimitRepository;
    }
}
