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
package io.gravitee.policy.ratelimit.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gravitee.ratelimit.ErrorStrategy;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class RateLimitPolicyConfigurationTest {

    @Test
    public void test_quota01() throws IOException {
        RateLimitPolicyConfiguration configuration = load(
            "/io/gravitee/policy/ratelimit/configuration/ratelimit01.json",
            RateLimitPolicyConfiguration.class
        );

        Assertions.assertThat(configuration).isEqualTo(
            RateLimitPolicyConfiguration.builder()
                .errorStrategy(ErrorStrategy.FALLBACK_PASS_TROUGH)
                .rate(
                    RateLimitConfiguration.builder()
                        .limit(10)
                        .dynamicLimit("{(2*5)}")
                        .periodTime(10)
                        .periodTimeUnit(TimeUnit.MINUTES)
                        .build()
                )
                .build()
        );
    }

    @Test
    public void test_quota02() throws IOException {
        RateLimitPolicyConfiguration configuration = load(
            "/io/gravitee/policy/ratelimit/configuration/ratelimit02.json",
            RateLimitPolicyConfiguration.class
        );

        Assertions.assertThat(configuration).isEqualTo(
            RateLimitPolicyConfiguration.builder()
                .errorStrategy(ErrorStrategy.FALLBACK_PASS_TROUGH)
                .async(true)
                .addHeaders(true)
                .rate(RateLimitConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(TimeUnit.MINUTES).build())
                .build()
        );
    }

    @Test
    public void test_quota03() throws IOException {
        RateLimitPolicyConfiguration configuration = load(
            "/io/gravitee/policy/ratelimit/configuration/ratelimit03.json",
            RateLimitPolicyConfiguration.class
        );

        Assertions.assertThat(configuration).isEqualTo(
            RateLimitPolicyConfiguration.builder()
                .errorStrategy(ErrorStrategy.BLOCK_ON_INTERNAL_ERROR)
                .async(false)
                .addHeaders(false)
                .rate(
                    RateLimitConfiguration.builder()
                        .dynamicLimit("{(2*5)}")
                        .limit(10)
                        .periodTime(10)
                        .periodTimeUnit(TimeUnit.MINUTES)
                        .build()
                )
                .build()
        );
    }

    private <T> T load(String resource, Class<T> type) throws IOException {
        URL jsonFile = this.getClass().getResource(resource);
        return new ObjectMapper().readValue(jsonFile, type);
    }
}
