# Routes and Malaysia: closing the reachability gaps, Residents for Kuala Lumpur and Kampung Village

Branch `feat/routes-malaysia` (off `feat/companion-manager` a435dc14b). The coordinator's town audit (2026-10-25,
gaps 6, 7, 8 and 10 of its section 4.2) found towns that bots, citizens or Residents' directions cannot reach, and
Malaysia without Residents. This branch owns the shared routing and region files so the Resident sessions do not
collide on them: `world-portal-route-graph.json` (and its exporter), `npc-travel-legs.json`,
`potion-supplier-catalog.json`, `scripts/config/knowledge/travel.yaml`, `KnowledgeExportMain.region()`, and the
regeneration of `scripts/config/population/regions.yaml` / `fields.yaml`.

## 1. Two route graphs

| Graph | What it is | Who uses it |
|---|---|---|
| agent | `victoria-portal-route-graph.json` + `world-portal-route-graph.json` (standard portals from WZ) + `npc-travel-legs.json` (NPC rides, each checked against its script by `AgentTravelLegScriptTest`) + the level-15 catalog's few scripted portals | bots, companions, citizens (`AgentVictoriaRouteRuntime`, `CitizenTravel`) |
| knowledge | `server-knowledge.json` map exits (standard and scripted portal targets) + `travel.yaml` legs | `WorldKnowledge.route`: Resident directions, recall ETAs, content answers |

From Henesys the agent graph reached 721 of 1,157 graph maps; the knowledge graph 928 of 5,262.

## 2. The gaps and what closes each

| Gap | Cause (checked in the scripts and WZ) | Agent graph | Knowledge |
|---|---|---|---|
| El Nath Dungeon, Dead Mine, Zakum | Ice Valley II's `top00` has no target; Jeff (2030000) warps to Sharp Cliff I (211040300) from level 50 | leg `ice-valley-to-sharp-cliff`, **level 50 and up** (new leg field `minLevel`); the way back is a plain portal | `guide` leg |
| Kuala Lumpur, Kampung Village | Audrey (9201135) flies from CBD for 42,000; Spinel (9000020) from Boat Quay Town for 10,000; Audrey's way back from KL goes to the saved `WORLDTOUR` location (Boat Quay Town by default). Kampung is on foot from KL (Muddy Banks, Hibiscus Road) | legs `boat-quay-to-kuala-lumpur`, `singapore-cbd-to-kuala-lumpur`, `kuala-lumpur-to-boat-quay` (new leg field `savedLocation`) | `world-tour` / `plane` legs; Audrey's KL-Kampung hop as a `taxi` (a shortcut: the road is walkable) |
| Mushroom Shrine, Showa Town | Spinel's world tour was in `travel.yaml` only | legs from Henesys and Lith Harbor (3,000), and back (`savedLocation: WORLDTOUR`, Lith Harbor by default) | already there |
| Florina Beach | Pason (1002002, Lith Harbor, 1,500) saves `FLORINA`; Pison (1081001) returns there (Lith Harbor by default) | legs both ways (`savedLocation: FLORINA` on the return) | already there |
| Kerning Square | the `KerningTrain` event: ticket reader 1052007 option 0 at the subway (103000100) rides 103000301 to Kerning Square Station (103000310); back through the station's `out00` portal (`Depart_ToKerning`), riding 103000302 | legs both ways; the way back boards **through a portal** (new leg field `portal`, `npcId` 0) | `subway` legs |
| Mushroom Castle | scripted portal `TD_MC_first` in An Empty House (100000002), open only after quest 2260 (completed) or any of 2300-2310 (started or completed) | **scripted portal** 100000002 -> 106020000 with that **quest gate**; the way back is a plain portal | already there (map exits) |
| Altaire Camp (Ellin Forest) | scripted portal `move_elin` in the Time Control Room (222020400), no condition | scripted portal 222020400 -> 300000100 | already there |
| Temple of Time | Leafre Station's Halfling (2082003) turns the rider into a dragon and drops it on the flight map 200090500; the rider flies east into 200090510 and through `in00` (`templeenter`) to the Temple (270000100). Back: the Temple's `out00` (`outTemple`) to 200090510, west to 200090500, `minar00` (`undodraco`) to Leafre Station. Both flight maps are `fly=1`, which bots could not move on, and sat in the graph's excluded ride range | leg `leafre-station-to-the-flight`; the two flight maps leave the excluded range (their east/west portals become graph edges); scripted portals `templeenter`, `outTemple`, `undodraco`; **agents move on `fly=1` maps as on swim maps** (MapleMap learns `fly`) | `guide` leg Leafre Station -> 200090500 (the flight maps' exits are already in `server-knowledge.json`) |
| Amoria in knowledge | Thomas Swift (9201022) was only in `npc-travel-legs.json`; `region()` null for the 670 fields | already there | `guide` legs; `region()` 670 -> `amoria` |
| Community Repack towns | entry NPCs spawned by `RepackMaps.ENTRIES` (Cokebear operator, Perry, Ralph, Spirit of Snow Statue) and the Temple Keeper's menu in Three Doors (`docs/REPACK_IMPORT_PLAN.md`); their portals were never exported to the world graph | world graph re-exported with the Repack maps' portals (356 edges in the ossyria, singapore and zipangu groups); legs for the four entry NPCs both ways and the Temple Keeper's Future towns (bots pass the files gate; only stock-client humans are kept out). Arcane River (450) is in no group, so no agent leg | `guide` legs for every entry, Arcane River included |

The Temple of Time's own roads stay as they are for agents: each road's forward portal (`timeQuest`) opens only
with that road's quest (3500-3522) done, and no bot does those quests. Memory Lane 1 is open from the Temple.

### 2.1 Engine changes (small, each tested)

- **Leg fields** (`AgentTravelLegCatalog.Leg`, all optional): `minLevel` (the router takes the leg only for an agent
  at that level or above, as the NPC would refuse it otherwise); `savedLocation` (the script lands the rider where
  that saved location points, `toMapId` when none; the router plans with `toMapId` and, when the rider lands on
  another walkable map, takes the ride as done and plans on from where it stands, instead of counting a refusal);
  `portal` (board by walking into this portal on `fromMapId` instead of talking to an NPC).
- **Scripted portals in the world graph.** `Export-WorldPortalRouteGraph.ps1` gains an authored list of scripted
  portals (source, destination, portal name and id, script, optional quest gate) written into the catalog;
  `AgentWorldPortalRouteGraphFreshnessTest` checks each against WZ (the portal exists, carries that script, and the
  script warps to the destination). `AgentVictoriaTrainingRouteCatalog` adds their edges and portal ids next to the
  level-15 catalog's. A gated portal is left out of an agent's route while the agent does not meet the gate (its
  edge joins that agent's blocked edges), so a bot that has not done the Mushroom Castle quests never walks into a
  portal that refuses it.
- **Flight.** `MapFactory` reads `info/fly`; the agents' map gateway treats a `fly=1` map like a swim map, so a
  dragon-shaped bot moves through the air the way it swims in Aqua Road.
- **Citizens.** `AgentVictoriaRouteRuntime.plannedRoute` carries a portal leg's portal (citizens walk to it and are
  set down at the far end, as they are after an NPC ride) and honours `minLevel` and the quest gates for the citizen
  it plans for.

## 3. Regions and fields

`FieldCatalogMain.region()` and `regions.yaml`:

| Change | Why |
|---|---|
| new region `temple-of-time`, levels 80-140, town 270000100, no lobby | the audit's gap 8; its 15 road maps (Memory Lane, Road of Regrets, Road to Oblivion; mobs 80-141) become citizen fields. Citizens reach the gated roads only while unwatched, like any field the walking graph does not reach |
| Kerning Square (103040000) joins Victoria's towns (small weight) | reachable now by the train |
| Tera Forest Time Gate (240070000) joins Leafre's towns (small weight) | a town map on foot from Minar Forest; Neo City's year maps stay out (quest gated) |
| Haunted House (682000000) joins New Leaf City's towns (small weight) | the NLC taxi (a `cab`) lands there; the Phantom Forest starts behind it |
| Community Repack maps never become citizen fields | `FieldCatalogMain` skips every `RepackMaps.listed` map (Lion King's Castle, Ulu City and Ninja Castle carry existing region prefixes): a stock-client player can never see them, so citizens there would be invisible to most players. **Open for the user**: citizens in Repack areas for Repack players |

Mushroom Castle (106020000) stays out of the town lists: its door is quest gated. Mu Lung Dojo, the Free Market,
Happyville, the Internet Cafe and the Exclusive Training Center are instances or hubs, not homes.

`KnowledgeExportMain.region()`: 670 -> `amoria`, 925 -> `mu-lung` (Dojo), 926 -> `ariant` (Pyramid), 930 ->
`ellin-forest` (Ellin PQ), 809 -> `ludibrium` (the Maze), 910 -> `free-market`. Then `tools\knowledge-export.bat`.

## 4. Shops and potions

The supplier catalog lists NPC shops that sell Red/Orange/White Potions and Blue Potion or Mana Elixir. The stock shop
data (`101-shops-data.sql`, `102-shopitems-data.sql`, NPC placement in the map XML) has none in the four towns the
audit named, so nothing is added; the gaps stay:

| Town | What stands there | Nearest catalog supplier on foot |
|---|---|---|
| Crimsonwood Keep | no shop | Mo (9201099) in the Phantom Forest |
| Kampung Village | Ali (9270065): 18 local foods (2022203-2022215, 2022476-2022480), no potions | Kuala Lumpur 9270057, walkable through Muddy Banks and Hibiscus Road |
| Mushroom Shrine | 5 food stalls (takoyaki, yakisoba, ...) | Showa Town 9120002 (801000300), on foot |
| Amoria | Vivian Boutique (9201020), clothes | **none**: the only way out is Thomas Swift, and supply trips never ride |

Amoria is the one real gap: a bot there restocks only by riding back to Henesys. Teaching the supply logic to buy food
items, or riding for supplies, is outside this branch.

## 5. Malaysia's Residents

Wave 3's rules (`docs/REGIONAL_RESIDENTS_PLAN.md` sections 2, 8 and 9): 7 Residents a town, about a third
recruitable, each recruit earned a different way, one regional arc tying Kuala Lumpur and Kampung Village (lightly
to Singapore by gossip), finales once per server (`requires.notFlags`, pinned in `ArcFinalBeatOncePerServerTest`),
rumors at venues where their cast is awake, quests on Malaysia's own fields (Muddy Banks 46-65, Hibiscus Road 59-63,
Fantasy Theme Park 70-87, Spooky World 94+). The recruits carry the third regional tier of the four weapon lines that
still stop at waves 1 and 2 (two-handed swords, spears, one-handed axes, two-handed blunts).
Quest-log ids 31600-31749, appended to `quest-log-ids.yaml`. The cast table goes in section 8 once authored.

Shared Resident files are append-only: `loadouts.yaml` (one section "# Malaysia Residents (docs/ROUTES_MALAYSIA_PLAN.md)"),
`ResidentDialogue.PORTRAITS` (`kuala-lumpur`, `kampung-village`), `quest-log-ids.yaml`. Pinned counts that move here
(and will conflict with the parallel Resident sessions at the merge): `ContentLintFriendshipTest` (weapon Residents),
`ContentLintLootTermsTest` (contracts, 69 before), `ArcFinalBeatOncePerServerTest`. No new TownLife profile (both
exist), so `AgentTownLifeProfileRepositoryTest` (50) does not move. No database changeset.

## 6. Tests

- `AgentTravelLegScriptTest`: every new leg runs its NPC (or portal) script in Graal; the stubs learn saved
  locations, levels and the portal API. Jeff refuses a level 49 rider.
- `AgentTravelLegRouteTest` / a new route test: Henesys reaches Kuala Lumpur, Kampung Village, Mushroom Shrine, Showa
  Town, Florina Beach, Kerning Square, Altaire Camp, the Temple of Time and Sharp Cliff I with rides; a level 49 agent
  does not get the dungeon leg; an agent without the Mushroom Castle quests is routed around An Empty House's portal.
- `AgentWorldPortalRouteGraphFreshnessTest`: standard edges (now with the Repack maps and the flight maps) and the
  scripted portals match WZ.
- Knowledge: `KnowledgeLintTest`, the travel-leg loader keeps every new leg (no unknown map or NPC), and routes to the
  same towns exist.
- Content: the ContentLint tests, `AgentConfigurationBoundaryTest`, the pinned counts above; then the full split suite.

## 7. Live checks after deploy

GM commands are fine for setup (`!warp`, `!level`, `!item`, `!world`); the behaviour itself is watched, not skipped.

1. **El Nath Dungeon.** A level 50+ companion called from El Nath to Sharp Cliff I (or its owner standing there):
   it walks to Jeff on Ice Valley II, talks, lands on 211040300. A level 45 one does not try (no route).
2. **Malaysia.** Owner in Kuala Lumpur, companion in Henesys, `call <name>`: it rides Kerning -> Singapore, walks to
   Boat Quay Town, talks to Spinel (10,000), lands in KL. `!warp` the owner to Kampung Village: the companion walks
   through Muddy Banks and Hibiscus Road. Back: from KL it talks to Audrey (option 2) and lands in Boat Quay Town.
3. **Zipangu.** Same from Henesys to Showa Town (Spinel 3,000, the Shrine, on foot); back via Spinel to the tour's
   start.
4. **Florina, Kerning Square.** Companion called to Florina Beach (Pason, 1,500) and back; to Kerning Square (the
   train, 10 s ride on 103000301) and back through the station's exit (ride 103000302).
5. **Mushroom Castle.** A bot that has not done quest 2260 is never routed into An Empty House's portal (no route, no
   "strange force" refusals in the log); one that has, walks in.
6. **Altaire Camp.** Companion called from Ludibrium to Altaire Camp: up Helios Tower to the Time Control Room,
   through the Time Gate.
7. **Temple of Time (flight).** Companion called from Leafre to the Temple of Time: it talks to the Halfling at
   Leafre Station, becomes a dragon, flies east across both flight maps and enters the Temple. Watch that it moves
   through the air (not stuck on the first flight map) and comes back the same way. This is the riskiest check: the
   flight maps are the first `fly=1` maps agents move on.
8. **Citizens.** `!citizens seed temple-of-time 3`: they live on the Temple roads (unwatched placement past the
   quest gates). Kerning Square, Tera Forest Time Gate and the Haunted House get a little citizen town life.
9. **Knowledge.** Ask a Resident in Henesys the way to Kuala Lumpur, to the Temple of Time and to Sharp Cliff;
   Amoria's fields show up in training suggestions for levels 30-50.
10. **Malaysia's Residents.** As wave 3's checks 1-4, 6 and 7 for `kuala-lumpur` and `kampung-village` (section 8).

## 8. Malaysia cast

(Filled in when the content lands.)
