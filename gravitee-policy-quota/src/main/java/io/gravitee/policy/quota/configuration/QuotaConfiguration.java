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
package io.gravitee.policy.quota.configuration;

import io.gravitee.ratelimit.KeyConfiguration;
import java.time.temporal.ChronoUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class QuotaConfiguration implements KeyConfiguration {

    private long limit;

    private String dynamicLimit;

    @Builder.Default
    private Long periodTime = 1L;

    private String dynamicPeriodTime;

    @Builder.Default
    private ChronoUnit periodTimeUnit = ChronoUnit.MONTHS;

    private String key;

    private boolean useKeyOnly;

    public boolean hasValidDynamicPeriodTime() {
        return dynamicPeriodTime != null && !dynamicPeriodTime.isBlank();
    }

    public ChronoUnit getPeriodTimeUnit() {
        if (hasValidDynamicPeriodTime()) {
            return ChronoUnit.HOURS;
        }
        return periodTimeUnit != null ? periodTimeUnit : ChronoUnit.MONTHS;
    }

    public Long getOrDefaultPeriodTime() {
        return periodTime != null && periodTime > 0 ? periodTime : 1L;
    }
}
