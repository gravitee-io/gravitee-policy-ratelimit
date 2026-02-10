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
package io.gravitee.policy.ratelimit.configuration;

import io.gravitee.ratelimit.KeyConfiguration;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RateLimitConfiguration implements KeyConfiguration {

    private long limit;

    private String dynamicLimit;

    private Long periodTime;

    @Builder.Default
    private TimeUnit periodTimeUnit = TimeUnit.SECONDS;

    private String dynamicPeriodTime;

    private String key;

    private boolean useKeyOnly;

    public boolean hasValidDynamicPeriodTime() {
        return dynamicPeriodTime != null && !dynamicPeriodTime.isBlank();
    }

    public TimeUnit getOrDefaultPeriodTimeUnit() {
        if (hasValidDynamicPeriodTime()) {
            return TimeUnit.SECONDS;
        }
        return periodTimeUnit != null ? periodTimeUnit : TimeUnit.SECONDS;
    }

    public Long getOrDefaultPeriodTime() {
        return periodTime != null && periodTime > 0 ? periodTime : 1L;
    }
}
