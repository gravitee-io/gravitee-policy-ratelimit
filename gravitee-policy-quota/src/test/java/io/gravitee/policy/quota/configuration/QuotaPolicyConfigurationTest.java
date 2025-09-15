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
package io.gravitee.policy.quota.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URL;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
class QuotaPolicyConfigurationTest {

    @Test
    void test_quota01() throws IOException {
        QuotaPolicyConfiguration configuration = load(
            "/io/gravitee/policy/quota/configuration/quota01.json",
            QuotaPolicyConfiguration.class
        );

        assertThat(configuration).isEqualTo(
            QuotaPolicyConfiguration.builder()
                .addHeaders(true)
                .async(false)
                .quota(
                    QuotaConfiguration.builder().limit(10).dynamicLimit("{(2*5)}").periodTime(10).periodTimeUnit(ChronoUnit.MINUTES).build()
                )
                .build()
        );
    }

    @Test
    void test_quota02() throws IOException {
        QuotaPolicyConfiguration configuration = load(
            "/io/gravitee/policy/quota/configuration/quota02.json",
            QuotaPolicyConfiguration.class
        );

        assertThat(configuration).isEqualTo(
            QuotaPolicyConfiguration.builder()
                .addHeaders(false)
                .async(true)
                .quota(QuotaConfiguration.builder().limit(10).periodTime(10).periodTimeUnit(ChronoUnit.MINUTES).build())
                .build()
        );
    }

    private <T> T load(String resource, Class<T> type) throws IOException {
        URL jsonFile = this.getClass().getResource(resource);
        return new ObjectMapper().readValue(jsonFile, type);
    }
}
