# Living world: how to run it, play it and author it

Implementation of `docs/LIVING_RPG_PLAN.md` (first cut, 2026-09-20). Everything lives under
`server.living.*` plus small hooks in the engine; content is YAML under
`scripts/config/living-world/`.

## Switches

`config.yaml` -> `living_world:` (`ENABLED`, `WORLD`/`CHANNEL`, `CONTENT_DIR`, presence and
sleep timers, the Jev per-player budget, bond thresholds, roster/bench knobs, rumor pacing).
`ALWAYS_AWAKE_TOWNS: "lith-harbor"` keeps that town's Residents materialized with nobody around
(handy for testing; clear it for production so empty towns cost nothing). Tables come from
Liquibase changeset `29-living-world`.

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

Progress messages appear as pink system text; long replies arrive as whispers from the persona.
Anything the regexes miss goes to the `resident-intent` Jev judge (budgeted per player per day)
which either acts, hints (`looking for work? say 'quest'`) or lets the Resident shrug.

### Companions (roster)

When a contract is met the persona joins your party as a companion (the normal `follow me` /
`stop` / `grind` companion commands work, plus the phrase packs). Roster commands work whether
the companion is with you or benched:

`call <name>`, `bench <name>`, `swap <a> for <b>`, `roster`, `everyone bench`, `call the usual`,
`<name> camp here`, `<name> train | farm meso | farm <item> [n] | gather for my quests | go sell |
set up a stall | rest`, `<name> what did you get`, `<name> hand it over | send it | bank it`,
`release <name>`.

Party cap is 6: with two humans you share four companion slots. Benched companions accrue EXP
toward `ownerLevel - paceGap` (banked, granted when called), or meso/items into a haul under the
bench efficiency factor and the daily farming cap; a persona never farms its own quests from
the bench (24 h haul provenance is checked at turn-in).

## GM commands (`!world`, GM 5)

`status`, `census`, `reload` (re-reads content, reports lint errors), `residents`,
`wake <town|persona>` / `sleep <town>`, `arc <persona> <chapter>`, `flag set|clear <flag>`,
`bond <player> <persona> <points>`, `rumor play <id>`, `phrases [approve|reject <text>]`,
`teach <canonical> <phrase...>`, `haul <player>`, `events <player>`.

`tools\living-world-lint.bat [dir]` validates content offline (exit 1 on errors).

## Authoring

One YAML file per town under `personas/`, `quests/`, `contracts/`, `arcs/`, `rumors/`; each file
holds a list. `loadouts.yaml` and `command-phrases.yaml` are single files. Schemas are the records
in `server.living.config.Content`; the plan's section 4 has annotated examples.

Persona dialogue intents the runtime looks up (chapter overrides first, then the persona):
`greetFirst`, `greet`, `idle`, `noWork`, `nothingDue`, `who`, `bye`, `thanks`, `smallTalk`,
`speakPlainly`, `declined`, `offer`, `join`, `bench`, `leave`, `refuse`, `refuse.<blocker>`
(`bond`, `flags`, `arc`, `taken`, `cooldown`, `roster-full`, `not-recruitable`),
`refuseOwnErrand`, `questOffer.<quest>`, `questAccept.<quest>`, `questWaiting.<quest>`,
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
| arcs and rumors | `server.living.arc.*` |
| phrase packs and learned phrases | `server.living.phrases.PhraseMatcher` (installed into `AgentChatIntentRuntime`) |
| commands | `client.command.commands.gm5.LivingWorldCommand`, `gm0.LivingQuestsCommand`, `gm0.LivingRosterCommand` |

## Not done yet (from the plan)

Realistic recall travel (called companions appear beside you; the whistle item is unused),
`BACKGROUND_ABSTRACT` bench sessions (layer 1 ledger only), the Quest.wz patch for the in-game
quest log and the proxy-NPC dialogue window, wages, `LEADER_DEATH_3X`, `PARTY_QUEST_CLEAR`
events (step type exists; nothing fires it), Director ranking for rumors, MapleEdit authoring page,
and the upstream contribution split.
