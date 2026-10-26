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
- Written: 579 maps (`wz/Map.wz/Map/Map0,2,4,5,8`; Map4 is new), 339 NPCs, 65 mobs, 2 reactors, and their String.wz
  Map/Mob/Npc entries. A map name goes into the String.wz group `MapFactory.getMapStringName` reads (Arcane River:
  `etc`). 28 maps have no name in the Repack either; they get their area's name. 19 Coke Town names were already
  there (identical); no existing String entry changed.
- Changed on the way, all listed in the report:
  - Arcane River (and the unnamed Maple Island ship map): the 5,802 mob spawns are left out and the Arcane mobs are not
    imported, so those maps are scenery.
  - 26 spawns whose template is in neither WZ set (NPCs 1540941-1540944 on the 5th-job maps, reactors 2112018,
    2708001-2708004, 2709001-2709002 in Lion King's Castle and Arcarium's altar) are left out: the server's map load
    would fail on them and the Repack client lacks them too.
  - Mob skills: `LifeFactory` throws on a (skill, level) that `Skill.wz/MobSkill.img` lacks, which would fail the map
    load. The five levels the imported mobs use (100/30, 102/10, 123/35, 142/7, 200/224) are imported from the
    Repack's MobSkill.img, and the one mob the summon level calls (8610015) with them. A skill id the server does not
    know at all would be taken off the mob; there was none.
  - Lachelein's five Dream Defender boards (9010101-9010105) have no name in the Repack's String.wz either.
- `src/main/resources/mods/repack-maps.txt`: the 579 map ids and their area, which the gate reads.
- `/wz/` is in `.gitignore` as a guard (every base file is tracked), so the new files are added with `git add -f`.
- Size: 985 new WZ files, 53 MiB of XML; String.wz grows by about 4,500 lines. The whole branch adds 1,056 blobs,
  60 MiB raw, 4.7 MiB as a git pack (measured with `git pack-objects` over ff6fbe48a..HEAD). The scrub push script
  drops only `tools/client-kit/data`, `video-assets` and media files, so all of it would be pushed; no single file
  is over 0.4 MiB.

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
  2. The account's answer, table `account_client_files` (changeset `48-account-client-files`, file 039): `repack` or
     `cosmic`, with where it came from (`player`, `gm`, `crash`, `client`). A DLL report is kept there as `client`, so
     the next login (before the report arrives) knows it too.
  3. None: unknown. A bot (BotClient) has no game files and counts as Repack.
- Every warp goes through `Character.changeMapInternal`; a listed map is refused there unless the client is known to be
  Repack (bots always pass while the switch is on). A refusal is a chat line and enableActions, never a warp:
  - cosmic: the place is only in the full Community Repack game files;
  - unknown: talk to the guide who knows the way, or type `@gamefiles repack` once you know which files you installed.
- The question: an entry NPC asks an unknown account once ("full Community Repack set" / "smaller Cosmic set" / "not
  sure", which explains the check: a `base=cosmic` line in the game folder's `config.ini` means Cosmic, no such line
  means the Repack set). `@gamefiles [repack|cosmic]` lets a player say or change it (no word: what the server
  knows); `!setgamefiles <player> [repack|cosmic|forget]` (GM 3) shows or sets it for an online player.
- Safety net: a warp into a listed map made on an account's answer (not a DLL report) is pending until the client's
  PLAYER_MAP_TRANSFER says the field loaded. A close (not a channel change or cash shop visit) within 60 s while it
  is pending marks the account `cosmic` (source `crash`); a later close says nothing, so a lost acknowledgement never
  marks a Repack player at a logout hours later.
- Login (`Character.loadCharFromDB`): a character saved in a listed map whose client may not be there now (the account
  is not Repack, or the switch is off) starts at its area's way out: the entry map (Henesys, Mushroom Shrine,
  Suburban Area 2, Wolf Territory II, Three Doors), or the boss maps' town (Leafre, El Nath, Lith Harbor for the ship).

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
| Gate to the Future, Crack in Time, Dimensional Schism, Twilight Perion, Arcane River towns | Three Doors 270000000 | Temple Keeper 2140000 (already there) | the maps' own exits to 270000000; elsewhere a town guide |

Scripts: `scripts/npc/9001000.js`, `9110000.js`, `9270030.js`, `2020012.js` (each: ask an unknown account, refuse a
Cosmic one, offer the trip to a Repack one; the Cokebear operator and Perry also take you home from inside; away from
their entry map they only talk), `2140000.js` (the Temple Keeper's menu: Gate to the Future, Crack in Time,
Dimensional Schism, Twilight Perion around level 160-190, and the nine Arcane River towns, "nothing hunts there now").

## 4. Scripts, drops

- Imported NPCs without a script talk with their own String.wz lines (`NPCTalkHandler` runs `npc/repack_talk.js` for
  an uncoded NPC on a listed map; `server.mods.RepackGuide` reads the lines), so none of them is mute and none starts
  a post-v83 quest. In a town with no road back, its guide also offers Three Doors: Claudine (Twilight Perion), Legato
  (Nameless Town), Lyon (Chu Chu Village), Protective Mask (Lachelein), Spirit of Harmony (Arcana), Beatrice
  (Trueffet Square), Gate (Esfera Base Camp), the Temple Keepers (Moonbridge Outpost, Labyrinth Crash Site), Mask
  (World's Sorrow).
- Missing map scripts are harmless (the server skips a map script it does not have). Portal scripts only matter where
  the walkable part of an area uses them: Ulu City's mini dungeon `MD_longkiss` (Long Kiss Duku Good Night,
  541020620-541020639, entered from Destroyed Park II 541020610) goes through `MiniDungeonInfo`. The rest guard boss
  and quest maps (Cygnus, Arkarium, Von Leon): left for the boss work.
- Drops: `tools/repack-import/drops.js` writes `db/extensions/2026-10-26-repack-import-drops.xml`: 1,384 rows for the
  55 mobs of the huntable areas that had none, each copied from the stock field mob closest in level (a boss from a
  boss), without quest items and the donor's own monster etc. Coke Town's mobs had no rows either. The Future mobs
  (lv 162-198) borrow from the highest stock field mob, Temple of Time's lv 131. The 93xxxxx mobs in Arkarium's maze
  (party-quest style gimmicks) get none.

## 5. The rest of BotStory

- TownLife: 15 profiles appended to `agents/town-life/index.json` (`TownLifeProfileMain generate ... --index
  --admission MANUAL_ONLY`): coke-town, ninja-castle, henesys-ruins, crack-in-time, twilight-perion,
  tribal-refugee-camp, nameless-town, chu-chu-village, lachelein, arcana, morass, esfera, moonbridge,
  labyrinth-of-suffering, limina. MANUAL_ONLY until the routes session gives the towns travel legs (then AMBIENT).
  Gate to the Future 271000000 is a swim map, which the nav graph does not model, so it has none (like Aquarium).
- `KnowledgeExportMain.region()`: 219 coke-town, 271-273 future, 450 arcane-river (211 and 541 and 800 already map to
  el-nath, singapore, zipangu); `server-knowledge.json` re-exported (5,841 maps, 1,629 mobs).
- For the routes session (via the coordinator, not built here): the hand-over note `repack-import.md` in the side
  session notes folder lists the legs (NPC, selections, landing portal), the ways back, shop candidates and region
  notes for route graph, travel legs, citizen regions, shops and `fields.yaml`.

## 6. Out of scope, listed for later

- Bosses: Von Leon (211070100), Cygnus (271040100), Arkarium (272020200), Krexel (541020800), Ninja Castle's tower
  (800040410), Chaos Zakum (280030001), Chaos Horntail (240060201), Lucid, Will, Jin Hilla, Black Mage. Their maps are
  imported, but nothing leads in.
- Unreached on foot: Ani's Jail 211061100, Knight Stronghold entrance 271030000 and Hallowed Ground 271030201, Ruins
  in Flames 272000310 and 272000410 (quest portals), Ninja Castle corridor 800040210.
- Residents for the Repack towns (quest-log ids 31750-32199 reserved).
- Arcane River systems (symbols, flying mobs, Arcane mobs).

## 7. Checks

Tests:

- `server.mods.RepackImportDataTest`: every listed map has its XML and a String name where MapFactory reads it, spawns
  only templates the server has, no mob in Arcane River; all 579 load through `MapFactory.loadMapFromWz` (about 5 s);
  every imported mob and NPC loads through `LifeFactory`; Ulu's mini dungeon.
- `server.mods.RepackMapsTest`: the list and areas, unknown/cosmic/repack answers, the store cache, a DLL report,
  bots, the switch, the safety net (and what is not a crash), ways out, the entry NPCs standing on their foothold.
- `scripting.npc.RepackTravelScriptTest`: every travel script run in Graal (question, Cosmic refusal, unsure, the
  trips in and back, the Temple Keeper's menus, repack_talk's lines and way back).
- `node tools/repack-import/export.js --check` and `drops.js --check` (need the Repack files, so not a JUnit test).
- Moved pins: `ServerModsTest` (repack_maps), `AgentTownLifeProfileRepositoryTest` (45 profiles),
  `KnowledgeFileGoldenTest` (5,841 maps, 1,629 mobs), `SeedDataTest` (the drops changeset, +1,384 rows).
- `AgentWorldPortalRouteGraphFreshnessTest`: the 356 portal edges of the new maps may be missing from
  `world-portal-route-graph.json` until the routes work exports it with them (then the test wants all of them).

GM live checks (they need the game client, so they run after the deploy, by the session that drives the client; this
branch's session did not run them):

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
7. Ulu City: Destroyed Park II's MD00 portal opens a Long Kiss room; its out00 portal leads back.
