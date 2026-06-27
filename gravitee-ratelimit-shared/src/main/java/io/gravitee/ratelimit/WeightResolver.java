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

import io.gravitee.gateway.reactive.api.context.http.HttpBaseExecutionContext;
import io.reactivex.rxjava3.core.Single;

/**
 * Resolves how much a single invocation consumes ("weight") for the rate-limit, quota and token-bucket
 * policies.
 *
 * <p>By default a request consumes {@code 1} (request-count rate limiting). When <em>token budget</em>
 * mode is enabled, the weight is instead the <em>cost</em> of the request — e.g. the number of LLM tokens
 * (or their priced equivalent) — so the limit is spent against accumulated cost rather than a raw request
 * count. The cost is resolved exactly like {@code limit}/{@code refillRate}: a static positive value wins,
 * otherwise the EL expression is evaluated per request (via async {@code eval()}, which supports deferred
 * variables such as a token-usage attribute populated by an upstream policy).</p>
 *
 * <p>A blank/unresolved expression defaults to {@code 1} so enforcement is never silently disabled, and a
 * negative result is clamped to {@code 0} (a free request).</p>
 */
public final class WeightResolver {

    private WeightResolver() {}

    /**
     * @param budget        whether token-budget mode is enabled
     * @param weight        static weight (cost) per invocation, used when {@code > 0}
     * @param dynamicWeight EL expression for the weight (cost), evaluated when the static weight is unset
     */
    public static Single<Long> resolve(HttpBaseExecutionContext ctx, boolean budget, long weight, String dynamicWeight) {
        if (!budget) {
            return Single.just(1L);
        }
        if (weight > 0) {
            return Single.just(weight);
        }
        if (dynamicWeight == null || dynamicWeight.isBlank()) {
            return Single.just(1L);
        }
        return ctx
            .getTemplateEngine()
            .eval(dynamicWeight, Long.class)
            .defaultIfEmpty(1L)
            .map(w -> Math.max(0L, w));
    }
}
