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
        if (System.currentTimeMillis() >= Date.from(Instant.ofEpochMilli(lastCheck).plus(duration)).getTime()) {
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

        rateLimitResult.setRemains(limit - rateLimit.getCounter());
        rateLimits.put(key, rateLimit);

        return rateLimitResult;
    }

    public void clean() {
        rateLimits.clear();
    }
}
