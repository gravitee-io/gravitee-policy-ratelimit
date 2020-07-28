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
package io.gravitee.policy.quota.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public class QuotaPolicyConfigurationTest {

    @Test
    public void test_quota01() throws IOException {
        QuotaPolicyConfiguration configuration =
                load("/io/gravitee/policy/quota/configuration/quota01.json", QuotaPolicyConfiguration.class);

        Assert.assertNotNull(configuration);

        Assert.assertTrue(configuration.isAddHeaders());
        Assert.assertFalse(configuration.isAsync());

        Assert.assertNotNull(configuration.getQuota());
        Assert.assertEquals(10, configuration.getQuota().getLimit());
        Assert.assertEquals("10", configuration.getQuota().getTemplatableLimit());
        Assert.assertEquals(10, configuration.getQuota().getPeriodTime());
        Assert.assertEquals(ChronoUnit.MINUTES, configuration.getQuota().getPeriodTimeUnit());
    }

    @Test
    public void test_quota02() throws IOException {
        QuotaPolicyConfiguration configuration =
                load("/io/gravitee/policy/quota/configuration/quota02.json", QuotaPolicyConfiguration.class);

        Assert.assertNotNull(configuration);
        Assert.assertFalse(configuration.isAddHeaders());
        Assert.assertTrue(configuration.isAsync());
    }   
    
    // TODO test on old format (no templatableLimit)

    private <T> T load(String resource, Class<T> type) throws IOException {
        URL jsonFile = this.getClass().getResource(resource);
        return new ObjectMapper().readValue(jsonFile, type);
    }
}
