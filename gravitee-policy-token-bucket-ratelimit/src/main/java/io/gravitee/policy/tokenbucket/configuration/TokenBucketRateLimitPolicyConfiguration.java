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
package io.gravitee.policy.tokenbucket.configuration;

import io.gravitee.policy.api.PolicyConfiguration;
import io.gravitee.ratelimit.ErrorStrategy;
import io.gravitee.ratelimit.KeyConfiguration;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Configuration for the {@code token-bucket-rate-limit} policy. The refill is expressed as a
 * whole-token count per period ({@code refillRate} tokens every {@code refillPeriodTime}
 * {@code refillPeriodTimeUnit}, e.g. 100 tokens / 1 MINUTE), mirroring the {@code rate-limit} policy
 * so all arithmetic is integer. Reuses the shared key model ({@link KeyConfiguration}) so consumer
 * identification matches the {@code rate-limit} policy.
 *
 * @author GraviteeSource Team
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenBucketRateLimitPolicyConfiguration implements PolicyConfiguration, KeyConfiguration {

    @Builder.Default
    private ErrorStrategy errorStrategy = ErrorStrategy.FALLBACK_PASS_TROUGH;

    private boolean addHeaders;

    /** Whole tokens added to the bucket each refill period. Used when {@code > 0}; otherwise {@link #dynamicRefillRate} is evaluated. */
    private long refillRate;

    /** EL expression for the refill rate (tokens per period), evaluated per request when {@link #refillRate} is not set ({@code <= 0}). */
    private String dynamicRefillRate;

    /** Length of the refill period, combined with {@link #refillPeriodTimeUnit} (e.g. 10 SECONDS). */
    @Builder.Default
    private long refillPeriodTime = 1;

    /** Unit of {@link #refillPeriodTime}. */
    @Builder.Default
    private TimeUnit refillPeriodTimeUnit = TimeUnit.SECONDS;

    /** Burst capacity — the maximum number of accumulated tokens. Used when {@code > 0}; otherwise {@link #dynamicBurstCapacity} is evaluated. */
    private long burstCapacity;

    /** EL expression for the burst capacity, evaluated per request when {@link #burstCapacity} is not set ({@code <= 0}). */
    private String dynamicBurstCapacity;

    private String key;

    private boolean useKeyOnly;
}
