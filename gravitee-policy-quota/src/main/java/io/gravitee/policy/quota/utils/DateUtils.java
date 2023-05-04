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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public final class DateUtils {

    public static long getEndOfPeriodForStrict(long startingTime, long periodTime, ChronoUnit periodTimeUnit) {
        return ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(startingTime), ZoneId.systemDefault())
            .plus(periodTime, periodTimeUnit)
            .toInstant()
            .toEpochMilli();
    }

    public static long getEndOfPeriodForRound(long currentTime, long periodTimeIncrement, ChronoUnit periodTimeUnit, String timeZoneId) {
        if (periodTimeIncrement > Integer.MAX_VALUE || periodTimeIncrement <= 0) {
            throw new IllegalArgumentException("Invalid period time increment");
        }
        Calendar cal = Calendar.getInstance(
            (timeZoneId == null || timeZoneId.trim().isEmpty()) ? TimeZone.getDefault() : TimeZone.getTimeZone(timeZoneId)
        );
        cal.setTimeInMillis(currentTime);
        cal.clear(Calendar.MILLISECOND);
        int field;
        switch (periodTimeUnit) {
            case YEARS:
                field = Calendar.YEAR;
                cal.clear(Calendar.SECOND);
                cal.clear(Calendar.MINUTE);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.MONTH, 0);
                break;
            case MONTHS:
                field = Calendar.MONTH;
                cal.clear(Calendar.SECOND);
                cal.clear(Calendar.MINUTE);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                break;
            case WEEKS:
                field = Calendar.WEEK_OF_YEAR;
                cal.clear(Calendar.SECOND);
                cal.clear(Calendar.MINUTE);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.DAY_OF_WEEK, 1);
                break;
            case DAYS:
                field = Calendar.DATE;
                cal.clear(Calendar.SECOND);
                cal.clear(Calendar.MINUTE);
                cal.set(Calendar.HOUR_OF_DAY, 0);
                break;
            case HOURS:
                field = Calendar.HOUR_OF_DAY;
                cal.clear(Calendar.SECOND);
                cal.clear(Calendar.MINUTE);
                break;
            case MINUTES:
                field = Calendar.MINUTE;
                cal.clear(Calendar.SECOND);
                break;
            case SECONDS:
                field = Calendar.SECOND;
                break;
            case MILLIS:
                field = Calendar.MILLISECOND;
                break;
            default:
                throw new IllegalArgumentException("Invalid period time unit");
        }
        cal.add(field, (int) periodTimeIncrement);
        return cal.getTimeInMillis();
    }
}
