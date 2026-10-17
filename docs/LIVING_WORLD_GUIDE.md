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
| `@roster` / `@bond <name>` | companions; relationship, friendship level and haul with one persona |
| `Olwen talk` / `menu` | opens the persona's NPC window: work, quests in progress, travel, a gift, who are you |
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

### Friendship

Every Resident keeps a friendship meter with each player, Lv 1-10 over the same bond points the
stages use (`FRIENDSHIP_LEVEL_POINTS`, default 1/25/50/75/110/150/200/260/330/400: Lv 2 is
ACQUAINTED, Lv 4 TRUSTED, Lv 6 SWORN, Lv 7 Close friend, Lv 10 Best friend). The NPC window shows
it under the greeting and `@bond <name>` prints it. Points come from:

| Source | Points | Limit |
|---|---|---|
| Quests | the quest's `bondPoints` | each quest's own rules |
| Fame / defame (the stock Fame button on the Resident) | +8 / -8 | the game's once a month per target |
| A gift: "I brought you something." in the Resident's window, or "Supplies & loot..." on a companion's card | +2, or +6 for one of its `giftItemIds` | 1 a day per Resident; later ones it turns down |
| A party quest cleared with the Resident in your party | +10 | 3 a day per Resident |
| A boss killed with the Resident in your party on the map (you hit it) | +15 | 3 a day per Resident |

Gifts are one of anything tradeable from use, etc, setup or equip (never cash, quest or untradeable
items); the counts use `living_event_log` (`friend.gift`, `friend.pq`, `friend.boss`) and reset at
midnight in `ROUTINE_ZONE`. Hired citizens are not Residents and keep no friendship. Level changes
show as pink text, and reaching Close friend and Best friend plays the persona's `friend.close` /
`friend.best` line.

**Friendship quests** (`quests/friendship.yaml`): Brannock, Fenna, Casse, Vex, Garrick, Kettle and
Thane each offer four, one after another (`server.living.quest.FriendForge` does the scrolling):

| Friendship | Reward | Scrolls |
|---|---|---|
| Lv 4 | a piece of armour for **your** class (gloves, shoes, earrings, a helmet or a cape) | scaled by friendship, below |
| Lv 5 (lower) | the Resident's **own** weapon, of its class | 7 x 100%, every one taken |
| Lv 7 (mid) | a better weapon of its own | 7 x 60%, every one taken |
| Lv 10 (final) | its prized weapon | 2 x 10% + 5 x 60%, every one taken |

The weapons are the Resident's, whatever your class: Brannock's blunt weapons (Iron Mace, War
Hammer, Jacker), Fenna's bows (Battle Bow, Red Viper, Olympus), Casse's wands (Ice Wand, Wizard
Wand, Cromi), Vex's daggers (Cass, Bazlud, Sai), Garrick's swords (Traus, Jeweled Katar, Neocora),
Kettle's knuckles (Neozard, Fury Claw, Psycho Claw) and Thane's axes (Buck, Hawkhead, Mikhail).
Each is scrolled with its own class's attack scrolls (magic attack for a wand): +7 att, then +14
att and +7 of its main stat, then +20 att, +11 of its main stat and +2 defence.

Eighteen more Residents exist for friendship alone, so every weapon type and every armour slot has
someone who gives it. Each has an intro quest (no gate), a repeatable quest tied to their craft
(+10 points each time) and the three tiers at Lv 5, 7 and 10:

| Weapon (their own, recruitable) | Resident | Town | Armour (made for your class) | Resident | Town |
|---|---|---|---|---|---|
| staff | Isolde, Cleric | Ellinia | overall (a top where your class wears no robe) | Mirabel | Ellinia |
| 2H sword | Brunhild, Fighter | Perion | gloves | Hilde | Perion |
| 2H axe | Gunnar, Fighter | Henesys | hat | Posy | Henesys |
| 2H blunt | Magna, Page | Sleepywood | cape | Wendell | Sleepywood |
| spear | Corvin, Spearman | Perion | shield (warriors; magicians and thieves while one exists) | Brom | Perion |
| polearm | Ingrid, Spearman | Lith Harbor | bottom (an overall for pirates) | Hob | Lith Harbor |
| crossbow | Sorrel, Crossbowman | Henesys | top (an overall where your class has no top) | Silas | Kerning City |
| claw | Kestrel, Assassin | Kerning City | earrings | Opal | Kerning City |
| gun | Flint, Gunslinger | Nautilus | shoes | Tilda | Nautilus |

The armourers' tiers use the same recipes on every slot the piece has: all 100%, all 60%, then two
10% and the rest 60% (five-slot gloves: 2 x 10% + 3 x 60%; a ten-slot overall: 2 x 10% + 8 x 60%),
every scroll taking, chosen for your class. Pieces come from the class pools in v83: no thief or
bowman shield exists past Lv 22 and no magician shield past Lv 33, so Brom's later tiers are not
offered to those classes. The weapon Residents are recruitable (a contract once you are acquainted
and have done their intro quest), so party quests and bosses with them count toward friendship;
the armourers are townsfolk, befriended through their quests, gifts and fame.

The armour piece:

- **Which piece:** the pool's first piece made for your job (one per job; a piece with no job
  requirement fits everyone). A pool with nothing for your job (a beginner) keeps the quest from
  being offered at all.
- **Which scrolls:** of the standard 100%, 60% and 10% scrolls for that piece, the one that does
  most for your job (attack or magic attack, then your main stat). Event scrolls that give 10%
  stats at 100% never count, for the weapons either.
- **How many take:** every slot is scrolled and none is left open. Your friendship level when you
  hand the quest in fixes the result, with no dice: 100% scrolls always take, and 60% and 10%
  scrolls take `FRIENDSHIP_SCROLL_TAKES_60` / `_10` percent of the slots at each level (60% from
  Lv 4, 10% from Lv 7). The Resident uses whichever grade makes the better piece, so a higher
  level never gives a worse one. Five-slot gloves: Lv 4 four 60% (+8 att), Lv 10 five 10%
  (+15 att). Failed slots are spent; nothing is ever destroyed.

The offer window shows the piece and what it gets (for armour, at your current level). The final
hand-in waits until you have a free equip slot.

**Residents wear their own quest gear** (`server.living.resident.ResidentKit`): per equip slot, the
highest-level piece from their friendship quests that the game lets them wear (the bot class rule,
level and stats; a pool piece also their gender), scrolled the way the quest scrolls it (a recipe in
full; a friendship-scaled armour piece as at that quest's friendship level). Isolde carries the
Circle-Winded Staff she gives at Lv 5 from the start, and once she reaches Lv 40 as your companion
she switches to the Hall Staff. The Resident's copies are its own: each carries its name as the
owner tag and is untradeable, so no Agent trade, drop or sale takes it, and each piece is made at
most once per Resident (`kit.issued` in `living_event_log`). The kit only replaces what is the
Resident's own (an empty slot, its plain loadout and outfit pieces, its earlier quest pieces); a gift,
loot, a scrolled piece or a contract's kept piece that it chose to wear stays, and so does everything
it carries. The only thing the kit deletes is its own superseded quest piece for that slot, so the Agent
gear optimizer cannot switch back; the wardrobe's sweep of spare outfit weapons skips anything tagged,
untradeable, scrolled or kept by its contract, and a bench harvest or stall never moves its own copy
into the haul. The wardrobe leaves the regular piece of a quest slot alone (a cash item still goes
over it). This happens when the Resident appears, on `!world reload`, after every level it
gains and whenever the roster or bench loads it as a companion (on the Agent's own mailbox), which is
how a companion grows into its next tier. Hired citizens and townsfolk citizens keep
their own gear; the armourers are beginners, so only pieces with no class requirement fit them (Opal
wears her earrings).

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

With server knowledge (`knowledge.DECISIONS`, docs/SERVER_KNOWLEDGE_PLAN.md 8.3) `farm <item>` reads players' names and
slang ("steelies", "red pots", "pig heads") and asks back on a tie ("glove att 60 or glove att 10?"). The companion camps
at the nearest field where a monster it can fight drops the item, and says where; it refuses in its own words what it
cannot farm: cash items, shop-only items, script-spawned or event monsters, quest-only drops, monsters far above it and
bosses. The bench ledger credits FARM_ITEM and GATHER only from such droppers, with no DB query.

Calling a benched companion is a trip: it whispers an ETA from its camp (Victoria road hops, 20 s
plus 40 s per town; off Victoria, a trip server knowledge walks with no ride, Orbis to El Nath, counts its town hops the
same way, and anything needing a ship is 3 min) and appears beside you when it arrives; a recall whistle
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

Living quests can appear in the client's quest log next to the stock quests (In Progress and
Complete tabs), in their own "Residents" group. Checked in a v83 client (2026-10-03): the list
groups quests by `area` under a header named in Etc.wz `QuestCategory.img` (31 = "Residents", an
unused slot), labels each entry with `parent` as plain text (colour codes print literally, so the
label is the plain title), and titles the detail pane with `name`, where `#b` renders blue; the
header shows "Over Level N" from `lvmin` and the giver's own portrait (`npc`, from
`portrait-npc-ids.yaml`) when it has one. It takes one WZ patch:

1. `tools\living-world-questlog.bat` - assigns every quest a stable client id (30000-32767) in
   `quest-log-ids.yaml` and exports name/description text to `target/living-quest-log.json`.
   New quests get the next free id; ids are never renumbered (they live in players' save data).
2. `cd MapleEdit\app && python living_world_quests.py` - writes QuestInfo/Check/Act entries into
   `out/Quest.wz` and names the Residents group in `out/Etc.wz` (both stacking with other edits)
   and stages the server XML; deploy Quest.wz and Etc.wz from the MapleEdit dashboard so the patch
   server ships them to every client. Without MapleEdit, `tools\living-world-client.bat "<client
   folder>"` runs both exports and writes the entries (and the portraits) straight into a client
   folder with the same code (`tools/wz-patcher/residents.py`).
3. `living_world.QUEST_LOG_MIRROR: true` - the server then sets the matching quest STARTED on
   accept and COMPLETED on completion (`QuestLog`), and reconciles older ledger state at login.

Leave the flag off until the WZ is shipped, or clients list nameless quests. The entries carry an
event `end` date long past, so the client never shows them as "available" - Residents offer them
in person. Forfeit in the quest window abandons the quest (`QuestLedger.abandon`): progress is
lost and collected items stay, a first take can be offered again, a repeat goes back to completed,
and an escort it started heads home.

The Quest Helper tracks them too: `Check.img/<id>/1` lists each kill step's first monster and each
collect step's item (`QuestLogTargets`; the WZ entry is static, so every step's targets show at
once and a later step's read 0 until it is reached). The client counts items from the inventory;
kills are the ledger's, sent as the quest's progress string (three digits per monster in that
order) on every kill and step (`QuestLog.progressed`) and at login. The stock kill counter skips
quest ids 30000-32767, and a Resident quest earns the quest point and the Growing Quest Ring's
growth only on its first completion (a repeat, or a forfeited repeat put back to completed, does
not). Names stay plain because the Helper prints colour codes literally; the giver's name in the
detail text is blue, and Kaentake draws the list rows blue.

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
of its own, rendered from the client's own Character.wz, so no Resident image ships with the
repository: `ResidentPortraitExportMain` reads each Resident's database look (skin, face, hair,
visible equipment) and assigns the ids, and `tools/wz-patcher/residents.py` renders the standing pose
and writes `Npc.wz <id>.img` (the render as `stand/0`) plus `String.wz Npc.img/<id>/name`. Ids start
at 9970000, never change once assigned and live in `portrait-npc-ids.yaml` next to the content, which
the server reads (`!world reload` after a run). Two ways to run it: `tools\living-world-client.bat
"<client folder>"` patches any client folder (with the quest log, see above), and
`MapleEdit/app/living_world_portraits.py` installs into the live client: `--preview DIR` writes PNGs,
`--only a,b --preview DIR` renders just those, `--install` backs up and replaces the live
Npc.wz/String.wz, copies the ids into the content directory and regenerates the patch manifest
(Npc.wz is ~580 MB, so friends download it again after every run). It patches the live client files,
not MapleEdit `out/`, and the ids reach the server only with `--install`. Re-run after Residents' gear changes or after new
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
`tools\living-world-questlog.bat` exports the quest log (above). Item, mob, map and NPC ids are checked
against the server knowledge (`scripts/config/knowledge`, plus the Liquibase seed rows offline): an unknown
item, mob or map is an error; quest items nobody can farm, mobs that never spawn and gear its holders never
wear are warnings (docs/SERVER_KNOWLEDGE_PLAN.md section 9).

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
applied and refuses on errors (backups under MapleEdit `backups/`; of the knowledge checks only unknown
ids are errors), "Reload on server" and the materialized-Residents table through the AdminControlPort
(`/runtime/living`, `/actions/living-reload`), and "Export quest log" (the two steps above, minus the deploy).
It needs `cosmic_root` set on the Deploy page and `target/Cosmic.jar` built.

One YAML file per town under `personas/`, `quests/`, `contracts/`, `arcs/`, `rumors/`; each file
holds a list. `loadouts.yaml` and `command-phrases.yaml` are single files. Schemas are the records
in `server.living.config.Content`; the plan's section 4 has annotated examples. A persona may carry
`knows: [prices, free market]` (docs/SERVER_KNOWLEDGE_PLAN.md 7.7): maps, regions, PQs, mobs or items, by alias or exact
name, that the Resident knows on top of its home town and a past around it, and `prices` for what things go for; an entry
that names nothing is a lint warning. VETERAN and ELITE Residents quote exact drop chances.

Persona dialogue intents the runtime looks up (chapter overrides first, then the persona):
`greetFirst`, `greet`, `idle`, `noWork`, `nothingDue`, `who`, `bye`, `thanks`, `smallTalk`,
`speakPlainly`, `declined`, `offer`, `join`, `bench`, `leave`, `refuse`, `refuse.<blocker>`
(`bond`, `flags`, `arc`, `taken`, `cooldown`, `roster-full`, `not-recruitable`),
`refuseOwnErrand`, `delegate`, `onMyWay`, `leaveDeaths`, `questOffer.<quest>`, `questAccept.<quest>`, `questWaiting.<quest>`,
`questComplete.<quest>`, `questRoom.<quest>` (no equip slot free for its gear), `about.<persona>`, `aboutUnknown`, `bond.<stage>`,
`chapterEnter`, `friend.close`, `friend.best`, `famed`, `defamed`, `gift`, `gift.favorite`, `gift.again`, `gift.gone`, `gift.none`.

Friendship quests: `requires: { friendship: 4 }` gates on the giver's friendship level (0-10). A
`rewards.gear` entry is one scrolled piece (see Friendship above), in one of two forms:

- `{ pool: [<equip ids>] }`: a piece for the player's class, scrolled by friendship. Put one piece
  per job in the pool (warrior, magician, bowman, thief, pirate) or one with no job requirement.
- `{ itemId: <equip id>, scrolls: [{ success: 10, count: 2 }, { success: 60, count: 5 }] }`: the
  giver's own piece, given to any class and scrolled to that recipe with every scroll taking. A
  weapon gets the scrolls for its own class.
- `{ pool: [<equip ids>], scrolls: [...] }`: a piece for the player's class, scrolled to the recipe
  (every scroll taking, chosen for the player's class).

A recipe line without `count` fills every slot left (`[{ success: 10, count: 2 }, { success: 60 }]`
is two 10% and the rest 60% on any piece).

A quest with gear must end in a `TALK` or a `DELIVER` to its giver, because that hand-in checks
for a free equip slot. The lint errors on a level outside 0-10, on a gear id that is not non-cash
equipment, on a recipe line that is not 10, 60 or 100 percent or has a count under 1, and on a
gear quest that ends any other way. It warns about jobs a pool has nothing for, and about a giver's
own piece that is not made for the giver's class.

Quest steps: `TALK`, `KILL_MOBS`, `COLLECT_ITEM` (optional `dropFrom` custom drop and `label`),
`DELIVER` (`to` persona, optional `take`), `VISIT_MAP`, `TALK_TO`, `HEAR_RUMOR`, `ESCORT`,
`HUNT_WITH` (giver must be co-present), `PAY`, `GIVE_ITEM`, `REACH_LEVEL`, `WAIT`,
`PARTY_QUEST_CLEAR`, `KILL_BOSS`. Any kill/collect step takes `with: <persona>`.

Contracts: `offerWhen` (bond stage, flags, arc chapter), `price` (`meso`, `items` with `keep`,
`proof.killMobs`), `terms` (`durationHours`, `maxLevelGap`, `lootShare`, `leavesOn`,
`cooldownHoursAfterLeave`, `rejoinPrice`, `ownDropsPercent`). Personas: `benchToleranceDays`
(0 = leaves after an hour on the bench, -1 = never), `paceGap`, `maxLag`, `giftItemIds`.

Looks: `look: { gender: female|male, skin: 0-11, face: <id>, hair: <id> }`, every field optional.
Unset fields get a pick that is stable for the persona id and `living_world.RESIDENT_LOOK_SEED`
(natural skin tone, a regular face and hairstyle for the gender with an eye and hair color, from the
server's Character.wz, which every client has); an unset gender is picked the same way, so set it
wherever the writing says he or she. Seed 0 gives the looks Residents always had; another number gives
a server its own cast (every Resident is re-dressed on the next boot).
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
| friendship meter, fame/gift/PQ/boss points | `server.living.bond.Friendship` (hooks: `GiveFameHandler`, `LivingWorldHooks.partyQuestCleared`/`mobKilled`) |
| friendship gear (piece, scrolls, takes) | `server.living.quest.FriendForge`, `QuestLedger.complete` |
| roster, bench ledger, haul | `server.living.roster.*` |
| arcs and rumors (deterministic rumor director: waiting listener, unheard, least performed) | `server.living.arc.*` |
| MapleEdit page | `MapleEdit/app/routes_living.py`, `templates/living.html`, `static/js/living.js`; server side `AdminControlPort` `/runtime/living`, `/actions/living-reload` |
| Resident portraits in the NPC window | `server.living.config.ResidentPortraitExportMain`, `tools/wz-patcher/residents.py`, `MapleEdit/app/living_world_portraits.py`, `portrait-npc-ids.yaml`, `ResidentDialogue.portraitOf` |
| quest markers over Residents, click-to-talk | `server.living.quest.QuestMarkers`, `ResidentDialogue.onCharacterClicked` (via `CharInfoRequestHandler`), `MapleEdit/app/living_world_markers.py` |
| client quest-log mirror and export | `server.living.quest.QuestLog`, `server.living.config.QuestLogExportMain`, `tools/wz-patcher/residents.py`, `MapleEdit/app/living_world_quests.py` |
| phrase packs and learned phrases | `server.living.phrases.PhraseMatcher` (installed into `AgentChatIntentRuntime`) |
| commands | `client.command.commands.gm5.LivingWorldCommand`, `gm0.LivingQuestsCommand`, `gm0.LivingRosterCommand` |

## Not done yet (from the plan)

Live sessions for GATHER/SELL/STALL (they stay on the offline ledger; live sessions run at
`BACKGROUND_ACTIVE` priority rather than a true mutation-free `BACKGROUND_ABSTRACT` tick, which
the Agent runtime only supports for town life), per-step kill counters in the client quest
log (the mirror tracks start/complete only), and form-based editing in MapleEdit (the page edits
YAML text with lint, not per-field forms).
