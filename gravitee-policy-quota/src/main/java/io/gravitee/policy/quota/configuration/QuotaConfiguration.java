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
package io.gravitee.policy.quota.configuration;

import java.time.temporal.ChronoUnit;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class QuotaConfiguration {

    private long limit;

    private String dynamicLimit;

    private long periodTime;

    private ChronoUnit periodTimeUnit;

    private String key;

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public String getDynamicLimit() {
        return dynamicLimit;
    }

    public void setDynamicLimit(String dynamicLimit) {
        this.dynamicLimit = dynamicLimit;
    }

    public long getPeriodTime() {
        return periodTime;
    }

    public void setPeriodTime(long periodTime) {
        this.periodTime = periodTime;
    }

    public ChronoUnit getPeriodTimeUnit() {
        return periodTimeUnit;
    }

    public void setPeriodTimeUnit(ChronoUnit periodTimeUnit) {
        this.periodTimeUnit = periodTimeUnit;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }
}
