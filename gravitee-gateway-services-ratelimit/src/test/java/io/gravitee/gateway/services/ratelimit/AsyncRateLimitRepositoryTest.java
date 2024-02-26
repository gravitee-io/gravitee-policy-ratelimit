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
package io.gravitee.gateway.services.ratelimit;

import static org.mockito.Mockito.spy;

import io.gravitee.gateway.services.ratelimit.rx.TrampolineSchedulerProvider;
import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class AsyncRateLimitRepositoryTest {

    private static final String RATE_LIMIT_KEY = "test-key";

    private AsyncRateLimitRepository rateLimitRepository;

    @Mock
    private RateLimitRepository<RateLimit> remoteRateLimitRepository;

    private LocalRateLimitRepository localRateLimitRepository;

    private TestScheduler testScheduler;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        localRateLimitRepository = spy(new LocalRateLimitRepository());
        testScheduler = new TestScheduler();
        rateLimitRepository = spy(new AsyncRateLimitRepository(new TrampolineSchedulerProvider()));
        rateLimitRepository.setLocalCacheRateLimitRepository(localRateLimitRepository);
        rateLimitRepository.setRemoteCacheRateLimitRepository(remoteRateLimitRepository);
    }
    /*
    @Test
    public void shouldMakeSingleCall_emptyRemoteRate() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS, testScheduler)
                .repeat()
                .subscribe(tick -> rateLimitRepository.merge());

        when(remoteRateLimitRepository.get(RATE_LIMIT_KEY)).thenReturn(Maybe.empty());

        TestObserver<LocalRateLimit> rateObs = rateLimitRepository.incrementAndGet(RATE_LIMIT_KEY, 1, new Supplier<RateLimit>() {
            @Override
            public RateLimit get() {
                RateLimit rate = new RateLimit(RATE_LIMIT_KEY);
                rate.setLimit(1000L);
                rate.setResetTime(System.currentTimeMillis() + 50000L);
                return rate;
            }
        }).test();

        rateObs
                .assertTerminated()
                .assertNoErrors()
                .assertValue(rate -> rate.getCounter() == 1L);

        // Check that the stored counter is equals to the number of hits
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 1L)
                .assertValue(rate -> rate.getCounter() == 1L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        Mockito.verify(remoteRateLimitRepository).get(RATE_LIMIT_KEY);
        Mockito.verify(localRateLimitRepository).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        // Refresh has been never called
        Mockito.verify(rateLimitRepository, never()).merge();

        subscribe.dispose();
    }

    @Test
    public void shouldMakeSingleCall_existingRemoteRate() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS, testScheduler)
                .repeat()
                .subscribe(tick -> rateLimitRepository.merge());

        RateLimit remoteRateLimit = new RateLimit(RATE_LIMIT_KEY);
        remoteRateLimit.setCounter(500L);
        remoteRateLimit.setLimit(1000L);
        remoteRateLimit.setResetTime(System.currentTimeMillis() + 50000L);

        when(remoteRateLimitRepository.get(RATE_LIMIT_KEY)).thenReturn(Maybe.just(remoteRateLimit));

        // Supplier is not called because a remote value is existing
        TestObserver<LocalRateLimit> rateObs = rateLimitRepository.incrementAndGet(RATE_LIMIT_KEY, 1, null).test();

        rateObs
                .assertTerminated()
                .assertNoErrors()
                .assertValue(rate -> rate.getCounter() == 501L);

        // Check that the stored counter is equals to the number of hits
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 1L)
                .assertValue(rate -> rate.getCounter() == 501L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        Mockito.verify(remoteRateLimitRepository).get(RATE_LIMIT_KEY);
        Mockito.verify(localRateLimitRepository).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        // Refresh has been never called
        Mockito.verify(rateLimitRepository, never()).merge();

        subscribe.dispose();
    }

    @Test
    public void shouldMakeSingleCall_existingExpiredRemoteRate() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS, testScheduler)
                .repeat()
                .subscribe(tick -> rateLimitRepository.merge());

        RateLimit remoteRateLimit = new RateLimit(RATE_LIMIT_KEY);
        remoteRateLimit.setCounter(500L);
        remoteRateLimit.setLimit(1000L);
        remoteRateLimit.setResetTime(System.currentTimeMillis() - 1000L);

        when(remoteRateLimitRepository.get(RATE_LIMIT_KEY)).thenReturn(Maybe.just(remoteRateLimit));

        TestObserver<LocalRateLimit> rateObs = rateLimitRepository.incrementAndGet(RATE_LIMIT_KEY, 1, new Supplier<RateLimit>() {
            @Override
            public RateLimit get() {
                RateLimit rate = new RateLimit(RATE_LIMIT_KEY);
                rate.setLimit(1000L);
                rate.setResetTime(System.currentTimeMillis() + 50000L);
                return rate;
            }
        }).test();

        rateObs
                .assertTerminated()
                .assertNoErrors()
                .assertValue(rate -> rate.getCounter() == 1L);

        // Check that the stored counter is equals to the number of hits
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 1L)
                .assertValue(rate -> rate.getCounter() == 1L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        Mockito.verify(remoteRateLimitRepository).get(RATE_LIMIT_KEY);
        Mockito.verify(localRateLimitRepository).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        // Refresh has been never called
        Mockito.verify(rateLimitRepository, never()).merge();

        subscribe.dispose();
    }

    @Test
    public void shouldMakeMultipleCalls_emptyRemoteRate() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS, testScheduler)
                .repeat()
                .subscribe(tick -> rateLimitRepository.merge());

        when(remoteRateLimitRepository.get(RATE_LIMIT_KEY)).thenReturn(Maybe.empty());

        for (int i = 0 ; i < 1000 ; i++) {
            TestObserver<LocalRateLimit> rateObs = rateLimitRepository.incrementAndGet(RATE_LIMIT_KEY, 1, new Supplier<RateLimit>() {
                @Override
                public RateLimit get() {
                    RateLimit rate = new RateLimit(RATE_LIMIT_KEY);
                    rate.setLimit(1000L);
                    rate.setResetTime(System.currentTimeMillis() + 50000L);
                    return rate;
                }
            }).test();

            rateObs
                    .assertTerminated()
                    .assertNoErrors();
        }

        // Check that the stored counter is equals to the number of hits
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 1000L)
                .assertValue(rate -> rate.getCounter() == 1000L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        // Remote has been called only one time, since we have had a value from local cache
        Mockito.verify(remoteRateLimitRepository).get(RATE_LIMIT_KEY);
        Mockito.verify(localRateLimitRepository, times(1000)).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        // Refresh has been never called
        Mockito.verify(rateLimitRepository, never()).merge();

        subscribe.dispose();
    }

    @Test
    public void shouldMakeSingleCall_emptyRemoteRate_refreshRates() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS, testScheduler)
                .repeat()
                .subscribe(tick -> rateLimitRepository.merge());

        RateLimit remoteRateLimit = new RateLimit(RATE_LIMIT_KEY);

        // Counter is 500L remotely, and we add the local counter (1, single hit)
        remoteRateLimit.setCounter(501L);
        remoteRateLimit.setLimit(1000L);
        remoteRateLimit.setResetTime(System.currentTimeMillis() + 50000L);

        when(remoteRateLimitRepository.get(RATE_LIMIT_KEY)).thenReturn(Maybe.empty());
        when(remoteRateLimitRepository.incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any())).thenReturn(Single.just(remoteRateLimit));

        TestObserver<LocalRateLimit> rateObs = rateLimitRepository.incrementAndGet(RATE_LIMIT_KEY, 1, new Supplier<RateLimit>() {
            @Override
            public RateLimit get() {
                RateLimit rate = new RateLimit(RATE_LIMIT_KEY);
                rate.setLimit(1000L);
                rate.setResetTime(System.currentTimeMillis() + 50000L);
                return rate;
            }
        }).test();

        rateObs
                .assertTerminated()
                .assertNoErrors()
                .assertValue(rate -> rate.getCounter() == 1L);

        // Check that the stored counter is equals to the number of hits
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 1L)
                .assertValue(rate -> rate.getCounter() == 1L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        Mockito.verify(remoteRateLimitRepository).get(RATE_LIMIT_KEY);
        Mockito.verify(localRateLimitRepository).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        testScheduler.advanceTimeBy(5, TimeUnit.SECONDS);

        // After the merge, current counter must be equals to the remote counter + local counter
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 0L)
                .assertValue(rate -> rate.getCounter() == 501L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        // Refresh has been called once
        Mockito.verify(rateLimitRepository).merge();
        Mockito.verify(remoteRateLimitRepository).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        subscribe.dispose();
    }

    @Test
    public void shouldMakeMultipleCalls_emptyRemoteRate_refreshRates() {
        Disposable subscribe = Observable
                .timer(5000, TimeUnit.MILLISECONDS, testScheduler)
                .repeat()
                .subscribe(tick -> rateLimitRepository.merge());

        RateLimit remoteRateLimit = new RateLimit(RATE_LIMIT_KEY);

        // Counter is 500L remotely, and we add the local counter (1, single hit)
        remoteRateLimit.setCounter(1500L);
        remoteRateLimit.setLimit(1000L);
        remoteRateLimit.setResetTime(System.currentTimeMillis() + 50000L);

        when(remoteRateLimitRepository.get(RATE_LIMIT_KEY)).thenReturn(Maybe.empty());
        when(remoteRateLimitRepository.incrementAndGet(eq(RATE_LIMIT_KEY), eq(1000L), any())).thenReturn(Single.just(remoteRateLimit));

        for (int i = 0 ; i < 1000 ; i++) {
            TestObserver<LocalRateLimit> rateObs = rateLimitRepository.incrementAndGet(RATE_LIMIT_KEY, 1, new Supplier<RateLimit>() {
                @Override
                public RateLimit get() {
                    RateLimit rate = new RateLimit(RATE_LIMIT_KEY);
                    rate.setLimit(1000L);
                    rate.setResetTime(System.currentTimeMillis() + 50000L);
                    return rate;
                }
            }).test();

            rateObs
                    .assertTerminated()
                    .assertNoErrors();
        }

        // Check that the stored counter is equals to the number of hits
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 1000L)
                .assertValue(rate -> rate.getCounter() == 1000L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        // Remote has been called only one time, since we have had a value from local cache
        Mockito.verify(remoteRateLimitRepository).get(RATE_LIMIT_KEY);
        Mockito.verify(localRateLimitRepository, times(1000)).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1L), any());

        testScheduler.advanceTimeBy(5, TimeUnit.SECONDS);

        // After the merge, current counter must be equals to the remote counter + local counter
        localRateLimitRepository.get(RATE_LIMIT_KEY)
                .test()
                .assertComplete()
                .assertNoErrors()
                .assertValue(rate -> rate.getLocal() == 0L)
                .assertValue(rate -> rate.getCounter() == 1500L)
                .assertValue(rate -> rate.getLimit() == 1000L);

        // Refresh has been called once
        Mockito.verify(rateLimitRepository).merge();
        Mockito.verify(remoteRateLimitRepository).incrementAndGet(eq(RATE_LIMIT_KEY), eq(1000L), any());

        subscribe.dispose();
    }
    */
}
