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

import static java.util.function.Predicate.not;

import io.gravitee.el.TemplateEngine;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.reactive.api.context.http.HttpBaseExecutionContext;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class KeyFactory {

    static final String ATTR_OAUTH_CLIENT_ID = "oauth.client_id";
    private static final String KEY_SEPARATOR = ":";
    private final String type;

    public Single<String> createRateLimitKey(HttpBaseExecutionContext executionContext, KeyConfiguration configuration) {
        return createRateLimitKey(executionContext.getAttributes(), executionContext::getTemplateEngine, configuration);
    }

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
     * @param attributes   Attributes of execution context
     * @param templateEngineSupplier template
     * @param configuration The policy's configuration
     * @return A rate limit key
     */
    public Single<String> createRateLimitKey(
        Map<String, Object> attributes,
        Supplier<TemplateEngine> templateEngineSupplier,
        KeyConfiguration configuration
    ) {
        String resolvedPath = (String) attributes.get(ExecutionContext.ATTR_RESOLVED_PATH);

        if (configuration.isUseKeyOnly() && configuration.getKey() != null && !configuration.getKey().isEmpty()) {
            return templateEngineSupplier
                .get()
                .eval(configuration.getKey(), String.class)
                .defaultIfEmpty("")
                .map(key -> key + KEY_SEPARATOR + type);
        }

        return Single
            .zip(
                Single.just(susbcription(attributes)),
                template(templateEngineSupplier, configuration).defaultIfEmpty(""),
                Single.just(type),
                resolvedPath != null ? Single.just(Integer.toString(resolvedPath.hashCode())) : Single.just(""),
                Stream::of
            )
            .map(stream -> stream.filter(not(String::isBlank)).collect(Collectors.joining(KEY_SEPARATOR)));
    }

    private static String susbcription(Map<String, Object> attributes) {
        var plan = attributes.get(ExecutionContext.ATTR_PLAN);
        if (plan != null) {
            return String.valueOf(plan) + attributes.get(ExecutionContext.ATTR_SUBSCRIPTION_ID);
        } else if (attributes.containsKey(ATTR_OAUTH_CLIENT_ID)) { // TODO manage also APIKey when managed by K8S plugins
            return String.valueOf(attributes.get(ATTR_OAUTH_CLIENT_ID));
        } else {
            return String.valueOf(attributes.get(ExecutionContext.ATTR_API));
        }
    }

    private static Maybe<String> template(Supplier<TemplateEngine> templateEngineSupplier, KeyConfiguration configuration) {
        return configuration.getKey() != null && !configuration.getKey().isEmpty()
            ? templateEngineSupplier.get().eval(configuration.getKey(), String.class)
            : Maybe.empty();
    }
}
