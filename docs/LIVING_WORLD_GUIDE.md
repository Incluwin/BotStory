# Living world: how to run it, play it and author it

Implementation of `docs/LIVING_RPG_PLAN.md` (first cut, 2026-09-20). Everything lives under
`server.living.*` plus small hooks in the engine; content is YAML under
`scripts/config/living-world/`.

## Switches

`config.yaml` -> `living_world:` (`ENABLED`, `WORLD`/`CHANNEL`, `CONTENT_DIR`, presence and
sleep timers, the Jev per-player budget, bond thresholds, roster/bench knobs, rumor pacing).
`ALWAYS_AWAKE_TOWNS: "lith-harbor"` keeps that town's Residents materialized with nobody around
(handy for testing; clear it for production so empty towns cost nothing). `ROUTINE_ZONE` is the
IANA zone persona routines and rumor schedules are read in (the JVM itself runs in GMT, so
without it "23:00" means 19:00 Eastern). Tables come from Liquibase changeset `29-living-world`.
Set `<Logger name="server.living" level="debug"/>` in `log4j2.xml` to get one reconcile decision
line per persona per tick when a Resident does not show up.

## What players do

Residents stand in their home towns (all seven Victoria towns have a cast). Talk to them in map
chat with their name anywhere in the line, or stand next to one and speak, or whisper them.

| Say | Effect |
|---|---|
| `hi Olwen` | greeting; first greeting creates the bond (stage MET) |
| `Olwen quest` / `any work?` / `need a hand?` | offers the first quest you qualify for |
| `accept` / `sure` / `ok` | starts the offered quest (a TALK first step completes immediately) |
| `done` / `here you go` / `brought it` | turns in items or reports a finished step |
| `Hadrik join me` / `come with me` | asks to recruit; the Resident states its price or why not |
| `pay 5000` | pays part of an open price |
| `tell me about Tobin` | persona-specific gossip, sets an `asked.<persona>` bond flag |
| `bye`, `thanks`, `who are you` | in character |
| `@quests` | your active resident quests and current step |
| `@roster` / `@bond <name>` | companions; relationship and haul with one persona |
| `Olwen talk` / `menu` | opens the persona's NPC window: work, quests in progress, travel, who are you |

Quest offers, completions, contract terms and `@quests` open a real NPC dialog window (the persona
borrows a townsperson's portrait; set `portraitNpcId` on a persona to pick one). Progress messages
appear as pink system text; long chat replies arrive as whispers from the persona.
Anything the regexes miss goes to the `resident-intent` Jev judge (budgeted per player per day)
which either acts, hints (`looking for work? say 'quest'`) or lets the Resident shrug.

### Companions (roster)

When a contract is met the persona joins your party as a companion (the normal `follow me` /
`stop` / `grind` companion commands work, plus the phrase packs). Roster commands work whether
the companion is with you or benched:

`call <name>`, `bench <name>`, `swap <a> for <b>`, `roster`, `everyone bench`, `call the usual`,
`<name> camp here`, `<name> train | farm meso | farm <item> [n] | gather for my quests | go sell |
set up a stall | rest`, `<name> what did you get`, `<name> hand it over | send it | bank it`, `<name> wages` / `<name> pay wages 5000`,
`release <name>`.

**Delegated remote work.** Prefix a job with `go` to send an *active* companion off without
giving up its party slot: `<name> go farm meso here`, `<name> go train`, `<name> go farm
snail shell 30`, `<name> go sell`. `here` means the map you are standing on; otherwise it works
at its camp (fighting jobs need a field camp, selling works from town). The roster shows it as
`away`; `<name> come back` recalls it with the usual travel ETA. While away it follows every
bench rule (ledger, live sessions, haul, no own-quest credit) and `freeSlots` still counts it, so
two humans sharing a party see each other's away companions in the slot count.

Calling a benched companion is a trip: it whispers an ETA from its camp (Victoria road hops, 20 s
plus 40 s per town) and appears beside you when it arrives; a recall whistle
(`living_world.RECALL_WHISTLE_ITEM_ID`, off by default) makes it instant. Up to `BENCH_LIVE_MAX`
benched or away companions on TRAIN, FARM_MESO or FARM_ITEM duty are materialized at their camp
fields in rotating slices (`BENCH_LIVE_SLICE_MS`, extended while a human watches) and fight for
real at background priority; every two minutes the meso and stacks a farming session actually
looted are swept into the haul, and the session ends at the daily farming cap, when the item
count is met, when it would outlevel you, or when it dies back to town. The offline ledger only
moves the clock while a companion is live, so nothing is credited twice. Prepaid wages cover
upkeep; otherwise the haul pays and an unpaid haul halves efficiency. Owners who die three times with a companion whose contract lists `LEADER_DEATH_3X` lose
it; party quest clears (`eim.clearPQ()`) fire `PARTY_QUEST_CLEAR` steps.

Party cap is 6: with two humans you share four companion slots. Benched companions accrue EXP
toward `ownerLevel - paceGap` (banked, granted when called), or meso/items into a haul under the
bench efficiency factor and the daily farming cap; a persona never farms its own quests from
the bench (24 h haul provenance is checked at turn-in).

### The in-game quest log

Living quests can appear in the client's quest log (in progress / completed tabs, grouped under
the persona's name). It takes one WZ patch:

1. `tools\living-world-questlog.bat` - assigns every quest a stable client id (30000-32767) in
   `quest-log-ids.yaml` and exports name/description text to `target/living-quest-log.json`.
   New quests get the next free id; ids are never renumbered (they live in players' save data).
2. `cd MapleEditpp && python living_world_quests.py` - writes QuestInfo/Check/Act entries into
   `out/Quest.wz` (stacking with other edits) and stages the server XML; deploy Quest.wz from the
   MapleEdit dashboard so the patch server ships it to every client.
3. `living_world.QUEST_LOG_MIRROR: true` - the server then sets the matching quest STARTED on
   accept and COMPLETED on completion (`QuestLog`), and reconciles older ledger state at login.

Leave the flag off until the WZ is shipped, or clients list nameless quests. The entries carry
`lvmin 200` so the client never shows them as "available" - Residents offer them in person.

## GM commands (`!world`, GM 5)

`status`, `census`, `reload` (re-reads content, reports lint errors), `residents`,
`wake <town|persona>` / `sleep <town>`, `arc <persona> <chapter>`, `flag set|clear <flag>`,
`bond <player> <persona> <points>`, `rumor play <id>`, `phrases [approve|reject <text>]`,
`teach <canonical> <phrase...>`, `haul <player>`, `events <player>`.

`tools\living-world-lint.bat [dir]` validates content offline (exit 1 on errors);
`tools\living-world-questlog.bat` exports the quest log (above).

## Authoring

One YAML file per town under `personas/`, `quests/`, `contracts/`, `arcs/`, `rumors/`; each file
holds a list. `loadouts.yaml` and `command-phrases.yaml` are single files. Schemas are the records
in `server.living.config.Content`; the plan's section 4 has annotated examples.

Persona dialogue intents the runtime looks up (chapter overrides first, then the persona):
`greetFirst`, `greet`, `idle`, `noWork`, `nothingDue`, `who`, `bye`, `thanks`, `smallTalk`,
`speakPlainly`, `declined`, `offer`, `join`, `bench`, `leave`, `refuse`, `refuse.<blocker>`
(`bond`, `flags`, `arc`, `taken`, `cooldown`, `roster-full`, `not-recruitable`),
`refuseOwnErrand`, `delegate`, `onMyWay`, `leaveDeaths`, `questOffer.<quest>`, `questAccept.<quest>`, `questWaiting.<quest>`,
`questComplete.<quest>`, `about.<persona>`, `aboutUnknown`, `bond.<stage>`, `chapterEnter`.

Quest steps: `TALK`, `KILL_MOBS`, `COLLECT_ITEM` (optional `dropFrom` custom drop and `label`),
`DELIVER` (`to` persona, optional `take`), `VISIT_MAP`, `TALK_TO`, `HEAR_RUMOR`, `ESCORT`,
`HUNT_WITH` (giver must be co-present), `PAY`, `GIVE_ITEM`, `REACH_LEVEL`, `WAIT`,
`PARTY_QUEST_CLEAR`, `KILL_BOSS`. Any kill/collect step takes `with: <persona>`.

Contracts: `offerWhen` (bond stage, flags, arc chapter), `price` (`meso`, `items` with `keep`,
`proof.killMobs`), `terms` (`durationHours`, `maxLevelGap`, `lootShare`, `leavesOn`,
`cooldownHoursAfterLeave`, `rejoinPrice`, `ownDropsPercent`). Personas: `benchToleranceDays`
(0 = leaves after an hour on the bench, -1 = never), `paceGap`, `maxLag`, `giftItemIds`.

Phrase packs: `canonical` must be a phrase the companion classifiers accept whole; `aliases`,
`prefixes`, `tails`; `status: PLANNED` + `reply` answers deterministically for behaviours not
wired yet. The longest explanation wins, then the more specific alias.

## Where things are

| Piece | Class |
|---|---|
| bootstrap, timers, services | `server.living.LivingWorld`, engine entry points `LivingWorldHooks` |
| content model, loader, lint | `server.living.config.*` |
| persistence (`living_*`) | `server.living.store.LivingStore` |
| residents: provisioning, shaping, presence, spots, dialogue, Jev judge | `server.living.resident.*` |
| quest ledger | `server.living.quest.QuestLedger` |
| bonds and contracts | `server.living.bond.*` |
| roster, bench ledger, haul | `server.living.roster.*` |
| arcs and rumors (deterministic rumor director: waiting listener, unheard, least performed) | `server.living.arc.*` |
| client quest-log mirror and export | `server.living.quest.QuestLog`, `server.living.config.QuestLogExportMain`, `MapleEdit/app/living_world_quests.py` |
| phrase packs and learned phrases | `server.living.phrases.PhraseMatcher` (installed into `AgentChatIntentRuntime`) |
| commands | `client.command.commands.gm5.LivingWorldCommand`, `gm0.LivingQuestsCommand`, `gm0.LivingRosterCommand` |

## Not done yet (from the plan)

Live sessions for GATHER/SELL/STALL (they stay on the offline ledger; live sessions run at
`BACKGROUND_ACTIVE` priority rather than a true mutation-free `BACKGROUND_ABSTRACT` tick, which
the Agent runtime only supports for town life), per-step kill counters in the client quest
log (the mirror tracks start/complete only), and the MapleEdit authoring page for personas and
quests (content is edited as YAML and checked with `tools\living-world-lint.bat`).
