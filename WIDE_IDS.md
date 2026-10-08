# Wide IDs: Cleanroom's registry and ID system

Branch `feature/wide-ids`. Replaces Forge's numbered registries. Not compatible with JEID, REID or NEID; their
worlds are converted once on load (see "Older worlds"). Direction from Rongmario (Cleanroom): no hard limit,
soft-capped at `Integer.MAX_VALUE`; converted worlds need not load on anything else.

This file is the working design. It is updated as the work lands: "Status" at the bottom says what is done.

## The rule

**A numeric ID never leaves the running process.** Saves and network sync use names. Numbers are assigned at
startup, are dense, and may differ between launches. Nothing is written to pin them.

Everything else follows from that. Forge kept numbers stable across sessions (`FML Registries` in `level.dat`,
`BitSet` of used IDs, remap dialogs, dummy entries) because its saves held numbers. Ours don't, so that machinery
goes.

## Registries

One registry per type, as now: blocks, items, biomes, potions, potion types, enchantments, entities, sound
events, recipes, villager professions, data serializers. Each is an **ordered list of names**; an entry's number
is its index. Lookups both ways are array reads.

Numbering, per registry:

1. **Vanilla first, in vanilla's own order.** `minecraft:air` is 0, `minecraft:stone` is 1, speed is potion 1,
   as they always were. Costs nothing, keeps vanilla's hard-coded numbers and debug output meaningful. Nothing
   else depends on it.
2. **Then everything else, sorted by name.** The name carries the mod (`thermalfoundation:ore`), so one mod's
   entries land together without any per-mod range. Per-mod ranges are what Forge's config IDs and `BitSet` did
   and are where conflicts and caps came from; we don't have them.

**Vanilla keeps its exact numbers, not just its order.** Vanilla ids have holes (blocks skip 253 and 254;
potions start at 1), so "vanilla first" means each vanilla name *claims the id vanilla gave it*, and modded
names fill every remaining id from 0 up, in name order. That is what keeps the result dense: a modded block
may sit at 253. Vanilla `ItemBlock`s share their block's id because vanilla registered them that way; that
equality is preserved as part of "vanilla keeps its numbers".

**There is no block-item rule for modded content.** Forge never gave a modded `ItemBlock` its block's id
(modded items start at `MIN_ITEM_ID` = 4096), so nothing depends on it. Trying to enforce it would make a
modded block at id 256 evict vanilla's `iron_shovel` from item id 256. Items are sorted by name like
everything else, and `MIN_ITEM_ID` goes away.

Types are **not** merged: a block and an item can both be 5. They are never looked up in the same table, and
merging would break every `Block.getBlockById`.

Adding a mod shifts every entry sorted after its names. That is fine within the rule (numbers are session-only),
and it means a client must **reconnect** after the server's pack changes; a mod caching numbers across a
server restart without reconnecting breaks, same as caching across sessions.

No `min`/`max`, no availability map, no free-slot search. The only limit is `Integer.MAX_VALUE >> 5`, the
largest value a 5-byte varint carries; it is enforced by the list length and is unreachable in practice.

`getID(value)` / `getValue(id)` stay for mods and return the session number.

## Biomes

**There is no limit on how many biomes exist**, from one mod or in total. The real path is full-width end to
end: `GenLayer.getInts` returns `int[]`, `BiomeProvider` builds `Biome[]`, chunk generators write through
`ChunkBiomes.set(int)`, `Chunk.getBiome` reads full ints, saves are by name (`BiomePalette`/`BiomeIndices`),
and the chunk packet sends varints. **`Chunk.getBiomeIds().set(x, z, id)` is the supported write API.** Mods
that go through `Biome` objects (`world.getBiome(pos)`, their own `BiomeProvider`/`GenLayer`, which is how
Biomes O' Plenty, Traverse and the other generation mods work) never see a limit and need no change.

Today, on Forge, these mods "work together" only up to 256 biomes total; past that the game refuses to start.
That cap is what goes away. Nothing below describes a cap on biomes; it describes one old accessor.

### Direction (Rongmario, 2026-10-07)

> We have to redirect them with ASM, that field/getter needs to go anyway if we're going to be doing 3d biomes.

So: **`Chunk.blockBiomeArray` and `Chunk.getBiomeArray()` / `setBiomeArray()` are removed**, not kept as a
shim, and every mod access to them is **redirected at class load** onto `ChunkBiomes`. A `byte[256]` is one
biome per column; once biomes have a Y axis there is nothing for it to mean, and keeping it would mean
maintaining a fake 2D projection of 3D data forever.

This supersedes the earlier approach (a live low-byte view plus a `resolve()` that guessed what a byte write
meant from the chunk and the provider). That code is on the branch and tested; it is **removed** in the commit
that lands the redirect. Nothing below depends on it.

### The accessor being removed

`byte[256]`, one byte per column, index `z << 4 | x`, holding the biome's **number** (never the name). Only mods
that bypass the `Biome` API touch it; the audit found generators and a few decorators, in three patterns:

1. A chunk generator fills it: `array[i] = (byte) Biome.getIdForBiome(b)`.
2. A feature or decorator reads it: `if (array[i] == (byte) desertId)`.
3. Something changes biomes: reads the array, overwrites cells with `(byte) newId`.

The method could not simply return `int[]`: those mods' bytecode has `BASTORE`/`BALOAD` (byte-array store/load)
baked in and would throw. Hence the redirect.

### The ASM redirect (to build; replaces the accessor)

Generic: one transformer, every class, no per-mod code. REID did the same job with 49 hand-written mixins for
31 mods.

**Match** `INVOKEVIRTUAL net/minecraft/world/chunk/Chunk.getBiomeArray()[B` by **descriptor, not concrete
owner**, so calls through `EmptyChunk` or a `Chunk`-typed field are covered. Likewise `setBiomeArray([B)V`.
Track the returned reference through the method (stack slot or local variable).

**Rewrite** so the array never materialises:

- The `getBiomeArray()` call itself is replaced by nothing; the chunk reference (its receiver) is what gets
  tracked instead. Where the mod stored the array in a local, that local now holds the `Chunk`.
- `BALOAD` on the tracked reference → `INVOKESTATIC ChunkBiomes.get(Chunk, int index)`, returning the full id
  as an int. Stack effect is the same (array, index → value), so the surrounding code is untouched. The
  mod's own `(byte)` comparisons still work for ids under 256 and are its own limit above that.
- `I2B` + `BASTORE` on the tracked reference → `INVOKESTATIC ChunkBiomes.set(Chunk, int index, int id)`. The
  `I2B` is dropped **unconditionally**: there is no byte to store into, so the full int reaches `set`. Every
  write is exact. A `BASTORE` without an adjacent `I2B` (value came from a `byte` local) still routes to `set`
  with whatever int is on the stack; that is the mod's own truncation, not ours.
- `setBiomeArray(byte[])` → `INVOKESTATIC ChunkBiomes.setAllFromBytes(Chunk, byte[])`: the one place a real
  `byte[]` survives, and it is lossy by definition above 255. Logged once per mod.
- `arraylength` on the tracked reference → `BIPUSH 256` for now (one biome per column). With 3D biomes this
  constant changes with the layout.

No owner map, no fallback to a real `BASTORE`: there is no array, so there is nothing to fall back to.

**What it cannot follow.** A tracked reference that **escapes the method** (passed as an argument, stored in a
field, returned) is a `Chunk` where the callee expects a `byte[]`. The transformer detects the escape and
**fails the class load with a clear error naming the mod and method**, rather than letting a `ClassCastException`
surface at runtime in unrelated code. This is the compat line Rongmario is accepting: mods that hand the array
around need an update. The audit found none in the 32 jars; byte-array users do it inline.

**Cost and risk.**

- One scan per loaded class. Cleanroom already runs mixins, LoliASM and StellarCore transformers; the
  infrastructure and the per-class cost are known quantities.
- Run **late** (after mixins) so the final method bodies are seen.
- Log every rewritten site at debug level, so a surprise is traceable to a class and method.
- Test against a real pack with BoP and Traverse before calling it done; the escape detector is the part most
  likely to need tuning.

### 3D-biome readiness (constraint, not a feature)

3D biomes are a separate project (format, generation, every `getBiome(pos)` ignoring Y today). Rongmario has
given the direction, so `ChunkBiomes` is shaped now so a Y axis can be added without rewriting it:

- Index by `(x, y, z)` internally, with one Y layer today. `get(x, z)` / `set(x, z, id)` become the Y=0 case of
  `get(x, y, z)` / `set(x, y, z, id)`; both signatures exist from the start.
- The save format (`BiomePalette` + `BiomeIndices`) gains a layer count; one layer now. Reading a one-layer
  chunk under a multi-layer build is a no-op upgrade, the same way old byte biomes are read today.
- The chunk packet sends the layer count then the varints. One layer now.
- The ASM redirect's `arraylength → 256` and `index → (x, z)` mapping is the only piece tied to one layer, and
  it is a compat shim for jars that predate Y anyway.

### Missing biomes

A saved biome name that no longer resolves currently falls back to plains (vanilla behaviour). Under the
manifest design that is a retention case like blocks: keep the name, read as plains, write the name back.

## World height (boundary only)

Height is a separate project (`Chunk`'s 16-section array, `int[256]` heightmaps, dozens of `y >> 4`/`< 256`
checks, lighting, `ChunkPrimer`, generators, packets, pathfinding). None of it is ID work. The one thing this
work must get right so height can change later without a format migration: **nothing in the save format
assumes 16 sections or a 0-255 Y range.**

- Sections are a list, each with its own `Y`, written as an **int** (was a byte: -128..127 sections). Readers
  accept the old byte.
- `BlockPalette` is per chunk, not per column. Height does not affect it.
- Heightmaps are already `int[256]`.
- Biomes are per column, 256 entries. 3D biomes (1.18-style) would be a format change regardless; not
  pre-built.

The runtime `Chunk` assumes 16 sections everywhere. That is the height project's problem, and it will not need
to migrate worlds because of us.

`ChunkPrimer` (the scratch buffer a generator fills before the terrain is copied into a `Chunk`) keeps its
16×16×256 layout (`int[65536]`, index `x << 12 | z << 8 | y`). It never touches disk or the network, so it is
not a format concern; its 256 is shared with every chunk generator that writes into it and changes with them
in the height project. The branch widened it from `char[]` to `int[]` for state ids; that part is done.

## Block states

Dense, derived from the block registry: block index, then `getValidStates()` order (already on the branch,
`BlockStateIdMap`; stored in `StateImplementation.runtimeStateId` so lookup is a field read). Unlisted states
(extended states, hand-built actual states) map to their metadata state.

## Saving

Names only.

| Data | Format |
|---|---|
| Chunk blocks | `BlockPalette` (chunk-wide list of `namespace:name[prop=value,...]`), per-section `Palette` (indices into it) + `BlockStates` (packed indices). Already on the branch. |
| Chunk biomes | `BiomePalette` + `BiomeIndices`. Already on the branch. |
| Item damage, enchantment `id`, potion `Id` | `int` tags. Byte/short/int tags compare equal by value (`NBTPrimitive`), so a short from a mod or an older save is the same tag. |
| Enderman carried block | `carriedBlockState` by name. |
| Tile entities, entities | Already by name in vanilla. Any Cleanroom patch that saves a number gets the same treatment. |
| `level.dat` | Only the `cleanroom_ids.Format` marker. **`FML Registries` is no longer written.** |

A world saved in this format does not load on Forge, older Cleanroom, or any ID mod. The format marker makes
older Cleanroom refuse it with a clear message; nothing stops Forge or an ID mod from misreading it.

## Network

**Numbers are derived, not sent.** Both sides compute the same ordered list from their own registries (vanilla
order, then sorted by name), so every varint agrees for the connection without any mapping message. Login
sends one **hash per registry** (the ordered names) to verify the lists match; a mismatch means the mod sets
differ, which the mod-list handshake reports anyway. This replaces Forge's `RegistryData` message (name→number
map plus dummied set) entirely.

**Client-only registrations** (entries a client mod adds that the server lacks) would shift the client's
numbers. Protocol: the server sends, per registry, the **name count N and the hash of its N ordered names**.
The client hashes its own first N names. Match, and any entries the client has beyond N are client-only: they
keep their positions after N, so everything the server has agrees on both sides. Mismatch is a real difference
in the mod sets and the connection is refused with the registry named. No dummies.

**Overrides** (two mods registering one name) do not need syncing: an override changes which object is behind
a name, not the order of names, so the hash and the numbers are unaffected. If only one side has the overriding
mod, that is a mod-list mismatch and the mod-list handshake already reports it.

Recipes and data serializers are Forge registries whose numbers go over the wire (`SPacketRecipeBook`,
`CPacketRecipeInfo`, `EntityDataManager`); the same rule applies to them. Statistics already sync by name and
are left alone.

Entity types have two numberings today: vanilla's `EntityList` (a `RegistryNamespaced`) and Forge's
`EntityEntry` registry wrapping it. One should remain, Forge's; `EntityList.getID` delegates to it.
`TileEntity.REGISTRY` is also a `RegistryNamespaced` with internal numbers nobody reads (saves and packets use
the name); it is out of scope and should not be "fixed".

Numbers only have to match per connection, not across sessions. The `cleanroom_ids` network format number
guards the packet layouts (currently 2).

Packets that carry IDs use varints or ints (block states, entity types, potions, enchantments, biomes, items,
`SPacketWindowProperty` values). Chunk-packet biomes are 256 varints.

## Missing content: manifest and retention

**Proposed, not started. Last piece of the work; needs Rongmario's opinion before it is built.**

No placeholder entries. Two situations want different behaviour and placeholders serve neither well:
testing a pack (mods come and go, you don't care what's missing, the world must just open) and a long-running
world (you rarely remove a mod on purpose; missing content is usually a mistake you want to undo). A
placeholder lets you keep playing on a world that has quietly lost content.

### World manifest

Written on save, replaces `FML Registries` in `level.dat`:

- per registry, the list of registered **names** when the world was last saved;
- the mod list (ids and versions).

Chunks already hold names, so nothing else is snapshotted: the names in a chunk *are* its snapshot. Size: tens
of thousands of short strings for a large pack, roughly 1-2 MB compressed, less than one region file. Under
100 KB for a 36-mod world.

On load the manifest is diffed against the registries:

| Situation | Behaviour |
|---|---|
| Nothing missing | Load. Rewrite the manifest. |
| Missing names, world is new or barely played | Load. Log what is missing. Missing blocks read as air, missing items show as unusable. (Testing.) |
| Missing names, world has real playtime | Stop and list the mods and entry counts. Offer "load anyway" (same as above) or go fix the profile. |

"Real playtime": default to the world's `Time` tag in `level.dat` (total ticks, already there). Under one
in-game day (24000 ticks, 20 minutes) is "new or barely played". Overridable per world. No new flag and no
`InhabitedTime` sum needed.

The manifest is also a plain record of what a world was made of: diffing two manifests shows what a mod update
added to or removed from the registries. Free, and useful to pack authors.

Replaces dummies, resuscitation, blocked IDs and the "missing entries" dialog. `MissingMappings` (mod renames)
keeps working on names, which is what it always keyed on.

### Retention rule

**Unresolved names are preserved through load and save.** This is what makes "load anyway" a genuine undo: put
the mod back (same version or any later version that keeps the names) and everything is where it was, with its
metadata, NBT and tile entity data. The system does not check versions.

- **Chunk blocks:** an unresolved palette entry is kept in the section palette. In memory the cell reads as air.
  On save, any cell still pointing at it writes the original name. Cost: one palette entry per missing state per
  section. Untouched and unmodified chunks are never rewritten anyway.
- **Items:** a stack whose item does not resolve stays in the slot as its original NBT. It can be moved, stored
  and dropped (the entity keeps the NBT); it cannot be used, crafted with or placed.
- **Tile entities and entities:** their NBT stays with the chunk, not instantiated, until the type resolves again.

The only loss is a real player action taken while the mod was out, e.g. placing a block into a cell that read as
air. That should stick.

### What the player sees

- **Blocks:** nothing. A missing block is air; there is no block object to render. Deliberate: a visible
  placeholder block could be broken, and breaking it loses the name. Air cannot be broken. The cost is that a
  missing block and a real air block look the same; the fix is an F3 / debug readout
  (`missing: thermalfoundation:ore[type=copper]`), not a block.
- **Items:** the standard missing-texture square (no `Item` means no model), with the saved name as the tooltip
  (`thermalfoundation:material`, damage, and the mod id on a second line so you know which jar to restore).
- **Tile entities, entities:** nothing; see above.

The only purple the player ever sees is in an inventory slot, and it always says what it is.

## Older worlds

Converted when first loaded; chunks are rewritten as they save. No automatic zip: the Forge backup prompt on
registry change still exists, and the log says the world won't load elsewhere afterwards.

| Source | Detection | Blocks | Biomes |
|---|---|---|---|
| Forge / older Cleanroom | no `BlockPalette` | `Blocks`/`Add`/`Data` via the legacy `id << 4 \| meta` map | `Biomes` bytes |
| JEID / REID | section has int-array `Palette` + `Blocks`/`Data` | `Blocks`/`Data` are 12-bit indices into `Palette` of `id << 4 \| meta` | `Biomes` int array |
| NEID | section has `Blocks16` | big-endian 16-bit id per cell + `Data` nibble | `Biomes` bytes |

JEID/REID/NEID readers were built from those mods' source and tested with synthetic sections, not with a real
world from them. NEID is the least certain.

**`FML Registries` on first load.** We stop writing it, but an old world's chunks hold numbers that only that
table decodes. First load **reads** `FML Registries` once to build the legacy `id << 4 | meta` state map (and
the biome byte map) for that world, converts chunks as they save, and does not write the table back. A world
without the table (never Forge) uses the current registries, as now.

The legacy `id << 4 | meta` state map exists only for this conversion.

## Registry internals (replacing Forge's)

- **One registry per type, one manager.** Forge keeps four managers (`ACTIVE`, `VANILLA`, `FROZEN`,
  `STAGING`), each a full copy, and freeze/revert copies entries between them to protect numbers across a world
  load. With nothing to protect, there is one registry per type and a frozen flag. `VANILLA` survives only as
  the boundary for "which entries go first". Biggest deletion in the rewrite; also a startup cost.
- **Callbacks run once, after all registrations, in registry order.** Forge's `addCallback`/`setup()` order
  depends on `BitSet` assignment and has bitten mods (block→item map built before all blocks exist). With
  deterministic numbering the trap is avoidable; the rewrite must not keep it by accident.
- **`MissingMappings` fires once per name, ever.** A remap (`old → new`) is recorded in the world manifest as
  an alias; chunks still holding `old` resolve through it and the event is not asked again. Forge re-asks on
  every world load. **Depends on the manifest**: until that lands, `MissingMappings` keeps Forge's
  every-load behaviour.

## Beyond Forge: further structural changes

Two groups. The first follows from the rule and is cheap now. The second fixes Forge mistakes this rewrite is
the natural moment for, but each widens scope or touches the mod-facing API and is a maintainer decision.

### Follows from the rule

- **Immutable after freeze, really.** Forge's registry is a mutable `BiMap` behind a `frozen` flag enforced by
  runtime checks that mods bypass (`unfreeze()` is public). An ordered-list registry can become an actual
  immutable array at freeze: `getValue(id)` is an array read, and nothing mutates it short of replacing the
  registry. **This one is load-bearing:** if the list could change after freeze, numbers could change
  mid-session and "numbers match per connection" would be false. Do it first.
- **The name is the key, not a field on the entry.** `IForgeRegistryEntry.setRegistryName` stays as the way a
  mod declares its name, but after registration the registry's own index is authoritative and the entry's field
  is not consulted. Removes the "registry name already set" class of errors.
- **One world load path.** Forge branches three ways in `injectSnapshot` (fresh, matching, mismatched). With
  no numbers to reconcile there is one: read manifest, diff, resolve names.

### Forge mistakes to fix here (maintainer decisions)

- **Single registration phase.** Forge fires `RegistryEvent.Register<Block>`, then `<Item>`, then the rest,
  in a fixed order mods must know. With deterministic sorting, registration order does not affect numbering,
  so all registries could be open in one event. Existing per-type events keep firing in order for old mods.
  *API-visible.*
- **Derived indices instead of slave maps.** `getSlaveMap(BLOCK_TO_ITEM)`, `BLOCKSTATE_TO_ID`,
  `ENTITY_CLASS_TO_ENTRY` are untyped maps bolted onto registries by string key. `BlockStateIdMap` on the
  branch is already the better shape: a class that declares which registry it derives from and rebuilds when
  that registry freezes. Generalise it; type-safe and testable.
- **Registries found by key, not by entry class.** `GameRegistry.findRegistry(Class)` is why
  `IForgeRegistryEntry.Impl<T>` carries a `registryType` and custom registries are awkward. Registries already
  have a `ResourceLocation` key; look them up by it. The class lookup stays as a shim.
- **One handshake message.** Mod list, per-registry count+hash, format version, in one packet with one
  rejection reason. Forge's multi-message state machine exists partly because `RegistryData` had to be chunked;
  hashes do not. *Protocol-visible.*

### Left alone, deliberately

`ResourceLocation` as the name type; the event bus and `RegistryEvent` as the registration hook (the content
changes, the shape does not); recipes, ore dictionary, capabilities, loot tables, advancements (name-keyed
already); anything about what a block or item *is*. This is an ID system, not a content system.

## What this removes

- `ForgeRegistry`: `BitSet availabilityMap`, `min`/`max`, free-slot search, `Snapshot` save/load, dummies,
  blocked IDs.
- `RegistryManager`: `FROZEN` and `STAGING`; freeze/revert copying.
- `GameData`: `injectSnapshot`, `processIdRematches`, missing-registry and missing-entry dialogs, the
  `fml.doNotBackup` path in `GuiOldSaveLoadConfirm` for ID changes.
- `FMLHandshakeMessage.RegistryData`: the whole message (name→number map, dummied set). Overrides move to
  the mod-list handshake or are derived the same way.
- `level.dat` `FML Registries`.
- `Chunk.blockBiomeArray`, `getBiomeArray()`, `setBiomeArray()`; `ChunkBiomes.resolve` and
  `GameData.getBiomeIdsWithLowByte` (the shim built before the ASM direction).

## Failure modes

- **Hash mismatch on login:** refused. The disconnect screen names the registry and, from the counts, says
  whether the client or the server has more entries. Not a silent kick.
- **Corrupt or unparseable manifest:** treated as "no manifest": load, warn, rewrite it on save. Never refuse a
  world over the manifest; vanilla's `level.dat_old` is still there if it mattered.
- **Registration after freeze:** hard error naming the mod, as Forge. The immutable array makes it impossible
  to silently corrupt the numbering, which is why the error exists.
- **Two mods register one name:** Forge's override rules survive. The later registration replaces the earlier
  only if it opts in (an override); otherwise it is an error naming both mods. Overrides do not affect numbering
  (see Network).
- **Sort collisions:** impossible; names are unique keys. `ResourceLocation` lowercases, so two names differing
  only in case are one name, as today.
- **A mod stores a number** (see Compatibility for mods): breaks on the next launch with a different pack. We
  cannot detect it; the manifest diff makes the symptom (a block or item that is "wrong") attributable.

## Testing the rewrite

Unit tests cover the formats (section palette, biome palette, legacy readers, NBT equality, packets). The
rewrite needs three kinds that do not exist yet:

- **Golden worlds.** Small Forge, JEID and NEID worlds as fixtures in `wideids-testmod`; each converted in a
  test and its per-chunk dump (`wideids.dump`, already in the harness) compared to a recorded one. The Forge
  fixture can be cut from `creative`. JEID and NEID fixtures need someone with those installs; a real NEID world
  retires the "least certain" caveat.
- **Two-sided network.** Server and client in-process with the same mods: hashes agree, a block placed on one
  side has the same name on the other. Then with one client-only mod: still connects, server entries keep their
  numbers. Then with a different mod set: rejected, correct registry named.
- **Mod-shaped tests.** Fake mods, one per row of the compatibility table: saves a number to NBT; caches a
  number across reconnect; writes the biome byte array in each of the three patterns. Each must fail, warn or
  work exactly as the table says. This proves the table rather than asserting it.

## Compatibility line

Mods keep working if they use names, or use numbers only within a session (packets they sync themselves,
in-memory tables). A mod breaks if it:

- stores a registry number across sessions (config, save data), or
- sends a number in its own packet without syncing it, or
- hard-codes a modded number.

Reading an int with `getShort`/`getByte` still wraps past that width; that is the mod's own limit, not ours.

## Compatibility for mods

The in-memory APIs stay and return dense session numbers: `getStateId`, `getBlockById`,
`Potion.getIdFromPotion`, `ExtendedBlockStorage`, `BlockStateContainer.get/set`, registry `getID`/`getValue`.
An audit of 32 jars (the test instance plus HEI/TOP/Hwyla/Forestry/CraftTweaker/IC2/AE2) found the heavy ID
users are lighting, rendering and performance mods using those in-memory APIs; they only need the number to be
stable while running.

| A mod ... | Result | Fix |
|---|---|---|
| saves a registry number to disk (world data, config, NBT) | breaks: the number may differ next launch | save the name; most already do |
| sends a number in its own packet | works: both sides agree per connection | none, unless it caches a number from a previous session |
| hard-codes a modded number | breaks (was already fragile on Forge) | use the name |
| is an ID mod (JEID, REID, NEID) | incompatible on purpose; worlds convert once | none |

## Open questions (for the maintainers)

- Dropping `getID` stability across sessions: acceptable? It is the whole design.
- Any Cleanroom-side saved data that holds a registry number we haven't found.
- Whether the login list should be per registry or one message.
- Derived numbering with a hash check instead of sending a list: any case where the two sides could legitimately
  differ beyond client-only registrations?
- Single registration phase and a one-message handshake: both change what mods and clients see. In or out?
- Biome ASM redirect: decided (redirect, remove the accessor). Remaining: should an escaping array reference
  fail the class load, or load with a warning and let the mod crash later? Failing is proposed.
- The manifest / retention design above, and how to decide "real playtime".
- "Load anyway" keeps unresolved names (proposed default) vs treats it as a clean removal.

## Status

Done, on the branch (tests pass, full build passes):

- [x] Dense block state numbering (`BlockStateIdMap`), stored in the state.
- [x] Name-based chunk blocks and biomes; legacy reader for Forge-format chunks.
- [x] JEID/REID/NEID section and biome readers (synthetic tests only).
- [x] Int NBT for damage, enchantment and potion IDs; byte/short/int tags equal by value.
- [x] Varint/int packets: entity effect, remove effect, block action, spawn mob, item stacks, window property.
- [x] Enderman carried block by name.
- [x] Registry caps raised to `Integer.MAX_VALUE >> 5`.
- [x] `cleanroom_ids` save format 1 / network format 2; refuses future formats and malformed markers.
- [x] No automatic world zip on conversion.
- [x] Biome byte writes resolve chunk-first, then provider, then lowest with a once-per-mod warning.
      **Superseded** by the ASM redirect (Rongmario, 2026-10-07): removed when the redirect lands.
- [x] Section `Y` saved as an int; old byte still read.

To do:

- [x] Numbering derived at `freeze()`: vanilla names claim vanilla's ids, the rest fill free ids by name;
      `getID`/`getValue`/iterator read dense arrays once frozen (`ForgeRegistry.renumber`). Registration-time
      ids still come from the `BitSet` until freeze.
- [ ] Remove `BitSet`, `min`/`max`, `MIN_ITEM_ID`, snapshots, dummies now that they only seed names.
- [ ] Immutable array after freeze; remove public `unfreeze()`.
- [ ] One manager; drop `FROZEN`/`STAGING` and freeze/revert copying.
- [ ] Derived indices (block states, block→item, entity class→entry) as typed classes rebuilt on freeze.
- [ ] Registry lookup by `ResourceLocation` key; class lookup as a shim.
- [ ] Callbacks once after all registrations, in registry order.
- [ ] Login sync: per-registry count + hash of the ordered names; client-only entries keep positions past N;
      drop `RegistryData`; overrides not synced.
- [ ] Recipes and data serializers on the same numbering; `EntityList.getID` delegates to the Forge registry.
- [ ] Stop writing `FML Registries`; read it once on first load of an old world to build the legacy maps.
- [ ] Audit Cleanroom patches for any other saved numeric registry ID.
- [ ] Metadata as a compatibility view only: `getStateFromMeta`/`getMetaFromState` stay for mods, nothing
      internal uses `id << 4 | meta` except the legacy chunk reader.
- [ ] `/cleanroom ids`: registry sizes and the owning mod per name range. (Optional.)
- [ ] **Biome ASM redirect** (approved direction): remove `blockBiomeArray` / `getBiomeArray` / `setBiomeArray`;
      transformer matching by descriptor, `BALOAD`/`BASTORE`/`arraylength` → `ChunkBiomes.get`/`set`/`256`,
      `I2B` dropped, escape detector failing the class load with the mod named, debug log per site; remove
      `resolve()`, `getBiomeIdsWithLowByte` and their tests; test against a pack with BoP and Traverse.
- [ ] `ChunkBiomes` indexed `(x, y, z)` with one layer; layer count in save and packet.

Last, pending Rongmario's review (see "Missing content"):

- [ ] World manifest in `level.dat` (names per registry, mod list).
- [ ] Load-time diff; "real playtime" from `Time` with a per-world override; remove the missing-entry dialogs.
- [ ] `MissingMappings` once per name via a manifest alias table.
- [ ] Retention: unresolved names kept in section palettes, item NBT and TE/entity NBT through save.
- [ ] Missing-item tooltip and F3 readout for missing blocks.
- [ ] Golden-world fixtures: Forge (from `creative`), JEID, NEID; conversion tests against recorded dumps.
- [ ] Two-sided network test: same mods, client-only mod, different mods.
- [ ] Mod-shaped tests, one per compatibility-table row.
