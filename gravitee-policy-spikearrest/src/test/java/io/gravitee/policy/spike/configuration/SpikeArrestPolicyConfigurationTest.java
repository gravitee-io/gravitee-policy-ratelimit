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
package io.gravitee.policy.spike.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * @author Eric LELEU (eric.leleu at graviteesource.com)
 * @author GraviteeSource Team
 */
public class SpikeArrestPolicyConfigurationTest {

    @Test
    public void test_spikeArrest01() throws IOException {
        SpikeArrestPolicyConfiguration configuration =
                load("/io/gravitee/policy/spike/configuration/spikearrest01.json", SpikeArrestPolicyConfiguration.class);

        Assert.assertNotNull(configuration);

        Assert.assertFalse(configuration.isAddHeaders());
        Assert.assertFalse(configuration.isAsync());

        Assert.assertNotNull(configuration.getSpike());
        Assert.assertEquals(10, configuration.getSpike().getLimit());
        Assert.assertEquals(10, configuration.getSpike().getPeriodTime());
        Assert.assertEquals(TimeUnit.MINUTES, configuration.getSpike().getPeriodTimeUnit());
    }

    @Test
    public void test_spikeArrest02() throws IOException {
        SpikeArrestPolicyConfiguration configuration =
                load("/io/gravitee/policy/spike/configuration/spikearrest02.json", SpikeArrestPolicyConfiguration.class);

        Assert.assertNotNull(configuration);
        Assert.assertEquals("10", configuration.getSpike().getDynamicLimit());
        Assert.assertTrue(configuration.isAddHeaders());
        Assert.assertTrue(configuration.isAsync());
    }

    private <T> T load(String resource, Class<T> type) throws IOException {
        URL jsonFile = this.getClass().getResource(resource);
        return new ObjectMapper().readValue(jsonFile, type);
    }
}
