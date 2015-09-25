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

import io.gravitee.common.http.HttpHeaders;
import io.gravitee.gateway.api.Request;
import io.gravitee.gateway.api.Response;
import io.gravitee.gateway.api.policy.PolicyChain;
import io.gravitee.gateway.api.policy.PolicyResult;
import io.gravitee.policy.ratelimit.configuration.RateLimitPolicyConfiguration;
import io.gravitee.policy.ratelimit.provider.RateLimitProviderFactory;
import io.gravitee.policy.ratelimit.provider.local.LocalCacheRateLimitProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static io.gravitee.common.http.GraviteeHttpHeader.X_GRAVITEE_API_KEY;
import static io.gravitee.common.http.GraviteeHttpHeader.X_GRAVITEE_API_NAME;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
@RunWith(MockitoJUnitRunner.class)
public class RateLimitPolicyTest {

    private static final String API_KEY_HEADER_VALUE = "fbc40d50-5746-40af-b283-d7e99c1775c7";
    private static final String API_NAME_HEADER_VALUE = "my-api";

    @Mock
    protected Request request;

    @Mock
    protected Response response;

    @Mock
    protected PolicyChain policyChain;

    @Before
    public void init() {
        ((LocalCacheRateLimitProvider)RateLimitProviderFactory.getRateLimitProvider()).clean();
        initMocks(this);
    }

    @Test
    public void singleRequest() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(X_GRAVITEE_API_KEY, API_KEY_HEADER_VALUE);
                put(X_GRAVITEE_API_NAME, API_NAME_HEADER_VALUE);
            }
        });

        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        policyConfiguration.setLimit(10);
        policyConfiguration.setPeriodTime(10);
        policyConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        when(request.headers()).thenReturn(headers);
        when(response.headers()).thenReturn(new HttpHeaders());
        rateLimitPolicy.onRequest(request, response, policyChain);

        verify(policyChain).doNext(request, response);
    }

    @Test
    public void multipleRequest() {
        final HttpHeaders headers = new HttpHeaders();
        headers.setAll(new HashMap<String, String>() {
            {
                put(X_GRAVITEE_API_KEY, API_KEY_HEADER_VALUE);
                put(X_GRAVITEE_API_NAME, API_NAME_HEADER_VALUE);
            }
        });

        RateLimitPolicyConfiguration policyConfiguration = new RateLimitPolicyConfiguration();
        policyConfiguration.setLimit(10);
        policyConfiguration.setPeriodTime(10);
        policyConfiguration.setPeriodTimeUnit(TimeUnit.SECONDS);

        RateLimitPolicy rateLimitPolicy = new RateLimitPolicy(policyConfiguration);

        when(request.headers()).thenReturn(headers);
        when(response.headers()).thenReturn(new HttpHeaders());

        InOrder inOrder = inOrder(policyChain);

        int calls = 11;

        for (int i = 0 ; i < calls ; i++) {
            rateLimitPolicy.onRequest(request, response, policyChain);
        }

        inOrder.verify(policyChain, times(calls-1)).doNext(request, response);
        inOrder.verify(policyChain, times(1)).failWith(any(PolicyResult.class));
    }
}
