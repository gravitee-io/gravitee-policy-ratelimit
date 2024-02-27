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
package io.gravitee.policy.quota;

import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.policy.quota.configuration.QuotaConfiguration;

public class RateLimitKeyFactory {

    static final String ATTR_OAUTH_CLIENT_ID = "oauth.client_id";
    private static final char KEY_SEPARATOR = ':';
    private static final char RATE_LIMIT_TYPE = 'q';

    private RateLimitKeyFactory() {}

    /**
     * Build the rate limit key based on the request and the execution context.
     *
     * <p>
     * Rate limit key is composed of
     * <ol>
     *     <li>(PLAN_ID, SUBSCRIPTION_ID) pair, note that for keyless plans this is evaluated to (1, CLIENT_IP)</li>
     *     <li>User-defined key, if it exists</li>
     *      <li>Rate Type (rate-limit / quota)</li>
     *      <li>RESOLVED_PATH (policy attached to a path rather than a plan)</li>
     * </ol>
     * </p>
     *
     * @param executionContext   The execution context
     * @param configuration The policy's configuration
     * @return A rate limit key
     */
    public static String createRateLimitKey(ExecutionContext executionContext, QuotaConfiguration configuration) {
        String resolvedPath = (String) executionContext.getAttribute(ExecutionContext.ATTR_RESOLVED_PATH);

        StringBuilder key = new StringBuilder();

        String plan = (String) executionContext.getAttribute(ExecutionContext.ATTR_PLAN);
        if (plan != null) {
            key
                .append(executionContext.getAttribute(ExecutionContext.ATTR_PLAN))
                .append(executionContext.getAttribute(ExecutionContext.ATTR_SUBSCRIPTION_ID));
        } else if (executionContext.getAttributes().containsKey(ATTR_OAUTH_CLIENT_ID)) { // TODO manage also APIKey when managed by K8S plugins
            key.append(executionContext.getAttribute(ATTR_OAUTH_CLIENT_ID));
        } else {
            key.append(executionContext.getAttribute(ExecutionContext.ATTR_API));
        }

        if (configuration.getKey() != null && !configuration.getKey().isEmpty()) {
            key.append(KEY_SEPARATOR).append(executionContext.getTemplateEngine().getValue(configuration.getKey(), String.class));
        }

        key.append(KEY_SEPARATOR).append(RATE_LIMIT_TYPE);

        if (resolvedPath != null) {
            key.append(KEY_SEPARATOR).append(resolvedPath.hashCode());
        }

        return key.toString();
    }
}
