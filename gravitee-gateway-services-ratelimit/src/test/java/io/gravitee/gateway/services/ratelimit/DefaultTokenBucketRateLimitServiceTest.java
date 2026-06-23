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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitRepository;
import io.gravitee.repository.ratelimit.model.TokenBucket;
import io.reactivex.rxjava3.core.Single;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(MockitoExtension.class)
class DefaultTokenBucketRateLimitServiceTest {

    @Mock
    private TokenBucketRateLimitRepository<TokenBucket> strictRepository;

    @Mock
    private TokenBucketRateLimitRepository<TokenBucket> asyncRepository;

    private DefaultTokenBucketRateLimitService cut;

    private final String key = "key1";

    @BeforeEach
    void setUp() {
        cut = new DefaultTokenBucketRateLimitService();
        cut.setTokenBucketRateLimitRepository(strictRepository);
        cut.setAsyncTokenBucketRateLimitRepository(asyncRepository);
    }

    @Test
    void routes_to_the_strict_repository_when_async_is_false() {
        when(strictRepository.refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 4, 0))
        );

        TokenBucketConsumeResult result = cut
            .refillAndTryConsume(key, 1, 1, 1_000L, 5, 1_000L, false, () -> new TokenBucket(key))
            .blockingGet();

        assertThat(result.remainingTokens()).isEqualTo(4);
        verify(strictRepository).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(asyncRepository, never()).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void routes_to_the_async_repository_when_async_is_true() {
        when(asyncRepository.refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 2, 0))
        );

        TokenBucketConsumeResult result = cut
            .refillAndTryConsume(key, 1, 1, 1_000L, 5, 1_000L, true, () -> new TokenBucket(key))
            .blockingGet();

        assertThat(result.remainingTokens()).isEqualTo(2);
        verify(asyncRepository).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
        verify(strictRepository, never()).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void wraps_a_synchronous_repository_failure_into_a_single_error() {
        // A repository that throws synchronously (rather than returning Single.error) must not blow up the
        // caller: the bridge wraps the throwable into Single.error so the reactive chain stays intact.
        RuntimeException boom = new RuntimeException("boom");
        when(strictRepository.refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenThrow(
            boom
        );

        cut
            .refillAndTryConsume(key, 1, 1, 1_000L, 5, 1_000L, false, () -> new TokenBucket(key))
            .test()
            .assertError(boom);
    }
}
