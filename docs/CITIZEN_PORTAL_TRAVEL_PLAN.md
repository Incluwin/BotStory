# Citizens travel through portals while someone can see them

Branch `feat/citizen-portal-travel` (from `70a29efa4`). Plan written 2026-10-25, before the build; section 7 holds
the results.

Goal: a citizen that a player can see goes to another map the way a player would. It walks to the right portal on its
map, enters it, comes out of the matching portal on the next map, and chains maps through the world portal graph when
the trip spans several maps. A citizen nobody can see may keep the cheap delayed path. The switch between the two must
never be visible: no citizen ever disappears from a map, or appears on one away from a portal, while a player is on
that map.

## 1. What happens today (70a29efa4)

Every map change of a loaded citizen goes through `PresenceScheduler.travel`
(`src/main/java/server/living/population/PresenceScheduler.java:965`). Its callers: the re-plan (`:897`), leaving a
field boss (`:936`), the move that waited for a supply trip (`:1095`), the job errand's re-plan
(`CitizenJobErrands.java:187`), circle parties sent to train (`SocialFabric.java:469`), Free Market rooms
(`Market.java:161`), and the party quest lobby trips and calls (`CitizenPartyQuests.java:597,617,773,797`).

`travel` stops TownLife, movement and the stall, then schedules `arrive` on the timer
(`PresenceScheduler.java:987-990`):

- the delay is 1 s when nobody is on either map, else `RecallTravel.etaMs / 4` clamped to 4-90 s (`:987-988`). "Someone
  is on a map" is `Population.humansIn`, which copies the map's player list (`ResidentRuntime.java:831`);
- `arrive` then calls `current.changeMap(target, target.getPortal(0))` (`:1019`). Portal 0 is the map's first start
  point ("sp"). On a watched source map the citizen stands still for 4-90 s and then vanishes. On a watched target map it
  appears on the start point, not at a portal.

`docs/POPULATION_GUIDE.md:53-54` documents this ("instant when nobody can see either side and paced by the road-hop
ETA when someone can"), and its "Not done yet" list names it (`:220-221`: "travel is a map change with a delay rather
than walking through portals").

What the engine already has, and this work reuses:

- **The portal walker.** `AgentVictoriaRouteRuntime.travelStatus`
  (`src/main/java/server/agents/progression/AgentVictoriaRouteRuntime.java:86`) is one tick of a walk to another map. It
  takes the next hop from the route graphs (`:109`, `AgentVictoriaTrainingRouteCatalog.nextHop`), walks to a reachable
  portal to that map (`:149`) and enters it (`:140`). It blocks edges whose portals keep refusing and settles after
  each crossing. The graphs are Victoria's (`victoria-portal-route-graph.json`) plus every other continent's
  (`world-portal-route-graph.json`, 1516 edges, from the shops-beyond-Victoria work). Supply trips
  (`AgentSupplyProcurementRuntime`), the boss death walk (`AgentBossDeathWalk`) and the expedition lobby call it through
  `PrimitiveCapabilityGateway.travelTo` on every tick.
- **The tick hook.** `AgentTickPreflightRuntime.runPreflight` (`src/main/java/server/agents/runtime/AgentTickPreflightRuntime.java:37-50`)
  runs the supply trip and then `AgentBossDeathWalk.tick` before the rest of the tick. A walk sets its next move and
  lets the tick go on, so the movement step makes the move.
- **The travel legs.** `npc-travel-legs.json` and `AgentTravelLegCatalog` list 23 boarding NPCs (ships, trains, Isa,
  the subway, Mu Lung's crane...). `AgentVictoriaTrainingRouteCatalog.ridePath` (`:73`) finds the cheapest path that
  walks the graphs and boards those legs.
- **Companion walk-in and walk-out.** `ArrivalPlacement` (`src/main/java/server/living/roster/ArrivalPlacement.java:41,57,85`)
  ranks a map's portals: open visible portals first, then plain start points. Hidden, scripted and Mystic Door portals
  are never used. `RosterService.walkOut` (`RosterService.java:1604`) walks a benched companion to its nearest portal
  and unloads it there. Companions arrive at a portal and leave through one. They never chain maps on foot, because
  they follow their owner or come in from the bench.
- **Observation.** `MapleMap.isObservedByPlayer` (`MapleMap.java:3075`) is a lock-free count of real clients on the
  map. The Agent mode policy decides PRESENTATION / BACKGROUND with it.

Gaps the graphs leave: the Free Market is in neither graph. Town portals into it are scripted (`market01.js` and the
others save the location and warp to 910000000 "out00"). Its room doors are plain type-1 portals (`in01`..`in22`, each
to its room's `out00`).

## 2. Design

### 2.1 The rule

At every point of a trip, the citizen's own map decides how it travels:

- **Its map is watched** (`MapleMap.isObservedByPlayer`): it walks on foot, the way a player does.
- **Its map is not watched**: it takes the cheap path. It waits out the old delay, standing where it is, then changes
  map straight to the destination.

The switch is invisible by construction:

- it goes from walking to cheap only when nobody is on its map;
- it goes from cheap to walking only on the map where it physically stands, and it starts walking from where it
  stands;
- a cheap map change that is due re-checks the map first. If a player came in meanwhile, the citizen walks instead
  (`arrive`);
- a cheap arrival on a watched destination lands at the portal that the last hop of the route enters by:
  - one portal away, the portal that its own map's portal names, which is where `GenericPortal.enterPortal` puts a
    player;
  - else the portal on the destination that leads back to the map before it;
  - for the Free Market, `out00`;
  - after a ride, portal 0, where the ride scripts set players down (`Boats.js:70`).

  An arrival nobody can see keeps portal 0, as before.

A trip that walks onto an unwatched map hands over to the cheap path there. Most visible walks are therefore one map
long: the next map usually has nobody on it.

### 2.2 Walking (watched)

The citizen picks the next leg of its walk from where it stands, in this order:

1. a **walking route** in the graphs. The engine walk calls `travelTo` on every tick until it stands on the
   destination;
2. a **portal on this map straight to the destination** (a Free Market room door, a room's `out00`), or, for the
   Free Market entrance, the town's scripted market portal. It walks to the portal and enters it. Entering runs the
   portal's script as it does for a player, so the market remembers which town to send it back to;
3. for the Free Market from a map without a market portal, the **nearest map with one** on foot (`MARKET_MAPS`, the 22
   maps whose Map.wz portals run a market script). Henesys town walks to Henesys Market (100000100). There step 2 takes
   it in;
4. a **route with a sea leg** (user decision 1, section 6). It walks through the portals to the first leg's station,
   up to the boarding NPC, and stands there for `PORTAL_TRAVEL_BOARD_MS`, as if talking. Then it is gone, the way a
   boarding player is, and lands at portal 0 of the leg's far dock. It pays no ticket or fare and uses no ride map.
   From the dock the rule applies again: it walks on if someone is there, else it goes the cheap way;
5. **no route** (a map off the graphs such as Amoria, or out of the Free Market to anywhere but its entrance): it walks
   out through the nearest visible portal (an open map portal or visible scripted portal; `ArrivalPlacement` ranks the
   same ones). It is gone there, as if it had gone through, and lands on the destination as in 2.1.

If its walk finds no route on the way (an edge blocked mid-walk), or stands on one map for
`PORTAL_TRAVEL_HOP_TIMEOUT_MS`, it walks out (step 5) once. If that fails too, or the whole walk passes
`PORTAL_TRAVEL_MAX_MS`, it **waits for nobody to watch**: it goes back to what it was doing on that map (a grind on a
field, else a stroll) and takes the cheap path once its map is empty. It never vanishes in front of anyone.

While it walks:

- it first lets TownLife finish what it is doing (graceful exit, as `travel` did before), at most
  `PORTAL_TRAVEL_TOWNLIFE_GRACE_MS`, then stops it;
- a supply trip of its own takes over, as before. The walk stops and the move waits for the trip (`waitForSupplyTrip`);
- a newer move replaces it (`latestMove`, as before);
- a re-plan, an AFK roll and idle wandering wait until it has arrived (`maybeReplan`);
- if it is down on the way, the walk waits until it is up again, then goes on from where it stands;
- `session.mapId` follows the map it stands on, so chatter and the party quests read the right map. Its activity
  changes on arrival, as before.

### 2.3 Where the code goes

- **Engine: `server.agents.capabilities.navigation.AgentMapWalk`** (new). It is a host-driven walk to a target, run on
  the Agent's own tick from `AgentTickPreflightRuntime`, after the supply trip and the boss death walk. A target is a
  map, an NPC on a map, a portal to enter, or a portal to reach. Each tick it calls `travelTo` until it stands on the
  target map, then walks up to the NPC or portal. It reports WALKING, ARRIVED, NO_ROUTE or STALLED (no map crossed for
  the host's hop timeout). It never decides where to go next. It knows no `server.living` type
  (`AgentLivingImportBoundaryTest`).
- **Engine: `AgentVictoriaRouteRuntime.plannedRoute`** (new, public). It gives the whole way as steps: portal hops,
  or with legs, where the walk only is impossible. The host plans with it: where the first sea leg boards, and which
  map the destination is entered from.
- **Living: `server.living.population.CitizenTravel`** (new). It holds the trip on the session
  (`CitizenSession.trip`), decides walk or cheap each second on the timer, picks the leg (2.2), boards and walks out,
  and hands arrival back to `PresenceScheduler`.
- **`PresenceScheduler`**: `travel` starts a walk when the citizen's map is watched. `arrive` walks instead when a
  player came in during the delay, and lands at the matching portal. `maybeReplan` waits while a trip is under way.
  The logout and hand-over paths end the trip and its engine walk.

### 2.4 Config (`population:` in config.yaml, `PopulationConfig`)

| Key | Shipped | Meaning |
|---|---|---|
| `PORTAL_TRAVEL_ENABLED` | `true` | Kill switch. false = today's delayed map change, exactly. |
| `PORTAL_TRAVEL_CHECK_MS` | `1000` | How often a trip on foot is checked (walk or cheap, arrived, stuck). |
| `PORTAL_TRAVEL_HOP_TIMEOUT_MS` | `120000` | A walk that stands on one map this long without crossing gives up its way. |
| `PORTAL_TRAVEL_MAX_MS` | `600000` | A trip still on foot after this long waits for nobody to watch, then goes the cheap way. |
| `PORTAL_TRAVEL_TOWNLIFE_GRACE_MS` | `30000` | How long its TownLife may finish what it is doing before it sets off anyway. |
| `PORTAL_TRAVEL_BOARD_MS` | `2000` | How long it stands at a boarding NPC before it is gone. |

Engine tuning in `agent-engine.yaml`: `AgentMapWalk.PORTAL_REACH_PX` (45, as `travelStatus`) and
`AgentMapWalk.NPC_REACH_PX` (100, as the travel legs' NPC distance).

## 3. Scheduler cost

The Agent tick scheduler is the measured scale limit, so the walk adds as little as possible to it:

- **Every Agent tick:** one `ConcurrentHashMap.isEmpty()` in `AgentMapWalk.tick` (plus one `get` while any walk
  runs). This is the same fast path as `AgentBossDeathWalk`.
- **A walking citizen's tick:** one `travelTo`, the same call a supply trip makes each tick: a BFS over the graphs
  (Victoria about 600 maps, the other continents about 1,500 edges) plus a `canReach` per portal candidate. Only
  citizens on a watched map walk, and each hands over to the cheap path on the first unwatched map. So the walkers at
  any time are about the citizens leaving a map that a human stands on. With 240 loaded citizens re-planning every
  25-45 minutes, that is ~6-10 trips a minute server-wide, of which only those on a human's map walk.
- **A walking citizen's ticks run at full rate** because its map is watched. That is the PRESENTATION cadence it
  already had standing there; the walk does not raise it. A cheap trip stays as cheap as today: one timer task, no
  tick work.
- **Off the tick:** one LOW_PRIORITY timer task a second per trip on foot (`PORTAL_TRAVEL_CHECK_MS`), and one
  `plannedRoute` (Dijkstra over the same graphs) per leg choice and per cheap arrival on a watched map. Observation is
  read with the lock-free `isObservedByPlayer`, not the player-list copy.

## 4. Tests

- `AgentMapWalkTest` (engine). It walks while `travelTo` moves, stops on the target map, walks up to an NPC and
  stops beside it, enters a portal and reports ARRIVED, reaches a portal without entering, reports NO_ROUTE and
  STALLED, a newer walk replaces an older one, the tick is a no-op without walks or for another entry, and a downed
  walker waits. It also checks the preflight order: the walk runs after the supply trip and the death walk, and a
  walking tick is not consumed.
- `AgentVictoriaRoutePlannedRouteTest` (engine, real graphs). Henesys to a Henesys field is portal hops only, and
  each hop is a graph edge. Henesys to Orbis boards at Ellinia station (1032008). Same map or off the graphs gives an
  empty route.
- `CitizenPortalTravelTest` (living, mocked maps):
  - a watched citizen walks instead of vanishing, and arrives on foot with its activity;
  - an unwatched one keeps the old 1 s delayed map change and portal 0;
  - a walk onto an unwatched map hands over to the cheap path;
  - a player arriving during the cheap delay makes it walk from where it stands;
  - a cheap arrival on a watched map lands at the portal that leads back to the map before it;
  - a sea leg boards at the NPC after the board pause;
  - NO_ROUTE walks out through the nearest portal;
  - a stalled walk with no way out waits until nobody watches;
  - a supply trip takes over and the move waits;
  - a newer move replaces the walk;
  - logout ends the walk;
  - the kill switch restores the old path;
  - Free Market: from a town it enters the market portal, from Henesys town it walks to Henesys Market first, and at
    the entrance it walks to the room door;
  - a cheap arrival with no return portal (Ellinia's library to Ellinia) comes in at the portal its own map's portal
    names;
  - a due re-plan waits while it walks.
- `CitizenTravelMarketMapsTest`: `MARKET_MAPS` equals the maps whose Map.wz portals run a market script (all 5,263
  map files are scanned, about 3 s).
- `PopulationConfigPortalTravelTest`: the class defaults and the shipped `config.yaml` text.
- The existing travel tests (`PresenceSchedulerAbstractTravelTest`, `PresenceSchedulerSupplyTripTest`,
  `CitizenPartyQuestsTest`, ...) keep passing unchanged: their maps are unwatched, so they take the cheap path as
  before.

## 5. Live checks (for the coordinator after the merge; not run here)

The client belongs to another session, so none of these ran on this branch. Each needs one human (GM) client and
`!citizens` (gm5).

1. **Walk-out on a watched map.** Stand in Henesys (100000000) near some citizens and wait for one to re-plan
   (`!citizens` lists sessions; `!citizens login <name>` loads one). Expect a log line
   `citizen <name> sets off on foot from 100000000 to <map>`. The citizen walks to a portal (west00 / east00 /
   east10), goes through it, and does not vanish in place. Then `citizen <name> walked onto <map> where nobody can see
   it` and, after the delay, it arrives.
2. **Walk-in on a watched destination.** Stand on a Henesys hunting field (for example 100010000) and wait for, or
   force, a citizen trip there. It appears at the portal the route enters by (west00 from Henesys), not at a start
   point.
3. **Player arrives mid-delay.** Make a citizen in an empty town re-plan, then warp in within the 1-4 s delay.
   Expect `citizen <name>: someone came onto <map> during its move; it walks` and a visible walk.
4. **Free Market.** Stand in Henesys Market (100000100) and wait for a MARKET citizen. It walks into the market portal,
   comes out at the FM entrance's out00, and walks to a room door (watch from the entrance).
5. **Sea trip.** Stand on Ellinia station (101000300) while a citizen of another region travels home: `!citizens`
   shows its plan, or a job errand sends a citizen to El Nath. It walks up to Cherry (1032008), stands ~2 s and is
   gone, with the log line `citizen <name> boards at 1032008 for <map>`.
6. **Stuck case.** Look for `waits for nobody to watch` lines in a day of logs. The count should be small. Each one
   names a map where the walk could not get out. That map is a nav or graph follow-up, not a pop-out.
7. **Cost.** `!agentscheduler` before and after an hour with a human in a town: tick p95 and backlog should not move.
   Count `sets off on foot` lines per hour to see how many walks ran.
8. **Kill switch.** `PORTAL_TRAVEL_ENABLED: false` and a restart give the old behaviour (no `sets off on foot`
   lines).

## 6. User decisions (2026-10-25)

1. **Sea trips while watched: board at the dock** (recommended, chosen). The citizen walks to the station and up to
   the boarding NPC, then is gone the way a boarding player is. It comes off at the far dock. There are no tickets,
   fares or ride maps. Rejected: real rides (spends citizen mesos, the ride code never ran live) and walking out
   through the nearest portal.
2. **On by default** (recommended, chosen): `PORTAL_TRAVEL_ENABLED: true`, and false restores the old behaviour.

## 7. Results

(Filled in after the build.)
