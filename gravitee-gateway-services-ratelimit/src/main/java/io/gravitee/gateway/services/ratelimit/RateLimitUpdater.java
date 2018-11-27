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

import io.gravitee.repository.ratelimit.api.RateLimitRepository;
import io.gravitee.repository.ratelimit.model.RateLimit;
import org.springframework.beans.factory.annotation.Value;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
class RateLimitUpdater implements Runnable {

    @Value("${services.ratelimit.queue.pollingTimeout:1000}")
    private long pollingTimeout;

    private BlockingQueue<RateLimit> queue;

    private RateLimitRepository delegateRateLimitRepository;

    RateLimitUpdater(final BlockingQueue<RateLimit> queue) {
        this.queue = queue;
    }

    @Override
    public void run() {
        while (!Thread.interrupted()) {
            try {
                RateLimit rateLimit = queue.poll(getPollingTimeout(), TimeUnit.MILLISECONDS);
                if (rateLimit != null) {
                    queue.offer(rateLimit);
                }

                while (!queue.isEmpty()) {
                    rateLimit = queue.poll(getPollingTimeout(), TimeUnit.MILLISECONDS);
                    if (rateLimit != null) {
                        delegateRateLimitRepository.save(rateLimit);
                    }
                }
            } catch (InterruptedException ie) {

            } catch (Exception ex) {
                // Do nothing here
            }
        }
    }

    public long getPollingTimeout() {
        return pollingTimeout;
    }

    public void setPollingTimeout(long pollingTimeout) {
        this.pollingTimeout = pollingTimeout;
    }

    public RateLimitRepository getRateLimitRepository() {
        return delegateRateLimitRepository;
    }

    public void setRateLimitRepository(RateLimitRepository delegateRateLimitRepository) {
        this.delegateRateLimitRepository = delegateRateLimitRepository;
    }
}
