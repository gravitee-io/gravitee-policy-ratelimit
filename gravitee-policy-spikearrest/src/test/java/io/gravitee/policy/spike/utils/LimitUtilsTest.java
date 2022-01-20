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

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(Parameterized.class)
public class LimitUtilsTest {

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(
            new Object[][] {
                { 30, 1, TimeUnit.MINUTES, new LimitUtils.SliceLimit(2000, 1) }, // 1 req / 2s
                { 30, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 3) }, // 3 req / 100ms
                { 1000, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 100) },
                { 2000, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 200) },
                { 10, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 1) },
                { 9, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(111, 1) },
                { 11, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(90, 1) },
                { 16, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(125, 2) },
                { 20, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 2) },
                { 368500, 1, TimeUnit.MINUTES, new LimitUtils.SliceLimit(99, 614) },
            }
        );
    }

    private long limit;
    private long period;
    private TimeUnit periodUnit;
    private LimitUtils.SliceLimit expectedSlice;

    public LimitUtilsTest(long limit, long period, TimeUnit periodUnit, LimitUtils.SliceLimit expectedSlice) {
        this.limit = limit;
        this.period = period;
        this.periodUnit = periodUnit;
        this.expectedSlice = expectedSlice;
    }

    @Test
    public void testComputeSliceLimit() {
        LimitUtils.SliceLimit result = LimitUtils.computeSliceLimit(limit, period, periodUnit);
        assertEquals("Slice Limit is different", expectedSlice.getLimit(), result.getLimit());
        assertEquals("Slice period is different", expectedSlice.getPeriod(), result.getPeriod());
    }
}
