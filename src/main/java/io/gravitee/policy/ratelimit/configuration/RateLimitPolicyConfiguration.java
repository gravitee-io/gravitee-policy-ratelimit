package io.gravitee.policy.ratelimit.configuration;

import io.gravitee.gateway.api.policy.PolicyConfiguration;

import java.util.concurrent.TimeUnit;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class RateLimitPolicyConfiguration implements PolicyConfiguration {

    private long limit;

    private long periodTime;

    private TimeUnit periodTimeUnit;

    public long getLimit() {
        return limit;
    }

    public void setLimit(long limit) {
        this.limit = limit;
    }

    public long getPeriodTime() {
        return periodTime;
    }

    public void setPeriodTime(long periodTime) {
        this.periodTime = periodTime;
    }

    public TimeUnit getPeriodTimeUnit() {
        return periodTimeUnit;
    }

    public void setPeriodTimeUnit(TimeUnit periodTimeUnit) {
        this.periodTimeUnit = periodTimeUnit;
    }
}
