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

import static org.assertj.core.api.Assertions.assertThat;

import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.model.TokenBucket;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class LocalTokenBucketRateLimitRepositoryTest {

    private final LocalTokenBucketRateLimitRepository cut = new LocalTokenBucketRateLimitRepository();
    private final String key = "key1";

    @Test
    void fresh_bucket_starts_full_and_consuming_one_token_records_a_local_delta() {
        long now = 1_000L;

        TokenBucketConsumeResult result = cut
            .refillAndTryConsume(key, 1, 1, 1_000L, 5, now, () -> new LocalTokenBucket(new TokenBucket(key)))
            .blockingGet();

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(4); // full bucket of 5, minus the one consumed

        LocalTokenBucket stored = cut.get(key).blockingGet();
        assertThat(stored.getLocalConsumed()).isEqualTo(1); // one token consumed locally, pending sync to the store
    }

    @Test
    void rejected_request_does_not_count_toward_the_local_delta() {
        long now = 1_000L; // same instant for every call: no refill, so the full bucket of 5 is the only budget
        for (int i = 0; i < 5; i++) {
            cut.refillAndTryConsume(key, 1, 1, 1_000L, 5, now, () -> new LocalTokenBucket(new TokenBucket(key))).blockingGet();
        }

        TokenBucketConsumeResult rejected = cut
            .refillAndTryConsume(key, 1, 1, 1_000L, 5, now, () -> new LocalTokenBucket(new TokenBucket(key)))
            .blockingGet();

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remainingTokens()).isEqualTo(0);

        LocalTokenBucket stored = cut.get(key).blockingGet();
        assertThat(stored.getLocalConsumed()).isEqualTo(5); // the 6th request was rejected and must not be owed to the store
    }
}
