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
package io.gravitee.policy.quota.local;

import io.gravitee.el.TemplateEngine;
import io.gravitee.el.spel.SpelTemplateEngineFactory;
import io.gravitee.gateway.api.ExecutionContext;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.tracing.api.Tracer;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class ExecutionContextStub implements ExecutionContext {

    Map<String, Object> attributes = new HashMap<>();

    @Override
    public Request request() {
        return null;
    }

    @Override
    public Response response() {
        return null;
    }

    @Override
    public <T> T getComponent(Class<T> aClass) {
        return null;
    }

    @Override
    public void setAttribute(String s, Object o) {
        attributes.put(s, o);
    }

    @Override
    public void removeAttribute(String s) {}

    @Override
    public Object getAttribute(String s) {
        return attributes.get(s);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public TemplateEngine getTemplateEngine() {
        return new SpelTemplateEngineFactory().templateEngine();
    }

    @Override
    public Tracer getTracer() {
        return null;
    }
}
