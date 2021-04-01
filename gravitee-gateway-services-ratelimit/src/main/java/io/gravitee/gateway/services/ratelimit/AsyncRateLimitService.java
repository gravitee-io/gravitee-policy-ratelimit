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
import io.gravitee.gateway.services.ratelimit.rx.SchedulerProvider;
import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.api.RateLimitService;
import io.gravitee.repository.ratelimit.model.RateLimit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class AsyncRateLimitService extends AbstractService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncRateLimitService.class);

    @Value("${services.ratelimit.enabled:true}")
    private boolean enabled;

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
        DefaultListableBeanFactory parentBeanFactory = (DefaultListableBeanFactory) ((ConfigurableApplicationContext) applicationContext.getParent()).getBeanFactory();

        // Retrieve the current rate-limit repository implementation
        RateLimitRepository<RateLimit> rateLimitRepository = parentBeanFactory.getBean(RateLimitRepository.class);
        LOGGER.debug("Rate-limit repository implementation is {}", rateLimitRepository.getClass().getName());

        if (enabled) {
            // Prepare local cache
            LocalRateLimitRepository localCacheRateLimitRepository = new LocalRateLimitRepository();

            LOGGER.debug("Register rate-limit repository asynchronous implementation {}",
                    AsyncRateLimitRepository.class.getName());
            AsyncRateLimitRepository asyncRateLimitRepository = new AsyncRateLimitRepository(new SchedulerProvider());
            beanFactory.autowireBean(asyncRateLimitRepository);
            asyncRateLimitRepository.setLocalCacheRateLimitRepository(localCacheRateLimitRepository);
            asyncRateLimitRepository.setRemoteCacheRateLimitRepository(rateLimitRepository);
            asyncRateLimitRepository.initialize();

            LOGGER.info("Register the rate-limit service bridge for synchronous and asynchronous mode");
            DefaultRateLimitService rateLimitService = new DefaultRateLimitService();
            rateLimitService.setRateLimitRepository(rateLimitRepository);
            rateLimitService.setAsyncRateLimitRepository(asyncRateLimitRepository);
            parentBeanFactory.registerSingleton(RateLimitService.class.getName(), rateLimitService);
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
        }
    }

    @Override
    protected String name() {
        return "Asynchronous Rate Limit proxy";
    }
}
