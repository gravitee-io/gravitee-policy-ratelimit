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
package io.gravitee.ratelimit;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import lombok.experimental.UtilityClass;

@UtilityClass
public class DateUtils {

    public static long getEndOfPeriod(long startingTime, long periodTime, TimeUnit periodTimeUnit) {
        return startingTime + periodTimeUnit.toMillis(periodTime);
    }

    public static long getEndOfPeriod(long startingTime, long periodTime, ChronoUnit periodTimeUnit) {
        return ZonedDateTime
            .ofInstant(Instant.ofEpochMilli(startingTime), ZoneId.systemDefault())
            .plus(periodTime, periodTimeUnit)
            .toInstant()
            .toEpochMilli();
    }
}
