# Changelog

## [0.3.3](https://github.com/dbjpanda/kwery/compare/v0.3.2...v0.3.3) (2026-08-20)


### Documentation

* add a social preview card ([3b7d9bc](https://github.com/dbjpanda/kwery/commit/3b7d9bccf0c4303d4234d8f36d671a8b8c1a210d))
* answer "why not just use Room?" ([977142c](https://github.com/dbjpanda/kwery/commit/977142c9baec85b4467037624d73b5811743e4ff))
* build the documentation site with MkDocs on Read the Docs ([f6e40d9](https://github.com/dbjpanda/kwery/commit/f6e40d9f3fb1e5070c0f3d01ce9cf3a0f61377b8))
* Convex-style sidebar with per-item icons ([731bd79](https://github.com/dbjpanda/kwery/commit/731bd796e3830385b0473d39fe10dabad356a7b7))
* dark theme and card grid for the documentation site ([b3eb1e9](https://github.com/dbjpanda/kwery/commit/b3eb1e98e208a87516ec630c2cc65aba6db4a404))
* fix unreachable sidebar items and hover masquerading as selected ([404de4d](https://github.com/dbjpanda/kwery/commit/404de4d6bf19345f19759e818aa093f2c3af732a))
* keep the sidebar still when you click a link in it ([8dc219c](https://github.com/dbjpanda/kwery/commit/8dc219cf64ef3b167ac52162dde187be767cb87d))
* make the docs home a landing page, not a contributor briefing ([2366e45](https://github.com/dbjpanda/kwery/commit/2366e45db3745ef7471537d0767719a4abcc13ec))
* quiet the sidebar scrollbar, and stop hover hiding the current page ([6019317](https://github.com/dbjpanda/kwery/commit/6019317487e5d2ca5af0150b562ac91fef4da707))
* regenerate the social card, and make it reproducible ([5cf5384](https://github.com/dbjpanda/kwery/commit/5cf5384762081cc71ac3a3aa5d4c1f45b1f89260))
* remove the last href-dependent sidebar selectors ([bab5660](https://github.com/dbjpanda/kwery/commit/bab5660666a093e8cb7d15f5b5f97d00be35eaea))
* replace the competitor matrix with something checkable ([6602b74](https://github.com/dbjpanda/kwery/commit/6602b7438ed40c4625bfcc3b5341e4e7efcba73f))
* reposition from "offline-first caching" to server state ([2896728](https://github.com/dbjpanda/kwery/commit/2896728e90b297e143594786e5eec881880d9b7e))
* reposition the README hero banner too ([72a2f36](https://github.com/dbjpanda/kwery/commit/72a2f36cdaa6aa109c9f8380e6b87c6e9cd82acf))
* say what Kwery is not, before saying what it is ([87ae58b](https://github.com/dbjpanda/kwery/commit/87ae58bf908afbed37a0d3a5812280da9de59e5d))

## [0.3.2](https://github.com/dbjpanda/kwery/compare/v0.3.1...v0.3.2) (2026-08-18)


### Documentation

* fix RELEASE.md drift on Maven Central and device-test status ([66fab78](https://github.com/dbjpanda/kwery/commit/66fab787d19b2652c22e60926d2e366b31e3e680))

## [0.3.1](https://github.com/dbjpanda/kwery/compare/v0.3.0...v0.3.1) (2026-08-18)


### Documentation

* fix the one install snippet I missed ([c09be29](https://github.com/dbjpanda/kwery/commit/c09be2935ca2cc1a59c3caa33a085248bdb8a363))
* update install to 0.3.0, and the facts around it ([6839024](https://github.com/dbjpanda/kwery/commit/68390249772572d6812e548a49ab17d1d019e7f2))

## [0.3.0](https://github.com/dbjpanda/kwery/compare/v0.2.1...v0.3.0) (2026-08-18)


### Features

* **core:** QueryEvent stream, so the cache can explain itself ([766a64b](https://github.com/dbjpanda/kwery/commit/766a64b6645ce833e1018f9f1d854c3bc14d29b2))
* **persist-room:** Room-backed stores that write only what changed ([a1d3609](https://github.com/dbjpanda/kwery/commit/a1d36090a1b0b6696b02b5dfeb37701aa9dd68de))


### Fixes

* **persist:** concurrent writers broke persistence permanently ([53571d6](https://github.com/dbjpanda/kwery/commit/53571d6ddccb65a1157522d412f40a4f825707f7))


### Documentation

* give Kwery its own identity, and use terms people search for ([983bf60](https://github.com/dbjpanda/kwery/commit/983bf60cab259530af7c984f82b87fe8babadfa2))
* hero banner, and positioning that argues from measurements ([ce923ac](https://github.com/dbjpanda/kwery/commit/ce923ac457d6296719fc1c0b69a55ddad06d3e93))
* rewrite the README shorter, and add a flow diagram ([77ccac7](https://github.com/dbjpanda/kwery/commit/77ccac7442b2b7b2f2fa431f74533d5cc5bb72a6))

## [0.2.1](https://github.com/dbjpanda/kwery/compare/v0.2.0...v0.2.1) (2026-08-18)


### Fixes

* **build:** publish with the configuration cache off, and allow re-publishing ([a44f688](https://github.com/dbjpanda/kwery/commit/a44f688b2cb5f0f410ff9115b56a0617ff1604c5))

## [0.2.0](https://github.com/dbjpanda/kwery/compare/v0.1.2...v0.2.0) (2026-08-18)


### Features

* **core:** typed combineQueries for up to five queries ([95fe442](https://github.com/dbjpanda/kwery/commit/95fe4428dc7db2227364aac19911fbd3b88ef343))


### Fixes

* **build:** publish from the release-please workflow, not on: release ([9ba9b34](https://github.com/dbjpanda/kwery/commit/9ba9b3445a652c3c2112fe4c2a6a14ccec1ae641))

## [0.1.2](https://github.com/dbjpanda/kwery/compare/v0.1.1...v0.1.2) (2026-08-18)


### Fixes

* **build:** make release-please actually update the version ([7c7d225](https://github.com/dbjpanda/kwery/commit/7c7d225186a7cfe2b2acdee8a5c3d1f3b7cd83be))


### Build and publishing

* automate releases with release-please and the vanniktech plugin ([f8cabc6](https://github.com/dbjpanda/kwery/commit/f8cabc6822a9f2098829dff81f30fce198c0b185))
* bump main to 0.2.0-SNAPSHOT after v0.1.1 ([a3174a1](https://github.com/dbjpanda/kwery/commit/a3174a1d204751c04906b912319aad0bfca439d1))
