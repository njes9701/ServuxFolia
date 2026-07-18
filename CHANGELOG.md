# Changelog

All notable changes to ServuxFolia are documented in this file.

## [Unreleased]

### Added

- MiniHUD Villager Info Overlay support through Folia-safe tracked entity NBT queries.
- Per-player tracked-entity lookup, entity type allowlist and a separate entity request rate limit.
- Villagers and zombie villagers are allowed by default; untracked or distant entities return empty data.

### Fixed

- Fixed `UnsupportedOperationException` while encoding an empty MiniHUD structure scan result.
- Structure responses now sort a mutable snapshot instead of mutating a possibly immutable caller list.

## [1.0.0] - 2026-07-16

First stable release.

### Added

- Folia-native Easy Place V3 decoding and precise directional block placement.
- Authoritative delayed block and inventory resynchronization to repair client-predicted ghost blocks.
- Servux `servux:litematics` protocol v1 metadata, block/entity NBT and bounded Bulk NBT responses.
- Inline Litematica Direct Paste with block entities, entities, pending ticks, rotation, mirror and replace modes.
- MiniHUD `servux:structures` v2 main/component structure boxes using region-safe, loaded-chunk-only scans.
- MiniHUD `servux:entity_data` v1 beehive data for bee-count info lines.
- Configurable permissions, distance/rate/payload limits, paste concurrency and structure/entity-data allowlists.
- Player-safe features are available to regular players by default; Direct Paste and administration remain operator-only.
- `/servuxfolia` (`/sfolia`) runtime status and configuration reload command.

### Verified

- Directional and unsupported/air-adjacent Easy Place placement with a live Litematica client.
- Inline blueprint paste with a live Litematica client.
- Structure overlays with a live MiniHUD client.
- Beehive bee-count data with a live MiniHUD client.

### Known limitations

- Requires Folia 26.2 and Java 25.
- Per-subregion rotation/mirroring in Direct Paste is rejected.
- Multi-frame `Litematic-Transmit*` file transfer is not advertised.

[1.0.0]: https://github.com/njes9701/ServuxFolia/releases/tag/v1.0.0
