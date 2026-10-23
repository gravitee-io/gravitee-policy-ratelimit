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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.vertx.rxjava3.core.Vertx;
import io.vertx.rxjava3.core.shareddata.Lock;
import io.vertx.rxjava3.core.shareddata.SharedData;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@ExtendWith({ MockitoExtension.class })
@Slf4j
public class AsyncRateLimitRepositoryTest {

    private LocalRateLimitRepository localCacheRateLimitRepository;

    @Mock
    private RateLimitRepository<RateLimit> remoteCacheRateLimitRepository;

    private final Vertx vertx = Vertx.vertx();

    private AsyncRateLimitRepository cut;

    private final String key = "key1";

    @BeforeEach
    public void setUp() {
        localCacheRateLimitRepository = new LocalRateLimitRepository();

        cut = new AsyncRateLimitRepository(vertx);
        cut.setRemoteCacheRateLimitRepository(remoteCacheRateLimitRepository);
        cut.setLocalCacheRateLimitRepository(localCacheRateLimitRepository);

        // Start the merge observable
        cut.initialize();
    }

    @AfterEach
    public void tearDown() {
        cut.clean();
    }

    @Test
    public void reconciles_at_the_configured_flush_interval() throws InterruptedException {
        RateLimit remote = new RateLimit(key);
        remote.setCounter(11);
        when(remoteCacheRateLimitRepository.incrementAndGet(anyString(), anyLong(), any())).thenReturn(
            Single.just(new LocalRateLimit(remote))
        );

        AsyncRateLimitRepository fast = new AsyncRateLimitRepository(vertx);
        fast.setRemoteCacheRateLimitRepository(remoteCacheRateLimitRepository);
        fast.setLocalCacheRateLimitRepository(new LocalRateLimitRepository());
        fast.setFlushIntervalMillis(500L); // much shorter than the default 5s
        fast.initialize();
        try {
            RateLimit seed = new RateLimit(key);
            seed.setCounter(1);
            seed.setResetTime(Instant.now().plus(20, ChronoUnit.SECONDS).toEpochMilli());
            fast.incrementAndGet(key, 1L, () -> seed).blockingGet();

            Thread.sleep(1_500); // > configured 500ms, well under the 5s default

            // With the hardcoded 5s delay the reconcile would not have fired yet; the configured 500ms must.
            verify(remoteCacheRateLimitRepository, atLeastOnce()).incrementAndGet(anyString(), anyLong(), any());
        } finally {
            fast.clean();
        }
    }

    @Test
    public void non_positive_flush_interval_falls_back_to_the_default() throws InterruptedException {
        AsyncRateLimitRepository zero = new AsyncRateLimitRepository(vertx);
        zero.setRemoteCacheRateLimitRepository(remoteCacheRateLimitRepository);
        zero.setLocalCacheRateLimitRepository(new LocalRateLimitRepository());
        zero.setFlushIntervalMillis(0L); // invalid: a 0ms delay would busy-loop the reconcile at 100% CPU
        zero.initialize();
        try {
            RateLimit seed = new RateLimit(key);
            seed.setCounter(1);
            seed.setResetTime(Instant.now().plus(20, ChronoUnit.SECONDS).toEpochMilli());
            zero.incrementAndGet(key, 1L, () -> seed).blockingGet();

            Thread.sleep(1_500); // well under the 5s default

            // Clamped to the 5s default: no reconcile yet. A 0ms interval would have fired immediately and repeatedly.
            verify(remoteCacheRateLimitRepository, never()).incrementAndGet(anyString(), anyLong(), any());
        } finally {
            zero.clean();
        }
    }

    @Test
    public void reconciles_a_large_backlog_without_exceeding_the_flush_concurrency() throws InterruptedException {
        int pendingKeys = 200;
        int flushConcurrency = 4;

        int peakInFlight = reconcileBacklog(pendingKeys, flushConcurrency);

        assertThat(peakInFlight).isEqualTo(flushConcurrency);
    }

    @Test
    public void non_positive_flush_concurrency_falls_back_to_the_default() throws InterruptedException {
        int peakInFlight = reconcileBacklog(50, 0);

        assertThat(peakInFlight).isEqualTo(AsyncRateLimitRepository.DEFAULT_FLUSH_CONCURRENCY);
    }

    @Test
    public void flush_concurrency_above_the_ceiling_is_clamped() throws InterruptedException {
        int peakInFlight = reconcileBacklog(200, 1000);

        assertThat(peakInFlight).isEqualTo(AsyncRateLimitRepository.MAX_FLUSH_CONCURRENCY);
    }

    @Test
    public void a_failed_lock_does_not_stop_later_reconcile_cycles() throws InterruptedException {
        SharedData sharedData = mock(SharedData.class);
        Vertx mockedVertx = mock(Vertx.class);
        when(mockedVertx.sharedData()).thenReturn(sharedData);
        Lock lock = mock(Lock.class);
        when(sharedData.getLocalLockWithTimeout(anyString(), anyLong())).thenReturn(Single.just(lock));
        AtomicInteger flushLocks = new AtomicInteger();
        when(sharedData.getLocalLock(anyString())).thenAnswer(invocation -> {
            if (flushLocks.getAndIncrement() == 0) {
                return Single.error(new RuntimeException("lock failed"));
            }
            return Single.just(lock);
        });
        when(remoteCacheRateLimitRepository.incrementAndGet(anyString(), anyLong(), any())).thenReturn(
            Single.just(new RateLimit("survives"))
        );

        AsyncRateLimitRepository repository = new AsyncRateLimitRepository(mockedVertx);
        repository.setRemoteCacheRateLimitRepository(remoteCacheRateLimitRepository);
        repository.setLocalCacheRateLimitRepository(new LocalRateLimitRepository());
        repository.setFlushIntervalMillis(200L);
        repository.initialize();
        try {
            RateLimit seed = new RateLimit("survives");
            seed.setCounter(1);
            seed.setResetTime(Instant.now().plus(1, ChronoUnit.MINUTES).toEpochMilli());
            repository.incrementAndGet("survives", 1L, () -> seed).blockingGet();

            Thread.sleep(1_000);

            verify(remoteCacheRateLimitRepository, atLeastOnce()).incrementAndGet(anyString(), anyLong(), any());
        } finally {
            repository.clean();
        }
    }

    private int reconcileBacklog(int pendingKeys, int flushConcurrency) throws InterruptedException {
        int cap = flushConcurrency <= 0
            ? AsyncRateLimitRepository.DEFAULT_FLUSH_CONCURRENCY
            : Math.min(flushConcurrency, AsyncRateLimitRepository.MAX_FLUSH_CONCURRENCY);
        int expectedInFlight = Math.min(pendingKeys, cap);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        CountDownLatch reconciled = new CountDownLatch(pendingKeys);
        // Hold the first wave until the cap is in flight. A short timer finishes before a large cap is subscribed, so the peak is short of the cap on a slow runner.
        CompletableSubject firstWave = CompletableSubject.create();
        when(remoteCacheRateLimitRepository.incrementAndGet(anyString(), anyLong(), any())).thenAnswer(invocation -> {
            String reconciledKey = invocation.getArgument(0);
            return Single.defer(() -> {
                int now = inFlight.incrementAndGet();
                peakInFlight.accumulateAndGet(now, Math::max);
                if (now >= expectedInFlight) {
                    firstWave.onComplete();
                }
                return firstWave.andThen(Single.just(new RateLimit(reconciledKey)));
            }).doFinally(() -> {
                inFlight.decrementAndGet();
                reconciled.countDown();
            });
        });

        AsyncRateLimitRepository bounded = new AsyncRateLimitRepository(vertx);
        bounded.setRemoteCacheRateLimitRepository(remoteCacheRateLimitRepository);
        bounded.setLocalCacheRateLimitRepository(new LocalRateLimitRepository());
        bounded.setFlushIntervalMillis(200L);
        bounded.setFlushConcurrency(flushConcurrency);
        bounded.initialize();
        try {
            for (int i = 0; i < pendingKeys; i++) {
                String pendingKey = "backlog-" + i;
                RateLimit seed = new RateLimit(pendingKey);
                seed.setResetTime(Instant.now().plus(1, ChronoUnit.MINUTES).toEpochMilli());
                bounded.incrementAndGet(pendingKey, 1L, () -> seed).blockingGet();
            }

            assertThat(reconciled.await(10, TimeUnit.SECONDS)).isTrue();
            return peakInFlight.get();
        } finally {
            bounded.clean();
        }
    }

    @Test
    public void should_merge_local_rate_limit_with_remote_long() {
        // Mock remote to return 11 when called
        RateLimit remoteRateLimit = new RateLimit(key);
        remoteRateLimit.setCounter(11);
        when(remoteCacheRateLimitRepository.incrementAndGet(anyString(), anyLong(), any())).thenReturn(
            Single.just(new LocalRateLimit(remoteRateLimit))
        );

        // Call increment and get method
        RateLimit localCounter = new RateLimit(key);
        localCounter.setCounter(1);
        localCounter.setResetTime(Instant.now().plus(10, ChronoUnit.SECONDS).toEpochMilli());
        TestObserver<RateLimit> incrementObs = cut.incrementAndGet(key, 1L, () -> localCounter).test();

        incrementObs
            .awaitDone(5, TimeUnit.SECONDS)
            .assertValue(rateLimit -> {
                assertEquals(key, rateLimit.getKey());
                assertEquals(2, rateLimit.getCounter());
                return true;
            })
            .assertComplete();

        // Wait 6 seconds
        try {
            Thread.sleep(6000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        incrementObs = cut.incrementAndGet(key, 1L, () -> localCounter).test();

        incrementObs
            .awaitDone(5, TimeUnit.SECONDS)
            .assertValue(rateLimit -> {
                assertEquals(key, rateLimit.getKey());
                assertEquals(12, rateLimit.getCounter());
                return true;
            })
            .assertComplete();

        TestObserver<LocalRateLimit> localObs = localCacheRateLimitRepository.get(key).test();
        localObs
            .awaitDone(5, TimeUnit.SECONDS)
            .assertValue(localRateLimit -> {
                assertEquals(1, localRateLimit.getLocal());
                assertEquals(12, localRateLimit.getCounter());
                return true;
            })
            .assertComplete();
    }

    @Test
    public void should_release_lock_even_when_exception_occurred_long() {
        // Mock remote to return 11 when called
        RateLimit remoteRateLimit = new RateLimit(key);
        remoteRateLimit.setCounter(11);
        when(remoteCacheRateLimitRepository.incrementAndGet(anyString(), anyLong(), any()))
            .thenReturn(Single.timer(6, TimeUnit.SECONDS).flatMap(v -> Single.error(new RuntimeException(key))))
            .thenReturn(Single.just(new LocalRateLimit(remoteRateLimit)));

        // Call increment and get method
        RateLimit localCounter = new RateLimit(key);
        localCounter.setCounter(1);
        localCounter.setResetTime(Instant.now().plus(20, ChronoUnit.SECONDS).toEpochMilli());
        TestObserver<RateLimit> incrementObs = cut.incrementAndGet(key, 1L, () -> localCounter).test();

        incrementObs
            .awaitDone(5, TimeUnit.SECONDS)
            .assertValue(rateLimit -> {
                assertEquals(key, rateLimit.getKey());
                assertEquals(2, rateLimit.getCounter());
                return true;
            })
            .assertComplete();

        // Wait 12 seconds
        try {
            Thread.sleep(12000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Check that lock is released
        incrementObs = cut.incrementAndGet(key, 1L, () -> localCounter).test();
        incrementObs
            .awaitDone(5, TimeUnit.SECONDS)
            .assertValue(rateLimit -> {
                assertEquals(key, rateLimit.getKey());
                assertEquals(12, rateLimit.getCounter());
                return true;
            })
            .assertComplete();

        TestObserver<LocalRateLimit> localObs = localCacheRateLimitRepository.get(key).test();
        localObs
            .awaitDone(5, TimeUnit.SECONDS)
            .assertValue(localRateLimit -> {
                assertEquals(1, localRateLimit.getLocal());
                assertEquals(12, localRateLimit.getCounter());
                return true;
            })
            .assertComplete();
    }
}
