# MiniHUD structure bounding boxes

## Wire compatibility

- Channel: `servux:structures`
- Protocol: version 2
- C2S type 3: register, followed by NBT (MiniHUD sends its version string)
- C2S type 4: unregister, followed by empty NBT
- S2C type 1: metadata NBT with `name`, `id`, `version`, `servux`, `timeout`
- S2C type 5 is the logical structure-data start. The encoded NBT is split and
  every transmitted slice is wrapped as S2C type 2.

The reconstructed NBT contains `Structures`, a list of vanilla structure-start
tags. MiniHUD 26.2 only needs each start's `id`, `Children`, every child's
six-int `BB`, and Servux's extra `ExpandBox` boolean.

## Implemented Folia-native pipeline

1. Register `servux:structures` independently from `servux:litematics` and
   track opted-in players.
2. Every configured update interval, run on the player entity scheduler to
   capture world, current chunk and the requested radius.
3. Dispatch already-loaded chunks to their owning RegionScheduler. Read
   structure references only on that region thread; never force chunk loads.
4. Deduplicate referenced start chunks, then dispatch those start chunks to
   their owning RegionScheduler to read and serialize `StructureStart` data.
5. Aggregate by a request generation number. Discard results if the player
   changes world, unregisters, disconnects, or a newer scan supersedes them.
6. Encode and split NBT asynchronously, then send all slices on the player's
   entity scheduler.
7. Cache serialized starts by world, start chunk and structure id until the
   configured timeout. Apply whitelist/blacklist and a strict maximum response
   size before encoding.

## Safety defaults

- Separate `servuxfolia.structures` permission
- Update interval: 40 ticks
- Timeout: 600 ticks
- Radius: server view distance + 2, with a configurable hard ceiling
- Only loaded chunks; no generation and no synchronous cross-region reads
- Bounded chunks per scan, structures per response and encoded response bytes

The channel was introduced in ServuxFolia 0.2.0 and promoted to the stable
1.0.0 release after a live MiniHUD 26.2 smoke test confirmed structure main
and component boxes.

## Verification matrix

- [x] MiniHUD 26.2 registers, accepts metadata version 2 and displays structures
- [x] Main and component boxes are delivered to the client
- [x] No chunk generation or wrong-region-thread exception during live use
- [ ] Broader long-running coverage of every vanilla structure type
- [ ] Stress testing across rapid world changes and many Folia regions

## Upstream references

- https://github.com/sakura-ryoko/servux/tree/26.2
- https://github.com/sakura-ryoko/minihud/tree/26.2
