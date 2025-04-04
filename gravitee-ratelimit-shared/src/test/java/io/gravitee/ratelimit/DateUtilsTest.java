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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
class DateUtilsTest {

    static Stream<Arguments> add_chronos_unit() {
        return Stream.of(
            arguments("2017-08-01", 1, ChronoUnit.MONTHS, 2_678_400_000L),
            // From 01/08/2017 to 01/10/2017
            arguments("2017-08-01", 2, ChronoUnit.MONTHS, 2_678_400_000L + 2592_000_000L),
            arguments(LocalDate.now().toString(), 1, ChronoUnit.DAYS, 86_400_000),
            arguments(LocalDate.now().toString(), 2, ChronoUnit.DAYS, 86_400_000 * 2)
        );
    }

    @ParameterizedTest
    @MethodSource
    void add_chronos_unit(String startDate, long period, ChronoUnit unit, long expectedDiff) {
        LocalDate date = LocalDate.parse(startDate);
        long start = LocalTime.now().atDate(date).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endChrono = DateUtils.getEndOfPeriod(start, period, unit);

        assertThat((endChrono - start)).isEqualTo(expectedDiff);
    }
}
