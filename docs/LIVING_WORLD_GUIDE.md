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

Residents stand in their home towns (all seven Victoria towns have a cast; Orbis, El Nath, Ludibrium, Leafre, Mu Lung,
Herb Town, Ariant, Magatia, Omega Sector, Korean Folk Town, Aquarium, Singapore, Zipangu, Kuala Lumpur and Kampung
Village have seven each, and New Leaf City 25; wave 4 adds the Temple of Time, Neo City at the Time Gate, Altaire Camp,
more of Ereve and Rien, and two or three each on the Ossyria outposts such as Orbis Park, the Town of Ariant and the Door
to Zakum, and in Victoria Florina Beach, Amoria, Kerning Square, Mushroom Castle, the Free Market, the Excavation
Site camp, the three Maple Island towns and Happyville: `docs/REGIONAL_RESIDENTS_PLAN.md`, `docs/ROUTES_MALAYSIA_PLAN.md`). Talk to them in map
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
midnight in `ROUTINE_ZONE`. Hired citizens are not Residents and keep no friendship, unless
`HIRE_FRIENDSHIP_ENABLED` (see Standing below). Level changes
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

Off Victoria the same recipes continue one tier up (`docs/REGIONAL_RESIDENTS_PLAN.md` sections 4, 5 and 9): Orbis's Vesna (wands)
and Ansel (bows), El Nath's Sigrun (two-handed swords) and Bjarke (spears) and Ludibrium's Saskia (claws) and Jasper
(guns) give weapons at L58-90 and are recruitable; the armourers Aster (hats) and Perrin (earrings) in Orbis, Ulla
(gloves) and Oskar (shoes) in El Nath, and Della (coats) and Marlow (capes, up to the Ludibrium Cape) in Ludibrium
give pieces at L60-100. Wave 2 adds the other ten weapon lines, one recruit each (Bastian and Runa in Leafre, Baojun
and Suyin in Mu Lung, Taejin and Maren in Herb Town, Sefa and Harun in Ariant, Vael and Kipp in Magatia; L50-100),
and the next hat, glove and shoe tiers (Mirelle and Dovan in Leafre, Fengzi in Mu Lung; L90-120). Wave 3 gives
twelve of the lines a third regional tier (L88-120: Vesper and Rourke in Omega Sector, Gwang and Seori in Korean Folk
Town, Thalia and Kael in Aquarium, Genzo and Rin in Zipangu, Brody, Vance, Delphine and Duke in New Leaf City),
Singapore's Arjun and Priscilla give earrings and capes, and New Leaf City's other Residents hand out the iTCG
items. Malaysia's Iskandar, Devi, Kamal and Bayu (`docs/ROUTES_MALAYSIA_PLAN.md` section 8) give the four lines that
stopped at waves 1 and 2 (two-handed swords, spears, two-handed blunts, one-handed axes) an L100-120 tier beside
Masteria's. Wave 4 gives armour and accessories only: the Temple of Time's Caedmon and Liesl carry Della's coats and
Perrin's earrings past L100 (to the Timeless suits and earrings), and the outposts give their places' own pieces (the
Dojo's belts, Zakum's helmet and capes, the Altaire earrings, the Icarus capes, the Pharaoh belts, the society capes). Wave 4's ten Victoria recruits (Florina Beach, Amoria, Kerning Square, Mushroom Castle, the Excavation Site) give
armour and accessories for the player's class in their places' bands, L20-55. Quest rewards are added with `InventoryManipulator.addById`, which does not create a pet: never reward a pet
item until a pet-aware reward path exists, and never ask for an untradeable item as a hand-in.

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

### Letters

Off by default: `living_world.LETTERS_ENABLED` (docs/BOT_CONCEPT_PLAN.md F3, `CompanionLetters`). A few seconds
(`LETTERS_DELAY_MS`) after you log in from a real logout, or for the first time since a restart, each companion that did
something while you were away writes you a normal note (the memo window), sent as itself and in its voice: the level
its banked EXP is ready for (or one it reached out there), what it found by the rarity rule (an equip within
`LETTERS_EQUIP_LEVEL_SPAN` levels of yours, an equip scroll, or an item in the top tenth of the citizens' price index),
job advances, a camp move from a rough camp, gifts it sent, deaths as a soft count ("took a tumble twice") and the meso
it put in the haul. At most `LETTERS_MAX_NOTES` notes a login, and never past the 100-unread cap: the companions past
the first ones share one crew letter, written by the first of them. One pink line sums it up in place of the old
"While you were away" lines: who wrote, who went home, and which citizens asked after you.

- **The news survives a restart.** Bench work (the ledger's credit, live slices' harvests and ends) is written as
  `bench.report` rows (JSON) to `living_event_log` at each ledger tick and slice end. The digest reads the rows,
  `job.advanced`, `letters.gift` and `citizen.asked` after your newest `letters.away` (written at a logout) or
  `letters.told` (written by a digest) marker, so it tells only the time you were away, once. A companion the letters
  told about has no call-back report to repeat.
- **Gifts.** A Resident (or a hire with `HIRE_FRIENDSHIP_ENABLED`) at friendship `LETTERS_GIFT_FRIENDSHIP_LEVEL` or more sometimes
  (`LETTERS_GIFT_CHANCE` a ledger tick, `LETTERS_GIFTS_PER_WEEK` each) mails you one such find from its haul by Duey,
  with a message. It is taken from the haul, never minted; you get Duey's live notice when you are online.
- **Asking after you.** A citizen with warmth `LETTERS_ASK_WARMTH` or more toward you (its person memory) sometimes
  says "anyone seen Ann?" in place of an idle line while you are offline (`memory.asked` in `chatter/memory.yaml`; at
  most once per `LETTERS_ASK_GAP_HOURS` for you); your next login's pink line says who asked.
- **Voice.** `letters.yaml` in the content directory: per part of a letter, its lines and each dialogue style's own (the
  style a persona's personality resolves to). Templated: no Jev calls.
- **Your rival crew** (F7, population `RIVALS_ENABLED`; docs/POPULATION_GUIDE.md "Rival crew and records"). Its clears
  while you were away (`rival.news` rows on your main) come as one note signed by its leader, counted in
  `LETTERS_MAX_NOTES`, and the pink line names the writer.

Always on, as the fix of two tells: "send it" mails an equip with drop-like random stats (a piece a package), and meso
alone in a package with no item, as a player's meso-only Duey package; you get Duey's live notice.

### Habits and learned spots

Companions pick up how their owner plays (`living_world.OWNER_HABITS_ENABLED`, off until its live checks pass; plan
F2.2-F2.4). Only humans who own companions are watched, only while online: the HP/MP they drink at, what they pick up,
drop or leave on the ground until it expires (with room in their bag), what they sell to shops, the fields they train on
per level band (EXP, kills, deaths) and the skills that land their damage. Only the best of it is learned: a drink in the
`OWNER_HABIT_DEATH_WINDOW_MS` before a death came too late; a category is skipped only when nearly all of it is left and
its drops are cheap (never quest items, scrolls or books, never more than `OWNER_HABIT_LOOT_MAX_SKIPS` categories, so it
never comes to picking up nothing); an item is vendored only when sold on separate visits; a map counts for its EXP an
hour against the owner's best in the band, never one that keeps killing them; a skill for its share of the damage. Boss
content (an event instance, a field boss on the map) is neither watched nor ever uses a habit.

What it changes: a companion whose potions were never set drinks at the owner's median HP point (never below
`OWNER_HABIT_HP_FLOOR`) outside boss content; the default loot mode becomes "What you pick up" (`LIKE_OWNER`), which
leaves the categories the owner always leaves; a bench farmer holds what the owner sells and sells it at its resupply
instead of hauling it; Your call weighs the owner's best maps at its level band; a companion of the owner's job starts
those skills a little higher in its combat learner (measured values still win). Each habit is mentioned once, in voice
(`owner-habits.yaml`); the card's **Learned from you...** page lists them, with **Forget my habits** (D7). GM:
`!habits <player>` (GM 6) shows what is learned and how much it rests on. Stored in `living_owner_habit` (changeset 52).

Citizens learn where to train (`population.CITIZEN_SPOT_LEARNING_ENABLED`, plan F2.1): a loaded citizen's TRAIN stint is
booked when it leaves its field (EXP and deaths an hour per class line, band and field, `companion_yield` rows of source
`citizen`; AFK time, stints under `CITIZEN_SPOT_MIN_MS` and shop walks are not). Among the fields that fit, it picks by
what citizens measured, a UCB bonus for fields measured less and a crowding cost for fields already full of citizens,
drawn by weight so they spread. The same rows sharpen companions' EXP and death priors (`CITIZEN_YIELD_WEIGHT`).

### Standing

One ladder for every bot, **STRANGER, REGULAR, FRIEND, CLOSE** (docs/BOT_CONCEPT_PLAN.md C1, `server.living.standing`).
It is a view over what each kind of bot already keeps, not a store of its own:

| Rung | Resident (friendship level) | Citizen (warmth) | Hire (friendship level with its owner) |
|---|---|---|---|
| STRANGER | Lv 0-1 | under 15 | (none) |
| REGULAR | Lv 2-3 | 15-39 | Lv 0-3 |
| FRIEND | Lv 4-6 | 40 and up | Lv 4-6 |
| CLOSE | Lv 7+ | (only once hired) | Lv 7+ |

`!standing <bot> [player]` (GM 5) prints the rung and the raw numbers: bond points, level and stage; warmth, the
party and map minutes together and the friendship a hire would start at; a hire's perks.

With `living_world.HIRE_FRIENDSHIP_ENABLED` (off until its live checks pass) a hired citizen joins the friendship meter:

- **The bond.** At the hire it gets a `living_bond` row keyed `hire:<characterId>`, seeded once from its warmth then
  (40+ the start of Lv 4, 15+ the start of Lv 2, anyone else Lv 1; never below the points an earlier hire left). From
  then on the Resident sources add to it: party quests, bosses and boss runs, fame, and gifts ("I brought you
  something..." on its card). Level changes show as pink text. Quests, arcs, the ResidentKit, portraits and weddings
  stay Residents-only (D1, D3). Its bench pace follows the bond stage, as a Resident's does.
- **Offers to stay on** go by standing (D2): a guest with a grudge (warmth -30 or under) never offers and says nothing;
  one under REGULAR does not offer; a FRIEND always offers; a REGULAR's temperament decides as before. Each needs the
  same `HIRE_OFFER_PARTY_MINUTES` in your party. A FRIEND's fee is `HIRE_FRIEND_FEE_DISCOUNT_PERCENT` lower (at most 90,
  never free). The offer cooldown is read back from the `hire.offer` rows of `living_event_log`, so a restart keeps it.
- **Memory while hired.** The population has no session for a hire, so the hooks write its memories directly: a party
  quest or boss run cleared with a player, and a fame or defame. Let go, it feels at least the warmth of its friendship
  toward its old owner (Lv 0-3 15, 4-6 40, 7-9 60, 10 80), so it greets them as a friend when they meet again.
- **Perks.** Lv 4: a buddy request (as a Resident's, besides the old 30 party minutes). Lv 7: wages
  `HIRE_CLOSE_WAGE_DISCOUNT_PERCENT` lower (the ledger's charge, the Wages page and paying ahead). Lv 10: permanent;
  an owner away `RESIDENT_OWNER_INACTIVE_DAYS` keeps it. That sweep is the only thing that can end a hire on its own:
  `hired-hand.yaml` has no end rules and `ROSTER_CONTRACT_MODE` is `PERMANENT_ON_ROSTER`, so a hire never walks out,
  and the exemption is all "permanent" can mean for one. Letters: a hire at `LETTERS_GIFT_FRIENDSHIP_LEVEL` mails
  gifts like a Resident.
- **Display.** Its card shows "Friendship Lv 4 Friend (35 points to Lv 5)"; its Crew window row ends " - Friendship Lv 4"
  (the dialog and the native window).

**Hire guards** (the audit for C1.2: where code tells a hire apart, and whether that stays):

| Where | Kept or lifted | Why |
|---|---|---|
| `Friendship.residentOf` | lifted with the switch | points, gifts and level changes are C1 itself |
| `ResidentDialogue` gift option; `CompanionCardScreens` "I brought you something..." | lifted with the switch | a gift is a point source |
| `CompanionLetters.mailGifts` / `gift` | lifted with the switch | letters waited for C1; the friendship gate now applies to hires |
| `Friendship.onBossRunCleared` (no guard) | as it was | it already befriended hires before C1 (harmless: seeding keeps the higher points) |
| `ArcService.arcOf`, `RumorScheduler` (2) | kept | arcs and rumors are authored; hires have none (D1) |
| `QuestLedger.available`, `QuestMarkers.compute` | kept | authored quests stay Residents-only (D1) |
| `CompanionWeddings.personaOf`, `LoreSpouseDirectory` (2) | kept | no weddings for hires (D3); lore spouses are authored |
| `ResidentKit.picks`, `LivingWorldHooks` kit refresh, `ResidentRuntime.refreshKit` | kept | the kit is authored quest gear |
| `ResidentLooks.apply`, `ResidentRuntime.syncLooks` / `ensureCharacter` | kept | a hire's face and character are its own |
| `ResidentShaper` (2), `ResidentStarterSwap`, `ResidentWardrobe`, `ResidentRuntime.wearMissingEquips` / `prepareCompanion` | kept | a hire's level, job and gear are its own |
| `ResidentDialogue.portraitOf` | kept | no rendered portrait: the Bulletin Board speaks |
| `ResidentDialogue` Resident relation | kept | a hire let go is a citizen again, with no persona menu |
| `ResidentDialogue.hiredHandNamed`, `ResidentRuntime.characterIdOf` / `hireLapsed`, `LivingContent` (2) | kept | identity plumbing, not a gate |
| `JobErrands.personaJob` | kept | the owner picks a hire's job (an apprentice's pick is `role_target`, read by `JobErrands.jobOf`) |
| `LootTerms.termsOf` | kept | a hire works under `hired-hand.yaml` |
| `ContractService.blocker`, `RosterService.recruit`, `ResidentAllocation.atCap` | kept | decision 18 b: hires never count toward the Resident cap |
| `ResidentAllocation.sweep` | kept, plus the Lv 10 exemption | C1.4 permanence |
| `CompanionBuddies.residents` / `hiredHands` | kept, plus the Lv 4 trigger | a hire's ask is remembered on `living_acquaintance` |
| `HiredHands.hiresOf`, `HireOffers.roomFor`, `CompanionCrewScreens` hire count | kept | the hire cap |
| `RosterService.release` / owner deletion (`HIRE_PREFIX`) | kept | a hire goes back to the population; no `resident.released` |
| `ContentLint` (2) | kept | content may never name a `hire:` id |
| the population's `citizen.hired()` skips (presence, ledger, churn, social, market, guilds, alliances, errands) | kept | a hire is its owner's, not the population's; its memories come from the hooks above |

### Mentor and apprentice

Two switches, both off until their live checks pass (docs/BOT_CONCEPT_PLAN.md F4, decisions D8 and D9):
`living_world.MENTOR_ENABLED` and `living_world.APPRENTICE_ENABLED`. The roles live on the roster row
(`living_roster.role` MENTOR or APPRENTICE and `role_target`, changeset 54), read by `server.living.roster.CompanionRoles`.

**The mentor** (`server.living.mentor.Mentorship`, lines in `scripts/config/living-world/mentor.yaml`):

- **The offer (D8).** There is no new-character hook, so a first login is read from the character itself: a human's
  Lv 1 Explorer Beginner on Maple Island whose `lastLogoutTime` is still the column default, with no `mentor.*` row in
  `living_event_log` (`FirstLogin`). `MENTOR_OFFER_DELAY_MS` later a yes/no window asks whether a traveller may show it
  around. The window goes through LivingTalk's own guard and never over another window (a second NPC_TALK disconnects
  the v83 client): with one open it tries again every `MENTOR_OFFER_RETRY_MS`, `MENTOR_OFFER_TRIES` times, and an offer
  that never got on screen (`mentor.pending`) is offered again at the next login. `mentor.offered` is written just
  before the window shows, so it is offered once whatever the answer: No, or closing the window, is final.
- **Who it is.** Yes: a citizen is born for the newcomer (`CitizenGenerator.birthMentor`: an Explorer, homed in
  Victoria so it has somewhere to live afterwards, `MENTOR_LEAD_MIN`..`MAX` levels above it: "a player on a fresh
  alt"), with one of the authored voices in `mentor.yaml` (drawn by the newcomer's id). It is hired at no fee
  (`HiredHands.hireAs`, role MENTOR), camps on the newcomer's map so it walks in from the nearest portal, and joins
  the party. It holds a party seat but no roster slot and counts toward neither `ROSTER_MAX` nor
  `HIRE_MAX_PER_OWNER`; its loot is QUEST_ONLY; it rests while benched and turns duties and delegated jobs
  down ("i'm just here to show you around"); the newcomer's login brings it back.
- **Staying ahead.** It earns no kill EXP of its own (`LivingWorldHooks.withholdsKillExp`; the newcomer's share is
  already whole with `COMPANION_EXP_SHARE`). Instead, one level at a time and `MENTOR_LEVEL_DELAY_MIN_MS`..`MAX_MS`
  after the newcomer's, it gains exactly a level (a real level-up: effect, AP, SP) until it is its lead ahead again.
  It never goes down. Its own jobs come by the companions' instructor errand (its career's line), only on Victoria,
  never mid-lead, and its first only after the newcomer's.
- **Tips (F4.2).** Each once per mentorship, at most `MENTOR_TIPS_PER_HOUR`, in party chat (a whisper without a
  party): the first time under `MENTOR_LOW_HP_PERCENT` HP (potions and a hotkey), the first death (where to train,
  from ContentAnswers), Lv 8 (the jobs; a window to pick one, so nothing needs typing), Lv 10 (advance now, or first
  get off the island), reaching Victoria (how to become the picked branch, or where to train). Facts are
  ContentAnswers' deterministic replies; the framing is `mentor.yaml`. "quiet please", "shh" or "no more tips" mutes
  them, "tips on" brings them back; both are kept (`mentor.muted`). A branch word in chat ("i wanna be a mage") is
  the newcomer's pick too.
- **The lead (F4.3).** Companions follow, never lead; `MentorLead` is the one exception. It leads when a map is
  outgrown (its top monster's level + `MENTOR_OUTGROWN_LEVELS`, at once out of Mushroom Town), to Shanks at
  Southperry from Lv 7 with the 150 meso fare (he refuses less, so it says so and waits), on landing at Lith Harbor
  to the cab by the dock (or, short of the fare, off the ship deck by its hidden portal and on foot), and to the
  picked branch's instructor once the level fits (Henesys when undecided). On a two-way portal it walks ahead, goes
  through and waits `MENTOR_LEAD_WAIT_MS` on the far side; not followed, it comes back ("you coming?") up to
  `MENTOR_LEAD_TRIES` times, then just follows. The first four hops out of Mushroom Town are one-way, so there it
  waits beside the portal and lets the newcomer go first; NPC legs (Shanks, the cab, the instructor) wait beside the
  NPC. While it leads, the follow guard holds it off follow. Riding along needs no code: a newcomer warped by Shanks
  or the cab is followed there by the follow sync. No route graph covers Maple Island, so the road is `MentorRoute`'s
  own list: `MentorRouteTest` checks every hop against WZ and `MentorRouteWalkTest` walks each map in the movement
  lab (the hops themselves, and Lith's far side to its road out, are live checks).
- **Graduation (F4.4, D9).** At the newcomer's second job: its goodbye lines a beat apart, then `MENTOR_GIFT_ITEMS`
  dropped in front of them (owner-locked; into their bag on a map that bars drops), then a step later its last line
  and home: its warmth toward them floored at 40 (FRIEND) and, with `HIRE_FRIENDSHIP_ENABLED`, its hire bond at
  Lv 4, so a later hire starts as a friend. Released, it is a citizen of its Victoria town again. A newcomer away
  `MENTOR_ABANDON_DAYS` lets it go quietly; `release <name>` ends it early; switched off, it goes home at the
  newcomer's next login.

**The apprentice** (`server.living.mentor.Apprentices`, F4.5): a player of `APPRENTICE_ADOPT_MIN_OWNER_LEVEL`+ takes
a Beginner citizen of up to `APPRENTICE_ADOPT_MAX_LEVEL` on, by double-clicking it at REGULAR standing (warmth 15+),
or with no standing needed through its "looking for a mentor" request (needs board kind MENTOR: at most
`APPRENTICE_NEEDS_MAX` open, posted at a load with `APPRENTICE_NEED_CHANCE`, said once on its map; its board details
offer "Take them on as your apprentice"). It is a hire at no fee with role APPRENTICE, counted like any hire. At each
advancement (Lv 8 Magician or Lv 10, then Lv 30) its owner picks the branch in a window, then the build where the
branch has more than one (Thief claw or dagger, Pirate gun or knuckle: its career bundle); the window comes at the
level-up, at the owner's next login, and from its card ("Choose <name>'s path..."). The pick goes in `role_target`,
and `JobErrands` sends it to its instructor for the job. A job word in chat to an apprentice opens that window instead
of the instant change (`AgentJobPath.Rule.holdsChatJobChange`; every other companion keeps the instant change). At
its second job it graduates: the role goes, it is a companion like any other, and its friendship (its `hire:` bond)
is untouched. Switched off, an apprentice is a plain hire.

`!mentor <player>` (GM 5) shows a newcomer's mentor, lead, voice, pick, tips told and muted and the leg it leads, or
its newest mentor row; `!mentor offer <player>` opens a test character's offer again at its next login. Rows:
`mentor.pending|offered|accepted|declined|graduated|ended`, `mentor.tip`, `mentor.muted`, `mentor.pick`;
`apprentice.adopted|pick|build|graduated`.

### Personal story arcs

Two switches, both off until their live checks pass (docs/BOT_CONCEPT_PLAN.md F6.1-F6.3 and F6.6, decisions D14
and D15): `living_world.PERSONAL_ARCS_ENABLED` (the arcs, their scenes and the race) and
`living_world.JOB_ERRAND_ATTEND_ENABLED` (attended job errands, which change every Resident companion's 3rd and 4th
job errand, not only the pilots'). Code in `server.living.story`; content in `scripts/config/living-world/stories/`,
`scenes/` and `quests/stories-henesys.yaml`.

**What a player sees.** A recruited Resident with a story tells it to the player who has it on their roster, one
chapter at friendship Lv 3, 6 and 9 (World arcs, above, are one story for the whole server; these are per player and
companion). When a chapter opens (its friendship level reached, or at the owner's login), the companion says its
`nudge` line once in party chat at a calm moment (both on one open map, no window waiting, a town or no monster within
`STORY_CALM_RANGE_PX`) and a pink hint follows ("Fenna wants a word. Double-click her."); it never says it again. Its
card then has "Something on your mind?": the chapter's pages in its NPC window, the player's pick of two replies (each
sets a bond flag and gets its own answer), and "Go on." starts the chapter's quest. Chapters 1 and 2 are talk only.
The third ends in a scene ("I'm ready. Let's go."). A chapter is offered by nothing else: its quest is in no persona's
`questLines`, so the Resident's own window never lists it and nobody else sees it. Released and recruited again, a
companion picks up where it was; another owner starts at chapter 1.

**Scenes (the SCENE step).** A quest step `{ type: SCENE, scene: <id> }` plays a scene from `scenes/*.yaml`: the owner
and the companions in its `cast` who are out with them go into a private copy of a stock map (`SceneInstance`, an
event instance with no script, on the channel's no-op event manager). Lines are map-chat balloons, paced by length.
Effects come from the safe list only. `spawn` beats wait until every monster is down. An `ifPresent` line is skipped
when that companion did not come. Leaving (a return scroll, a warp, a death: the revive lands at the exit), a
disconnect, the lead companion lost for 12 s, `maxMinutes` (at most `STORY_SCENE_MAX_MINUTES`) or the switch going off
ends it early: everyone is set down where the owner started, the companion says its `scene.abort` line, and the step
stays on SCENE, to be played again. Finished, everyone is set down the same way and the step's closing window opens
once the owner has landed and no window is open. Each character is unregistered from the instance before its warp
out, so it lands on the channel's map, and the copy is let go. A boss killed on a stage pays no boss friendship.

**The safe effects** (`SceneEffect`, each a packet stock play already sends): `clear` (the party-quest clear),
`fail` (the party-quest miss), `banner` (a weather banner of narration; item 5120016, 5120024 or 5120027, as the
stock dojang and rescue scripts), `jobAura` (the job-advance aura on the speaking companion), `questClear` (the
quest-clear sparkle on the owner), `tremble` (the screen shake) and `clock` (a countdown). **Never in a scene:** the
QuestAlert avatar effect and any `showInfo` / `showIntro` / `showForeignInfo` path (they crash the v83 client; Quest
markers, above), a second NPC window while one is open (it disconnects the client; every scene and chapter window
opens only as the answer to a click or through LivingTalk's guard), NPC talk speaker bit 0x04 (it crashes the
client), music changes and temporary NPCs. The lint rejects any other effect name. Every map, mob and NPC must
already exist in the stock client (the client patch is on hold).

**The race (F6.6, D14).** Two authored rivals on one owner's roster, each at friendship Lv 6 or more, once the story's
chapter 2 is done: either rival's card offers "About you and Dorran..." (the story's `rival` block). The race scene
brings one foe, picked from the scene's `foes` by the two racers' average level. Whoever dealt the larger share of
its HP wins (`Monster.damageBy`, the player's own damage counts for neither); shares within `STORY_RACE_EVEN_PERCENT`
points are even. A rival who walks off forfeits, and the foe still has to fall. Once the foe is down the race is
settled: leaving before the outcome lines still completes it, and it is never run twice. Both bonds get
`story.rivalry.<a>-<b>` and its outcome flag, and the numbers go in a `story.race` row. Who counts as rivals is
`StoryRivals.rivals` (17 relationship kinds, either side); only an authored race is ever offered.

**Attended job errands (F6.3).** Before its 3rd or 4th job a companion asks its owner in a yes/no window to come
along (`errand.attend.ask.<3|4>` lines in its story, else stock ones). Yes: it stays in the party and follows them
(ships included), saying once per town where the way goes on. In the instructor's town (El Nath, Leafre, Ereve,
Rien) it leads the last stretch with the mentor's lead: through two-way doors first, waiting beyond; Leafre's `in00`
gate its owner takes first. It walks up to its instructor, says its arrive line, advances in front of them (the stock
job-advance effect) and thanks them. At friendship Lv 9, its story's last chapter opens right after. No, a window
closed unanswered, no moment to ask (`JOB_ERRAND_ATTEND_ASK_TRIES` x `_ASK_RETRY_MS`), not in town within
`JOB_ERRAND_ATTEND_MAX_MINUTES`, a logout, a lead not followed or the switch going off: it goes alone, as before.

**The pilots (D15, for review in game).** Henesys, Fenna, Dorran and Ilse. Quest-log ids 31600-31609 are reserved
in the story files; the client's quest log shows them only once the client patch ships.

| Story | Chapter 1 (Lv 3) | Chapter 2 (Lv 6) | Chapter 3 (Lv 9, scene) |
|---|---|---|---|
| Fenna, `fenna-long-road` | Why she keeps the boar road (her father's cart) | The shot she missed at twelve | `fenna-old-blind`: the ridge, Land of Wild Boar 101040001, 6 Wild Boars; Dorran's line if he comes |
| Dorran, `dorran-eleven-names` | The eleven names on the banner | The night of the fire: he had the watch | `dorran-last-grove`: the guild's last hunt, Field South of Ellinia 100050000, 8 Zombie Mushrooms; Ilse's lines if she comes |
| Ilse, `ilse-house-calls` | Why she never sends a bill | Callo, the guildsman she lost | `ilse-forest-call`: a hurt hunter, Forest of Evil I 100040105, Evil Eyes then Curse Eyes; Dorran's lines if he comes |

The race: `rivals-fenna-dorran`, "The Bet", Iron Boar Land 101040003; the foe by level band is Stumpy (to Lv 40),
Faust (60), Mushmom (80), Jr. Balrog (120) and Griffey. Each chapter's reward is bond points and its done flag.

`!story <player>` (GM 5) shows each story's chapter stages for a player, their race, a running scene and an attended
errand. `!world bond <player> <persona> <points>` sets friendship for a test. Rows: `story.nudge`, `story.chapter`,
`story.scene.start|finish|abort`, `story.race`, `story.capstone.errand`; `job.attend.ask`, `job.attend`,
`job.attend.end`, `job.attend.done`.

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
collect (or hand-over) step's item (`QuestLogTargets`; the WZ entry is static, so every step's
targets show at once and a later step's read 0 until it is reached). The client counts items from
the inventory; kills are the ledger's, sent as the quest's progress string (three digits per
monster in that order) on every kill and step (`QuestLog.progressed`) and at login. A kill sends
only that record, as the stock kill counter does; a record in another order, or with a monster the
quest no longer counts, is replaced whole. The stock kill counter skips
quest ids 30000-32767, and a Resident quest earns the quest point and the Growing Quest Ring's
growth only on its first completion (a repeat, or a forfeited repeat put back to completed, does
not). Names stay plain because the Helper prints colour codes literally; the giver's name in the
detail text is blue, and Kaentake draws the list rows blue.

The quest window counts them as well: it shows the In Progress text ("1") and builds no list of its
own, so that text carries the live codes stock quests use. It reads the giver, the line they said
on accepting, then one line per step, for example `Defeat #o5130104#: #r#a301971##k / 30` and
`Collect #t4000078#: #b#c4000078##k / 15`: `#a<log id><slot>#` prints the kills of the slot-th
Check.img/1 monster (one digit, from 1; `QuestLogTargets.mobSlot`), `#c<item>#` how many the
inventory holds, and `#o`/`#t`/`#m` are names the client fills from its own String.wz. Line breaks
are a backslash and an `n`, as in stock quest text. See `docs/QUEST_STEP_PROGRESS_PLAN.md`; the
text changes only with a client re-export.

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
`teach <canonical> <phrase...>`, `haul <player>`, `events <player>`. `!standing <bot> [player]` (GM 5) shows a
player's standing with a Resident, citizen or hire and the numbers behind it (Standing, above). `!mentor <player>`
and `!mentor offer <player>` (GM 5): a newcomer's mentorship (Mentor and apprentice, above).

`tools\living-world-lint.bat [dir]` validates content offline (exit 1 on errors; a home town is any TownLife
profile in `agents/town-life/index.json`, and every routine and rumor spot must name a venue, hotspot or rest spot of
that town's profile);
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

## Tell audit

How bot-like the bots look, per build (docs/BOT_CONCEPT_PLAN.md C2). Off unless `living_world.TELL_AUDIT_ENABLED`
(read at boot). It measures from the boot on, from where bots already act: the reaction to a new set of mobs
(`AgentCombatBehaviorRuntime.responseReady`; a bot with no behaviour calibration, which only the Maple Island cohort
configures, acts on the tick it first sees them and is timed at 0 ms) and a content chat reply's delay
(`ContentChat.timed`), each citizen's gaps
between chatter lines and a line said again on one map within 10 min (`ChatterEngine`), idle bots standing 3 or more
within 30 px (a minute sample of every loaded bot), each citizen's share of the last 7 days online and AFK spells by
map kind (`PresenceScheduler`), the route of each host-started walk (`AgentMapWalk`, hashed per bot, from and to map),
the HP point autopot drinks at and how far through a buff it is recast, and bot deaths per class and activity with the
bot-hours behind them. Deaths are not a tell: they are the guard the imperfections work (F8) is held to.

`GET /runtime/tells` gives each measure's value, its "looks human" band and a verdict (`GREEN`, `AMBER`, `RED`,
`NO_DATA` until it has enough samples, `INFO`). The red lines are the plan's (per-bot reaction CV under 0.25, 5th
percentile under 150 ms, over 60% of repeat walks retraced, over 80% of drinks or recasts at one value, a citizen
online over 20 h a day, any AFK over 2 min on a field); the amber margins are priors for the 24 h baseline to settle
(`server.living.audit.TellBands`). Every hour an `audit.tells` row (no character) goes to `living_event_log`:
`b=<build> h=<hours> <code>=<value>:<G|A|R|-|i> ... d=<class>:<deaths>/<botHours>,... da=field:n,boss:n,instance:n`.
The build is the checkout's HEAD at boot. `tools\tells-report.ps1` compares two builds from those rows
(`GET /runtime/tells/history`), or two saved `/runtime/tells` JSON files; with no arguments, the two newest builds.

## Imperfections

Small human slips with hard caps (docs/BOT_CONCEPT_PLAN.md F8). The user's rule: no severe mistakes, so no extra
deaths and no long idling. Off unless `living_world.IMPERFECTIONS_ENABLED` (read at boot); off, every Agent acts exactly
as before. Only citizens and companions slip (not Residents, the Maple Island cohort or any other Agent).

| Slip | What a player sees | Cap |
|---|---|---|
| Own potion point | each bot drinks at its own HP point, 0.60-0.75 at the configured 0.70 (by its id) | never under `IMPERFECTIONS_POTION_FLOOR` (0.45); the owner's percentages and a learned habit (F2) come first; boss content keeps the configured point |
| Reaction | a beat (180-500 ms, each bot its own typical time) before it goes for a new set of mobs | never after a hit; heals, potions and survival buffs never wait |
| Late potion | now and then (`IMPERFECTIONS_LATE_POTION_CHANCE`) a drink goes down 0.3-1.5 s late | only while HP stays at 50%+ and no mob in reach could hit it under 40%; asked again at every hit; the 1 s low top-up stays |
| Buff timing | recast anywhere in 85-100% of the duration; rarely (`IMPERFECTIONS_BUFF_LAPSE_CHANCE`) an attack or speed buff lapses | survival buffs (Magic Guard, guards, defence, Hyper Body...) never later than 90%; a lapse is 2-10 s |
| Loot slip | a cheap drop left on the ground | never meso, equips, cash or quest items, a player's drop, what the owner's quests need, or above the price-index median |
| Town fumbles | a jump beside a rope that misses it, then the grab; a walk 80-150 px past the NPC it went to, then back | town maps only, no hostile mob within 500 px; the walk past gives up after 6 s |
| Typo | a citizen's town line with two letters swapped, then "*word" | town, Free Market and lobby maps only |
| Companion AFK | while its owner has stood still in town for a minute, a companion may stand AFK 30-90 s ("brb") | town only, at most once an hour, ends at once when the owner moves 200 px or leaves |
| "rez pls?" | a bot dying beside a party member who has Resurrection ready lies there and asks; a bishop bot casts it for real on a dead party member inside its box | the corpse waits at most 10 s from death, then respawns as always |
| Field AFK (D6) | a citizen going AFK on a training field only pauses 30 s to 2 min ("brb water") | starts only at HP 80%+ with no mob within 400 px, ends early under 60% HP or a mob within 150 px; not behind the switch (population `FIELD_AFK_MAX_MS`, 0 = the old 5-15 min) |

One gate owns them (`server.living.imperfections.Imperfections`): nothing slips in party content (a citizen's party
quest or expedition, a companion's run), under `IMPERFECTIONS_HP_GUARD` HP, or while its owner or a human of its party
on its map is dead or under half HP; each bot has a sliding-hour budget (`IMPERFECTIONS_SLIPS_PER_HOUR`, and each
kind's own cap in `Imperfections.Kind`). The Agent engine asks it through `AgentImperfections` and keeps its own
exclusions (an event instance, a map with a boss, a party quest) and caps whatever the gate says.
`GET /runtime/imperfections` counts what fired (`fired.<kind>`) and what was held back (`blocked.<reason>`); one INFO
line an hour too. Acceptance (decision D16): bot deaths per class per bot-hour with the switch on stay within noise of
the same build with it off (D6 is in both). Both phases run one sha, so save `/runtime/tells` at the end of each 24 h
phase and compare the files (`tools\tells-report.ps1 -Before off.json -After on.json`, its "Deaths per bot-hour"
table); anything that raises deaths is cut. Engine numbers live in `agent-engine.yaml` (`AgentImperfectionPolicy.*`, `AgentFumbles.*`).

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

Once-per-server beats: a quest whose `rewards.worldFlags` turns an arc for everyone can name that flag in
`requires.notFlags` (flags and notFlags read world flags as well as the player's). It is then offered only until someone
on the server has done it; a player already on it when that happens can still finish. The regional arcs' last beats
work this way (docs/REGIONAL_RESIDENTS_PLAN.md sections 5 and 9).

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
| mentors and apprentices | `server.living.mentor.*` (`Mentorship`, `MentorLead`, `MentorRoute`, `MentorTips`, `FirstLogin`, `Apprentices`), roles `server.living.roster.CompanionRoles`, `!mentor` (`gm5.MentorCommand`) |
| tell audit | `server.living.audit.*`, Agent seam `AgentTellProbe`, `AdminTellsReader` (`/runtime/tells`), `tools\tells-report.ps1` |
| imperfections | `server.living.imperfections.*`, Agent seam `AgentImperfections` (+ `AgentImperfectionPolicy`, `AgentFumbles`, `AgentLateDrinkRuntime`, `AgentRezWaitRuntime`), `AdminImperfectionsReader` (`/runtime/imperfections`), field AFK `population.FieldPause` |

## Not done yet (from the plan)

Live sessions for GATHER/SELL/STALL (they stay on the offline ledger; live sessions run at
`BACKGROUND_ACTIVE` priority rather than a true mutation-free `BACKGROUND_ABSTRACT` tick, which
the Agent runtime only supports for town life), per-step kill counters in the client quest
log (the mirror tracks start/complete only), and form-based editing in MapleEdit (the page edits
YAML text with lint, not per-field forms).
