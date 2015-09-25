package io.gravitee.policy.ratelimit.provider;

import java.io.Serializable;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public class RateLimit implements Serializable {

    private long lastCheck = System.currentTimeMillis();

    private long counter;

    public long getLastCheck() {
        return lastCheck;
    }

    public void setLastCheck(long lastCheck) {
        this.lastCheck = lastCheck;
    }

    public long getCounter() {
        return counter;
    }

    public void setCounter(long counter) {
        this.counter = counter;
    }
}
