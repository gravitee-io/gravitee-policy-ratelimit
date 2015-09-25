package io.gravitee.policy.ratelimit.provider;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public interface RateLimitProvider {

    RateLimitResult acquire(Serializable key, long limit, long periodTime, TimeUnit periodTimeUnit);
}
