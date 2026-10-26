# Repack import: the Community Repack's extra areas on the server

Branch `feat/repack-import` from `feat/companion-manager` ff6fbe48a. Source: the town audit of 2026-10-25
(`TOWN_AUDIT.md` 2.1, coordinator session 8751a2d2's scratchpad).

The server's `wz\` XML is the stock Cosmic map set (5,262 maps). The Community Repack client's Map.wz has 579 more, in
20 area groups (40 maps with `town=1`). They never existed on the server, and no portal on either client leads into
them. A client on the Cosmic WZ files (the public installer offers that base) has none of these maps and closes when
it is sent into one.

User decisions (2026-10-25): import everything, Arcane River included; Arcane River is scenery only (its mobs are
lv 200-264, above v83's cap of 200, and its maps call post-v83 systems). The client gate asks each account once which
game files it has (AskUserQuestion, "Ask once per account").

## 1. Export (done, repeatable)

`tools/repack-import/` (Node; README there): `wz-binary.js` reads the binary .wz files (typed, order kept, canvas sizes
kept), `wz-xml.js` writes the server's dump format, `export.js` does the import, `areas.json` names the 20 groups.

```
node tools/repack-import/export.js            # writes into this tree
node tools/repack-import/export.js --check    # exit 1 when the tree differs from a fresh export
```

- Source: the pristine Repack files in `C:\Users\ganre\Desktop\MapleServer\Community Repack\` (not Game Files, whose
  Npc.wz and String.wz carry BotStory's patches). Sizes and SHA-256 are in `tools/repack-import/export-report.json`.
- Writer check: shared images (Henesys, Ludibrium, a mob, an NPC, a reactor) written from the Repack binary are
  byte-identical to the server's existing XML.
- Written: 579 maps (`wz/Map.wz/Map/Map0,2,4,5,8`; Map4 is new), 339 NPCs, 64 mobs, 2 reactors, and their String.wz
  Map/Mob/Npc entries. A map name goes into the String.wz group `MapFactory.getMapStringName` reads (Arcane River:
  `etc`). 28 maps have no name in the Repack either; they get their area's name.
- Changed on the way, all listed in the report:
  - Arcane River (and the unnamed Maple Island ship map): the 5,802 mob spawns are left out and the Arcane mobs are not
    imported, so those maps are scenery.
  - 26 spawns whose template is in neither WZ set (NPCs 1540941-1540944 on the 5th-job maps, reactors 2112018,
    2708001-2708004, 2709001-2709002 in Lion King's Castle and Arcarium's altar) are left out: the server's map load
    would fail on them and the Repack client lacks them too.
- `src/main/resources/mods/repack-maps.txt`: the 579 map ids and their area, which the gate reads.
- `/wz/` is in `.gitignore` as a guard (every base file is tracked), so the new files are added with `git add -f`.
- Size: 984 new files, 53 MiB of XML (about 3 MiB compressed); String.wz grows by about 4,500 lines. The scrub push
  script drops only `tools/client-kit/data`, `video-assets` and media files, so all of it would be pushed; no single
  file is over 0.4 MiB.

| Area (areas.json key) | Maps | Mobs | Towns |
|---|---|---|---|
| Lion King's Castle (lion-king-castle) | 28 | hunt, lv 117-150 | - |
| Coke Town (coke-town) | 19 | hunt, lv 10-70 (mobs and NPCs were already on the server) | 219000000 + 5 houses |
| Chaos Horntail, Chaos Zakum | 1 + 1 | boss maps, not built | - |
| Gate to the Future (future-gate) | 42 | hunt, lv 162-184 | 271000000, Henesys Ruins 271010000 |
| Crack in Time (future-crack) | 25 | hunt, lv 10-166 | 272000000, Ruins in Flames 272000310 |
| Twilight Perion (future-perion) | 20 | hunt, lv 190-198 | 273000000, Tribal Refugee Camp 273050000 |
| Arcane River, 10 groups (arcane-*) | 379 | none (scenery) | Nameless Town, Chu Chu Village, Lachelein, Arcana, Morass, Esfera, Moonbridge, Labyrinth, Limina towns |
| Ulu City (ulu-city) | 30 | hunt, lv 30-115 | - |
| Ninja Castle (ninja-castle) | 33 | hunt, lv 25-120 | Outside Ninja Castle 800040000 |
| Maple Island ship 2010002 (maple-misc) | 1 | none | - |

## 2. Client gate (`server.mods.RepackMaps`)

- Kill switch `mods.SWITCHES.repack_maps` (default on; `MOD_REPACK_MAPS` env, `!mods repack_maps off`). Off, nobody
  enters a listed map (bots included) and the entry NPCs are not spawned.
- What a player's client has, first answer wins:
  1. A Kaentake.dll report (P5 handshake) of an active feature `repack_wz`. No DLL sends it yet: the client patch is
     held. When it ships, it replaces the question.
  2. The account's answer, table `account_client_files` (changeset `48-account-client-files`): `repack` or `cosmic`,
     with where it came from (`player`, `gm`, `crash`).
  3. None: unknown.
- Every warp goes through `Character.changeMapInternal`; a listed map is refused there unless the client is known to be
  Repack (bots always pass while the switch is on). A refusal is a chat line and enableActions, never a warp:
  - cosmic: the place is only in the full Community Repack game files;
  - unknown: talk to the area's guide, or use `@gamefiles repack` once you know which files you installed.
- The question: an entry NPC asks an unknown account once ("full Community Repack set" / "smaller Cosmic set" / "not
  sure", which explains the check: a `base=cosmic` line in the game folder's `config.ini` means Cosmic, no such line
  means the Repack set). `@gamefiles [repack|cosmic]` lets a player answer or change it; `!gamefiles <player>
  repack|cosmic|forget` (GM) sets it for them.
- Safety net: a warp into a listed map made on an account's answer (not a DLL report) is pending until the client's
  PLAYER_MAP_TRANSFER says the field loaded. A disconnect while it is pending marks the account `cosmic` (source
  `crash`).
- Login: a character saved in a listed map whose client is not known to be Repack (or with the switch off) starts in
  its area's way-out map instead (`returnMap` chain to the first map outside the list, else the entry map).

## 3. Entries

The entry NPC stands on a shared map, so it is a stock NPC (both clients have it). It is spawned by `RepackMaps` when
the map loads, only while the switch is on; GMS had a portal or NPC there, which a v83 client cannot be given without a
client patch.

| Area | From | NPC | Way back |
|---|---|---|---|
| Coke Town 219000000 | Henesys 100000000 (Coca-Cola event NPC) | Cokebear operator 9001000 | Cokebear operator in Coke Town |
| Ninja Castle 800040000 | Mushroom Shrine 800000000 (GMS: an NPC at Zipangu) | Perry 9110000 | Perry in Outside Ninja Castle |
| Ulu City 541020000 | Suburban Area 2 540020100 (GMS: a portal top right) | Ralph the wanderer 9270030 | the map's own portal west00 |
| Lion King's Castle 211060000 | Wolf Territory II 211040600 (GMS: a portal) | Spirit of Snow Statue 2020012 | the map's own portal out00 |
| Gate to the Future, Crack in Time, Dimensional Schism, Twilight Perion, Arcane River towns | Three Doors 270000000 | Temple Keeper 2140000 (already there) | the maps' own exits to 270000000; elsewhere a town NPC |

## 4. Scripts, drops

- Imported NPCs without a script talk with their own String.wz lines (one shared script, `npc/repack_talk.js`), so
  none of them is mute and none starts a post-v83 quest. The travel NPCs have their own scripts.
- Missing map scripts are harmless (the server skips a map script it does not have). Portal scripts only matter where
  the walkable part of an area uses them: Ulu City's mini dungeon `MD_longkiss` (Long Kiss Duku Good Night,
  541020620-541020639) goes through `MiniDungeonInfo`. The rest guard boss and quest maps (Cygnus, Arkarium, Von
  Leon): left for the boss work.
- Drops: every mob that spawns in a huntable area gets drop rows (changeset under `db/extensions`), copied from the
  closest stock mob by level that has drops, without quest items and the donor's own monster etc.; generated by
  `tools/repack-import/drops.js`, so it is repeatable. Coke Town's mobs had no rows either.

## 5. The rest of BotStory

- TownLife profiles for the 16 player-facing towns, appended to `agents/town-life/index.json` (generated with
  `tools/town-life-profiles.bat generate`).
- `KnowledgeExportMain.region()`: 219 coke-town, 271-273 future, 450 arcane-river (211 and 541 and 800 already map to
  el-nath, singapore, zipangu).
- For the routes session (via the coordinator, not built here): route graph and travel legs for the five entries,
  citizen regions, shops (none of these towns has a shop row), `fields.yaml`.

## 6. Out of scope, listed for later

- Bosses: Von Leon (211070100), Cygnus (271040100), Arkarium (272020200), Krexel (541020800), Ninja Castle's tower
  (800040410), Chaos Zakum (280030001), Chaos Horntail (240060201), Lucid, Will, Jin Hilla, Black Mage. Their maps are
  imported, but nothing leads in.
- Unreached on foot: Ani's Jail 211061100, Knight Stronghold entrance 271030000 and Hallowed Ground 271030201, Ruins
  in Flames 272000310 and 272000410 (quest portals), Ninja Castle corridor 800040210.
- Residents for the Repack towns (quest-log ids 31750-32199 reserved).
- Arcane River systems (symbols, flying mobs, Arcane mobs).

## 7. Checks

Tests: the list matches the XML (every listed map has a file and a String name; every spawn's template is on the
server); every imported map loads through `MapFactory`; every imported mob and NPC loads through `LifeFactory`; the
gate decisions (switch, DLL report, answer, unknown, bots); the safety net; login relocation; export `--check`.

GM live checks (scratch server or live after deploy, both client bases):

1. Repack client, account unknown: `!warp 219000000` is refused with the unknown line; Cokebear operator in Henesys asks
   the question; answer Repack; warped to Coke Town.
2. Coke Town walkable: walk Coke Town -> Coke Ski Camp -> Forest of Coke Golem; kill a mob, see drops; Cokebear operator
   takes you back to Henesys.
3. Each entry: Perry (Mushroom Shrine), Ralph (Suburban Area 2), Spirit of Snow Statue (Wolf Territory II), Temple Keeper
   (Three Doors: Gate to the Future, Crack in Time, Twilight Perion, one Arcane town); each way back.
4. Stock (Cosmic) client: the entry NPCs refuse with the cosmic line after the Cosmic answer; `!warp 219000000` is
   refused; no warp, no crash.
5. Safety net: on a Cosmic client answer Repack, go in (the client closes), log in again: the character is outside and
   the account says cosmic.
6. `!mods repack_maps off`: entry NPCs gone after a map reload, warps refused, a character inside starts outside at
   its next login.
