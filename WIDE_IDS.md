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

### The ASM redirect (built: `BiomeArrayTransformer`)

**What was built differs from the sketch below in two deliberate ways.** The field is gone, but
`getBiomeArray()` stays as a deprecated method returning a **throwaway snapshot** `byte[256]` registered to its
chunk in a weak identity map, and the transformer rewrites only `BASTORE`/`BALOAD` on arrays that came from
that call (`storeByte`/`loadByte`), dropping an adjacent `I2B`. Reasons: (1) keeping the slot typed `byte[]`
means no stack-map frames or local types change, so `ClassWriter(0)` is enough and transformed classes verify
without `COMPUTE_FRAMES` (which needs class-hierarchy lookups inside a transformer); (2) an array that escapes
the method is a real `byte[]`, so reads through it stay correct and the only loss is the write, instead of a
`ClassCastException` in unrelated code. `resolve()` survives as the fallback inside `storeByte` for a write
whose `I2B` was not adjacent (the value arrives as a byte); it is no longer on `Chunk`. The accessor is matched
by name and descriptor, not owner: javac emits the receiver's static type, so a `Chunk`-subclass-typed call
would be missed by an owner check, and a false positive on an unrelated `getBiomeArray()[B` is harmless
(`storeByte` falls back to a plain store for an unknown array). Verified by a javac-compiled fixture covering
fill, read-modify-write, non-adjacent `I2B`, read-only, escaped-array and untracked-store patterns.

Original sketch, kept for the reasoning:

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
retention design that is a case like blocks: keep the name, read as plains, write the name back.

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
| Potion effects (`ActiveEffects`, `CustomPotionEffects`), beacon `Primary`/`Secondary` | Were still numeric after the int widening (range fixed, stability not: a modded potion's number changes when the mod set does). Now write the registry `Name` beside `Id` and read the name first. **A name that is present but does not resolve is "missing" (null), never the id**: a shifted id would rebind to a different potion, which is what the name exists to prevent. The id path is only for compounds with no name (old saves, mod-built tags). |
| Enchantment lists (`ench`, `StoredEnchantments`) | Same: `name` beside `id`, read through one `EnchantmentHelper.enchantmentFrom`, same missing rule. Caveat: a mod that builds an `ench` compound by hand (no `name`) yields a stack that no longer equals ours by NBT; vanilla had the same property and mods use `addEnchantment`/`setEnchantments`, which write the name. |
| Tile entities, entities | Already by name in vanilla. `TileEntityPiston` no longer writes `blockId`/`blockData` (its `blockState` by name is what is read; the old tags are still read from old saves). Villagers already write `ProfessionName`. |
| `level.dat` | Only the `cleanroom_ids.Format` marker. **`FML Registries` is no longer written.** |

A world saved in this format does not load on Forge, older Cleanroom, or any ID mod. The format marker makes
older Cleanroom refuse it with a clear message; nothing stops Forge or an ID mod from misreading it.

## Network

**Numbers are derived, not sent.** Both sides compute the same ordered list from their own registries (vanilla
order, then sorted by name), so every varint agrees for the connection without any mapping message. Login
sends one **hash per registry** (the ordered names) to verify the lists match; a mismatch means the mod sets
differ, which the mod-list handshake reports anyway. This replaces Forge's `RegistryData` message (name→number
map plus dummied set) entirely.

**The server's numbering is authoritative; the hash is the fast path.** The server sends, per synced
registry, the **name count N and a hash of its N ordered names** (SHA-256 over each name's UTF-8 bytes
followed by a zero byte, in id order; first 8 bytes as a long). The client hashes its own first N names in
the same registry. Match (the normal case, identical mods): numbers already agree and nothing is sent or
changed. No dummies, no `injectSnapshot` on connect.

**On mismatch the client asks for the server's name list** for that registry (one extra round-trip, only when
something differs) and reconciles against it:
- Names the server has that the client lacks: **refused**, since the client cannot represent them. The
  reason names the owning **mod** per namespace (missing mod / different version / same version but
  different content); the exact names stay in the log.
- Names the client has that the server lacks (an entry a client-side mod registers: minimap markers, camera
  mods, debug tools): the client **renumbers that registry to the server's order and appends its own extras
  after the server's block**, so every id the server uses means the same thing on both sides and the extras
  stay usable client-side. Forge dummied these; this keeps them.
- A registry the server does not send at all is client-only and ignored.
The client reverts to its own derived numbering on disconnect, as it must (the next server may differ).

*Status:* built. `RegistryNamesRequest` (client→server) and `RegistryNames` (server→client, split at
512 KiB so no chunk needs the payload multiplexer) carry the list only on mismatch; the client reconciles on
its render thread (`ForgeRegistry.adoptOrder`), fires `FMLModIdMappingEvent` so caches rebuild, and its own
derived numbering returns on disconnect through the existing `revertToFrozen`. A registry absent on the
client reports every server entry as missing. The client state flow could not be driven in the unit harness
(`TestNetworkHandshake` is disabled upstream); `reconcile`/`adoptOrder` are tested directly.

Verified live against a dev server + dev client (`wideids-testmod`, 2026-10-13): the hash is over the
**ordered** names, but numbering is derived (vanilla pinned, the rest sorted by `ResourceLocation`
string), so the same name set hashes identically regardless of registration order — reversing
registration cannot trip the handshake. A client-only extra name that sorts inside the server's range
(e.g. `b60` before `b7`) shifts the derived prefix, the hash reports "2 of 11 registries differ", the
client adopts the server's order and resolves the server's state ids to the same names (placed
`wideids:b7` probed as `wideids:b7`); extras are appended after the server's block. A client missing a
server name is refused: the shown reason names the **mod** that owns the names (`Wide IDs Test Mod 0.1.0:
the server registers 2 entries your copy doesn't (wideids:b59, wideids:b59) — check its config`), one
line per mod, most entries first; the full per-registry name list stays in the log at INFO.

**The Cleanroom network format is checked before any registry comparison.** Both sides advertise their
format as the `cleanroom_ids` version in the FML mod list. On a difference the client shows
`This server runs Cleanroom <server version> (network format X); you are running Cleanroom <client
version> (format Y). Update the <older side>.` and the server kicks with the mirror text. A client whose
mod list has no `cleanroom_ids` entry at all (vanilla, Forge, pre-format Cleanroom, FML protocol <3) gets
`This server runs Cleanroom <version> with wide IDs; your game is too old to join` as a plain-text
disconnect. `-Dcleanroom.ids.networkFormatOverride=<n>` makes a dev run advertise `<n>` instead — test
only, never set it on a real install.

**Overrides** (two mods registering one name) do not need syncing: an override changes which object is behind
a name, not the order of names, so the hash and the numbers are unaffected. If only one side has the overriding
mod, that is a mod-list mismatch and the mod-list handshake already reports it.

Recipes and data serializers are Forge registries whose numbers go over the wire (`SPacketRecipeBook`,
`CPacketRecipeInfo`, `EntityDataManager`); the same rule applies to them. Statistics already sync by name and
are left alone. A registry with no non-`minecraft` entries need not be sent at all (its content is known);
Fabric makes the same cut. Not done yet; a small refinement of `RegistrySync.local`.

There is one numbering per type already: vanilla's `Block.REGISTRY`, `Item.REGISTRY` etc. are
`NamespacedWrapper`s whose `getIDForObject`/`getObjectById` forward to the `ForgeRegistry`, and
`EntityList.getID`/`getClassFromID` read the Forge entity registry directly. Derived numbering therefore
reaches every vanilla lookup with no extra wiring (verified). `TileEntity.REGISTRY` is a plain
`RegistryNamespaced` with internal numbers nobody reads (saves and packets use the name); out of scope.

Numbers only have to match per connection, not across sessions. The `cleanroom_ids` network format number
guards the packet layouts and the handshake (3 once the hash handshake lands; 2 before).

Packets that carry IDs use varints or ints (block states, entity types, potions, enchantments, biomes, items,
`SPacketWindowProperty` values). Chunk-packet biomes are 256 varints.

## `level.dat` and missing content

Revised after Rongmario's review (2026-10-08). Two things that were tangled are now separate: what the **ID
system** needs from `level.dat` (almost nothing), and a **missing-content** feature that this work unblocks but
does not depend on.

### What the ID system does to `level.dat`

Not a new layout. One compound goes: `FML Registries` (Forge's name→number table under `FML`), because
nothing reads numbers from a save anymore. The `cleanroom_ids.Format` marker already on the branch stays.
Everything else in `level.dat` is untouched. On first load of an old world the table is read once to decode
chunks still holding numbers (see "Older worlds"), then not written back.

That is the whole `level.dat` change. Dummies, blocked ids, snapshots, `FROZEN`/`STAGING` and
`injectSnapshot` exist to reconcile saved numbers; with none saved, they go.

### Missing content (separate feature, opt-in retention)

With saves by name, a mod's removal leaves names that do not resolve: a block reads as air, an item has no
`Item`, a tile entity or entity is not instantiated. No placeholder entries. What happens to those names is
the user's choice, made on a screen like Forge's missing-entries screen:

- **Retain**: unresolved names are preserved through load and save so the content comes back if the mod does
  (same version or any later version that keeps the names). Chunk palettes keep the unresolved entry (one
  palette slot per missing state per section; the cell reads as air in memory and writes the original name on
  save), items keep their NBT in the slot (movable, not usable), tile-entity and entity NBT stays with the
  chunk uninstantiated. The only loss is a real player action taken meanwhile, e.g. placing a block where one
  read as air.
- **Remove**: a clean removal, as today.

Retention state lives in **its own `.dat` beside `level.dat`**, not in `level.dat`: it records, per registry,
the registered names at last save **excluding `minecraft:`** (vanilla cannot go missing) and the mod list, so
the screen can say what is missing and from which mod. `level.dat` stays as it was. A missing or unparseable
retention file means "no information": load normally, write it on save, never refuse a world over it.

No tooltip, no F3 readout: there is nothing to describe, since a missing block *is* air and a missing item has
no `Item` to render. The retention file holds the names if anyone later wants to surface them.

**Retention mechanism (built; the Retain/Remove screen is not, so the current default is retain and log once
per missing name).** `RetainedContent`, one per chunk. Blocks: a palette string whose block is not registered
reads as air and is recorded per cell; on save a cell that still reads as air writes the string back verbatim
(the chunk palette is already strings, so the format does not change), and `ExtendedBlockStorage.set` drops
the name for any cell written in the meantime. Biomes: an unresolved column name is kept beside the live id;
`resolveUnset` fills the live id from the provider for gameplay without touching the name, `set` clears it,
and save writes the kept name. Tile entities and entities with an unknown id keep their compound and are
written back if their position is still air / their UUID is not live. Items: an `ItemStack` whose item is
not registered would be `EMPTY`, and every slot, hopper and shift-click treats `EMPTY` as nothing, so an
unknown stack becomes **one internal item, `cleanroom:missing_item`**, carrying the original compound
verbatim under `CleanroomRetained`. It sits in the slot, moves, stores and drops, renders as the missing
texture, cannot be used, placed or crafted, and stacks only with an identical original. `writeToNBT`
writes the original compound back (with the live count), so the save is unchanged and the real item returns
with its damage and NBT when the mod does. One sink, hidden from creative tabs, never a per-name placeholder.
**Stand-ins.** A retained block reads as air by default, and for a biome mod's ground blocks (`grass[variant=loamy]`,
`dirt[variant=loamy]`, seen live) that is a hole, so `StandIns` gives a retained cell a vanilla *reading*: by
substring of the block path, leaves → oak leaves with decay pinned off, log/wood → oak log, planks/stairs/slab/
fence → oak equivalents, grass → grass block, dirt/mud → dirt, sand, sandstone, gravel, ore/stone/rock → stone;
same-named properties (`axis`, `facing`, `half`...) carry over. Plants, flowers, vines, saplings, mushrooms and
fluids stay air on purpose: a vanilla plant pops off at the next update and a fluid flows, both writes that
would erase the name. The chunk remembers which stand-in it put in each cell and writes the saved name back
only while the cell still holds that exact state; a player placing even the same block goes through `set()`
and wins. Overrides in `config/cleanroom/retained_standins.cfg` (`substring = block[state]` or `= air`). A
stand-in oak log looks like an oak log; the once-per-block log line is the indicator.
Biomes get the same treatment: a retained column read as whatever the *current* provider said there (vanilla's
layout for the seed, unrelated to the terrain), so swamp-shaped land could be tinted desert. `forMissingBiome`
picks a vanilla biome from substrings of the name (bayou/marsh/fen → Swampland, rainforest → Jungle, alps/
tundra → Ice Plains, outback → Mesa, ominous/dark/haunted → Roofed Forest, redwood/fir → Taiga, cherry/grove →
Forest, orchard/meadow → Plains...), falls back to the provider when nothing matches, and `biome:` lines in the
same config override it (`biome:ominous_woods = minecraft:swampland`, `= none` for the provider). Reading only;
the name is kept and the real biome returns with the mod.
World type: vanilla turns an unknown `generatorName` into Default *and saves it*, so a BoP world opened once
without BoP generates vanilla terrain forever (seen live). `WorldInfo` keeps the unknown name and version and
writes them back while the type is still Default; choosing a type on purpose (`setTerrainType`) drops them.

**What that means in play**, for a biome mod removed and later restored:
- Existing terrain keeps its shape; the mod's own blocks (logs, leaves, plants) read as air; the mod's
  biomes get a vanilla stand-in from the current provider for tint, fog and spawns, with colour edges where
  the two layouts disagree. Nothing is regenerated or re-decorated.
- Put the mod back and every untouched cell and column returns exactly; the edges vanish.
- **Last write wins per cell.** A chest placed where a log was stays a chest; the rest of the tree returns
  around it. A player action is real; returning content fills in only what nobody touched.
- Chunks first generated while the mod was out are vanilla terrain (there was never a name to keep) and meet
  returning content at a cliff. Forge behaves the same. The only fix is regenerating them, which is safe
  exactly for the chunks nobody touched: while the world's real type is missing (`WorldInfo` holds the
  retained generator name), every chunk generated gets a `GeneratedWithoutWorldType` mark naming the type,
  and any write outside a population window (players, mobs, fire, growth) clears it; `checkLight()` raises
  `isTerrainPopulated` before the generator plants trees, so population is recognised by Forge's cascading
  marker, not the flag. Marked chunks are indexed per world type and dimension in the retention file as
  they save; when the type is back and the world runs it, load asks once ("N chunks of the overworld were
  generated with vanilla terrain while X was missing": Regenerate / Keep). Regenerate backs the world up,
  then zeroes those chunks' region-file entries so X generates them on the next visit; Keep is remembered.
  A dedicated server keeps unless `-Dcleanroom.standInTerrain=regenerate`. Marked chunks that were built in
  are never regenerated, so the only remaining seams are around things players made.
- So retention is for the *mistake* case (a jar that did not copy, an update that renamed things, a
  dependency that failed), which is also the common case on a long-running world. Someone who means to keep
  playing without the mod picks Remove on the screen. Testers who add and remove mods constantly will see the
  odd case (a tree restored around a chest) and should read it as the rule working, not a bug.

Vanilla and Forge lose this content outright: `Chunk.getBiome` resolves an unknown column through the current
provider *and stores it*, and a missing block is saved as air, so chunks touched while a mod is out are
rewritten for good and re-adding the mod leaves hard-edged patches. Forge's reserved ids only stop a different
mod's content from appearing in those cells.

Dropped from this design: the "real playtime" heuristic (the screen makes it an explicit choice, so no
threshold is needed) and the `MissingMappings` alias table (an optimisation so a mod's rename answer is
recorded once instead of re-asked each load; not required, revisit separately if ever).

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

**The legacy id table (`LegacyIds`, built).** An old world's unconverted chunks and NBT hold the Forge-era
numbers recorded in `level.dat` `FML` → `Registries`. Under derived numbering a modded entry's live id differs
from its saved id, so every legacy numeric read goes through a per-world table built from that tag on load:
chunk sections (`id << 4 | meta`), legacy and JEID biome arrays, potion `Id`, enchantment `id`, beacon
`Primary`/`Secondary`, piston `blockId`, enderman `carried`. Saved id → saved name → live entry by name; an
id the table lacks falls back to the live id (vanilla keeps its ids; a never-Forge world has no table); a name
the table knows that no longer resolves is missing, never the number. The table is **preserved verbatim**
across saves (written back under the same `Registries` key, never regenerated from live ids) because chunks
convert lazily and the table must outlive the last unconverted one. A new world writes no `Registries`. One
table per server process; dimensions share `level.dat`; cleared on server stop.

The old `BLOCKSTATE_TO_LEGACY_ID` slave map and the legacy chunk *writer* are gone; nothing writes the
`Blocks`/`Add`/`Data` format.

## Registry internals (replacing Forge's)

- **One registry per type, one manager.** Forge keeps four managers (`ACTIVE`, `VANILLA`, `FROZEN`,
  `STAGING`), each a full copy, and freeze/revert copies entries between them to protect numbers across a world
  load. With nothing to protect, there is one registry per type and a frozen flag. `VANILLA` survives only as
  the boundary for "which entries go first". Biggest deletion in the rewrite; also a startup cost.
- **Callbacks run once, after all registrations, in registry order.** Forge's `addCallback`/`setup()` order
  depends on `BitSet` assignment and has bitten mods (block→item map built before all blocks exist). With
  deterministic numbering the trap is avoidable; the rewrite must not keep it by accident.
- **`MissingMappings` keeps Forge's behaviour** (fires on each world load for a name a mod renamed). Recording
  the answer once was considered and dropped from this design as an optimisation; revisit separately if ever.

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
  no numbers to reconcile there is one: resolve names; the retention feature, if present, reports what did not.

### Forge mistakes to fix here (maintainer decisions)

- **Single registration phase.** Forge fires `RegistryEvent.Register<Block>`, then `<Item>`, then the rest,
  in a fixed order mods must know. With deterministic sorting, registration order does not affect numbering,
  so all registries could be open in one event. Existing per-type events keep firing in order for old mods.
  *API-visible.* Done: `RegistryEvent.RegisterAll` fires once, before `Register<Block>`, with every registry
  open (`get(key)`, `get(Class)`, `keys()`); the per-type events follow unchanged.
- **Derived indices instead of slave maps.** `getSlaveMap(BLOCK_TO_ITEM)`, `BLOCKSTATE_TO_ID`,
  `ENTITY_CLASS_TO_ENTRY` are untyped maps bolted onto registries by string key. `BlockStateIdMap` on the
  branch is already the better shape: a class that declares which registry it derives from and rebuilds when
  that registry freezes. Generalise it; type-safe and testable.
- **Registries found by key, not by entry class.** `GameRegistry.findRegistry(Class)` is why
  `IForgeRegistryEntry.Impl<T>` carries a `registryType` and custom registries are awkward. Registries already
  have a `ResourceLocation` key; look them up by it. The class lookup stays as a shim.
- **One handshake message.** Mod list, per-registry count+hash, format version, in one packet with one
  rejection reason. Forge's multi-message state machine exists partly because `RegistryData` had to be chunked;
  hashes do not. *Protocol-visible.* Done: the server's `ModList` carries the registry hashes (handshake
  protocol 3, `cleanroom_ids` network format 4); the client answers with one ack, one name-list request, or
  one refusal whose reason covers mods and registries together. `RegistryHashes` and the client's
  `WAITINGSERVERCOMPLETE` state are gone.

### Left alone, deliberately

`ResourceLocation` as the name type; the event bus and `RegistryEvent` as the registration hook (the content
changes, the shape does not); recipes, capabilities, loot tables, advancements (name-keyed already); anything
about what a block or item *is*. This is an ID system, not a content system. **Ore dictionary** is out too:
it belongs with the tag system Rongmario is working out, not here.

## Code shape: when something gets its own class

`com.cleanroommc.ids` holds components that own **state or an algorithm** and have their own tests:
`ChunkBiomes` (a chunk's biome storage), `SectionBlockFormat` (the chunk save format), `EntityIds` (the
instance-id counter), and in `module/forge` `RegistrySync` and `BiomeArrayTransformer`. Pulling these out of
the 1,000-line vanilla classes they serve keeps the patch tree reviewable. `LegacyIdWorlds` and
`PackedItems` are compatibility bridges kept separate so they are easy to find and delete.

**A new class needs to own state, or an algorithm with more than one caller or more than one case to test.**
A one-method wrapper around arithmetic goes `private static` next to its single caller. Testability alone is
not a reason: it bends the test to the code. Two such wrappers (`ItemModelKey`, the last method of `WideNbt`)
were folded back into their callers; the test that remained exercises the real reader end to end instead.

## Prior art, compared

Checked against modern Minecraft, Fabric, NeoForge, Bedrock and the 1.12 ID mods, to see what they got right
and whether any of it beats what is here. Two small borrowings; nothing structural.

| System | What it does | Versus this design |
|---|---|---|
| **Vanilla 1.13+** | Per-state global ids, dense, derived from registry order; chunk palettes by name; 1.16 indices don't span longs | Same three choices (`BlockStateIdMap`, `SectionBlockFormat`). Vanilla has no notion of a *missing modded* entry; that hole is what the retention feature fills. |
| **Fabric registry sync** | Server sends full name→id maps; the client **remaps itself** to the server's ids (client-only entries hidden, server-only entries throw); three callbacks exist so mod caches survive the remap | Derived numbering removes the category: ids never change within a session, so no remap, no callbacks, one hash per registry instead of maps. **Taken:** Fabric skips registries with no modded entries (`MODDED` attribute); a vanilla-only registry has known content. Do the same. |
| **Fabric PR #5503 (2025)** | Warning / backup screen when opening a world with missing content registry entries; details page listing them; only selected registries, only non-`minecraft` entries | The retention design, arrived at independently. **Taken:** exclude the `minecraft:` namespace from the retention file; vanilla cannot go missing, so recording it is pure size. |
| **NeoForge** | Still full frozen snapshots per registry; client reverts on disconnect. PR #372: a full-snapshot revert replaced intrusive holders and broke worldgen; fixed by id-only snapshots | The client here never changes state on connect, so there is no revert and no such class of bug. Nothing to take. |
| **Bedrock** | Block network id = FNV1a-32 of the canonical state NBT: no id sync at all. Costs 5 bytes per id on the wire vs 1–2, admits collisions (`unknown` = -2 special case); PocketMine declined it for bandwidth | Same goal (no id sync at connect), different route: dense ids plus one verification hash, 1–2 byte ids, no collision risk. Not adopting. |
| **REID / JEID** | 1.13 chunk format in 1.12; int biome array; 49 per-mod mixins; since v2.3 a `BiomeApi.updateBiome(chunk, pos, id)` so mods stop casting internals | Same format; the 49 mixins become one generic transformer; `getBiomeIds().set` is the API. REID handles CubicChunks' 3D biomes by y-level: CubicChunks is a real consumer of the `(x, y, z)` biome API, which supports the readiness constraint. |
| **NEID** | `Blocks16`: 16-bit block ids | A 65535 cap. Nothing to take. |
| **GTNH NotEnoughIDs / EndlessIDs (1.7.10)** | Mixin coremods that widen every field in place: blocks/items to 2^24, biomes/potions/DataWatcher to 65,536, metadata to 16 bits; chunk NBT pre-processed to their layout at load. Mods that touch a widened field need a per-mod patch; EndlessIDs crashes with a link to its repo when a worldgen mod calls an old method. (Angelica is GTNH's OptiFine replacement and lwjgl3ify the modern-Java layer; neither touches IDs.) | Still limits, 24-bit because that is what their chunk layout holds; a name-based chunk has no width to run out of. Their multiplayer bug (PR #9: a packet byte count lost half a byte after the metadata widening, dedicated server only) is the class of bug field-widening produces and name-based formats cannot. Their biome-mutation trap (vanilla 1.7 mutations at `id + 128`; their offset-reservation system was disabled as unstable) does not exist in 1.12, which uses `Biome.getMutationForBiome` by object, but it motivates an audit for numeric-offset conventions. On unpatchable mods, three teams chose three answers: EndlessIDs crashes, REID hand-patches 49 mods, this design degrades to a warning and fixes the common form generically with ASM. Nothing to adopt. |

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
- **Corrupt or unparseable retention `.dat`:** treated as absent: load, warn, rewrite it on save. Never refuse a
  world over it; `level.dat` is untouched by the feature.
- **Registration after freeze:** hard error naming the mod, as Forge. The immutable array makes it impossible
  to silently corrupt the numbering, which is why the error exists.
- **Two mods register one name:** Forge's override rules survive. The later registration replaces the earlier
  only if it opts in (an override); otherwise it is an error naming both mods. Overrides do not affect numbering
  (see Network).
- **Sort collisions:** impossible; names are unique keys. `ResourceLocation` lowercases, so two names differing
  only in case are one name, as today.
- **A mod stores a number** (see Compatibility for mods): breaks on the next launch with a different pack. We
  cannot detect it; the retention file's mod list makes the symptom (a block or item that is "wrong")
  attributable.

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

## Known limitations

- **McRegion → Anvil conversion** (`ChunkLoader`, pre-1.2 worlds) still writes 8-bit block and biome ids
  and carries no `Add` array; a McRegion-era world holding block or biome ids past 255 loses the high
  bits on conversion. Reachable only through the legacy converter, never in normal play.
- **`SPacketEntityEffect.getEffectId()`** returns the id's low byte for compatibility; the wire carries
  the full id as a varint and the client reads `getFullEffectId()`. Mods must call `getFullEffectId()`
  for potion ids past 255.

## Open questions (for the maintainers)

Grouped by what each gates.

**The premise (built; needs an explicit yes before merge).** `getID` no longer returns the same number
across sessions; a mod that saved a registry number to disk breaks. Phases 1-3 are built and tested on this.

**`level.dat` and missing content: answered (2026-10-08).** Rongmario: the load-time diff is not an ID
concern; retention goes in a separate `.dat` with a popup like the missing-entries screen asking whether to
retain; no tooltip/F3. Adopted as written in "`level.dat` and missing content". Dropping `FML Registries`
(the only `level.dat` change the ID system needs) is therefore unblocked, and with it `FROZEN`/`STAGING`,
dummies, blocked ids, snapshots. The retention feature itself is separate work.

**API-visible changes, in or out (improvements over Forge, but mods/clients see them).**
- Single registration phase: every registry open in one event, instead of block → item → rest.
- One handshake message: mod list, per-registry hashes and format version together, instead of the state
  machine.

**Modifiable registries renumbering mid-session (found in review).** Recipes are `allowModification()`:
CraftTweaker's `/ct reload` or any runtime recipe removal does `unfreeze()` → change → `freeze()`, which
renumbers densely, so removing one recipe shifts every later recipe id for every connected client until they
reconnect. Forge left a hole, so only the removed id was affected. Options: (a) pin numbering for modifiable
registries once the server is running and append new entries (holes for removals; breaks the dense invariant,
the hash and `getRaw` would need hole handling); (b) accept it and document "reconnect after a recipe
reload", which is what 1.12 CraftTweaker users already do (`/ct reload` has never updated connected
clients' recipe books reliably). Proposed: **(b)**, revisit if a reload path that updates clients in place
appears.

**Hash handshake edge: resolved in design (2026-10-08).** The server's numbering is authoritative. On
mismatch the client fetches the server's name list and renumbers to it, appending its own extras; only names
the server has and the client lacks are a refusal. Not yet built.

Resolved since first written: the login message is one `RegistryHashes`; the biome array stays a real
`byte[]` snapshot so an escape loses the write rather than failing the class load; the saved-number audit found
and fixed potion/beacon/enchantment ids.

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
- [x] `BitSet`, `min`/`max`, every `MAX_*_ID`/`MIN_ITEM_ID` removed; `setIDRange`/`setMaxID` are deprecated
      no-ops. 70,000 entries register and number densely.
- [x] Snapshots and dummies removed: `injectSnapshot`, `STAGING`, `DummyFactory`/`isDummied`/`addDummy`,
      `BlockDummyAir`, `DummyRecipe`, `MissingFactory` gone (public setters kept as deprecated no-ops).
      World load is `GameData.loadWorldSnapshot`: `LegacyIds`, then `MissingMappings` per registry with
      REMAP recorded as an alias, FAIL aborting, everything else left to retention. No renumbering.
- [x] `unfreeze()` package-private; recipe `/reload` goes through `GameData.revertRecipesToCode`.
- [x] One manager: `FROZEN`/`VANILLA` deleted. "Revert" is `restoreLocalNumbering()`, a fresh derivation,
      since the numbering is a function of the name set; the vanilla seed ids live in a per-registry
      `vanillaBaseline` map captured at `vanillaSnapshot()`. Net −440 lines of registry code.
- [ ] Derived indices (block states, block→item, entity class→entry) as typed classes rebuilt on freeze.
- [ ] Registry lookup by `ResourceLocation` key; class lookup as a shim.
- [ ] Callbacks once after all registrations, in registry order.
- [x] Login sync: one `RegistryHashes` message (per-registry count + SHA-256 of the ordered names) replaces
      `RegistryData`; the client verifies and never renumbers to the server (`RegistrySync`). Network format 3.
      Interim: a server world holding Forge dummies hashes their names, so no client can join it until
      dummies are removed (the `level.dat` block below).
- [x] Recipes, data serializers and entity types are `ForgeRegistry`s and vanilla's wrappers forward to them,
      so derived numbering already covers every lookup (verified, no change needed).
- [ ] Stop writing `FML Registries`; read it once on first load of an old world to build the legacy maps.
- [ ] `RegistrySync.local`: skip registries with no non-`minecraft` entries.
- [x] Server-authoritative reconciliation on hash mismatch (`RegistryNamesRequest`/`RegistryNames`,
      `ForgeRegistry.adoptOrder`); refusal names the exact missing entries; own numbering returns on disconnect.
- [x] `unfreeze()` is package-private; no live main-code caller existed. The recipe reload goes through
      `GameData.revert`, not `unfreeze` (see the open question on modifiable registries).
- [x] Audit for saved numeric registry ids: found potion effects, beacon effects, enchantment lists and the
      piston's `blockId` (see Saving). Villagers, tile ticks, entities, spawners, items were already names.
- [x] Audit for numeric-offset conventions: clean. `MUTATION_TO_BASE_ID_MAP` is an object map, `GenLayer`s
      compare vanilla ids by equality (vanilla keeps them), `StatList` arrays are registry-sized, creeper record
      drops assume vanilla records are contiguous (they are). One runtime cap found instead:
      `ItemModelMesher` keys models by `itemId << 16 | meta`, which collides past item id 65,535.
- [x] Audit findings fixed: potion/beacon/enchantment names in NBT (unresolvable name = missing, not the id),
      piston `blockId` dropped, `ItemModelMesher` keyed by a long (`ItemModelKey`).
- [ ] Metadata as a compatibility view only: `getStateFromMeta`/`getMetaFromState` stay for mods, nothing
      internal uses `id << 4 | meta` except the legacy chunk reader.
- [ ] `/cleanroom ids`: registry sizes and the owning mod per name range. (Optional.)
- [x] **Biome ASM redirect**: `blockBiomeArray` removed; `getBiomeArray()` is a deprecated registered
      snapshot; `BiomeArrayTransformer` routes tracked `BASTORE`/`BALOAD` to `storeByte`/`loadByte`, drops
      adjacent `I2B`; `resolve()` kept as the byte fallback. Still to do: run against a pack with BoP and
      Traverse and read the transformer's info lines.
- [ ] `ChunkBiomes` indexed `(x, y, z)` with one layer; layer count in save and packet.

`level.dat` (unblocked by Rongmario's answer; ID-system work):

- [x] `LegacyIds`: the saved `Registries` table decodes every legacy numeric read and is preserved verbatim;
      new worlds write none. (Derived numbering had silently broken legacy biome/potion/enchantment decode
      for modded entries; blocks only worked through injectSnapshot's seed ids.)
- [x] Dummies, `injectSnapshot`'s reconciliation, `FROZEN`/`STAGING`/`VANILLA` and `revertToFrozen` removed; one
      registry manager. `Snapshot` remains as the reader of old `level.dat` tables (`LegacyIds`, missing-mapping
      detection); new worlds write none.

Missing content (separate feature; retention is opt-in via a popup, state in its own `.dat`):

- [x] Missing-entries screen (`GuiRetainRemove` via `StartupQuery.choice`): one prompt listing each missing
      mod by display name with per-registry counts (and the world type when it belongs to that mod);
      Retain / Remove / Cancel. One answer applies to all undecided namespaces; a dedicated server retains
      and logs unless `-Dcleanroom.missingContent=remove`. Remove drops the namespace's names on read (air
      without a retained cell, provider biome stored, no TE/entity/item retention, generator reset to
      default) and is remembered; a mod that returns has its decision forgotten.
- [x] Retention `.dat` (`cleanroom_retained.dat`, format 1): `Registries` (blocks, items, biomes, entities,
      tile entities; non-`minecraft` names at last save), `Mods` (modid → name version), `Decisions`,
      `Generator`. Read and written by `IdFormatContainer`; absent or unreadable = no information.
- [x] Retention of blocks, biomes, tile entities and entities (`RetainedContent`); default retain + log once.
- [x] Retention of items: `cleanroom:missing_item` carries the original compound; written back verbatim.
- [ ] Golden-world fixtures: Forge (from `creative`), JEID, NEID; conversion tests against recorded dumps.
- [x] Two-sided network test: same mods, client-only mod, different mods. Dev server + dev client with
      `wideids-testmod`: same name set connects and resolves state ids by name; a client-only extra
      triggers hash-mismatch adoption and still resolves correctly; a missing name is refused with the
      exact entries (see Network).
- [ ] Mod-shaped tests, one per compatibility-table row.
