# Regional Residents: Residents beyond Victoria Island

Phase F ("regional Residents") of `docs/LIVING_RPG_PLAN.md` and `docs/POPULATED_SERVER_PLAN.md` section 8.
How Residents are authored is in `docs/LIVING_WORLD_GUIDE.md`; this plan says what changes off Victoria, what wave 1
(Orbis, El Nath, Ludibrium) contains, and what to check after it is deployed.

## 1. Where things stood

All 72 named Residents (24 recruitable), 197 Resident quests (118 of them friendship quests), 25 contracts and 9
story arcs lived in the seven Victoria towns. Ossyria and the rest of the world already had citizens, shops, TownLife
profiles (generated 2026-10-03), party quests and field bosses, but no Residents.

The Resident runtime does not care which island a town is on: a persona's `home.town` names a TownLife profile, the
profile gives the map and its venues, presence wakes the town when a human is on that map, and bench camps, recall
travel (server-knowledge routes: Orbis to El Nath on foot, a ship is 3 min) and town names all go through
`ResidentSpots.townMapId`. What still named the seven Victoria towns:

| Where | Before | Now |
|---|---|---|
| `ContentLint.KNOWN_TOWNS` | the 7 Victoria profile ids: any other home town was a lint ERROR | every profile id in `agents/town-life/index.json` |
| routine and rumor spots | never checked: a misspelt or renamed venue put the Resident at the profile's fallback spot without a word | `ContentLint.unresolvedSpot`: every routine, chapter-routine and rumor spot must resolve in its town's profile (ERROR) |
| `ResidentDialogue.PORTRAITS` | borrowed townsperson portraits for the 7 towns, else the Maple Administrator | Orbis, El Nath and Ludibrium townspeople too, until the new Residents' own portraits are exported |
| `TownLifeProfileMain.HAND_AUTHORED` | the 7 Victoria profiles, so `generate` never rewrites venue ids persona YAML uses | unchanged: the spot check catches a regenerated profile that drops a venue a Resident uses, and keeping the three towns out of `HAND_AUTHORED` keeps them in `TownLifeGeneratedProfilesTest`'s geometry and reachability audit |

## 2. Decisions (the user, 2026-10-24)

| Decision | Chosen |
|---|---|
| Residents per town | 7 (Sleepywood/Nautilus size; 21 in wave 1) |
| Towns first | Orbis, El Nath, Ludibrium together (one connected band, levels 30-100) |
| Recruitable share | about a third, as in Victoria: each recruit earned a different way |
| Regional arcs and Victoria | tied to each other, lightly to Victoria (gossip, one errand), never gated by a Victoria arc |

## 3. Regions and waves

Level bands are the fields around each town in the server's own data (`scripts/config/knowledge/server-knowledge.json`).

| Wave | Town (profile) | Fields and levels | Theme |
|---|---|---|---|
| 1 (this branch) | Orbis (`orbis`) | Orbis Tower 23-36, Cloud Park pixies 35-52, Strolling Path 42-47, Gardens of colours 33-53, Garden of Darkness 73 | the city that waits: the station's ledger, a mother meeting the evening ship |
| 1 (this branch) | El Nath (`el-nath`) | Icy Path, Cold Field 50-58, Ice Valley, Sharp Cliff 56-82, Wolf Territory 75-80, Dead Mine 41-57, Cave of Trial 43-90 | what the mountain keeps: the dead mine's roll, the door to Zakum, a sentry who walked off a ship |
| 1 (this branch) | Ludibrium (`ludibrium`) | Terraces 30-39, Toy Factory 36-44, Path and Lost Time 34-46, Warped and Forgotten Path of Time 61-99 | broken time: the tower that lost an hour, the factory that will not stop for it |
| 2 | Leafre, Mu Lung, Herb Town, Ariant, Magatia | 30-110 | written with wave 2 |
| 3 | Omega Sector, Korean Folk Town, Singapore, New Leaf City, Aquarium | 25-80 | Aquarium has no TownLife profile (the generator refuses swim maps): it needs a hand-authored profile first |

## 4. Wave 1 cast

21 Residents, 7 recruitable, 83 quests (all with quest-log ids 30197-30279), 7 contracts, 3 arcs, 6 rumors, 7 loadouts.
Every Resident has the full Victoria kit: a voice for every intent the runtime asks for, an hourly routine on its
town's venues (aligned so each rumor's cast is awake at its spot), relationships and `about.<persona>` gossip, gifts
(`giftItemIds`), fame lines and friendship lines.

Friendship gear continues the Victoria lines one tier up (Victoria's weapons end at L40-70, its armour at L40-75): the
recruits give their own weapon at friendship 5 / 7 / 10 (7 x 100%, 7 x 60%, 2 x 10% + 5 x 60%, every scroll taking), the
armourers a piece made for the player's class at friendship 5 / 7 / 10 (all 100%, all 60%, two 10% and the rest 60%).

| Town | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| Orbis | Corliss | stationmaster (townsfolk), arc owner | - | - |
| Orbis | Ottilie | keeper of the hill steps, Edric's mother | - | - |
| Orbis | Fitch | letter runner to El Nath (Assassin 44-46) | - | - |
| Orbis | Vesna | cloud park warden (Ice/Lightning Wizard 60-62) | kill proof: 60 luster pixies + 30k | wands Evil Tale L58 / Angel Wings L68 / Phoenix Wand L78 |
| Orbis | Ansel | garden hunter (Hunter 60-62) | meso: 90k day rate | bows Asianic L60 / Golden Hinkel L70 / Marine Arund L80 |
| Orbis | Aster | milliner | - | hats L60 / L70 / L80 |
| Orbis | Perrin | jeweller | - | earrings Pink-Flowered L60 / Metal Silver L70 / Metal Heart L80 |
| El Nath | Edric | snowfield sentry (White Knight 70-72), arc owner | arc and rumor: his letter sent, Sigrun heard, TRUSTED, 45k | - |
| El Nath | Sigrun | warden of the door to Zakum (Crusader 70-73) | kill proof: 60 Jr. Cerebes + 20k | two-handed swords Doombringer L70 / Blue Screamer L80 / Berzerker L90 |
| El Nath | Bjarke | wolf hunter (Dragon Knight 70-73) | items: 30 White Pang Tails + 30k | spears Redemption L70 / Omega Spear L80 / Fairfrozen L90 |
| El Nath | Ulla | furrier | - | gloves L60 / L70 / L80 |
| El Nath | Oskar | bootmaker (Gordon's old apprentice) | - | shoes L70 / L80 / L90 |
| El Nath | Maud | keeper of the dead mine's roll | - | - |
| El Nath | Kalle | 3rd-job hopeful (Fighter 58-60) | - | - |
| Ludibrium | Ignatz | clockwinder, arc owner | - | - |
| Ludibrium | Prue | toy factory forewoman | - | - |
| Ludibrium | Benno | retired terrace guard | - | - |
| Ludibrium | Saskia | path runner (Assassin 60-63) | rumor: hear her terms at the weapon store + 40k | claws Sapphire Gigantic L60 / Blue Scarab L70 / Black Mamba L80 |
| Ludibrium | Jasper | warped-path bounty hunter, ex-Nautilus (Gunslinger 60-63) | kill proof: 50 master chronos + 20k | guns Burning Hell L60 / Abyss Shooter L70 / Infinity's Wrath L80 |
| Ludibrium | Della | toy tailor | - | coats (an overall where the class wears robes, else a top) L70 / L80 / L90 |
| Ludibrium | Marlow | banner keeper of the old guard | - | capes Blue Giles L85 / Turquoise Musketeer L95 / Ludibrium Cape L100 |

Quests use the region's own fields: each mob's signature drop (its 60% etc item, so collect steps are honest; one
`dropFrom` overlay, Maud's gold teeth), kill steps on the maps where those mobs spawn, VISIT_MAP to named places
(Old Man's House, the Door to Zakum, the Whirlpool of Time) and TALK_TO errands between Residents, several across towns
(Orbis to El Nath on foot, Orbis to Ludibrium by ship). Area bosses appear once each as a single kill: Riche (Vesna),
the Snow Witch (Ulla) and the Snowman (Bjarke).

**The regional thread.** Eleven years ago the crimson balrog boarded the evening ship from Ellinia. Corliss, Orbis's
stationmaster, never closed that page of his ledger: eleven boarded, one never landed. `corliss-the-unclosed-page`
sends the player to Oda, Ellinia's station master, who remembers the boy in the green hat (the one Victoria errand),
and sets the world flag that opens El Nath's arc: Edric, the sentry who froze on that ship, got off in Orbis
unrecorded and walked down the mountain. His letter (`edric-the-unwritten-letter`, which needs Corliss's quest) goes up
to his mother Ottilie; Corliss then closes the page in red (`corliss-the-last-name`), and Edric, once he has told Sigrun
at the door (rumor), can be recruited. Ludibrium's arc stands alone: Ignatz's tower has struck eleven twice for nine
years; the factory has to stop for one hour (`ludibrium-the-hour-argument`, then `ignatz-the-twelfth-strike`).

| Arc | Chapters (gate) |
|---|---|
| `corliss-the-unclosed-page` | Every Name Written / The Unclosed Page (his quest) / Read Aloud (rumor with Ottilie) / Arrived (world flag `orbis.page.closed`) |
| `edric-the-unwritten-letter` | Sentry / A Name in the Ledger (world flag `orbis.page.opened`) / Sent (his letter quest; unlocks his contract and the rumor) / The Step Is Swept (rumor with Sigrun) |
| `ignatz-the-lost-hour` | Wound Nightly / Eleven Strikes (his quest) / The Hour Argued (rumor with Prue) / Twelve (world flag `ludibrium.hour.found`) |

| Rumor | Cast, spot, hours (ROUTINE_ZONE) | Sets on listeners |
|---|---|---|
| `orbis-the-page-read-aloud` | Corliss, Ottilie at the Top of the Hill door, 19-21, from arc chapter 2 | - |
| `orbis-cloud-park-at-dusk` | Vesna, Ansel on the lower central street, 18-20, every 2 days | - |
| `el-nath-what-the-ship-took` | Edric, Sigrun at the Chief's Residence door, 18-19, from Edric's chapter 3 | `el-nath.heard-the-ship` (Edric's contract) |
| `el-nath-the-roll-call` | Maud, Bjarke, Kalle by Jade, 19-21, every 2 days | - |
| `ludibrium-the-hour-argument` | Ignatz, Prue at the lower central benches, 19-21, from Ignatz's chapter 2 | `ludibrium.heard-hour` (Ignatz's last quest) |
| `ludibrium-the-runners-cut` | Saskia, Jasper at the weapon store door, 18-21 | `ludibrium.heard-runner` (Saskia's contract) |

Victoria is touched in one place: Oda gained an `about.corliss` line.

## 5. Portraits and the client (after deploy)

Nothing in this branch ships an image or a WZ file. Two client steps follow the deploy:

1. **Quest log, with the deploy.** `living_world.QUEST_LOG_MIRROR` is already on, and the 83 new quests have
   quest-log ids, so a player who takes one before their client has the matching Quest.wz entries sees it nameless in
   the quest log (the server itself needs no Quest.wz entry). Run `tools\living-world-client.bat "<client folder>"`
   (or MapleEdit `living_world_quests.py` and deploy Quest.wz + Etc.wz) right after the restart and ship the patch.
   That run also renders the portraits of every Resident that already has a character.
2. **Portraits, once the new Residents exist.** A Resident's character is created the first time its town wakes, so
   the 21 regional Residents have no portrait until then and borrow a townsperson's (section 1). Wake each town once
   (`!world wake orbis`, `!world wake el-nath`, `!world wake ludibrium`, within each Resident's routine hours; Fitch,
   Bjarke are away 10-16, Prue 12-16), then re-run `tools\living-world-client.bat` (or MapleEdit
   `living_world_portraits.py --install`): it assigns ids 9970054+ in `portrait-npc-ids.yaml`, renders Npc.wz/String.wz
   from the client's own Character.wz, and `!world reload` makes the server use them. Npc.wz is ~580 MB, so friends
   download it again.

`portrait-npc-ids.yaml` held 54 ids before this branch, so the 18 friendship-only Victoria Residents added 2026-10-02
(Isolde, Mirabel, ...) still borrow portraits too; the same re-export fixes them. The new ids file and the
quest-log-ids file the tool writes belong in a commit afterwards.

## 6. Live checks after deploy

GM commands (`!world`, `!warp`, `!level`, `!item`) are fine for setup; the behaviour itself is played, not skipped.

1. **Boot.** `!world status` / `!world census`: 93 personas, towns include orbis=7, el-nath=7, ludibrium=7, no lint
   errors in the log.
2. **They live there.** `!world wake orbis` (and el-nath, ludibrium) inside the routine hours, `!world residents`:
   each Resident materializes at its venue (log `Resident <id> (<Name>) materialized in orbis at x,y`), dressed (job
   Residents in their loadout, Vesna/Ansel/Sigrun/Bjarke/Saskia/Jasper carrying their tier-1 friendship weapon from the
   kit). Walk the town at a slot change and watch one walk to its next venue.
3. **Talk.** `hi Corliss`, `who are you`, `tell me about Ottilie`, `Corliss talk`, double-click a Resident: the NPC
   window shows its name, a borrowed Orbis/El Nath/Ludibrium portrait (own portrait after section 5.2) and its menu.
4. **Quest log and markers.** On a level 30-40 character in Orbis: the lightbulb over Corliss, `Corliss quest`,
   accept `corliss-lamps-for-the-platform` (Accept/Decline window with rewards), kill 25 jr. sentinels on Orbis Tower
   20F: the quest is in the client quest log under "Residents" with its name (after the Quest.wz patch) and the kill
   count moves; the book over Corliss once 15 shellpieces are in the bag; `done` completes it.
5. **The thread across towns.** `corliss-the-unclosed-page`: ride to Ellinia, talk to Oda (her TALK_TO line), back to
   Corliss; Corliss's greeting changes (chapter 2) and Edric's in El Nath too (world flag). Level 50: `edric-the-watch`,
   then `edric-the-unwritten-letter` up to Ottilie and back (Edric's chapter 3: his contract unlocks);
   `corliss-the-last-name` becomes available and closes the page (Corliss's chapter 4 once the read-aloud rumor has
   played, evenings 19-21 on the hill steps).
6. **A recruit.** Quickest: Ansel. `ansel-the-colour-gardens` (L35), then `Ansel join me`: he states 90,000; pay it,
   he joins the party; `follow me`, `Ansel bench`, `call Ansel` from Victoria (3 min ship ETA whisper), `release
   Ansel`. A kill-proof one: Jasper (`jasper-the-platoon`, then 50 master chronos on the path of time). A rumor one: Saskia
   (be at the weapon store door 18-21 for `ludibrium-the-runners-cut`).
7. **Friendship gear.** `!world bond <player> vesna 110` (friendship 5), level 58, `vesna-evil-tale`: the hand-in gives
   Evil Tale with seven 100% scrolls taken; `!world bond ... aster 110` and `aster-yellow-plumes` on two classes gives
   the class's L60 hat.
8. **Rumors.** At their hours with a player in range: `orbis-cloud-park-at-dusk`, `el-nath-the-roll-call`,
   `ludibrium-the-runners-cut` play (`!world rumor play <id>` forces one); listeners get the flags in section 4.

## 7. Later waves

The same shape per town: 7 Residents, 2 recruits continuing two more Victoria weapon lines (wave 1 covers wand, bow,
two-handed sword, spear, claw and gun; next: staff, crossbow, dagger, knuckle, polearm, one- and two-handed axe and
blunt, one-handed sword), armourers for top/bottom/shield and the next hat/glove/shoe tiers, one arc per town, rumors
at venues where the cast is awake. Leafre and Mu Lung first (their profiles exist and their fields reach 110).
