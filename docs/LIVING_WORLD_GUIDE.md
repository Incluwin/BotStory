# Living world: how to run it, play it and author it

(The generated population - citizens with lives, guilds, chatter and grounded conversation - is
built on top of this; plan in `docs/POPULATED_SERVER_PLAN.md`, runbook in `docs/POPULATION_GUIDE.md`.)

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
| double-click the Resident | the same NPC window (instead of character info); companions keep character info |

Residents carry the NPC quest icons over their heads, per player: the lightbulb when that player
can take one of its quests, the book when they can hand something in (`QuestMarkers`).

Quest offers, completions, contract terms and `@quests` open a real NPC dialog window showing the
Resident's own look and name (see Portraits below; until a Resident has one it borrows a
townsperson's portrait, and `portraitNpcId` on a persona picks one explicitly). The menu lists
each quest by name; an offer is the ask followed by the client's Accept/Decline quest window with
the REWARD/MESO/EXP icons. Progress messages
appear as pink system text; long chat replies arrive as whispers from the persona.
Anything the regexes miss goes to the `resident-intent` Jev judge (budgeted per player per day)
which either acts, hints (`looking for work? say 'quest'`) or lets the Resident shrug.

### Companions (roster)

When a contract is met the persona joins your party as a companion (the normal `follow me` /
`stop` / `grind` companion commands work, plus the phrase packs). Roster commands work whether
the companion is with you or benched:

`call <name>`, `bench <name>` (also `dismiss <name>` and `park <name>`), `swap <a> for <b>`, `roster`,
`everyone bench`, `park` (everyone), `call the usual`,
`<name> camp here`, `<name> train | farm meso | farm <item> [n] | gather for my quests | go sell |
set up a stall | rest`, `<name> what did you get`, `<name> hand it over | send it | bank it`, `<name> wages` / `<name> pay wages 5000`,
`release <name>`. Every one runs on the companion-ops thread (the reply follows a moment later). The
companion commands that would unload one (`dismiss <name>` in party chat, `logout` + `yes`) bench it
through the roster instead; plain GM Agents keep the old behaviour. `park` is a bench until P2b adds
the PARKED state.

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

### Quest markers

The server replays the icon over a Resident as a foreign avatar effect every
`QUEST_MARKER_PULSE_MS` (about one animation length), only to the players it concerns; states
are cached per player for `QUEST_MARKER_REFRESH_MS` and recomputed after quest events, map
changes, pickups and level-ups. The client anchors such effects at the feet, so the icons are
head-height copies of `UI/UIWindow.img/QuestIcon/0` and `/2` in Custom.wz
(`Custom/UI/UIWindow.img/QuestMarker/available|ready`), written by
`MapleEdit/app/living_world_markers.py [--lift PX] --install` (backs the live Custom.wz up,
installs it, regenerates the patch manifest; keep `kaentake/src/Custom.wz` in step). Setting
`QUEST_MARKER_AVAILABLE`/`_READY` to the stock `UI/UIWindow.img/QuestIcon/0`/`2` works without
Custom.wz but draws at the feet. Do not use `Effect/BasicEff.img/QuestAlert`: the v83 client
crashes on it as an avatar effect.

### Portraits

The v83 NPC window can only draw an NPC (or the viewer's own avatar), so each Resident gets an NPC
of its own: `MapleEdit/app/living_world_portraits.py` renders the Resident's character (skin, face,
hair, visible equipment, standing pose) from Character.wz using its database look, and writes
`Npc.wz <id>.img` (the render as `stand/0`) plus `String.wz Npc.img/<id>/name`. Ids start at
9970000, never change once assigned and live in `portrait-npc-ids.yaml` next to the content, which
the server reads (`!world reload` after a run). `--preview DIR` writes PNGs, `--only a,b --preview
DIR` renders just those, `--install` backs up and replaces the live Npc.wz/String.wz and regenerates
the patch manifest (Npc.wz is ~580 MB, so friends download it again after every run). It patches the
live client files, not MapleEdit `out/`. Re-run after Residents' gear changes or after new
Residents have materialized once (a persona without a character yet is skipped and keeps a
borrowed portrait).

Client rules found the hard way: every NPC name in String.wz needs an image in Npc.wz (a name
alone makes the client quit seconds after entering the game), and an NPC image only in Custom.wz is
not enough either. When adding a directory to a WZ with MapleEdit's writer, keep directories that
have sub-directories last: its directory offsets assume that order.

## GM commands (`!world`, GM 5)

`status`, `census`, `reload` (re-reads content, reports lint errors), `residents`,
`wake <town|persona>` / `sleep <town>`, `arc <persona> <chapter>`, `flag set|clear <flag>`,
`bond <player> <persona> <points>`, `rumor play <id>`, `phrases [approve|reject <text>]`,
`teach <canonical> <phrase...>`, `haul <player>`, `events <player>`.

`tools\living-world-lint.bat [dir]` validates content offline (exit 1 on errors);
`tools\living-world-questlog.bat` exports the quest log (above).

**Operator endpoints** (loopback AdminControlPort, Bearer token): `GET /runtime/budget` gives heap and
old gen after the last GC, CPU, threads, registered Agents by kind, humans online, scheduler registrations
vs per-shard capacity with lag/tick percentiles, and LOW_PRIORITY timer lag (5 s probe); `?gc=1` forces
a full GC first (stop-the-world, measurement runs only). `GET /runtime/companions[/{ownerCharacterId}]` is a
read-only roster/party/registry drift report per online owner; gate on drift seen in two consecutive reads.
It also carries the auditor's status, which `GET /runtime/companions/audit` returns alone: every 60 s
`CompanionAuditor` compares the same three on companion-ops, repairs drift seen on two runs in a row toward
the roster row (ACTIVE: seated again; BENCHED/AWAY: taken out of the party), logs `companion.drift` to
`living_event_log`, and tries once per episode (a repair that does not hold is logged once more as stuck).

## Authoring

MapleEdit has a **Living World** page (`/living`): the content tree with every id, a one-file
YAML editor, "Save (lint first)" which runs the server's own lint on the whole tree with the edit
applied and refuses on errors (backups under MapleEdit `backups/`), "Reload on server" and the
materialized-Residents table through the AdminControlPort (`/runtime/living`,
`/actions/living-reload`), and "Export quest log" (the two steps above, minus the deploy).
It needs `cosmic_root` set on the Deploy page and `target/Cosmic.jar` built.

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

Looks: `look: { gender: female|male, skin: 0-11, face: <id>, hair: <id> }`, every field optional.
Unset fields get a pick that is stable for the persona id (natural skin tone, a regular face and
hairstyle for the gender with an eye and hair color, from the server's Character.wz, which every
client has); an unset gender is picked the same way, so set it wherever the writing says he or she.
`ResidentLooks` writes the look to the character at startup, on `!world reload` (a materialized
Resident whose look changed goes to standby and comes back with it) and when one is provisioned;
re-run the portrait tool afterwards so the NPC window matches.

Outfits: a loadout's `outfit:` maps slots (`hat`, `top`, `bottom`, `shoes`, `gloves`, `cape`, `weapon`) to pools
of item ids; `0` means nothing in that slot and a `top` may be an overall (the bottom then stays empty).
Each Resident walks every pool in an order that is stable for its persona id and wears the first item
it can (its gender, and the game's job/level/stat check), so Residents sharing a loadout still dress
differently. Cash (NX) items go into the cosmetic cash slots over the real gear and have no
requirements. `ResidentWardrobe` dresses a Resident when it materializes and on `!world reload`;
`loadouts` lint rejects unknown slots and items that do not belong in their slot. Re-run the portrait
tool after changing pools (a Resident has to materialize once to be dressed). The townsfolk carry work tools (axe, knife, mop, broom, fish spear, frying pan, paint
brush) from their `weapon` pool rather than swords; `weapon` takes regular weapons only (no cash covers), and a Resident given one discards its other weapons, since the
Agent gear optimizer would otherwise switch back to whichever hits harder.

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
| MapleEdit page | `MapleEdit/app/routes_living.py`, `templates/living.html`, `static/js/living.js`; server side `AdminControlPort` `/runtime/living`, `/actions/living-reload` |
| Resident portraits in the NPC window | `MapleEdit/app/living_world_portraits.py`, `portrait-npc-ids.yaml`, `ResidentDialogue.portraitOf` |
| quest markers over Residents, click-to-talk | `server.living.quest.QuestMarkers`, `ResidentDialogue.onCharacterClicked` (via `CharInfoRequestHandler`), `MapleEdit/app/living_world_markers.py` |
| client quest-log mirror and export | `server.living.quest.QuestLog`, `server.living.config.QuestLogExportMain`, `MapleEdit/app/living_world_quests.py` |
| phrase packs and learned phrases | `server.living.phrases.PhraseMatcher` (installed into `AgentChatIntentRuntime`) |
| commands | `client.command.commands.gm5.LivingWorldCommand`, `gm0.LivingQuestsCommand`, `gm0.LivingRosterCommand` |

## Not done yet (from the plan)

Live sessions for GATHER/SELL/STALL (they stay on the offline ledger; live sessions run at
`BACKGROUND_ACTIVE` priority rather than a true mutation-free `BACKGROUND_ABSTRACT` tick, which
the Agent runtime only supports for town life), per-step kill counters in the client quest
log (the mirror tracks start/complete only), and form-based editing in MapleEdit (the page edits
YAML text with lint, not per-field forms).
