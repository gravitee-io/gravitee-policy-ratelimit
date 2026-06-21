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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.repository.ratelimit.api.TokenBucketConsumeResult;
import io.gravitee.repository.ratelimit.api.TokenBucketRateLimitRepository;
import io.gravitee.repository.ratelimit.model.TokenBucket;
import io.reactivex.rxjava3.core.Single;
import io.vertx.rxjava3.core.Vertx;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith(MockitoExtension.class)
class AsyncTokenBucketRateLimitRepositoryTest {

    private LocalTokenBucketRateLimitRepository localCache;

    @Mock
    private TokenBucketRateLimitRepository<TokenBucket> remoteCache;

    private final Vertx vertx = Vertx.vertx();

    private AsyncTokenBucketRateLimitRepository cut;

    private final String key = "key1";

    @BeforeEach
    void setUp() {
        localCache = new LocalTokenBucketRateLimitRepository();
        cut = new AsyncTokenBucketRateLimitRepository(vertx);
        cut.setLocalCacheTokenBucketRepository(localCache);
        cut.setRemoteCacheTokenBucketRepository(remoteCache);
    }

    @AfterEach
    void tearDown() {
        cut.clean();
    }

    @Test
    void consumes_locally_without_calling_the_remote_store() {
        TokenBucketConsumeResult result = cut.refillAndTryConsume(key, 1, 1, 1_000L, 5, 1_000L, () -> new TokenBucket(key)).blockingGet();

        assertThat(result.allowed()).isTrue();
        assertThat(result.remainingTokens()).isEqualTo(4); // served from the local bucket
        verify(remoteCache, never()).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reconciles_the_local_delta_to_the_remote_store_and_adopts_its_remaining() throws InterruptedException {
        // After consuming the pushed delta, the store authoritatively reports 7 tokens remaining.
        when(remoteCache.refillAndTryConsume(eq(key), eq(2L), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 7, 0))
        );

        cut.initialize();

        // Two local consumes (capacity 10, same instant so no refill): local delta = 2, store untouched.
        cut.refillAndTryConsume(key, 1, 1, 1_000L, 10, 1_000L, () -> new TokenBucket(key)).blockingGet();
        cut.refillAndTryConsume(key, 1, 1, 1_000L, 10, 1_000L, () -> new TokenBucket(key)).blockingGet();
        verify(remoteCache, never()).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());

        Thread.sleep(6_000); // wait for the ~5s reconcile scheduler to fire

        // The batched delta of 2 is pushed to the store exactly once.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<TokenBucket>> seedCaptor = ArgumentCaptor.forClass(Supplier.class);
        verify(remoteCache).refillAndTryConsume(eq(key), eq(2L), anyLong(), anyLong(), anyLong(), anyLong(), seedCaptor.capture());

        // The store seed must be a PLAIN TokenBucket, never the LocalTokenBucket subclass: a distributed
        // store serialises it across the cluster where the gateway-services class is not on the classpath.
        assertThat(seedCaptor.getValue().get().getClass()).isEqualTo(TokenBucket.class);

        LocalTokenBucket local = localCache.get(key).blockingGet();
        assertThat(local.getTokens()).isEqualTo(7); // local bucket adopts the store's authoritative remaining
        assertThat(local.getLocalConsumed()).isEqualTo(0); // delta cleared after a successful sync
    }

    @Test
    void throttles_the_node_to_zero_when_the_store_rejects_the_reconciled_batch() throws InterruptedException {
        // Store is depleted: it cannot satisfy the batched delta, so it debits nothing and returns
        // allowed=false with a small positive remaining. The node must NOT re-arm to that remaining
        // (which would keep over-serving), it must throttle to its local refill rate.
        when(remoteCache.refillAndTryConsume(eq(key), eq(2L), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(false, 3, 0))
        );

        cut.initialize();

        cut.refillAndTryConsume(key, 1, 1, 1_000L, 10, 1_000L, () -> new TokenBucket(key)).blockingGet();
        cut.refillAndTryConsume(key, 1, 1, 1_000L, 10, 1_000L, () -> new TokenBucket(key)).blockingGet();

        Thread.sleep(6_000); // wait for the ~5s reconcile scheduler to fire

        verify(remoteCache).refillAndTryConsume(eq(key), eq(2L), anyLong(), anyLong(), anyLong(), anyLong(), any());

        LocalTokenBucket local = localCache.get(key).blockingGet();
        assertThat(local.getTokens()).isEqualTo(0); // throttled on rejection, NOT re-armed to the store's remaining (3)
        assertThat(local.getLocalConsumed()).isEqualTo(0); // un-absorbable delta dropped, not carried forward
    }

    @Test
    void clears_the_local_delta_without_error_when_the_store_is_a_no_op() throws InterruptedException {
        // A null Single is the documented no-op contract: rate limiting disabled. Reconcile must just clear
        // the locally-consumed delta and re-save the bucket, never accumulate it or emit an error.
        when(remoteCache.refillAndTryConsume(eq(key), eq(2L), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(null);

        cut.initialize();

        // Two local consumes (capacity 10, same instant so no refill): local delta = 2, tokens = 8.
        cut.refillAndTryConsume(key, 1, 1, 1_000L, 10, 1_000L, () -> new TokenBucket(key)).blockingGet();
        cut.refillAndTryConsume(key, 1, 1, 1_000L, 10, 1_000L, () -> new TokenBucket(key)).blockingGet();

        Thread.sleep(6_000); // wait for the ~5s reconcile scheduler to fire

        verify(remoteCache).refillAndTryConsume(eq(key), eq(2L), anyLong(), anyLong(), anyLong(), anyLong(), any());

        LocalTokenBucket local = localCache.get(key).blockingGet();
        assertThat(local.getLocalConsumed()).isEqualTo(0); // delta cleared, not left to accumulate
        assertThat(local.getTokens()).isEqualTo(8); // local bucket untouched: a no-op store does not re-anchor it
    }

    @Test
    void reconciles_at_the_configured_flush_interval() throws InterruptedException {
        when(remoteCache.refillAndTryConsume(eq(key), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(
            Single.just(new TokenBucketConsumeResult(true, 4, 0))
        );
        cut.setFlushIntervalMillis(500L); // much shorter than the default 5s
        cut.initialize();

        cut.refillAndTryConsume(key, 1, 1, 1_000L, 5, 1_000L, () -> new TokenBucket(key)).blockingGet();

        Thread.sleep(1_500); // > configured 500ms, but well under the 5s default

        // With the hardcoded 5s delay the reconcile would not have fired yet; the configured 500ms must.
        verify(remoteCache, atLeastOnce()).refillAndTryConsume(eq(key), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void non_positive_flush_interval_falls_back_to_the_default() throws InterruptedException {
        cut.setFlushIntervalMillis(0L); // invalid: a 0ms delay would busy-loop the reconcile at 100% CPU
        cut.initialize();

        cut.refillAndTryConsume(key, 1, 1, 1_000L, 5, 1_000L, () -> new TokenBucket(key)).blockingGet();

        Thread.sleep(1_500); // well under the 5s default

        // Clamped to the 5s default: no reconcile yet. A 0ms interval would have fired immediately and repeatedly.
        verify(remoteCache, never()).refillAndTryConsume(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any());
    }
}
