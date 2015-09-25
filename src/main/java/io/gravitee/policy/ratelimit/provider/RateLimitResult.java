package io.gravitee.policy.ratelimit.provider;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class RateLimitResult {

    private boolean exceeded;

    private long remains;

    public boolean isExceeded() {
        return exceeded;
    }

    public void setExceeded(boolean exceeded) {
        this.exceeded = exceeded;
    }

    public long getRemains() {
        return remains;
    }

    public void setRemains(long remains) {
        this.remains = remains;
    }
}
