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
package io.gravitee.policy.ratelimit;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.policy.ratelimit.configuration.RateLimitConfiguration;
import io.gravitee.policy.ratelimit.local.ExecutionContextStub;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RateLimitKeyFactoryTest {

    ExecutionContextStub context;

    @BeforeEach
    void setUp() {
        context = new ExecutionContextStub();
    }

    @Test
    void should_use_plan_and_subscription_when_defined() {
        // Given
        context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
        context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

        // When
        String key = RateLimitKeyFactory.createRateLimitKey(context, new RateLimitConfiguration());

        // Then
        Assertions.assertThat(key).isEqualTo("plansubscription:rl");
    }

    @Test
    void should_use_oauth_client_id_when_defined() {
        // Given
        context.setAttribute(RateLimitKeyFactory.ATTR_OAUTH_CLIENT_ID, "oauth-client-id");

        // When
        String key = RateLimitKeyFactory.createRateLimitKey(context, new RateLimitConfiguration());

        // Then
        Assertions.assertThat(key).isEqualTo("oauth-client-id:rl");
    }

    @Test
    void should_use_api_id_when_defined() {
        // Given
        context.setAttribute(ExecutionContext.ATTR_API, "api-id");

        // When
        String key = RateLimitKeyFactory.createRateLimitKey(context, new RateLimitConfiguration());

        // Then
        Assertions.assertThat(key).isEqualTo("api-id:rl");
    }

    @Test
    void should_use_the_static_key_defined() {
        // Given
        context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
        context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

        // When
        String key = RateLimitKeyFactory.createRateLimitKey(context, RateLimitConfiguration.builder().key("key").build());

        // Then
        Assertions.assertThat(key).isEqualTo("plansubscription:key:rl");
    }

    @Test
    void should_use_the_dynamic_key_defined() {
        // Given
        context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
        context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");

        // When
        String key = RateLimitKeyFactory.createRateLimitKey(context, RateLimitConfiguration.builder().key("{('k' + 1)}").build());

        // Then
        Assertions.assertThat(key).isEqualTo("plansubscription:k1:rl");
    }

    @Test
    void should_use_the_hashcoded_resolved_path_when_defined() {
        // Given
        context.setAttribute(ExecutionContext.ATTR_PLAN, "plan");
        context.setAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID, "subscription");
        context.setAttribute(ExecutionContext.ATTR_RESOLVED_PATH, "resolved-path");

        // When
        String key = RateLimitKeyFactory.createRateLimitKey(context, new RateLimitConfiguration());

        // Then
        Assertions.assertThat(key).isEqualTo("plansubscription:rl:148210906");
    }
}
