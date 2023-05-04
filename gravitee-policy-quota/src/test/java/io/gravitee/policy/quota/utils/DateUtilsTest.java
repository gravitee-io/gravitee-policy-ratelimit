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

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class DateUtilsTest {

    @Test
    public void add_one_month() {
        long start = LocalTime.now().atDate(LocalDate.of(2017, Month.AUGUST, 1)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = DateUtils.getEndOfPeriodForStrict(start, 1, ChronoUnit.MONTHS);

        Assert.assertTrue((end - start) == 2_678_400_000L);
    }

    @Test
    public void add_two_months() {
        // From 01/08/2017 to 01/10/2017
        long start = LocalTime.now().atDate(LocalDate.of(2017, Month.AUGUST, 1)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = DateUtils.getEndOfPeriodForStrict(start, 2, ChronoUnit.MONTHS);

        Assert.assertTrue((end - start) == 2_678_400_000L + 2592_000_000L);
    }

    @Test
    public void add_one_day() {
        long now = System.currentTimeMillis();
        long end = DateUtils.getEndOfPeriodForStrict(now, 1, ChronoUnit.DAYS);

        Assert.assertTrue((end - now) == 86_400_000);
    }

    @Test
    public void add_two_days() {
        long now = System.currentTimeMillis();
        long end = DateUtils.getEndOfPeriodForStrict(now, 2, ChronoUnit.DAYS);

        Assert.assertTrue((end - now) == 86_400_000 * 2);
    }

    @Test
    public void quotaReset_1minute() throws ParseException {
        testQuotaReset("08/03/2019 02:15:00", "08/03/2019 02:14:33", ChronoUnit.MINUTES, 1, "America/New_York");
    }

    @Test
    public void quotaReset_1hour() throws ParseException {
        testQuotaReset("08/03/2019 02:00:00", "08/03/2019 01:14:33", ChronoUnit.HOURS, 1, "America/New_York");
    }

    @Test
    public void quotaReset_3hours() throws ParseException {
        testQuotaReset("08/03/2019 02:00:00", "08/02/2019 23:44:21", ChronoUnit.HOURS, 3, "America/New_York");
    }

    @Test
    public void quotaReset_1day() throws ParseException {
        testQuotaReset("08/03/2019 04:00:00", "08/02/2019 04:01:54", ChronoUnit.DAYS, 1, "America/New_York");
    }

    @Test
    public void quotaReset_1day_prev() throws ParseException {
        testQuotaReset("08/02/2019 04:00:00", "08/02/2019 02:01:54", ChronoUnit.DAYS, 1, "America/New_York");
    }

    @Test
    public void quotaReset_1day_utc() throws ParseException {
        testQuotaReset("08/03/2019 00:00:00", "08/02/2019 04:01:54", ChronoUnit.DAYS, 1, "UTC");
    }

    @Test
    public void quotaReset_1day_utc2() throws ParseException {
        testQuotaReset("08/03/2019 00:00:00", "08/02/2019 04:01:54", ChronoUnit.DAYS, 1, "UTC");
    }

    @Test
    public void quotaReset_1day_utc_next() throws ParseException {
        testQuotaReset("08/03/2019 00:00:00", "08/02/2019 22:01:54", ChronoUnit.DAYS, 1, "UTC");
    }

    @Test
    public void quotaReset_1day_cst() throws ParseException {
        testQuotaReset("08/02/2019 05:00:00", "08/02/2019 04:01:54", ChronoUnit.DAYS, 1, "America/Chicago");
    }

    @Test
    public void quotaReset_1week() throws ParseException {
        testQuotaReset("08/08/2019 07:00:00", "08/02/2019 04:01:54", ChronoUnit.DAYS, 7, "PST");
    }

    @Test
    public void quotaReset_1month() throws ParseException {
        testQuotaReset("09/01/2019 07:00:00", "08/02/2019 04:01:54", ChronoUnit.MONTHS, 1, "PST");
    }

    private static final DateFormat DATE_CONVERTER = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss Z");
    private static final String DATE_SUFFIX = " UTC";

    private static void testQuotaReset(String expectedDate, String startDate, ChronoUnit unit, int increment, String timeZoneId)
        throws ParseException {
        long result = DateUtils.getEndOfPeriodForRound(parseDate(startDate, timeZoneId).getTime(), increment, unit, timeZoneId);
        Assert.assertEquals(parseDate(expectedDate, timeZoneId), new Date(result));
    }

    private static Date parseDate(String dateUtc, String timeZoneId) throws ParseException {
        return DATE_CONVERTER.parse(dateUtc + DATE_SUFFIX);
    }
}
