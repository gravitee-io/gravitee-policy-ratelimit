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
package io.gravitee.gateway.service.ratelimit.util;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 * @author GraviteeSource Team
 */
public final class KeySplitter {

    /**
     * Extract gateway_id from rate-limit key
     * RateLimit key format is gateway-id:api-id:app-id:resolved-path:hash:idx:gateway-id
     *
     * @param key
     * @return
     */
    public static String [] split(String key) {
        return new String [] {
                key.substring(0, key.indexOf(':')),
                key.substring(key.indexOf(':') + 1, key.length())
        };
    }
}
