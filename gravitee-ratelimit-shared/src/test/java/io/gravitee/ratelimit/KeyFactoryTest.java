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

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.ratelimit.utils.ExecutionContextStub;
import io.gravitee.ratelimit.utils.QuotaConfiguration;
import io.gravitee.ratelimit.utils.RateLimitConfiguration;
import io.gravitee.ratelimit.utils.SpikeArrestConfiguration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class KeyFactoryTest {

    @Nested
    class Quota {

        ExecutionContextStub context = new ExecutionContextStub();
        KeyFactory sut = new KeyFactory("q");

        @Test
        void should_use_plan_and_subscription_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new QuotaConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:q");
        }

        @Test
        void should_use_oauth_client_id_when_defined() {
            // Given
            context.setAttribute(KeyFactory.ATTR_OAUTH_CLIENT_ID, "oauth-client-id");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new QuotaConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("oauth-client-id:q");
        }

        @Test
        void should_use_api_id_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_API, "api-id");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new QuotaConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("api-id:q");
        }

        @Test
        void should_use_the_static_key_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, QuotaConfiguration.builder().key("key").build())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:key:q");
        }

        @Test
        void should_use_the_dynamic_key_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    QuotaConfiguration.builder().key("{('k' + 1)}").build()
                )
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:k1:q");
        }

        @Test
        void should_use_the_hashcoded_resolved_path_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");
            context.setAttribute(ExecutionContext.ATTR_RESOLVED_PATH, "resolved-path");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new QuotaConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:q:148210906");
        }

        @Test
        void should_use_only_the_key_defined_when_enabled() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    QuotaConfiguration.builder().key("key").useKeyOnly(true).build()
                )
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("key:q");
        }
    }

    @Nested
    class RateLimit {

        ExecutionContextStub context = new ExecutionContextStub();
        KeyFactory sut = new KeyFactory("rl");

        @Test
        void should_use_plan_and_subscription_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new RateLimitConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:rl");
        }

        @Test
        void should_use_oauth_client_id_when_defined() {
            // Given
            context.setAttribute(KeyFactory.ATTR_OAUTH_CLIENT_ID, "oauth-client-id");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new RateLimitConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("oauth-client-id:rl");
        }

        @Test
        void should_use_api_id_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_API, "api-id");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new RateLimitConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("api-id:rl");
        }

        @Test
        void should_use_the_static_key_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    RateLimitConfiguration.builder().key("key").build()
                )
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:key:rl");
        }

        @Test
        void should_use_the_dynamic_key_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    RateLimitConfiguration.builder().key("{('k' + 1)}").build()
                )
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:k1:rl");
        }

        @Test
        void should_use_the_hashcoded_resolved_path_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");
            context.setAttribute(ExecutionContext.ATTR_RESOLVED_PATH, "resolved-path");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new RateLimitConfiguration())
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("plansubscription:rl:148210906");
        }

        @Test
        void should_use_only_the_key_defined_when_enabled() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    RateLimitConfiguration.builder().key("key").useKeyOnly(true).build()
                )
                .blockingGet();

            // Then
            assertThat(key).isEqualTo("key:rl");
        }
    }

    @Nested
    class SpikeArrest {

        ExecutionContextStub context = new ExecutionContextStub();
        KeyFactory sut = new KeyFactory("sa");

        @Test
        void should_use_plan_and_subscription_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new SpikeArrestConfiguration())
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("plansubscription:sa");
        }

        @Test
        void should_use_oauth_client_id_when_defined() {
            // Given
            context.setAttribute(KeyFactory.ATTR_OAUTH_CLIENT_ID, "oauth-client-id");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new SpikeArrestConfiguration())
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("oauth-client-id:sa");
        }

        @Test
        void should_use_api_id_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_API, "api-id");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new SpikeArrestConfiguration())
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("api-id:sa");
        }

        @Test
        void should_use_the_static_key_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    SpikeArrestConfiguration.builder().key("key").build()
                )
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("plansubscription:key:sa");
        }

        @Test
        void should_use_the_dynamic_key_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    SpikeArrestConfiguration.builder().key("{('k' + 1)}").build()
                )
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("plansubscription:k1:sa");
        }

        @Test
        void should_use_the_hashcoded_resolved_path_when_defined() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");
            context.setAttribute(ExecutionContext.ATTR_RESOLVED_PATH, "resolved-path");

            // When
            String key = sut
                .createRateLimitKey(context.getAttributes(), context::getTemplateEngine, new SpikeArrestConfiguration())
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("plansubscription:sa:148210906");
        }

        @Test
        void should_use_only_the_key_defined_when_enabled() {
            // Given
            context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
            context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

            // When
            String key = sut
                .createRateLimitKey(
                    context.getAttributes(),
                    context::getTemplateEngine,
                    SpikeArrestConfiguration.builder().key("key").useKeyOnly(true).build()
                )
                .blockingGet();

            // Then
            Assertions.assertThat(key).isEqualTo("key:sa");
        }
    }
}
