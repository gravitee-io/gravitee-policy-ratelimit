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

import io.gravitee.gateway.reactive.api.ExecutionFailure;
import java.util.Map;
import lombok.Getter;

@Getter
public class PolicyRateLimitException extends Exception {

    private final transient ExecutionFailure executionFailure;

    public PolicyRateLimitException(ExecutionFailure executionFailure) {
        super(executionFailure.message());
        this.executionFailure = executionFailure;
    }

    public static PolicyRateLimitException serverError(String key, String message) {
        var ex = new ExecutionFailure(500).key(key).message(message);
        return new PolicyRateLimitException(ex);
    }

    public static PolicyRateLimitException overflow(String key, String message, Map<String, Object> parameters) {
        var ex = new ExecutionFailure(429).key(key).message(message).parameters(parameters);
        return new PolicyRateLimitException(ex);
    }

    public static PolicyRateLimitException overflow(String key, String message, Throwable throwable) {
        var ex = new ExecutionFailure(429).key(key).message(message).cause(throwable);
        return new PolicyRateLimitException(ex);
    }

    public static ExecutionFailure getExecutionFailure(String key, Throwable throwable) {
        return throwable instanceof PolicyRateLimitException ex
            ? ex.getExecutionFailure()
            : new ExecutionFailure(500).key(key).message("Unknown error").cause(throwable);
    }
}
