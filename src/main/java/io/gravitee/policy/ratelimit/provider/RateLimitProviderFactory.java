package io.gravitee.policy.ratelimit.provider;

import io.gravitee.policy.ratelimit.provider.local.LocalCacheRateLimitProvider;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class RateLimitProviderFactory {

    private static RateLimitProvider rateLimitProvider = new LocalCacheRateLimitProvider();

    public static RateLimitProvider getRateLimitProvider() {
        return rateLimitProvider;
    }
}
