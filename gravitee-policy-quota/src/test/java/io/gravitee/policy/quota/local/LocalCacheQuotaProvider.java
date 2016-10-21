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
package io.gravitee.policy.quota.local;

import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class LocalCacheQuotaProvider implements RateLimitService {

    private Map<Serializable, RateLimit> rateLimits = new HashMap<>();

    public void clean() {
        rateLimits.clear();
    }

    @Override
    public RateLimit get(String rateLimitKey, boolean sync) {
        return rateLimits.getOrDefault(rateLimitKey, new RateLimit(rateLimitKey));
    }

    @Override
    public void save(RateLimit rateLimit, boolean sync) {
        rateLimits.put(rateLimit.getKey(), rateLimit);
    }
}
