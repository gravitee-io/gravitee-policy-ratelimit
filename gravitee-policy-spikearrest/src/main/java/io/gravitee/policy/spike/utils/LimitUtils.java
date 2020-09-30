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
package io.gravitee.policy.spike.utils;

import java.util.concurrent.TimeUnit;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class LimitUtils {
    // slice a second in a minimum of 100ms to allow counter updates
    // otherwise there are too many false negative
    private static final int MIN_SLICE_PERIOD = 100;

    private static float computeSlicePeriod(long limit, long periodTime, TimeUnit periodTimeUnit) {
        return periodTimeUnit.toMillis(periodTime) / (limit * 1.0f); // return a float to allow more than 1000req/s
    }

    public static SliceLimit computeSliceLimit(long limit, long periodTime, TimeUnit periodTimeUnit) {
        if (limit < 0) {
            throw new IllegalArgumentException("SpikeArrest requires a non zero Limit");
        }

        // get slice period for ONE request
        float slicePeriod = computeSlicePeriod(limit, periodTime, periodTimeUnit);

        if (slicePeriod < MIN_SLICE_PERIOD) {
            // slice period is too small
            // aggregate few of them to reach around the minimum slice period
            final float computedSlicePerMinSlicePeriod = MIN_SLICE_PERIOD / slicePeriod;
            long nbOfSlice = Math.round(computedSlicePerMinSlicePeriod);
            return new SliceLimit((long)(slicePeriod * nbOfSlice), nbOfSlice);
        } else {
            return new SliceLimit((long)slicePeriod, 1);
        }
    }

    public static class SliceLimit {
        private long period;
        private long limit;

        public SliceLimit(long period, long limit) {
            this.period = period;
            this.limit = limit;
        }

        public long getPeriod() {
            return period;
        }

        public void setPeriod(long period) {
            this.period = period;
        }

        public long getLimit() {
            return limit;
        }

        public void setLimit(long limit) {
            this.limit = limit;
        }
    }

    public static long getEndOfPeriod(long startingTime, long periodTime, TimeUnit periodTimeUnit) {
        return startingTime + periodTimeUnit.toMillis(periodTime);
    }
}
