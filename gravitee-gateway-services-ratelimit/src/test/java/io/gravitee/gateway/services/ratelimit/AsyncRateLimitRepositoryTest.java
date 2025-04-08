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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.vertx.rxjava3.core.Vertx;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
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
    public void should_merge_local_rate_limit_with_remote_long() {
        // Mock remote to return 11 when called
        RateLimit remoteRateLimit = new RateLimit(key);
        remoteRateLimit.setCounter(11);
        when(remoteCacheRateLimitRepository.incrementAndGet(anyString(), anyLong(), any()))
            .thenReturn(Single.just(new LocalRateLimit(remoteRateLimit)));

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
