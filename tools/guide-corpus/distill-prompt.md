You turn one player-written MapleStory guide into short claims for a private v83 MapleStory server's bots.
The bots are simulated players. They use the claims to answer other players in chat and as starting points for
their own choices (where to train, how to build). You answer with JSON only, matching the given schema.

The server is GMS v83 content (Victoria, Ossyria, Ludus Lake, Leafre, Mu Lung, Herb Town, Ariant, Magatia, Temple
of Time, Showa, NLC, Singapore/Malaysia, Ellin Forest; explorers, Cygnus Knights, Aran). It is NOT stock v83:
skills are rebalanced, rates are custom, any class may wear any gear. A program checks every claim you write against
the server's real data afterwards, so name things exactly as the game does.

Input: a header (source, forum, title, author, date) and the guide text (the author's own posts).

Write claims a player would actually use:
- where to train at which levels, for which classes, and why (mobs, element weakness, safety, exp, drops);
- how to do a party quest, boss or quest: requirements, steps, what to bring, common mistakes;
- job advancement steps; AP and SP builds and the reasons behind them; which weapon or gear to use and when;
- what an item is good for or worth having; how to make mesos; travel routes;
- game mechanics (accuracy, HP washing, knockback, buffs) when the guide explains them.

Rules:
- One idea per claim. Write each `claim` fresh in plain words. Never copy a sentence or a distinctive phrase from the
  guide; you are summarising what it teaches, not quoting it.
- `say` is how a helpful player would type the same advice in game chat: lowercase, short (under 70 characters),
  casual, no quotation of the guide, no emoji, no links, no mention of guides, forums or servers.
- `entities` lists every map, monster, item, NPC, quest, party quest, boss, skill or region the claim depends on,
  using the in-game English name (e.g. "Wild Boar", "Ant Tunnel Park", "Steely Throwing-Knives", "Sabitrama",
  "Zakum", "Haste", "El Nath"). Common slang is fine only if you also know the real name; put the real name.
  - Maps: the map's own name as the minimap shows it, without the street ("Ant Tunnel I", "Line 1 <Area 3>",
    "Mysterious Path 3", "Monkey Swamp II"); an area may go by its common name as a region ("Sleepywood",
    "Ant Tunnel", "Kerning Subway", "Ludibrium Clocktower").
  - Party quests by their full common name: "Kerning City Party Quest", "Ludibrium Party Quest", "Orbis Party
    Quest", "Henesys Party Quest", "Romeo and Juliet Party Quest", "Pirate Party Quest", "Ellin Forest Party Quest",
    "Ludibrium Maze Party Quest", "Amoria Party Quest", "Monster Carnival", "Guild Party Quest", "Crimsonwood Keep
    Party Quest". Bosses as `boss`: "Zakum", "Horntail", "Papulatus", "Pink Bean", "Balrog".
- `jobs`: the branches the claim is for; empty means any class. `level_min`/`level_max`: the character levels the
  claim applies to, 0 when the guide gives none. A party quest's or boss's entry levels belong on the claim about its
  requirements only; other claims about it use 0 unless the guide ties them to levels.
- `depends_on_numbers`: true only when the claim states or rests on a value this server may change: a skill's damage,
  percent, duration or cooldown; exp or meso amounts and rates (exp per stage, exp per hour, "51 to 81 in a few
  hours"); drop chances; meso prices or costs. False for level ranges, AP and stat amounts, item stats, quest and
  recipe quantities, party sizes, and qualitative statements ("good exp", "drops a lot").
- Do not put monster levels, HP or avoid in claims or `say` lines; the server answers those from its own data.
- `strength`: "consensus" when the guide presents it as the standard view, "author_opinion" when it is the author's
  own preference, "contested" when the guide says players disagree. Leave out what the author marks as unconfirmed.
- Leave out: anything specific to the guide's own private server (donor items or NPCs, vote points, custom events,
  custom quests, server rules, staff, its own changes to the game, "in Royals" mechanics that differ from GMS),
  content from after v83 (Evan, Dual Blade, Mercedes, Big Bang changes, Potential, Nebulite, Cubes on equipment,
  Familiars, anything the guide calls post-Big Bang), real-money trading, account security, scam warnings, forum
  housekeeping, jokes, and any claim you cannot tie to something in the game.
- Set `usable` false (with `skip_reason`) when the text has no game knowledge worth keeping: a question, a
  sales thread, a tool link with no explanation, a story, or content wholly after v83 or wholly server-specific.
- At most 40 claims; prefer specific claims (named maps, monsters, items, levels) over general ones.
