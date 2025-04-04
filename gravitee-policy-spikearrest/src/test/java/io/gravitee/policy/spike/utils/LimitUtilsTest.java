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
package io.gravitee.policy.spike.utils;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
class LimitUtilsTest {

    @ParameterizedTest
    @MethodSource("data")
    void testComputeSliceLimit(long limit, long period, TimeUnit periodUnit, LimitUtils.SliceLimit expectedSlice) {
        LimitUtils.SliceLimit result = LimitUtils.computeSliceLimit(limit, period, periodUnit);
        Assertions.assertThat(result.limit()).describedAs("Slice Limit is different").isEqualTo(expectedSlice.limit());
        Assertions.assertThat(result.period()).describedAs("Slice period is different").isEqualTo(expectedSlice.period());
    }

    private static Stream<Arguments> data() {
        return Stream.of(
            Arguments.of(30, 1, TimeUnit.MINUTES, new LimitUtils.SliceLimit(2000, 1)), // 1 req / 2s
            Arguments.of(30, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 3)), // 3 req / 100ms
            Arguments.of(1000, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 100)),
            Arguments.of(2000, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 200)),
            Arguments.of(10, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 1)),
            Arguments.of(9, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(111, 1)),
            Arguments.of(11, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(90, 1)),
            Arguments.of(16, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(125, 2)),
            Arguments.of(20, 1, TimeUnit.SECONDS, new LimitUtils.SliceLimit(100, 2)),
            Arguments.of(368500, 1, TimeUnit.MINUTES, new LimitUtils.SliceLimit(99, 614))
        );
    }
}
