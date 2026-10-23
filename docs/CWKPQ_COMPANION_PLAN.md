# Companions in Crimsonwood Keep PQ (CWKPQ), with the keystone prequests

Written 2026-10-19. Exploration only: nothing here is built. Every fact below was read from this server's scripts, WZ
data and Java on `feat/companion-manager` cb21e2908, not from GMS guides. Where the community guide you shared
disagrees with this server, this plan follows the server and says so.

## Result

- **CWKPQ can be done, but it needs both of our tracks at once.** It is a stock *expedition* of 6 to 30 members
  (`ExpeditionType.java:44`, `CWKPQ(6, 30, 90, 255, 5)`), so sign-up must come from the boss track (expedition
  join at Jack, bench allies, overflow bot parties, two owners). Its six stages need the PQ track's stage
  coordinator (the RJPQ/OPQ shape). Neither track does this alone. COMPANION_BOSSING_PLAN.md:1311 already set
  CWKPQ aside for this reason.
- **The keystone prequest is short.** Only three quests are needed to enter: 8222 *The Brewing Storm* (10
  Stormbreaker Badges for Lukan), start 8230 *Stemming the Tide* at Jack in the Cavern of Pain, then 8223 *Storming
  the Castle* (75 Windraider, 75 Firebrand, 75 Nightshadow for Lukan). The Crimsonwood Keystone 3992041 is its
  reward. Subani, Elpam, Taggrin and *Lost in Translation* are not needed to join. They unlock Joko and Fiona
  (the exchange), which is optional.
- **Companions who go with their owner already get the quests.** `AgentPartyQuestSyncService` copies the owner's
  quest starts, progress and completions to every Agent in the party. When the owner completes 8223, his party
  companions receive their own keystones. What is missing is a companion doing the chain **by itself**: a benched
  ally, a new recruit, or a companion whose owner already holds a keystone. The plan adds a keystone errand for them
  (the JobErrands shape).
- **The road to Lukan and Jack is not in the bots' world map.** Haunted House 682000000 is outside the masteria
  route group, the NLC taxi (9201056) has no travel leg, and the Valley of Heroes climb is treated as a plain portal.
  Firebrand needs extra care: quest 8223 counts only the revived form 9400578, which appears when 9400577 dies.
- **Bots cannot touch a sigil today.** The sigil reactors accept only listed skill ids (`Reactor.java:276-281`,
  Reactor.wz `activeSkillID`). The bot gateway hits every reactor with skill id 0
  (`CosmicPrimitiveCapabilityGateway.java:854`). One new primitive fixes stages 2, 3 and 4: hit a reactor with a real
  skill the bot owns, from inside the skill's range, with the cast shown to players.
- **Stage 3 is a jump quest, and we have no jump-quest navigation.** The nav graph has no moving obstacles, and
  trap damage is reported only by a human client (`TakeDamageHandler.java:74-83`), so bots never take it. This is the
  riskiest stage. An offline nav probe on the real map (Step C0) decides how hard it is.
- **This server has four Grandmasters, not five.** Margana 9400590, Red Nirg 9400591, Rellik 9400592 and Hsalf
  9400593. There is no Mikhal in v83 String.wz. Stage 6 also starts with 10 Master Guardians and 20 Crimson Guardians.
- **This server's stages 4 and 5 differ from the guide.** Stage 4 is 8 shuffled sigil spots, 5 of them real, each
  for one class (no altar riddle). Stage 5 is five class-only rooms, each giving a weapon that is dropped onto its
  relic in the main hall (no relic handed to the leader).
- **The class rule is not enforced at entry.** Jack checks only levels 90-255. The classes matter later: each sigil
  takes only its class's skills, and each stage-5 room takes only its class. So the hard rule is at least one member
  of each of the five archetypes. Two of each is the guide's advice and our recommendation.
- **The run ends easily.** It ends when the count drops to 6 or fewer, or when the leader leaves or revives
  (`EventInstanceManager.java:1278-1286`, `CWKPQ.js:250-326`). A revive counts as leaving. So companion deaths must be
  held in the event, like the other PQs' death gates, and the desk should ask for at least 8 members.
- **Size: 11 phases (C0-C10).** By the plan's effort sizes, C0-C9 are about 11 to 22 weeks in sequence, less with
  the two parallel streams (keystone and PQ). C10 is optional. The first useful milestone is C1 + C2 (companions can
  earn keystones). The first full clear needs C0-C9.

## Decisions taken

You answered "do recommended for all" on 2026-10-19, so every question below takes its recommendation:

1. The keystone errand hunts live when a player can see it or a live bench slot is free, and on the bench ledger at
   the companion's measured kill rate otherwise.
2. The exchange chain for bots waits (C10).
3. Bots take trap damage in stages 3 and 4, with values measured from a human client in C0.
4. Both stock script faults are fixed, inside the scripts only (stage-5 weapon re-issue, stirge plate script).
5. Jack refuses to start companions without one member of each archetype, and warns below 2 of each or below 8
   members.
6. A new crew C under a new GM test owner is raised for the live checks. Crews A and B stay as they are.

## Progress

- **C0 offline part done 2026-10-19** (docs/CWKPQ_C0_RESULTS.md, probes in docs/cwkpq-c0/). What it changes:
  - Stage 3 needs Haste 20 or better (B4); the nav graph must obey CANNOTJUMPDOWN there (C5).
  - Stage 4's fakes look exactly like real sigils, so companions test a spot with an ordinary hit first (B5).
  - Stage 4/5, the warrior room, the relic drop spots and the Valley climb are all reachable at base stats.
  - The lineup rule cannot stop at archetypes: a Crusader or White Knight under the shaping build gets its sigil
    skill only at about Lv100 and an Outlaw at about Lv96 (a Gunslinger never takes Flash Fist). C3's lineup checks
    each member's own sigil skill.
  - The stirge plate script fails twice (`killAllMonsters(true)` arity, then `eim` undefined): Question 4 (b) fixes
    both.
  - The quest mirror gave a companion that had completed 8223 a second keystone. Fixed on feat/cwkpq (97c4cb0d0): the
    start and complete mirrors skip a quest an Agent completed for good.
  - Still open (live client): trap damage values and what field limit 0x1000 does to potions.
- **C1, first part, built 2026-10-19 on feat/cwkpq:**
  - Haunted House 682000000 joins the masteria route group (exporter + regenerated graph: 4 new edges with Bent
    Tree 610010000 and 610010002).
  - Travel legs `new-leaf-city-to-haunted-house` and back (NLC taxi 9201056, 15,000 mesos). Kerning to Phantom
    Road now routes by the subway and the taxi (AgentTravelLegRouteTest).
  - A bot's scripted quest start runs only when `Quest.canStart` allows it, as the client handler does
    (CosmicPrimitiveCapabilityGateway.startQuest). Scripted completes are unchanged; the client also checks
    `canComplete` there, which is a possible later fix.
  - Economy sell plans never sell the Marker 3992039, the Map 3992040 or the Keystone 3992041 to an NPC (all three
    are trade-blocked, so stalls already refuse them).
- **C1, left:** nothing for the Valley of Heroes: C0 found the in-map routes to CM1_E, CM2_F and Lower Ascent's CM3_2
  COMPLETE at base stats (the only stats a bot has there, MOVEMENTSKILLS), so the world graph's portal edges stand and
  the nav graph does the climb. Two items moved:
  - The Firebrand revive link needs no export change: the knowledge file's mobs already carry a `revive` column
    (9400577 revives into 9400578). C2's hunt choice reads it.
  - Quest prereqs in the knowledge export move to C2 item 7, together with the chat answer that uses them, so the
    export does not carry a column nothing reads.
- **C2 built 2026-10-19 on feat/cwkpq** (`server.living.roster.KeystoneErrands`, lines in `KeystoneErrandLines`):
  - The owner asks in chat: "<name> get your keystone" (also "get a keystone", "<name> keystone") or "everyone get
    keystones". Out with its owner, the companion says why and walks out keeping its seat; benched, it goes between
    its duties. Below COMPANION_KEYSTONE_ERRAND_MIN_LEVEL (85), holding a keystone already, or locked out (8230
    finished before 8223 began), it says so and stays.
  - The step is read from its own quest log and bags each time it is loaded, so a GM change, a restart or the owner's
    quest mirror never makes it repeat a step. Steps: start 8222 at Lukan, hunt badges in Stormhall 610030014 (only
    Stormbreakers spawn there), hand in, start 8230 at Jack in the Cavern of Pain, start 8223 at Lukan, hunt in the
    Cavern of Fortitude 610020014 (only Windraiders, Firebrands and Nightshadows), hand in. Firebrands are hunted in
    their first form. Two words with Lukan in a row are said without leaving him. A lost keystone is re-crafted by
    Lukan's script.
  - Talk steps are town-pool bench slices; hunts are field-pool slices fighting only the marks. Between live slices
    the ledger credits each mark at the rate the companion measured live there (no live measurement, no credit),
    capped at what the step needs, and writes it into the quest log and bag at the next load. A restart loses unwritten
    credit, never quest progress.
  - The Crew window says what it is doing ("hunting Stormbreakers for Lukan"); Crew suggestions and Party Search skip
    a companion out for its keystone. Kill switch COMPANION_KEYSTONE_ERRANDS_ENABLED (on, since a companion goes only
    when asked). Talk and walk times reuse the job errands' keys.
  - Not built: the chat answer about the keystone path (item 7) and the knowledge export prereqs it needs; a full
    SETUP tab when 8223 pays the keystone (the hand-in fails three times and the errand ends).
- **C3 built 2026-10-20 on feat/cwkpq** (the expedition desk at Jack):
  - CWKPQ runs on the boss track, not the PQ desks: an expedition of 6-30 needs the boss track's sign-up, bench allies
    and seat counts, and every reader of the switch is boss-track code. So the key `cwkpq` is in
    `CompanionBossKeys` (COMPANION_BOSS_ENABLED_TYPES / _ALLY_TYPES), not `CompanionPqKeys`, and `PqEntrance` has no
    row. It ships off: `LivingWorldConfigBossTest.STILL_BUILT` keeps it out of the "every built boss is on" pin.
  - `AgentBossRunCatalog.CWKPQ`: recruit 610030020 (Jack 9201113, no join choices), battle 610030600, reward room
    610030800 (out00 is portal 1), no exit NPC, marker items the five stage-5 weapons. boss-rules.json `cwkpq`:
    stageMaps 610030100-610030550 (every stage and class room, so adoption and the fight tick work there), the Blood
    Stirge never a target, entryItems the keystone (the New Leaf City approach store keeps the standard only for an
    owner carrying one). `BossEntrance.CWKPQ`: companions wait beside Jack, allies come and go by out00, trips out of
    the hall are held.
  - Death mode `HOLD_UNTIL_CLEAR` (AgentBossDeathDepartureGate): a dead INSIDE member stays down where it fell while
    the event lives, is not cleared and still holds it; after the clear it respawns in town. Raised where it fell
    (Resurrection), it plays on.
  - `AgentCwkpqRun` is the run's fight: it hands each tick's members to their stage map's play. No stage has a play
    yet (C4-C7); members stand where they are, logged once per stage.
  - `AgentCwkpqSigils`: the five archetypes' sigil reactors and skills, pinned to Reactor.wz and String.wz by a test.
  - Jack's script takes the Mark of the Squad's five hooks under `cwkpq` (pinned by JackCwkpqHookScriptTest) and one
    of its own: with any Agent on the list, Start is refused while some archetype's sigil can be turned by nobody on
    it (`CompanionBossRuns.cwkpqStartRefusal`, decision 5, hard). While the list is open a party companion warns once
    each: a sigil nobody can turn, fewer than 8 on the list, a sigil only one can turn (decision 5, soft).
  - Keystone gate (`CompanionKeystoneGate`, on with COMPANION_KEYSTONE_ERRANDS_ENABLED): as the owner comes into the
    hall, a following companion with no keystone stays in the Hall of Mastery and says why ("say get your keystone
    and i'll go see lukan"). The ally picker does not offer a benched companion read as holding none (read when the
    owner comes in), and the call reads its bag again; one with none whispers so and stays at camp.
  - Not built: a line when a held member goes down (the stock "down" line comes after the clear); a picker choice that
    sends a keystone-less ally on its errand.
- **C4 built 2026-10-20 on feat/cwkpq** (the sigil cast, stages 1, 2 and 4, and `!cwkpqtest`):
  - `AgentSkillAttackPlanRuntime.planSkillAttackAt` plans a skill at a point with no monster (the same gates as a
    monster plan, minus targets; Meso Explosion refused, it needs mesos). `AgentCombatAttackRuntime.attackAtNothing`
    swings it through the same handler as any attack: shown to the map, MP, ammo and cooldown paid, no damage, the
    learner not told. `PrimitiveCapabilityGateway.hitReactorWithSkill` then hits the reactor with that skill's id
    (the old `hitReactor` keeps skill 0 for every other reactor).
  - `AgentCwkpqSigilCast` (B3): of the sigil skills a member holds it tries attacks, then summons, then buffs (Chakra
    through the debuff runtime). A Paladin with only Charged Blow puts a charge up first. It stands 45 px beside the
    sigil, 100 px for a projectile, facing it; the hit is checked against the sigil's Reactor.wz area (lt -125,-19 /
    rb 123,122, pinned by a test). Outcomes CAST / CHARGING / NOT_READY / NO_SKILL; the play reads the sigil's state
    on a later tick.
  - `AgentCwkpqStagePlays` (B5), ticked by `AgentCwkpqRun` through `AgentCwkpqPorts`:
    - Stage 1: the first member by slot takes the hidden portal (next01) and says once "the way's behind the pillar
      under the lion's head"; the others wait by next00; with glpq1 set everyone takes next00.
    - Stage 2: per archetype whose sigil still waits, the first member by slot who can cast a sigil skill turns it; a
      sigil a player turned is left alone; with glpq2 = 5 everyone leaves.
    - Stage 4: a member of the spot picture's class takes each spot. Bowman and pirate pictures are always real (no
      fake shows them), so they cast at once; any other is tried with one plain hit (a fake turns and is spent; a real
      sigil ignores it) and, still standing after 800 ms, takes the class skill. Spot claims and verdicts are kept per
      run; a fake's tester says once which one it was ("the right one up top's a fake"). With glpq4 >= 5 everyone
      leaves.
  - `!cwkpqtest` (GM6, `AgentCwkpqTestService`): there is no "party prod" step, since the run starts at the real desk.
    `status` reads the run you stand in, or the list registering on your channel: run phase, stage and every glpq
    flag, each companion (job, level, which sigil it turns, phase, map, down, deaths) and the stage-4 spots with what
    each really is. `checkpoint <storage|agility|wit|unity|council|armory>` sets the earlier stages' flags as their
    clears do, sets `current_instance` to the stage before (CWKPQ.js restarts a stage's timer only when the counter is
    one short), runs the event's clearPQ for the armory, and moves everyone alive inside the instance to the stage's
    first portal; the down stay where they fell. `stop` runs the event's own end.
- **C5 built 2026-10-20 on feat/cwkpq** (stage 3, without traps):
  - No jump-down (`AgentNavigationFieldLimitPolicy`): route selection leaves out down-jumps (DROP edges with no
    launch step) on a map whose field limit has CANNOTJUMPDOWN (610030300, 610030510, 610030550, pinned to Map.wz);
    walk-off drops stay. A route-time filter, like the movement-skill one, so the cached graphs (GRAPH_VERSION 60)
    stand.
  - Stage 3 play (`AgentCwkpqStagePlays.agility`): a thief with Haste puts it up first (a party buff, so the members
    by it get it too). Each waiting sigil goes to the first member by slot who can cast its skill and reach it: a low
    one (y > -684) at any stats, a high one only at speed 120 + jump 120, speed 145 + jump 110, or with an active
    Flash Jump or Teleport (C0). When nobody can reach a high one, one of its class says once "i can't make that climb
    without haste. our sigil up there is yours". While fewer than 5 passes are counted, every member who climbs high
    and has no sigil to turn takes the top portal from (310,-1104), the portal's long floor (its own point snaps to the
    short duplicate foothold only a down-jump reaches). The rest wait by the bottom portal; with 5 sigils and 5 passes
    everyone takes it.
  - Not built: trap damage (decision 3). It waits for the live measurement of what a menhir hit does to a human
    client; bots take none until then, as today.
- **C6 stock fixes built 2026-10-20 on feat/cwkpq** (Question 4, user-approved content changes):
  - (a) Lost weapon: each room master (9201107-9201110) gives its weapon once more when the room's bit is already set
    but `server.partyquest.CwkpqRelicWeapons.lost` holds: its relic in the hall is still empty, no player in the event
    holds one, none lies on any stage-5 map, and it was not re-issued before (event property `glpq5_reissued`, one bit
    per weapon). The pirate room has no master, so the pirate master in the hall (9201111) gives a pirate the gun once
    its real chest is broken and the gun is lost.
  - (b) Stirge plates (6109013.js): `killAllMonsters()` and the event instance passed in; it fires when the shared
    count reaches 5 (five standing on the plates at once), once; stepping off never takes the count below 0. Bots send
    no touch packets, so the plates stay a human's job.
  - Knowledge re-exported for the script edits (only fingerprints and the timestamp change).
- **C6 stage 5 play built 2026-10-20** (`AgentCwkpqUnityPlays`):
  - Hall: a member holding a weapon walks to its drop spot (C0 check 1c: the first spot whose own drop lands in the
    relic's area) and drops it there once `predictDropLanding` falls inside `Reactor.getArea()`, then waits 6 s for
    the relic. A member whose archetype's weapon is still wanted (`CwkpqRelicWeapons.wanted`: relic empty, no copy
    held or lying) takes its room's portal (in01-in05); the rest wait by next00, which everyone takes at glpq5 = 5.
  - Rooms: once the weapon is held (by anyone, a human too) or a member holds it, it leaves by out00 (from the
    warrior master's ledge by down00 beside him). Otherwise: warrior, bowman and magician rooms are ground clear
    (`grind(entry, Set.of())`, the never-targets left alone) and the first by slot asks the master when
    `countMonsters()` is 0 (his own test); in the thief room each member breaks the nearest eye nobody else is on,
    then the first asks the master; in the pirate room each breaks the nearest chest (the real one and the decoys
    look alike) and the nearest pirate picks the gun up where it fell (`lootNearby`, since the weapons are marker
    items the shared loot rules keep members off). A taker says once "got the master sword, taking it to its relic".
    One asked three times without a weapon says "the master won't give me the ..." and leaves.
- **C7 built 2026-10-20** (`AgentCwkpqCouncilPlay`, boss-rules `cwkpq`):
  - Only the event leader (the human who made the expedition at Jack) can talk to the Battle Statue, so with the hall
    empty a companion says once per step "talk to the statue when you're ready" (before the guards) or "hall's clear,
    talk to the statue".
  - With monsters up, a magician takes the master its element hurts most (Mob.wz): fire/poison Rellik then Azure
    Ocelot, ice/lightning Hsalf then Scarlet Phoenix, the cleric line Red Nirg then both revives; Margana resists every
    mage element. Everyone else, and a mage with none of its masters up, fights `AgentBossPriorityFight` under the cwkpq
    rules: targetPriority Margana, Rellik, Hsalf, Red Nirg (last: his revive adds two), then the revives; adds the
    summons and guards; neverTarget now also Margana's invincible summon 9400598. One priority fight per run, ended with
    the run.
  - Not changed: `AgentBossScope.EXPEDITION_TYPES`. Its note says a type is added only after its live check, so the
    masters' skill attacks reach companions only once C9 adds CWKPQ there; until then companions take touch damage
    only in the hall.
- **C8, first part, built 2026-10-20** (`AgentCwkpqLootRule`, a refuse-only clause in AgentLootEligibility): in the
  Armory a companion takes an equip its archetype may wear (Item.wz reqJob bits), a book that teaches its job a skill,
  a weapon scroll for the weapon in its hand, and any potion; it leaves Gelt Chocolate (2022121) to physical attackers
  and the Tao materials, storybooks and the chair to the humans. This server's Armory drops no Pie; its other potion
  is Sunblock (2022277).
- **C8, pace estimate, built 2026-10-20** (`AgentCwkpqPaceProfile`, `CwkpqClearTime`, `JackLines`): hallway, storage,
  agility, wit, the five class rooms side by side, relics, the statue's talks, the hall's guards and the Grandmasters.
  A pace Clear can now let one class fight (`AgentPqPaceSegment.Fighters`): the warrior, bowman and mage rooms are
  fought by their class alone, and a member is weighed only on the clears it fights. Jack shows the estimate and up
  to two cautions over the leader's list when companions are on it (living_world.COMPANION_PQ_CLEAR_TIME and the
  cwkpq boss switch on). The estimate's horizon went from one hour to two (`HORIZON_MS` 7200000): the stage clocks add
  to 112 minutes. Priors only at first; recorded runs since 2026-10-21 (below).
  - Found while building it: the Grandmasters and Red Nirg's revives hold about 929M HP and cwkpq sets no
    `companionDamagePercent`, so ten companions at 30,000 damage a second take them past the 40-minute slow limit.
    Whether companions get a damage scale here (as Pink Bean's 2000%) is for the C9 live check.
  - Not modelled: the damage policy counts an element a mob is immune to as normal (Master Guardians resist all five).
- **C2 chat answer, built 2026-10-20** (server.knowledge): CWKPQ is a known PQ (aliases.yaml cwkpq: lobby 610030020,
  Jack 9201113, levels 90-255), with the keystone (3992041) and Lukan (9201098) by name. "how do i join cwkpq" names
  Jack and the keystone from Lukan; "how do i get the keystone" names Lukan's quests (it was "dont think anything drops
  those": quest rewards are not item sources); a bot under level 70 says it is past it. The NLC Taxi to the Haunted
  House is a travel leg of a new kind, cab (a taxi that is the only way, which routes take), so "how do i get to
  crimsonwood keep" is "subway to nlc, cab to haunted house, then thru valley of heroes". "how do i get into X" was
  read as an item question for every PQ; it now asks where X is.
- **C8, Armory, built 2026-10-20** (`AgentCwkpqArmoryPlay`): a boss fight may now play on after its clear on maps it
  names (`AgentBossFight.playsAfterClear`); only cwkpq does. After the statue's last talk the companions take the
  hall's next00, wait in the Secret Chamber until someone has started the Armory's minute (current_instance 6), then
  break only their own share of the 30 boxes: everyone on the Armory shares them along the shelves, the humans first,
  and one companion says which boxes are the humans' ("the top left boxes are yours, we'll break the rest"). With its
  share broken a companion picks up what the loot rules let it.
- **C8, clear-time records, built 2026-10-21** (`AgentCwkpqPaceMarks`, `PqPaceLedger.recordBoss`): each CWKPQ run
  now notes when it reached each step the stock scripts set (current_instance 1-5, each class room's glpq5_room bit,
  the pirate room's real chest broken, glpq6 1-3 and the clear) and, as it ends, reports one span per segment of the
  pace profile with every member's power read at its first tick. A boss fight may report a pace
  (`AgentBossFight.pace`, read before its end, carried on `RunEnded`); only cwkpq does. The run goes into pq_run_pace
  under pq_key cwkpq with the estimate Jack last showed its leader, is learned from at once, and is read back at boot
  while the cwkpq boss switch is on. Jack's estimate now reads what the records taught for the leader (any leader's
  while he has none, the priors until a run is recorded). Not seen by the marks: the walk into a room before its
  first kill (each room's span runs from the hall of unity) and the relics' carrying apart from the last room's
  weapon.
- **C9 live checks, 2026-10-22/23** (crew C: Keepfriend leads Hadrik, Storm383, SpearKnight, iWantPanda and
  NonThePriest; `!cwkpqtest checkpoint <stage>` per stage, `!cwkpqtest why` for each member's job, walk goal, combat
  step, accuracy and navigation graph):
  - The keystone gate, Jack's start check and estimate, the sign-up, the hallway and stages 2-4 pass live.
  - Stage 3 froze on empty navigation graphs: a graph queued against an event map disposed before it was built
    (`MapleMap.dispose` drops its footholds) was saved to disk with no regions and reused for that map. A cached graph
    with no regions is now rebuilt, a map without footholds is refused, and an empty graph is never saved.
  - A turned stage 2/3 sigil respawns about 10 s later and the stock reactor script counts every turn, so the run
    remembers the sigils it saw turned and never turns one twice.
  - Before a run a companion with fewer than 4 free ETC slots sells its monster drops at a shop (ores and quest items
    kept); Jack's start check names whoever is still short.
  - Stage 5: the magician room gets Teleport edges and the thief room Flash Jump edges (only for run members in that
    room); Jack cautions when no thief can Flash Jump. A room grind prefers the room's own monster kinds.
  - Stage 5 relics: C0's drop spots lay on each relic area's left edge, and two carriers stopped 3 px short with drops
    that would miss, for good. The spots sit 30-40 px inside now (a test on the hall's geometry checks every drop
    within reach), and a carrier drops as soon as its drop would land in the area.
  - The warrior room needs accuracy: Hadrik (DEX 40) hit nothing on the Guardians (level 120, avoid 40) and swung Shout
    from 277 px forever, since a swing that reaches holds the grind where it is. `!bossgear Hadrik cwkpq` (accuracy for
    the Grandmasters, avoid up to 55 at level 130) took him to 208 accuracy and every hit lands. The kit refuses a
    companion inside an event, so it goes on before the run. A close swing that reaches a lone monster on the Agent's
    level from afar now walks in first (as it already did for detached boss bodies): Shout from 196 px gave a kill a
    minute, Brandish cleared the room's 21 monsters in about 3 minutes.
  - **Stage 5 passes live (2026-10-23 12:41-12:51):** every room cleared or broken, every master asked, all five
    weapons placed (glpq5=5) in 9.5 minutes, and the companions took the hall's exit to the Council Hall on their own.
    The archer room's five Master Guardians (7,000,000 HP each) are its longest fight.
  - The companions going ahead leave the human leader in the hall. A checkpoint to the stage the run is on now brings
    whoever is still behind; a GM `!warpto` to a companion inside the event takes the leader out of it and ends the
    run.
  - Stage 6: a member entering a stage now drops the walk its last stage's play gave it (they stood at the Council
    Hall's spawn, aiming for the unity hall's portal). The Battle Statue opened nothing for a GM-hidden leader: the
    client sends no NPC talk while its player is hidden, so a test leader unhides for each talk. The guards (72M HP)
    fall in about 4-5 minutes.
  - The Grandmasters need a damage scale: crew C dealt them about 10M a minute against 929M HP, so the first run ran
    out the Council Hall's hour with Rellik at 69%. boss-rules `companionDamagePercent` 700 on the six Grandmaster
    ids (adds keep their own strength), and the pace profile reads their HP through it. The second run took about
    68M a minute.
  - Ranged members with low HP (Storm383, NonThePriest: 6,716) stood 1-30 px outside a Grandmaster's touch keep-out
    and never moved, since a walk ends 30 px from its target: the Pink Bean fix for this (walk 30 px inside the edge)
    is ported. Storm383 went from 10-18 attacks a minute to 45-63.
  - A member inside a touch by the wall was sent to the gap beyond it (Storm383, 0 attacks for four minutes, then dead).
    A gap with no floor under it now gives way to the one on the other side (the platform comes from the member's
    navigation-graph region).
  - CWKPQ had no boss supply standards, so companions came in with the party quest default (20/25 potions, 500
    projectiles): Storm383, Hadrik and iWantPanda died in the Council Hall with none left, and NonThePriest threw 2,400
    stars in 18 minutes. `BOSS_STANDARDS` cwkpq=600/600 and `AMMO_STANDARDS` cwkpq=4800 now. Checked live: passing
    through New Leaf City (the approach town; trips out of Jack's hall are held) four of five bought up to 600/600, and
    New Leaf City's store recharged NonThePriest's Ilbis to 5,200 (it sells no stars, but recharges stacks he owns).
    Mo (9201099, Phantom Forest 610010004) is in the supplier catalog too: the only star seller near the keep, for a
    claw user with no stacks left, and a potion shop for keystone errands in Crimsonwood.
  - The boss gate fires when the owner enters Jack's hall, not when they log in there: logged in beside Jack, the
    crew said nothing until he walked out and back. iWantPanda's readiness trip expired while an earlier general potion
    visit of hers was still open (she retries five minutes later).
  - **Stage 6 and the Armory pass live (2026-10-23 14:29-15:23, from the Council checkpoint):** guards 4 minutes,
    the Grandmasters about 30 minutes at 700% (Hsalf, physical defence 8000, last, with two fighters left), the third
    statue talk cleared the keep, the companions walked through the Secret Chamber, waited for the leader, and broke
    their box shares in the Armory ("the top left boxes are yours, we'll break the rest"). The record: 2,980 s, cleared;
    its masters span (2,627 s) includes 13 minutes the leader took to make the third talk.
  - Open: Margana (she heals every monster within 800 px for 500k every 30 s) is never targeted first; the grind takes
    the nearest Grandmaster. NonThePriest has no Triple Throw at Lv140 (a persona build gap). The Armory loot the
    companions took is not logged.

## Questions for you (answered, kept for the record)

1. **Keystone errand: real hunting or a ledger?** Recommended: a companion without a keystone hunts for real (live)
   when a live bench slot is free or a player is on the map, and otherwise progresses on the bench ledger at its
   measured kill rate. The other choice is to keep today's waiver only (companions get the quests only by following
   their owner). If you do not answer, I use the recommendation.
2. **Should bots also do the exchange chain (Subani, Elpam, Taggrin, *The Right Path*) and trade badges with Joko?**
   It is not needed to join. Recommended: later (phase C10). If you do not answer, it waits.
3. **Trap damage in stages 3 and 4: should bots take it?** A human takes menhir and flame hits; a bot would not.
   Recommended: yes, the server applies the same hit to a bot that crosses an active trap, using values measured from
   a human client in C0. If you do not answer, I use the recommendation.
4. **May I fix two stock script faults?** (a) A lost stage-5 weapon can never be re-issued (`glpq5_room` bit), which
   locks the run. (b) The stirge plate script `reactor/6109013.js` calls `killAllMonsters(true)`, which does not
   exist. Recommended: fix both, inside the scripts only. If you do not answer, I leave them.
5. **Lineup rule at Jack.** Recommended: refuse to start companions without one member of each archetype (hard), and
   warn below 2 of each or below 8 members (soft). If you do not answer, I use the recommendation.
6. **Test crew.** A full check needs about 10 bots at Lv120-150 in 4th job, two of each archetype, including two
   pirates, with boss gear. Which crew may I raise with `!setlevel` / `!bossgear`? Recommended: a new crew C under a
   new GM test owner, so the bossing crews A and B stay as they are. If you do not answer, I ask again before C9.

## Words used

- **Keystone:** Crimsonwood Keystone 3992041, a SETUP item (slotMax 1, trade-blocked). The Gate (NPC 9201114 or
  portal `in06` in Hall of Mastery 610030010, `glpqEnter.js`) lets only holders into the recruit map 610030020.
- **Recruit map:** Hall to Inner Sanctum 610030020, where Jack (9201113) makes the expedition.
- **Sigil:** a class reactor that turns only for listed skill ids. Stage 2 and 3 use 6109000-6109004, stage 4 uses
  6109016-6109020.
- **Archetype:** one of the five job branches, `jobId / 100 % 10` (1 warrior, 2 mage, 3 bowman, 4 thief, 5 pirate),
  the same "job niche" the stage-5 portals read.
- **Mirror (quest):** `AgentPartyQuestSyncService`, which copies a human's quest start, progress and completion to the
  Agents in his party (`Quest.java:332`, `:355`, `:420`, `:449`).
- **Mirror (boss):** P8's `AgentBossAttackMirror`, which applies client-controlled boss attacks to bots.
- **Errand:** a companion leaves its owner or its bench duty, does one job elsewhere and comes back (`JobErrands.java`).
- **Death gate:** a clause in `AgentDeathTickCoordinator` that holds a dead member in the event until the clear.
- **Effort sizes:** S is 1-3 days, M is 1-2 weeks, L is 2-4 weeks (BOT_MANAGEMENT_PLAN.md:544).

## Part A: the keystone prequests

### A1. The chain on this server

From `wz/Quest.wz` (QuestInfo, Check, Act) and `scripts/quest/82xx.js`. Quest prereqs are exact states: "done" is 2,
"in progress" is 1. Every quest script here only needs to be accepted; none warps, times or branches.

**Needed to enter CWKPQ** (the minimum path, level 70 and up):

| Order | Quest | Start and end | Needs | Reward |
|---|---|---|---|---|
| 1 | 8222 The Brewing Storm | Lukan 9201098, Phantom Road 610010003 | 10 Stormbreaker Badge 4032006 (taken). Stormbreaker 9400581 (Lv80) drops it at 20%; nearest is Forgotten Path 610010005 | 85,000 EXP |
| 2 | 8230 Stemming the Tide (start) | Jack 9201096, Cavern of Pain 610020011 | Nothing (no prereq) | Nothing yet |
| 3 | 8223 Storming the Castle | Lukan | 8222 done, 8230 in progress. Kill 75 Windraider 9400576, 75 Firebrand **9400578**, 75 Nightshadow 9400579 | **Keystone 3992041**, 220,000 EXP |
| 4 | 8230 (finish, optional) | Jack | Holds 3992041 (not taken) | Text only |

Lukan's script re-issues a lost keystone once 8223 is done (`scripts/npc/9201098.js`). The Gate checks the item only,
never a quest.

**Optional** (the exchange): 4913-4915 *Subani's Legacy* and 4916-4917 *Lost!* / *ReVersal* lead to 8219 *Finding
Jack* (Map of Phantom Forest 3992040, 175,000 EXP). With 8224 *The Fallen Woods* (50 Phantom Seeds for Taggrin) that
opens 8225 *The Right Path*, which alone unlocks Joko (9201097) and Fiona (9201095). 8226 *The Fallen Warriors* (50
Elder Ashes) is not needed for either here, although GMS needed it for Fiona. 8221 *Mark of Heroism* gives the Marker
of Heroism 3992039, which opens the Valley of Heroes statue portals. *How to get to Lukan* is not a quest in v83.

### A2. Where the bots stand today

| Need | State | Evidence |
|---|---|---|
| Reach New Leaf City | Exists: Kerning subway leg | `agents/catalogs/npc-travel-legs.json` |
| NLC to Haunted House 682000000 by taxi 9201056 (15,000 mesos) | **Missing** leg; taxi entry has `destinationMapId: 0` | `town-life/new-leaf-city.json` |
| Haunted House to Bent Tree 610010000 (`right01`) | **Missing**: 682000000 is outside the masteria group (600000000-620000000) | `world-portal-route-graph.json` |
| Dead Man's Gorge to Lukan (`U5_4`, `A1_2`, `A2_2`) and Forgotten Path | In the graph | same |
| Valley of Heroes 1 and 2 climb (610020000/610020001) to Lower Ascent, then `CM3_2` to Jack | **Unproven**: the graph treats it as a portal edge. A fall in Lower Ascent drops you back to Valley 1 | portal data; C0 probe |
| Firebrand 9400578 for the kill count | **Missing**: it spawns only when 9400577 dies (`Mob.wz/9400577.img.xml` revive). A spawn-list lookup finds it only in CWKPQ rooms | drop and spawn data |
| Scripted quest start checks prereqs | **Hole**: `CosmicPrimitiveCapabilityGateway.startQuest` runs a start script without `Quest.canStart`. A bot could start 8223 without 8230 in progress | survey, gateway :615-628 |
| Bots know the prereqs | **Missing**: `scripts/config/knowledge/server-knowledge.json` quests have no prereq quests or states | knowledge export |
| Keep the keystone, Map and Marker | **Risk**: SETUP quest items are not "needs", so a sell plan could sell them | `CosmicMarketSellerPlanReader` |
| Companions going with the owner get the quests | **Works** (quest mirror) | `AgentPartyQuestSyncService.java` |
| A companion does a quest chain alone | **Missing**: journey Agents have a quest scheduler (`AgentVictoriaQuestSchedulerRuntime`, Victoria under Lv30 only); companions have only job errands | survey |

Level note: the chain allows Lv70, but its mobs are Lv80 (Stormbreaker), Lv90 (Firebrand) and Lv100 (Nightshadow).
The errand should start at Lv85 and up (decision C2-1), and CWKPQ itself needs 90.

### A3. How the keystone errand works (phase C2)

The shape is `JobErrands` (`src/main/java/server/living/roster/JobErrands.java`), which already sends a companion off
to its instructor and back with its seat kept.

1. **When.** A companion at Lv85+ with no keystone sets off when its owner turns the CWKPQ key on for it (the Crew
   window or chat: "get your keystone"), or when a CWKPQ desk names it as missing one. A benched companion goes
   between its bench duties. It says so in its own words: "i don't have a keystone yet. going to see Lukan."
2. **Road.** Kerning subway, NLC taxi, Haunted House, Bent Tree, Dead Man's Gorge, Phantom Road. The road time is
   paid as for job errands; the companion loads live on Lukan's map.
3. **Steps.** Each step is a small objective the errand runs in order. It always checks prereqs itself (fix A2 row 6),
   so it can never start 8223 before 8230:
   - talk to Lukan, start 8222;
   - hunt Stormbreakers at Forgotten Path until it holds 10 badges, then hand them in;
   - travel to Jack (Valley of Heroes climb, or the statue portals if it holds the Marker) and start 8230;
   - back to Lukan, start 8223;
   - hunt the three kinds. For Firebrand it hunts 9400577 and finishes the revived 9400578;
   - hand in to Lukan, receive the keystone, say so, come back to its owner or duty.
4. **Live or ledger** (Question 1). While a player is on the hunting map or a live bench slot is free
   (`BENCH_LIVE_MAX`), the companion hunts for real and is visible. Otherwise the bench ledger advances its count at
   the kill rate it measured live for that mob, so a ledgered errand never runs faster than a live one.
5. **Interrupts.** Called back by its owner, it saves its step and quest progress (the quest log holds the counts) and
   resumes later. A death sends it to the nearest town and it carries on.
6. **Owner on the same chain.** If its owner is doing the chain too, the companion joins its owner instead and the
   quest mirror does the rest, as today.
7. **Help for the owner.** A companion that holds a keystone can answer "how do I get into Crimsonwood Keep?" from
   server data (knowledge grounding), once C1 exports prereqs.

## Part B: the PQ itself

### B1. The stages on this server

From `scripts/event/CWKPQ.js` and the stage scripts. Timers restart the first time anyone reaches each stage.

| Stage | Map | Time | What clears it | Implemented by |
|---|---|---|---|---|
| 1 Inner Sanctum Hallway | 610030100 | 2 min | Someone uses the hidden portal `next01` at (1547,336) under the lion's head. That sets `glpq1`. Then everyone takes `next00` at (824,328). 20 Master Guardians (7M HP, immune to all elements) spawn at 60 s if anyone is still there | `glpqPortalDummy.js`, `glpqPortal0.js` |
| 2 Forgotten Storage Chamber | 610030200 | 10 min | All five sigils (6109000-6109004), each hit with a skill of its class | `reactor/6109000-6109004.js`, `glpq2 == 5` |
| 3 Test of Agility | 610030300 | 10 min | Five sigils high up a jump map (CANNOTJUMPDOWN), and five passes through the top portal (215,-1107), which drops you to the bottom | `glpq3 >= 5 && glpq3_p >= 5`; menhir traps move with each sigil |
| 4 Test of Wit | 610030400 | 10 min | 8 sigil spots shuffled each run: 5 real class sigils (6109016-6109020) plus fakes. Any 5 activations clear it. 48 invincible Blood Stirges; flame traps toggled by touch plates | `CWKPQ.js:98-150`, `glpq4 >= 5` |
| 5 Test of Unity | 610030500 | 20 min | Five class rooms, entered by job niche. Each gives one weapon (4001256-4001260), dropped onto its relic reactor (6109005-6109009) in the hall | `glpqPortal00-04.js`, room NPCs 9201107-9201111, `glpq5 >= 5` |
| 6 Grandmaster Council Hall | 610030600 | 60 min | Event leader talks to the Battle Statue 9201115: 10 Master Guardians + 20 Crimson Guardians spawn; when the hall is empty, he talks again and the four masters spawn; when empty again, he talks a third time to clear | `scripts/npc/9201115.js`, `glpq6` 0-3 |
| Secret Chamber | 610030700 | - | Walk through | `glpqPortal7.js` |
| Armory (bonus) | 610030800 | 1 min | Break 30 boxes (5 hits each); items spray out. Mastery books, Gelt Chocolate 2022121, Tao materials for Fiona | `reactor/6102002-6102005.js`, reactordrops |

Stage 5 rooms:

| Room | Map | To get the weapon |
|---|---|---|
| Warrior | 610030510 | Kill 21 Crimson Guardians (120k HP each), climb to the master at (117,-2259), talk |
| Bowman | 610030540 | Kill 5 Master Guardians (7M HP each, immune to all elements), talk |
| Mage | 610030521 | Kill 13 Firebrand, 7 Stormbreaker, 2 Elderwraith, talk. Fall pits send you back |
| Thief | 610030530 | Break the 5 eye reactors 6108004 (any hit), talk |
| Pirate | 610030550 | Hit the 5 chests (4 hits each); one real (6102001) drops the Forbidden Gun; pick it up |

Sigil skills (Reactor.wz `activeSkillID`):

| Archetype | Skills that turn its sigil |
|---|---|
| Warrior | 1111008 Shout, 1211002 Charged Blow, 1311003/1311004 Dragon Fury |
| Bowman | 3111006/3211006 Strafe, 3111005 Silver Hawk, 3211005 Golden Eagle, 3221001 Piercing Arrow |
| Mage | 2001005 Magic Claw (any mage), 2111002 Explosion, 2211002 Ice Strike, 2321003 Bahamut |
| Thief | 4111002 Shadow Partner, 4111005 Avenger, 4211001 Chakra, 4211004 Band of Thieves, 4211006 Meso Explosion |
| Pirate | 5001001 Flash Fist (any pirate), 5111005 Transformation, 5121001 Dragon Strike, 5211002 Gaviota, 5221004 Rapid Fire |

Every 3rd-job branch has at least one, but a bot must have put SP into it. C0 checks every persona's SP build.

Boss numbers (Mob.wz): Margana 200M HP (immune to holy; resists lightning, fire, ice), Red Nirg 255M (no element
traits; revives into Scarlet Phoenix 9400596 and Azure Ocelot 9400597), Rellik 231M (immune to holy, weak to fire),
Hsalf 233M (immune to holy, weak to ice). All are Lv130. With the guards, stage 6 holds about 1 billion HP for 60
minutes. Bot damage already applies element effects (`CombatFormulaProvider.java:307-320`), so a Bishop's holy attacks
do nothing to any master but Red Nirg.

### B2. What we reuse and what is missing

| Need | Reuse | Missing |
|---|---|---|
| Sign-up of 6-30 at Jack | Boss track: `AgentBossRunService.enlist` / `AgentBossRunSession.signUp` (runNpc with join selection, then check `expedition.contains`), `CompanionBossAllies` (bench allies travel, overflow bot parties of up to 6), multiple owners (`COMPANION_BOSS_OWNERS_MAX`), the five boss NPC hooks (e.g. `scripts/npc/2083004.js:46-62`) | Hooks in `scripts/npc/9201113.js`; a CWKPQ lineup rule (archetypes); keystone filter for allies |
| Stage play | PQ track: RJPQ shape (Session, Coordinator, StagePlay, CompanionRun, PaceProfile, TestService); `PartyQuestGateway` (runNpc, enterPortal, property, eventMembers) | A `partyquest/cwkpq` package, not tied to a party |
| Archetype rule | `AgentEpqRosterRequirementPolicy` (one of each of five), OPQ five-branch preference (`PqEntrance.BranchPreference`) | Two-of-each version |
| Sigils | `hitReactor` primitive | **Skill-on-reactor primitive** (B3) |
| Touch plates | none | Touch primitive calling `ReactorScriptManager.touch/untouch` (only for stage 4 flames) |
| Weapon onto relic | `dropInto` with `predictDropLanding` (RJPQ, OPQ) | Relic drop areas are high in the hall (e.g. sword relic x 1093-1293, y -665..-454); C0 probe |
| Hidden portals | `enterPortal` (walk on, press up) | Nothing |
| Jump quest | Nav graph jump/rope edges; RJPQ Haste jump | **Moving traps, trap damage, CANNOTJUMPDOWN route** (B4) |
| Class rooms | Grind, NPC talk, reactor hits, pick-up | Room choice by job niche; weapon hand-over |
| Council Hall | `AgentBossPriorityFight`, `boss-rules.json`, boss mirror, server authority | A `cwkpq` boss-rules entry; leader statue calls |
| Deaths | Death gate chain (`AgentDeathTickCoordinator.java:33-56`) | A CWKPQ gate (hold the corpse; a revive unregisters and can end the run) |
| Armory loot | Loot clauses (`AgentLootEligibility.java:76-127`), boss loot rules (P12 G10) | Box areas per member; etiquette |

### B3. The skill-on-reactor primitive (phase C4)

`hitReactorWithSkill(agent, reactorOid, skillId)`:

1. Pick the skill: the sigil's `activeSkillID` list, filtered to skills the bot has at level 1 or more and can cast now
   (MP, cooldown, weapon). Prefer an attack the bot already uses (Avenger, Strafe, Explosion, Dragon Strike), then a
   summon (Silver Hawk, Gaviota, Bahamut), then a buff (Shadow Partner, Chakra, Transformation).
2. Stand where a player would: inside the skill's range box (Skill.wz `lt`/`rb`) from the reactor, facing it, on the
   reactor's ground.
3. Cast it for real: MP and cooldown are paid, and the attack, summon or buff is broadcast as for any other cast. An
   attack with no monster targets is still shown.
4. Then call `Reactor.hitReactor(true, x, stance, skillId, client)`. A wrong skill does nothing, as for a player.

This is narrow on purpose. The existing `hitReactor` stays as it is for every other reactor.

### B4. Stage 3, the jump quest (phase C5)

C0 answered the reach question (docs/CWKPQ_C0_RESULTS.md, check 1a):

- **Haste is the key.** At base 100/100 a climber reaches only the two low sigils; a 110 px flat gap at y -684 stops
  it. With Haste 20 (145/120), or from 120/120 or 145/110 up, all five sigils and the top portal are reachable (38-55
  jumps). Flash Jump or Teleport reach everything even at base. Every thief at 3rd job or above holds Haste 20, and
  Haste is a party buff, so a thief casts it on the climbers before they set off (the RJPQ Dark Lab runner already
  casts Haste through `AgentCombatBuffRuntime.tryCastExplicitUtilityBuff`). With no Haste in the run, the high sigils
  are a human's job and the desk says so.
- **The graph must obey the map's no-jump-down limit.** The server never reads CANNOTJUMPDOWN, and the nav graph
  authors down-jumps everywhere; on 610030300 and the warrior room 610030510 a bot must not take them (walk-off
  drops stay). C5 adds that rule to the graph.
- **The top portal is targeted on its long floor** (footholds 397-479, x 145..295 at y -1104), not on the short
  duplicate foothold 442 its point snaps to, which only a down-jump reaches.
- **Traps.** The menhirs are `trapGL` map objects that move with `moveEnvironment("menhirN", 1)` after each sigil.
  Their damage is client-side. C0 measures, with a human client, which hits land where and for how much
  (damagefrom -3/-4 in `TakeDamageHandler`). With your yes (Question 3), the server applies the same hit to a bot whose
  path crosses an active trap, so bots have to heal like players do.
- **Who climbs.** One climber per archetype, chosen by speed and jump. A human of that archetype may climb instead:
  the companion yields once the human's sigil turns. Each climber hits its sigil, then takes the top portal, which
  counts a pass and drops it to the bottom. Five passes are needed in total, so climbers who finish early climb again
  if fewer than five passes are counted.
- **No teleport cheat.** A climber without Haste (or its own Flash Jump or Teleport) never goes for a high sigil.

### B5. Stages 1, 2 and 4 (phase C4)

- **Stage 1.** The companion nearest to the lion's head takes the hidden portal, then everyone takes the exit. Target:
  done before the 60 s guardians. A companion says where the way is the first time: "it's behind the pillar under the
  lion's head".
- **Stage 2.** Each archetype's nearest member takes its sigil (claim leases like RJPQ's `claimDoor`); the others hold
  by the exit.
- **Stage 4.** C0 found the fakes pixel-identical to real sigils (6108001 the warrior's, 6108000 the mage's, 6108005
  the thief's), so neither a player nor a bot can read a spot's class from its picture. But a fake turns on any hit
  and a real sigil ignores a wrong skill. So companions test a spot the way a player does: one ordinary hit. A spot
  that turns was a fake (and is spent); one that does not is real, and the member of the class whose picture it shows
  hits it with its sigil skill. Spots tried are shared in party chat ("the right one up top is fake"). The flame
  plates are optional; companions step on them only if the trap damage of Question 3 is on.

### B6. Stage 5, the class rooms (phase C6)

- Each archetype's members enter their own room (`getJobNiche`). A human goes to his own room; companions of his
  archetype go with him.
- The weapon goes to the member who talks to the room's master (or picks up the gun). That member carries it back and
  drops it into its relic's area. Nobody else may drop it. If its holder dies, the death gate keeps the corpse in the
  event, and the weapon with it (exclusive items are removed only on unregister, `CWKPQ.js:73`).
- **Pace risk: the bowman room.** Its five Master Guardians hold 35M HP and are immune to every element, so only
  physical bowman attacks count. A rough estimate: two Lv100 Rangers need about 16 minutes of the room's 20. The pace estimate
  must check this, and the desk should warn when the bowmen are under 4th job.
- With Question 4 (a), a lost weapon can be re-issued once when no copy exists in the instance.

### B7. Stage 6, the Council Hall (phase C7)

- The event leader must talk to the Battle Statue three times (`cm.isEventLeader()`). With a human leader,
  companions call him when the hall is empty: "the guards are down, talk to the statue." With an Agent leader (not
  planned here), it would talk itself.
- A new `cwkpq` entry in `boss-rules.json` sets target priority for the four masters and their summons, and lets
  each member prefer a master it can hurt: fire mages on Rellik, ice mages on Hsalf, Bishops on Red Nirg or healing.
  Recommended order: one master at a time, Red Nirg last (his revive adds two more). This is a prior; the learner can
  change it later.
- The boss mirror and server authority apply to all four masters, as for the P12 bosses, so masters really hit bots.
- **Potion check.** These maps set field limit bit 0x1000, which Cosmic names CANNOTUSEPOTION (`FieldLimit.java:39`)
  and never checks on the server. The client's own flag list may mean something else by 0x1000. C0 checks with a human
  client whether potions work in 610030400 and 610030600. Bots must obey whatever a human client obeys.

### B8. Armory, claims and release (phase C8)

- The Armory lasts one minute. Each member gets a share of the 30 boxes by area, so no two members hit the same box.
- Loot (proposal): humans may pick up anything. A companion picks up only what its own class can use (its weapon
  types, its class's mastery books), leaves Gelt Chocolate for physical attackers and magic-attack food for mages, and
  never takes Tao materials a human could want. The CWKPQ loot clause is refuse-only, like the others.
- After the clear the run ends in 610030020; companions follow their owner, and allies go back to their duties.

## Part C: the phases

| Phase | What | Size | Needs |
|---|---|---|---|
| **C0** | Step 0 checks, no gameplay code: offline nav probes (610030300 sigils and top portal; 610030400 spots; 610030500 relic areas; warrior room top; Valley of Heroes 1 and 2; Lower Ascent to Jack); a human client measures trap hits and checks potions at 0x1000; Reactor.wz fake-sigil pictures; every persona's SP in a sigil skill; stirge script behaviour; mirror behaviour when a companion already holds a keystone | S | - |
| **C1** | Keystone road and quest data: Haunted House in the masteria group, NLC taxi leg, Valley climb edges (from C0), Firebrand revive link in the hunt planner, prereq states in the knowledge export, `Quest.canStart` before any bot scripted start, keep SETUP quest items out of sell plans | M | C0 |
| **C2** | Keystone errand (A3): errand states, live or ledger hunting, interrupts, lines | M | C1, Question 1 |
| **C3** | Expedition desk: hooks in 9201113.js, sign-up of party companions and bench allies through the boss track, lineup rule (Question 5), keystone filter, follow guard (companions without a keystone wait in Hall of Mastery and say why), CWKPQ death gate, `cwkpq` key in `CompanionBossKeys` (built 2026-10-20) | L | - |
| **C4** | Skill-on-reactor primitive (B3) + stages 1, 2, 4 (B5) + `!cwkpqtest status / checkpoint <stage> / stop` (built 2026-10-20) | M | C0, C3 |
| **C5** | Stage 3 jump quest (B4), trap damage if Question 3 is yes (stage 3 built 2026-10-20; traps wait for the live measurement) | L | C0, C4 |
| **C6** | Stage 5 rooms and relics (B6), weapon re-issue if Question 4 is yes (built 2026-10-20) | M | C4 |
| **C7** | Stage 6 Council Hall (B7): boss rules, statue calls, mirror for the masters (built 2026-10-20; the mirror waits for C9) | M | C3 |
| **C8** | Armory, claims, release, pace profile and desk estimate, guidance lines once per run | M | C6, C7 |
| **C9** | Live checks with crew C, GM-first (`!cwkpqtest checkpoint` per stage), then one full human-led run | M | all, Question 6 |
| **C10** | Optional exchange chain and Joko badge trades for bots | M | C2, Question 2 |

C1-C2 and C3-C4 can be built in parallel; they touch different files. Everything ships behind the `cwkpq` key, off.

## Risks

1. **Stage 3 may be beyond bot movement.** If C0 shows sigils out of reach, that archetype needs a human there.
2. **Client memory.** Up to 30 members and about 1 billion HP of fighting in one map, with summons. The client memory
   gate from the bossing plan applies to stage 6.
3. **The run ends at 6.** A run of 7 survives one departure; a run of 6 none. The desk warns below 8.
4. **The human leader must not revive.** If he dies and takes the revive, the stock rule ends the run for everyone.
   Companions can warn him once; they cannot stop it.
5. **Quest mirror edge.** When the owner completes 8223, the mirror force-completes it for a companion that may
   already hold a keystone (slotMax 1). C0 checks that this does not fail or duplicate.
6. **Ledger fairness.** A ledgered keystone errand must never finish faster than the same companion would live.

## Stock facts worth knowing

- Jack's sage 9201103 says "level 100"; the real gate is 90. The requirement string says "2 minutes"; that is only
  stage 1.
- Stage 3 counts top-portal passes, not players; stage 4 counts any 5 activations, not 5 classes.
- `9201115.js` spawns guards only on the left half and does not register the masters with the event.
- A late joiner at stage 6 is sent to 610030700, not 610030800.
- No daily limit applies: `BossLogEntry` has no CWKPQ entry.
- Clear rewards are stage EXP and mesos, the Armory boxes and this server's Perfect Pitch (10-18,
  `EventInstanceManager.java:1227`). There is no clear item.
