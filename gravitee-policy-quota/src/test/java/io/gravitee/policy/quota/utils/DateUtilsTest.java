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
package io.gravitee.policy.quota.utils;

import org.junit.Assert;
import org.junit.Test;

import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class DateUtilsTest {

    @Test
    public void add_one_month() {
        long start = LocalTime.now().atDate(LocalDate.of(2017, Month.AUGUST, 1)).
                atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = DateUtils.getEndOfPeriod(start, 1, ChronoUnit.MONTHS);

        Assert.assertTrue((end - start) == 2_678_400_000L);
    }

    @Test
    public void add_two_months() {
        // From 01/08/2017 to 01/10/2017
        long start = LocalTime.now().atDate(LocalDate.of(2017, Month.AUGUST, 1)).
                atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = DateUtils.getEndOfPeriod(start, 2, ChronoUnit.MONTHS);

        Assert.assertTrue((end - start) == 2_678_400_000L + 2592_000_000L);
    }

    @Test
    public void add_one_day() {
        long now = System.currentTimeMillis();
        long end = DateUtils.getEndOfPeriod(now, 1, ChronoUnit.DAYS);

        Assert.assertTrue((end - now) == 86_400_000);
    }

    @Test
    public void add_two_days() {
        long now = System.currentTimeMillis();
        long end = DateUtils.getEndOfPeriod(now, 2, ChronoUnit.DAYS);

        Assert.assertTrue((end - now) == 86_400_000 * 2);
    }
}
