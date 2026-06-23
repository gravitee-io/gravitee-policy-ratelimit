/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.services.ratelimit;

import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitRepository;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitService;
import io.gravitee.repository.ratelimit.model.TokenBucket;
import io.reactivex.rxjava3.core.Single;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.Setter;

/**
 * Bridge that selects the strict (per-request, exact) or async (local-then-reconcile, non-strict)
 * token-bucket repository based on the {@code async} flag. The token-bucket analogue of
 * {@code DefaultRateLimitService}.
 */
@Getter
@Setter
public class DefaultTokenBucketRateLimitService implements TokenBucketRateLimitService {

    private TokenBucketRateLimitRepository<TokenBucket> tokenBucketRateLimitRepository;
    private TokenBucketRateLimitRepository<TokenBucket> asyncTokenBucketRateLimitRepository;

    private TokenBucketRateLimitRepository<TokenBucket> repository(boolean async) {
        return async ? asyncTokenBucketRateLimitRepository : tokenBucketRateLimitRepository;
    }

    @Override
    public Single<TokenBucketConsumeResult> refillAndTryConsume(
        String key,
        long tokensRequested,
        long refillRate,
        long refillPeriodMillis,
        long capacity,
        long nowMillis,
        boolean async,
        Supplier<TokenBucket> supplier
    ) {
        try {
            return repository(async).refillAndTryConsume(
                key,
                tokensRequested,
                refillRate,
                refillPeriodMillis,
                capacity,
                nowMillis,
                supplier
            );
        } catch (Exception ex) {
            return Single.error(ex);
        }
    }
}
