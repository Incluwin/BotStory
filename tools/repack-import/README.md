# repack-import

Exports the Community Repack's extra maps (the ones the Cosmic map set in `wz/` lacks) into the server's XML dump,
with every NPC, mob and reactor they spawn and their String.wz names. Plan and results: `docs/REPACK_IMPORT_PLAN.md`.

Needs Node (18 or newer); no other packages. Run from the server directory:

```
node tools/repack-import/export.js [--repack <folder with the Repack .wz files>] [--server <server directory>]
node tools/repack-import/export.js --check     # writes nothing; exit 1 when the tree differs from a fresh export
node tools/repack-import/drops.js              # the drop rows changeset for the huntable areas
```

`--repack` defaults to `C:\Users\ganre\Desktop\MapleServer\Community Repack` (the untouched Repack download; the
client's Game Files carry BotStory's own Npc.wz and String.wz patches). The run takes a few seconds and is
deterministic: a second run writes nothing.

| File | What |
|---|---|
| `wz-binary.js` | read-only v83 .wz reader (directory and .img property trees, node order, value types, canvas sizes); ported from `tools/wz-patcher/wzlib/reader.py` by way of the 2026-10-25 town audit |
| `wz-xml.js` | writes a node tree as the server's dump: two-space indent, canvases with width and height but no pixels, `&apos;` escapes; a Repack image the server already has comes out byte-identical to its file |
| `areas.json` | the 20 area groups by map id range, and what happens to their mobs (`hunt`, `none` = scenery, `boss`) |
| `export.js` | the import (below) |
| `export-report.json` | written by `export.js`: source sizes and SHA-256, per-area counts and towns, imported and reused ids, left-out spawns, map and portal scripts the maps call and whether `scripts/` has them |
| `drops.js` | drop rows for the huntable areas' mobs, from the closest stock mob by level |

What `export.js` does:

1. Repack-only maps = images under the Repack's `Map/Map0-9` that `wz/Map.wz/Map` lacks (plus every map already in
   `src/main/resources/mods/repack-maps.txt`, so a rerun keeps them). Each must fall in exactly one `areas.json` range.
2. Maps in a `none` area lose their mob spawns.
3. NPCs, mobs and reactors the maps spawn, and what they link to (`info/link`, a mob's `revive`), are written when the
   server lacks them. A spawn whose template is in neither WZ set is left out of its map.
4. String.wz: Map names into the group `MapFactory.getMapStringName` reads, Mob and Npc entries at the root; an entry
   replaces the one with its id or goes before the first higher id, so existing entries keep their order.
5. `src/main/resources/mods/repack-maps.txt` (the gate's list) and the report.

`/wz/` is in `.gitignore`; add new files under it with `git add -f`.
