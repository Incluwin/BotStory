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
| Temple of Time | Leafre Station's Halfling (2082003) turns the rider into a dragon and drops it on the flight map 200090500; the rider flies east into 200090510 and through `in00` (`templeenter`) to the Temple (270000100). Back: the Temple's `out00` (`outTemple`) to 200090510, west to 200090500, `minar00` (`undodraco`) to Leafre Station, whose own exit `west00` is scripted too (`dracoout`). Both flight maps are `fly=1`, which bots could not move on, and sat in the graph's excluded ride range | leg `leafre-station-to-the-temple-flight`; the two flight maps leave the excluded range (their east/west portals become graph edges); scripted portals `templeenter`, `outTemple`, `undodraco`, `dracoout`; **agents move on `fly=1` maps as on swim maps** (MapleMap learns `fly`) | `guide` leg Leafre Station -> 200090500 (the flight maps' exits are already in `server-knowledge.json`) |
| Amoria in knowledge | Thomas Swift (9201022) was only in `npc-travel-legs.json`; `region()` null for the 670 fields | already there | `guide` legs; `region()` 670 -> `amoria` |
| Community Repack towns | entry NPCs spawned by `RepackMaps.ENTRIES` (Cokebear operator, Perry, Ralph, Spirit of Snow Statue) and the Temple Keeper's menu in Three Doors (`docs/REPACK_IMPORT_PLAN.md`); their portals were never exported to the world graph | world graph re-exported with the Repack maps' portals (356 edges in the ossyria, singapore and zipangu groups); 9 legs: Coke Town and Ninja Castle both ways, Ulu City and Lion King's Castle in (out by plain portals), Gate to the Future, Crack in Time and Dimensional Schism from the Temple Keeper (out by plain portals). Bots pass the files gate (a bot counts as a Repack client); only stock-client humans are kept out. Left out for agents: Twilight Perion (its guide back answers only through `repack_talk.js`, which the bot gateway does not open) and Arcane River (in no graph group) | `guide` legs for every entry and every guide back, Twilight Perion and Arcane River included |

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

### 2.2 Still out of reach for agents (not in this branch)

| Where | Why |
|---|---|
| The Temple of Time's far roads | every road's forward portal (`timeQuest`) needs that road's quest (3500-3522); no bot does them, so routes stop at Memory Lane 1 |
| Mushroom Castle past Mushroom Forest Field | inner gates `obstacle.js` (quest 30000 or a Poison Spore) and `gotocastle.js` (quest 2324); `fields.yaml` keeps capacity 0 there (user decision 2026-10-10) |
| Cave of Life (Horntail) | `hontale_morph` scripted portals at 240040700 (the bossing work enters its own way) |
| Twilight Perion, Arcane River | the guides back answer only through `repack_talk.js`; Arcane River is in no graph group |
| Mu Lung Dojo, Happyville, Maple Island | NPC warps into instances or event maps, and the one-way ship from Maple Island |
| Ereve, Rien | unchanged: their docks are not joined to their towns by plain portals |

## 3. Regions and fields

`FieldCatalogMain.region()` and `regions.yaml`:

| Change | Why |
|---|---|
| new region `temple-of-time`, levels 79-140, town 270000100 (Temple of Time), no lobby | the audit's gap 8; its 15 road maps (Memory Lane 89-98, Road of Regrets 103-111, Road to Oblivion 118-132) become citizen fields. Citizens reach the gated roads only while unwatched, like any field the walking graph does not reach; the way back from every road to the Temple is plain portals, and the flight back to Leafre (its potion shop) is walkable, so no citizen is stranded the way the Mushroom Castle ones were (the 2026-10-10 decision in `fields.yaml`). The floor is 79, not 80: seeding tops out near lv86 (`CitizenSeedPlanTest`'s seed ceiling), and the band needs enough settled citizens to seed from; below 86 they train in the nearest fitting field elsewhere until they grow into Memory Lane |
| Haunted House (682000000) joins New Leaf City's towns (90/10) | the NLC taxi (a `cab`) lands there; the Phantom Forest starts behind it; its `haunted-house` profile came with the Masteria merge |
| TownLife profile `temple-of-time` (generated, appended to `index.json`; `AgentTownLifeProfileRepositoryTest` 50 -> 51) | `RegionFieldConsistencyTest`: every region town but the Aquarium has a profile |
| **At the merge** (not on this branch): add Three Doors 270000000 to `temple-of-time`'s towns, Tera Forest Time Gate 240070000 to `leafre`'s and Kerning Square 103040000 to `victoria`'s | their profiles belong to the Resident sessions (`three-doors`, `tera-forest-time-gate` on feat/ossyria-remainder; `kerning-square` on feat/victoria-remainder), whose Residents use their venues; a copy here would collide. Suggested weights: Temple 50/50, Leafre 85/15, Kerning Square 2 |
| Community Repack maps never become citizen fields | `FieldCatalogMain` skips every `RepackMaps.listed` map (Lion King's Castle, Ulu City and Ninja Castle carry existing region prefixes): a stock-client player can never see them, so citizens there would be invisible to most players. **Open for the user**: citizens in Repack areas for Repack players |

Mushroom Castle (106020000) stays out of the town lists: its door is quest gated. Mu Lung Dojo, the Free Market,
Happyville, the Internet Cafe and the Exclusive Training Center are instances or hubs, not homes.

`KnowledgeExportMain.region()`: 670 -> `amoria` (Purplewood Forest, Amos' Training Ground), 809 -> `zipangu` (the
spas, below 809050000) or `ludibrium` (the Maze), 926 -> `ariant` (Nett's Pyramid), 930 -> `ellin-forest` (the Forest of
Poison Haze). None of the 809/926/930 maps that lack a forced return has a mob spawn, so none turns into a training
suggestion. 910 (the Free Market and its hub maps, HPQ's Primrose Hill) and 925 (Mu Lung Dojo, three of whose maps
have mobs and no forced return) stay null. Then `tools\knowledge-export.bat`.

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
rumors at venues where their cast is awake, quests on Malaysia's own fields (Muddy Banks 45-65, Hibiscus Road 59-65,
Fantasy Theme Park 68-87, Spooky World 94+). The recruits give an L100-120 tier of the four weapon lines that stopped
at waves 1 and 2 (two-handed swords, spears, one-handed axes, two-handed blunts; section 8 says how they sit beside
Masteria's). Quest-log ids 32000-32149 (32000-32042 used), appended to `quest-log-ids.yaml`. The cast is in section 8.

Shared Resident files are append-only: `loadouts.yaml` (one section "# Malaysia Residents (docs/ROUTES_MALAYSIA_PLAN.md)"),
`ResidentDialogue.PORTRAITS` (`kuala-lumpur`, `kampung-village`), `quest-log-ids.yaml`. Pinned counts that move here
(and will conflict with the parallel Resident sessions at the merge): `ContentLintFriendshipTest` (weapon Residents),
`ContentLintLootTermsTest` (contracts, 69 before), `ArcFinalBeatOncePerServerTest`. Malaysia needs no new TownLife
profile (both exist); `AgentTownLifeProfileRepositoryTest` moves 50 -> 51 for the Temple of Time's (section 3). No
database changeset.

## 6. Tests

- `AgentTravelLegScriptTest`: every new leg runs its NPC (or portal) script in Graal; the stubs learn saved
  locations, levels and the portal API. Jeff refuses a level 49 rider.
- `AgentTravelLegRouteTest` / a new route test: Henesys reaches Kuala Lumpur, Kampung Village, Mushroom Shrine, Showa
  Town, Florina Beach, Kerning Square, Altaire Camp, the Temple of Time and Sharp Cliff I with rides; a level 49 agent
  does not get the dungeon leg; an agent without the Mushroom Castle quests is routed around An Empty House's portal.
- `AgentVictoriaRouteRuntimeTest`: Kerning Square's way back boards by walking into the station exit; Spinel's way
  back landing in Henesys (where the tour began) counts as the ride done, not a refusal; Jeff's leg is no way below
  level 50. `AgentRideTravelRuntimeTest`: a ticketless rider on Leafre Station's platform now walks out (dracoout) to
  Mue instead of finding no route.
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
9. **Repack areas (a Repack-files client).** Owner in Coke Town, companion in Kerning City, `call <name>`: it walks to
   the Cokebear operator in Henesys and lands in Coke Town; the same into Ninja Castle from the Shrine (Perry) and
   Ulu City from Suburban Area 2 (Ralph). A stock-client owner's companion is never asked to (the owner cannot go).
10. **Knowledge.** Ask a Resident in Henesys the way to Kuala Lumpur, to the Temple of Time and to Sharp Cliff;
    Amoria's fields show up in training suggestions for levels 30-50.
11. **Malaysia's Residents.** As wave 3's checks 1-4, 6 and 7 for `kuala-lumpur` and `kampung-village` (section 8).

## 8. Malaysia cast

14 Residents (7 in Kuala Lumpur, 7 in Kampung Village), 4 recruitable, 43 quests (quest-log ids 32000-32042), 4
contracts, 2 arcs, 4 rumors, 4 loadouts. Files: `personas|quests|contracts|arcs|rumors/malaysia.yaml` (both towns in
one file each, as Singapore and Zipangu), the "Malaysia Residents" section at the end of `loadouts.yaml`, the
`kuala-lumpur` and `kampung-village` entries of `ResidentDialogue.PORTRAITS`. The kit and the gear rules are wave 3's;
each recruit carries the tier below what it gives.

| Town | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| Kuala Lumpur | Farah | producer for MY Maple TV, the Trend Zone's big screen (townsfolk), arc owner | - | - |
| Kuala Lumpur | Lim | repairman of the middle street, built the park's ride motors | - | - |
| Kuala Lumpur | Ravi | teh tarik man of the mamak stall | - | - |
| Kuala Lumpur | Hafiz | despatch rider of the kampung road | - | - |
| Kuala Lumpur | Cheryl | stylist of the Trend Zone | - | - |
| Kuala Lumpur | Iskandar | night guard of the east road, once the park's security chief (Crusader 98) | kill proof: 60 Galloperas + 30k | two-handed swords The Beheader L100 / Dragon Claymore L110 / Timeless Nibleheim L120 |
| Kuala Lumpur | Devi | bodyguard for hire (Dragon Knight 98) | day rate: 130k | spears Stormshear L100 / Dragon Faltizan L110 / Timeless Alchupiz L120 |
| Kampung Village | Awang | Tok Awang, woodcarver who carved the carousel's forty horses, arc owner | - | - |
| Kampung Village | Zara | Awang's granddaughter, wants to be on the big screen | - | - |
| Kampung Village | Hamid | fisherman of the muddy banks | - | - |
| Kampung Village | Rohani | batik painter by Ali's stall | - | - |
| Kampung Village | Meera | teacher of the kampung school | - | - |
| Kampung Village | Kamal | boat-builder, once the park's high-striker strongman (White Knight 103) | rumor and intro quest: ring the bell with him, hear Rohani on him, 60k | two-handed blunts Blue Seal Cushion L105 / Dragon Flame L110 / Timeless Bellocce L120 |
| Kampung Village | Bayu | ranger of Hibiscus Road (Crusader 105) | items: 20 Viking Helmets + 30k | one-handed axes Dragon Axe L110 / Reverse Bardiche L120 / Timeless Bardiche L120 |

**Recruits.** Four of wave 3's five ways, one each: a kill proof counted after the offer against the monster of the
intro quest (Iskandar), a day rate (Devi), an item price (Bayu), a rumor on top of the intro quest with meso only
(Kamal). Day-rate, item and kill-proof recruits need their intro quest (`<id>.intro.done`); every `refuse.flags` line
names what the contract still needs. They carry Berzerker, Fairfrozen, Leomite and Tomahawk with a Gold Kalkan.

**Gear.** The four lines that stopped at waves 1 and 2 (Sigrun, Bjarke, Runa, Maren). Masteria (merged before this
branch) had already given the two-handed sword, spear and two-handed blunt lines an L100-120 tier (Ragna's Stonetooth
Sword / Dragon Claymore / Reverse Nibleheim, Ivor's Pinaka / Dragon Faltizan / Reverse Alchupiz, Osgar's Golden Smith
Hammer / Dragon Flame / Reverse Bellocce), so Malaysia gives the other v83 pieces at those levels (The Beheader,
Stormshear, the Blue Seal Cushion, the Timeless pieces) and shares only the L110 ones, of which v83 has one each.
Runa's one-handed axes ended at Tomahawk L100, so Bayu's tier starts at L110 and both its upper pieces are L120 (the
Timeless Bardiche hits harder than the Reverse). No piece is on the Repack-only list; `ContentLintFriendshipTest` counts
53 weapon Residents.

**Fields.** Every quest is on Malaysia's own maps, levels 45-94: the Outskirts and the three Muddy Banks (45-65),
Hibiscus Road (59-65), the three Fantasy Theme Parks (68-87), the Entrance to the Spooky World (94), and the Longest
Ride on ByeBye Station (the third park's mini-dungeon; its steps list the nineteen copies MD_high.js opens). Collect steps use each mob's
own 30% etc drop. Scarlion and Targa are script-spawned boss-event mobs (ScargaBattle's clear is a boss clear, not a
party-quest clear), so the L118 tiers are long kill runs through the park instead. Errands cross the two towns on foot
(Ravi to Hamid, Hafiz to Zara, Cheryl to Rohani, Zara to Farah, Meera to Hafiz, and the finale's Awang and Lim).

**The regional thread.** Thirty years ago the city built the Fantasy Theme Park on the kampung's rubber land; Tok
Awang carved the forty horses of its carousel, Lim built its motor, and the kampung's children rode free on opening
night. Two years later the park went wild (the carousel horses are the Galloperas at the Spooky World's gate) and the
city closed its gates. Farah finds the opening-night tape in MY Maple TV's archive; Lim mends it and it plays on the
big screen every night (`farah-the-opening-night`, `kl.tape.mended`). Awang's lead horse, Bintang, the one with the
star, comes home from the Galloperas (`awang-bintang`, L90, `kampung.bintang.home`), and Farah's finale clears the
park's road, asks Awang, and tells Lim (`farah-the-carousel-turns`, L80, `my.carousel.turns`): the carousel turns once,
the kampung's children ride free, and Awang goes to the city for the first time in thirty years. Singapore is touched
only by gossip (Ravi on Wei, Hamid on Rosnah, Hafiz and Farah on Spinel's and Audrey's flights).

| Arc | Chapters (gate) |
|---|---|
| `farah-the-big-screen` | The Tape in the Archive / Opening Night (`kl.tape.mended`) / Bintang (`kampung.bintang.home`) / The Carousel Turns (`my.carousel.turns`) |
| `awang-the-carousel-horses` | Forty Horses / On the Big Screen (`kl.tape.mended`) / Bintang Comes Home (`kampung.bintang.home`) / One More Turn (`my.carousel.turns`) |

| Rumor | Cast, spot, hours | Sets on listeners |
|---|---|---|
| `kl-the-big-screen` | Farah, Lim on KL's lower east street, 20-21, from Farah's chapter 2 | - |
| `kl-the-mamak-stall` | Ravi, Hafiz on KL's lower central street, 17-18, every 2 days | - |
| `kampung-on-the-big-screen` | Awang, Zara by Audrey in the kampung, 19-20, from Awang's chapter 2 | - |
| `kampung-the-strongman` | Rohani, Kamal on the kampung's middle east street, 18-19 | `kampung.heard-kamal` (Kamal's contract) |

**Once per server.** `farah-the-opening-night`, `awang-bintang` and `farah-the-carousel-turns` name the world flag they
set in `requires.notFlags` (pinned in `ArcFinalBeatOncePerServerTest`). No later quest lists them in
`questsCompleted` (the finale needs Farah's first quest and the Bintang flag), so nobody is cut off; `zara-on-the-big-screen`
is offered between the tape and the finale only (`notFlags: [my.carousel.turns]`).

**Portraits.** Borrowed until the portrait tool runs: Encik Musa, Chiang, Riduan and Kok Hua in Kuala Lumpur; Lam, Ali
and Village Chief Malek (9270061, who stands on Muddy Banks 2) for the kampung, which has only two townspeople of its
own. All have their own standing image in Npc.wz.

**Live checks** (section 7 item 11): `!world census` shows kuala-lumpur=7 and kampung-village=7; wake both towns at the
routine hours, `!world residents`; `hi Farah`, double-click Awang; level 58 in KL, after `farah-the-muddy-banks-footage`: `farah-the-opening-night` (Lim, the
Scaredy Scarlions on Hibiscus Road 2, the first park's gates), then a second character is no longer offered it; at
19-20 by Audrey in the kampung `kampung-on-the-big-screen` plays; level 90, after `awang-the-charmers-flutes`: `awang-bintang`, then level 80
`farah-the-carousel-turns` (both chapters 4 open together). Recruits: Iskandar (60 Galloperas counted after the offer),
Devi (130,000 for 24 hours), Bayu (20 Viking Helmets), Kamal (hear `kampung-the-strongman`, middle east street 18-19).
