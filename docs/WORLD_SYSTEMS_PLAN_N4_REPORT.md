# N4 world events: report (feat/world-events, 2026-11-23)

From `feat/companion-manager` 65f47211e. Not merged, pushed or deployed. Changeset 68 / `062-living-world-event.sql`.
Switches, all off: `population.WORLD_EVENTS_ENABLED`, `WORLD_EVENT_MARKET_DAY_ENABLED`, `WORLD_EVENT_INVASION_ENABLED`,
`WORLD_EVENT_FESTIVAL_ENABLED` (+ `WORLD_EVENT_RALLY_MAX` 12, `WORLD_EVENT_POTION_FLOOR` 50). Check scripts (written, not
run): `MapleServer\tools\checks\checks\n4-world-events` (E1-E8). New code: `server.living.population.events`.

## Hot-file hunks

- `PresenceScheduler`: 2 imports; `plan()` one line before `if (weight > 0)`; `mapFor()` a target-map return before the
  switch; `startActivity` `case EVENT`; `refused()` EVENT waits like PQ and JOB; `avoiding()` one check first (its callers
  then include overrun fields); private `worldModifier()`.
- `ChatterEngine.tick`: 3 lines after the paper talk.
- `Market`: 2 imports; `stallCap()` in place of `MARKET_STALLS_MAX` (2 uses); `ask()` times `askShare()`; `newAsks()`,
  `learnSales()` and `unsold()` read `holdsPrices()`; private `worldModifier()`. The last three reads are more than the
  brief's "cap and ask" (decision 3).
- `Population`: field, construction, one minute line, one 20 s tick line, `shutdown()` in `stop()`, accessor, and the
  grind-manners predicate (`!worldEvents.defends(id)`).
- `config.yaml` population section after `FIELD_BOSS_ROLLED_KILLS_PER_DAY`; `changelog-tables.xml` changeset 68 at the
  end. `NeedScreens`/`NeedKind`/`NeedLedger`/`NeedStore`: not touched.
- Shared, not hot: `CitizenSession.Activity` + EVENT, `PopulationLoader` TRIGGERS and MEMORY_TRIGGERS, `PopulationConfig`,
  `PopulationHooks.mobKilled` (kill steals skip invaders), `PersonMemory` + `Hearsay` (Kind DEFENDED, deed "def"),
  `CitizenMiniGames.towns` and `played`, `CitizenJumpQuests.top`, `casino/HostPost` (a post's own map) and `DropGame`,
  `paper.yaml` (9 stories), `CommandsExecutor`.

## Decisions the plan did not settle

1. The busiest hour is the sum over the four weeks (an hour sampled once is not busier than one sampled every week).
   Events pick in `events.yaml` order; under 7 sampled days, or nobody ever online: the event's fallback slot. Next week
   is planned six hours before the paper. The tournament blocks two hours.
2. A start missed by under 3 h runs late in full; past that the row is MISSED. Events run on the citizens' channel with
   the most players at the start.
3. During a market day and the hour after, sales and unsold lines teach neither the price index nor the I2 asks. Otherwise
   a 15 % sale would become the index the paper and the E4 controller read.
4. Invasion: one wave at a time, the next 20 s after it is down, or on its share of the time left. Held = everything
   down in time. Monsters drop nothing even when watched (their meso would be a faucet); EXP is as for any monster, the
   last one's scaled with its HP. They go 40 s after the last player leaves and the unbeaten wave comes again if one
   comes back. Under 3 minutes left nothing spawns and it is rolled.
5. The last monster is an ordinary one with 12-14 times its HP, so the boss rules stay out of it, and no site's monster
   is more than 3 levels over the lowest citizen sent. Leafre, Aquarium and Ariant have no site: the first hits too hard
   for its band, the other two fields hold too few spawn points.
6. Real damage = the HP of two first-wave monsters. Prizes also go to bots who earned them; only players are remembered.
   A rolled outcome pays nobody.
7. An unwatched roll: 30 %, +5 % a citizen there, +1 % a level over the site's lowest, 5-90 %.
8. Kill-steal memory and the manners (N2.4) leave an invasion out, or citizens would stop at the first hit of a player.
9. Festival towns are Henesys and Ellinia in turn: jump quests start only there. Table champion = most different people
   beaten (at least 3; only games with a citizen at the table are counted). A player wins the climb when seen at the top
   after being seen below it since the meet began, on a 20 s poll; on the two Forest of Patience courses a player who
   takes the pile's warp between polls is missed. A drop game under way finishes where it is.
10. Not done: an overrun's one-hour avoidance and talk are in memory, so a restart in that hour forgets them. Rewards
    for damage dealt before a restart mid-invasion are lost with it.
