# [3.0.0](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.1.3...3.0.0) (2025-04-15)


* feat!: allow use spike arrest on V4 message APIs ([44840df](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/44840dff1a0e4b25527523f91bbc09f3e854970f))
* feat!: allow use rate limit on V4 message APIs ([468334d](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/468334dc522b706f81e6a1abd90d2a387bf45e33))
* feat!: allow use quota on V4 message APIs ([7a5ac9a](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/7a5ac9adef185aefea217ceb003cc69e4ea031a8))


### Features

* create library to shared code between policies ([6960c5c](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/6960c5c69f034ab5695e5664badc54a6b755e25e))


### BREAKING CHANGES

* use HttpPolicy break compatibility with APIM v4.5 and below

APIM-9188
* use HttpPolicy break compatibility with APIM v4.5 and below

APIM-9188
* use HttpPolicy break compatibility with APIM v4.5 and below

APIM-9188

## [2.1.3](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.1.2...2.1.3) (2024-10-09)


### Bug Fixes

* avoid deadlock when exception was thrown by ([50bc691](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/50bc691bbb2c56bdcc1464d33af9a67e3e14cb91))

## [2.1.2](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.1.1...2.1.2) (2024-06-24)


### Bug Fixes

* update language used in the HTTP response ([7018194](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/7018194597ba60bc25b7e48256dbecde4fc6d7d7))

## [2.1.1](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.1.0...2.1.1) (2024-04-30)


### Bug Fixes

* use async vertx lock mechanism ([46f732b](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/46f732b43236cc81dce35ec4aef6990b3c63ea83))

# [2.1.0](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.0.2...2.1.0) (2024-02-29)


### Features

* add an option to ignore host IP and subscription detail ([ba5d3ee](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/ba5d3ee6349c9fce9ad15f82ac7f0bc4a95adfba))

## [2.0.2](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.0.1...2.0.2) (2023-07-20)


### Bug Fixes

* update policy description ([52855b9](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/52855b9e978192eaef5e98e374775390832874fb))

## [2.0.1](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/2.0.0...2.0.1) (2023-04-11)


### Bug Fixes

* clean schema-form to make them compatible with gio-form-json-schema component ([3e1ae23](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/3e1ae23b5f70f2f663259e6cee4d5b033761a71c))

# [2.0.0](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/1.15.0...2.0.0) (2022-12-09)


### chore

* bump to rxJava3 ([0641730](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/064173010225c118982d2805e0a7377a6f46ca13))


### BREAKING CHANGES

* rxJava3 required

# [2.0.0-alpha.1](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/1.15.0...2.0.0-alpha.1) (2022-10-19)


### chore

* bump to rxJava3 ([0641730](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/064173010225c118982d2805e0a7377a6f46ca13))


### BREAKING CHANGES

* rxJava3 required

# [1.15.0](https://github.com/gravitee-io/gravitee-policy-ratelimit/compare/1.14.0...1.15.0) (2022-01-21)


### Features

* **headers:** Internal rework and introduce HTTP Headers API ([b96b14a](https://github.com/gravitee-io/gravitee-policy-ratelimit/commit/b96b14ad3a64848cd7d8e94742331d65317a6862)), closes [gravitee-io/issues#6772](https://github.com/gravitee-io/issues/issues/6772)
