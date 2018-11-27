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

import io.gravitee.common.service.AbstractService;
import io.gravitee.common.util.BlockingArrayQueue;
import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import net.sf.ehcache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.concurrent.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AsyncRateLimitService extends AbstractService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncRateLimitService.class);

    @Value("${services.ratelimit.enabled:true}")
    private boolean enabled;

    @Value("${services.ratelimit.async.polling:10000}")
    private int polling;

    @Value("${services.ratelimit.async.queue:10000}")
    private int queueCapacity;

    @Autowired
    @Qualifier("local")
    private Cache localCache;

    @Autowired
    @Qualifier("aggregate")
    private Cache aggregateCache;

    private ScheduledExecutorService rateLimitPollerExecutor;

    private ExecutorService rateLimitUpdaterExecutor;

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        DefaultListableBeanFactory parentBeanFactory = (DefaultListableBeanFactory) ((ConfigurableApplicationContext) applicationContext.getParent()).getBeanFactory();

        // Retrieve the current rate-limit repository implementation
        RateLimitRepository rateLimitRepository = parentBeanFactory.getBean(RateLimitRepository.class);
        LOGGER.debug("Rate-limit repository implementation is {}", rateLimitRepository.getClass().getName());

        if (enabled) {
            // Prepare caches
            RateLimitRepository aggregateCacheRateLimitRepository = new CachedRateLimitRepository(aggregateCache);
            RateLimitRepository localCacheRateLimitRepository = new CachedRateLimitRepository(localCache);

            // Prepare queue to flush data into the final repository implementation
            BlockingQueue<RateLimit> rateLimitsQueue = new BlockingArrayQueue<>(queueCapacity);

            LOGGER.debug("Register rate-limit repository asynchronous implementation {}",
                    AsyncRateLimitRepository.class.getName());
            AsyncRateLimitRepository asyncRateLimitRepository = new AsyncRateLimitRepository();
            beanFactory.autowireBean(asyncRateLimitRepository);
            asyncRateLimitRepository.setLocalCacheRateLimitRepository(localCacheRateLimitRepository);
            asyncRateLimitRepository.setAggregateCacheRateLimitRepository(aggregateCacheRateLimitRepository);
            asyncRateLimitRepository.setRateLimitsQueue(rateLimitsQueue);

            LOGGER.info("Register the rate-limit service bridge for synchronous and asynchronous mode");
            DefaultRateLimitService rateLimitService = new DefaultRateLimitService();
            rateLimitService.setRateLimitRepository(rateLimitRepository);
            rateLimitService.setAsyncRateLimitRepository(asyncRateLimitRepository);
            parentBeanFactory.registerSingleton(RateLimitService.class.getName(), rateLimitService);

            // Prepare and start rate-limit poller
            rateLimitPollerExecutor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "rate-limit-poller"));
            RateLimitPoller rateLimitPoller = new RateLimitPoller();
            beanFactory.autowireBean(rateLimitPoller);
            rateLimitPoller.setRateLimitRepository(rateLimitRepository);
            rateLimitPoller.setAggregateCacheRateLimitRepository(aggregateCacheRateLimitRepository);

            LOGGER.info("Schedule rate-limit poller at fixed rate: {} {}", polling, TimeUnit.MILLISECONDS);
            rateLimitPollerExecutor.scheduleAtFixedRate(
                    rateLimitPoller, 0L, polling, TimeUnit.MILLISECONDS);

            // Prepare and start rate-limit updater
            rateLimitUpdaterExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "rate-limit-updater"));
            RateLimitUpdater rateLimitUpdater = new RateLimitUpdater(rateLimitsQueue);
            beanFactory.autowireBean(rateLimitUpdater);
            rateLimitUpdater.setRateLimitRepository(rateLimitRepository);

            LOGGER.info("Start rate-limit updater");
            rateLimitUpdaterExecutor.submit(rateLimitUpdater);
        } else {
            // By disabling async and cached rate limiting, only the strict mode is allowed
            LOGGER.info("Register the rate-limit service bridge for strict mode only");
            DefaultRateLimitService rateLimitService = new DefaultRateLimitService();
            rateLimitService.setRateLimitRepository(rateLimitRepository);
            rateLimitService.setAsyncRateLimitRepository(rateLimitRepository);
            parentBeanFactory.registerSingleton(RateLimitService.class.getName(), rateLimitService);
        }
    }

    @Override
    protected void doStop() throws Exception {
        if (enabled) {
            super.doStop();

            if (rateLimitPollerExecutor != null) {
                try {
                    rateLimitPollerExecutor.shutdownNow();
                } catch (Exception ex) {
                    LOGGER.error("Unexpected error when shutdown rate-limit poller", ex);
                }
            }

            if (rateLimitUpdaterExecutor != null) {
                try {
                    rateLimitUpdaterExecutor.shutdownNow();
                } catch (Exception ex) {
                    LOGGER.error("Unexpected error when shutdown rate-limit updater", ex);
                }
            }
        }
    }

    @Override
    protected String name() {
        return "Asynchronous Rate Limit proxy";
    }
}
