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
package io.gravitee.policy.ratelimit.provider.local;

import io.gravitee.policy.ratelimit.provider.RateLimit;
import io.gravitee.policy.ratelimit.provider.RateLimitProvider;
import io.gravitee.policy.ratelimit.provider.RateLimitResult;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class LocalCacheRateLimitProvider implements RateLimitProvider {

    private Map<Serializable, RateLimit> rateLimits = new HashMap<>();

    @Override
    public RateLimitResult acquire(Serializable key, long limit, long periodTime, TimeUnit periodTimeUnit) {
        RateLimit rateLimit = rateLimits.getOrDefault(key, new RateLimit());
        RateLimitResult rateLimitResult = new RateLimitResult();

        // Do we need to reset counter in rate limit ?
        long lastCheck = rateLimit.getLastCheck();

        Duration duration = null;

        switch (periodTimeUnit) {
            case SECONDS:
                duration = Duration.ofSeconds(periodTime);
                break;
            case MINUTES:
                duration = Duration.ofMinutes(periodTime);
                break;
            case HOURS:
                duration = Duration.ofHours(periodTime);
                break;
            case DAYS:
                duration = Duration.ofDays(periodTime);
                break;
        }

        // We prefer currentTimeMillis in place of nanoTime() because nanoTime is relatively
        // expensive call and depends on the underlying architecture.
        if (System.currentTimeMillis() >= Instant.ofEpochMilli(lastCheck).plus(duration).toEpochMilli()) {
            rateLimit.setCounter(0);
        }

        if (rateLimit.getCounter() >= limit) {
            rateLimitResult.setExceeded(true);
        } else {
            // Update rate limiter
            rateLimitResult.setExceeded(false);
            rateLimit.setCounter(rateLimit.getCounter() + 1);
            rateLimit.setLastCheck(System.currentTimeMillis());
        }

        // Set the time at which the current rate limit window resets in UTC epoch seconds.
        rateLimitResult.setResetTime(getResetTimeInEpochSeconds(periodTime, periodTimeUnit));
        rateLimitResult.setRemains(limit - rateLimit.getCounter());
        rateLimits.put(key, rateLimit);

        return rateLimitResult;
    }

    public void clean() {
        rateLimits.clear();
    }

    private long getResetTimeInEpochSeconds(long periodTime, TimeUnit periodTimeUnit) {
        long now = System.currentTimeMillis();

        Duration duration = null;

        switch (periodTimeUnit) {
            case SECONDS:
                duration = Duration.ofSeconds(periodTime);
                break;
            case MINUTES:
                duration = Duration.ofMinutes(periodTime);
                break;
            case HOURS:
                duration = Duration.ofHours(periodTime);
                break;
            case DAYS:
                duration = Duration.ofDays(periodTime);
                break;
        }

        return ((Instant.ofEpochMilli(now).plus(duration).toEpochMilli()) / 1000L);
    }
}
