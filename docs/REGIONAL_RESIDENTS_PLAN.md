# Regional Residents: Residents beyond Victoria Island

Phase F ("regional Residents") of `docs/LIVING_RPG_PLAN.md` and `docs/POPULATED_SERVER_PLAN.md` section 8.
How Residents are authored is in `docs/LIVING_WORLD_GUIDE.md`; this plan says what changes off Victoria, what wave 1
(Orbis, El Nath, Ludibrium), wave 2 (Leafre, Mu Lung, Herb Town, Ariant, Magatia) and wave 3 (New Leaf City, Omega
Sector, Korean Folk Town, Aquarium, Singapore, Zipangu; section 9) contain, and what to check after each is deployed.

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
| `ResidentDialogue.PORTRAITS` | borrowed townsperson portraits for the 7 towns, else the Maple Administrator | every Resident town: wave 1 added Orbis, El Nath and Ludibrium; wave 2 adds Leafre, Mu Lung, Herb Town, Ariant, Magatia and the Cygnus/Aran branch's Ereve and Rien; wave 3 adds New Leaf City, Omega Sector, Korean Folk Town, the two Aquarium interiors, Singapore's CBD and Boat Quay Town, Mushroom Shrine and Showa Town (stock townspeople of each town, all with their own standing image). `Map.of` stops at 10 entries, so the map is `Map.ofEntries` now |
| `TownLifeProfileMain.HAND_AUTHORED` | the 7 Victoria profiles, so `generate` never rewrites venue ids persona YAML uses | unchanged: the spot check catches a regenerated profile that drops a venue a Resident uses, and keeping the regional towns out of `HAND_AUTHORED` keeps them in `TownLifeGeneratedProfilesTest`'s geometry and reachability audit |

## 2. Decisions (the user, 2026-10-24)

| Decision | Chosen |
|---|---|
| Residents per town | 7 (Sleepywood/Nautilus size; 21 in wave 1, 35 in wave 2) |
| Towns first | Orbis, El Nath, Ludibrium together (one connected band, levels 30-100) |
| Recruitable share | about a third, as in Victoria: each recruit earned a different way |
| Regional arcs and Victoria | tied to each other, lightly to Victoria (gossip, one errand), never gated by a Victoria arc |

Wave 2 (asked for 2026-10-25) keeps all four: the next five towns from section 3, 7 Residents each, 2 recruits per
town, arcs tied to each other in pairs and to wave 1 only by gossip.

Wave 3 (asked for 2026-10-25) keeps them too, with three additions from the user:

| Decision | Chosen |
|---|---|
| Recruit gear past wave 2 | option A: a third regional tier (L88-120) for twelve weapon lines, two recruits per region; Singapore's fields stop at L68, so its two recruits give earrings and capes instead |
| New Leaf City | "a lot more residents with unique rewards for NLC, so we can get some iTCG items in circulation": 25 Residents, and every iTCG code item the server had no source for is a reward (section 9) |
| Olwen | Gunslinger (her lore left the branch open; the user picked it) |
| Arc finales | once per server, as wave 2 (`requires.notFlags`) |

## 3. Regions and waves

Level bands are the fields around each town in the server's own data (`scripts/config/knowledge/server-knowledge.json`).

| Wave | Town (profile) | Fields and levels | Theme |
|---|---|---|---|
| 1 | Orbis (`orbis`) | Orbis Tower 23-36, Cloud Park pixies 35-52, Strolling Path 42-47, Gardens of colours 33-53, Garden of Darkness 73 | the city that waits: the station's ledger, a mother meeting the evening ship |
| 1 | El Nath (`el-nath`) | Icy Path, Cold Field 50-58, Ice Valley, Sharp Cliff 56-82, Wolf Territory 75-80, Dead Mine 41-57, Cave of Trial 43-90 | what the mountain keeps: the dead mine's roll, the door to Zakum, a sentry who walked off a ship |
| 1 | Ludibrium (`ludibrium`) | Terraces 30-39, Toy Factory 36-44, Path and Lost Time 34-46, Warped and Forgotten Path of Time 61-99 | broken time: the tower that lost an hour, the factory that will not stop for it |
| 2 (this branch) | Leafre (`leafre`) | Minar Forest 70-78, Dragon Forest and the Kentaurus fields 80-88, turtles and Rextons 90-97, Wyvern Canyon 97-103, Cornians 100-105, the dragon nests 105-113 | what the dragons left: an egg kept warm forty winters, and a nest that never died |
| 2 (this branch) | Mu Lung (`mu-lung`) | Sky Forest chipmunks and porkies 40-43, the practice field 50-51, Wild Bear area 56-60, peach farm 62, sage cats 66, the walking library 55 | the Book of Forms: a library that walked into the fog, and the one page that didn't |
| 2 (this branch) | Herb Town (`herb-town`) | flower serpents 45, jars 47-48, bellflower fields 53-54, the red-nose pirate den 64-70 | the hundred-year root, and the borrowed page round its marker |
| 2 (this branch) | Ariant (`ariant`) | the Burning Sands 20-29, the north desert road 29-32, the sunset giants 40, the Sahel road 24-32 | the town that carries its water: a well gone to sand in a week |
| 2 (this branch) | Magatia (`magatia`) | Zenumist institute 32-80, Alcadno institute 42-75 | the city of two doors, and a bench between them |
| 3 | New Leaf City (`new-leaf-city`) | Masteria's jungle 19-50, MesoGears 25-80, the Haunted House 20-100, Phantom Forest 35-110, Crimsonwood Mountain 70-120 | the Leaf Cup: a card club with nothing on its prize table, and the old Masteria forging drawings that fill it |
| 3 | Omega Sector (`omega-sector`) | Kulan and Boswell fields 40-46, the hidden fields to 99, Ludibrium's clock tower 74-108 for the recruits | a radio operator and a dead channel that talks at nine |
| 3 | Korean Folk Town (`korean-folk-town`) | Black Mountain, the ridges and the goblin house 22-62, the clock tower 36-108 for the recruits | a girl, her father's radio, and the voice that answers it from the base under the tower |
| 3 | Aquarium (`aquarium-hall`, `aquarium-store`) | Aqua Road 23-110 (the shallows to the Grave of the Wrecked Ship) | an old diver and the light in the wreck |
| 3 | Singapore (`singapore-cbd`, `boat-quay-town`) | the suburbs, the Mysterious Path and the ghost ship 18-68 | lanterns for a lost crew, and the one that never came home |
| 3 | Zipangu (`mushroom-shrine`, `showa-town`) | the shrine's fox mountain and cemetery roads, the spas and Showa's streets 25-130 | a shrine bell with no voice, and the boy who took its clapper |

Aquarium's town map is a swim map, which the TownLife generator refuses (bots do not path by swimming), so its
Residents live in the two walkable interiors, the Center Hall (230000001) and the Department Store (230000002); wave 3
generated both profiles. Neither gets ambient citizens, so citizen traffic is unchanged. Recruits still follow their
owner into the swim fields (the follow and grind ticks swim).

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

## 5. Wave 2 cast

35 Residents, 10 recruitable, 124 quests (quest-log ids 30285-30408, appended after the Cygnus/Aran branch's
30280-30284), 10 contracts, 5 arcs, 10 rumors, 10 loadouts. The kit and the gear rules are wave 1's. Each recruit gives
the next tier of a Victoria weapon line that wave 1 did not take, and carries the tier below it in its loadout; three
armourers continue wave 1's hat, glove and shoe lines one tier up.

| Town | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| Leafre | Hessa | keeper of the cold egg (townsfolk), arc owner | - | - |
| Leafre | Arlo | would-be dragon rider, Hessa's grandson | - | - |
| Leafre | Bastian | knight of the west border (White Knight 70) | kill proof: 60 dark rashes + 30k | one-handed swords Red Katana L70 / Khan L80 / Fraute L90 |
| Leafre | Runa | axe of the canyon road (Crusader 80) | meso: 120k day rate | one-handed axes Gaea L80 / Vifennis L90 / Tomahawk L100 |
| Leafre | Mirelle | hatter | - | helmets for the player's class L90 / L100 / L110 |
| Leafre | Dovan | cobbler of wyvern hide | - | boots L100 / L110 / L120 |
| Leafre | Elsk | porter of the Orbis ship, Corliss's letter-friend | - | - |
| Mu Lung | Meiwen | teacher of the forms, arc owner | - | - |
| Mu Lung | Qing | keeper of the walking library | - | - |
| Mu Lung | Baojun | guard of the sky road (Spearman 60) | kill proof: 60 black porkies + 25k | polearms Skylar L60 / The Gold Dragon L70 / Eclipse L80 |
| Mu Lung | Suyin | brawler of the practice field (Marauder 70) | intro quest and rumor: warm up for her, hear her challenge to Xiaobo, 50k | knuckles Beia Crash L70 / Steelno L80 / White Fangz L90 |
| Mu Lung | Fengzi | glover of the forms | - | gloves for the player's class L90 / L100 / L110 |
| Mu Lung | Lai | peach farmer | - | - |
| Mu Lung | Xiaobo | pupil of the practice field | - | - |
| Herb Town | Oksun | keeper of the hundred-year garden, arc owner | - | - |
| Herb Town | Dohyun | root-digger, Oksun's grandson | - | - |
| Herb Town | Taejin | woodcutter of the old swamp (Fighter 60) | items: 30 bellflowers + 20k | two-handed axes The Shining L60 / Chrono L70 / Helios L80 |
| Herb Town | Maren | hunter of the red-nose pirates, ex-Aquarium cutters (White Knight 70) | meso: 70k day rate | two-handed blunts Gigantic Sledge L70 / The Morningstar L80 / Leomite L90 |
| Herb Town | Sookja | keeper of the tea house | - | - |
| Herb Town | Gyuwon | radish farmer, formerly a red-nose | - | - |
| Herb Town | Aeri | apprentice herbalist | - | - |
| Ariant | Nadira | water-seller of the market, arc owner | - | - |
| Ariant | Farid | jar-runner | - | - |
| Ariant | Sefa | once a sand bandit (Bandit 60) | rumor: hear her terms to Farid + 40k | daggers Deadly Fin L60 / Kandine L70 / Dragon's Tail L80 |
| Ariant | Harun | guard of the palace gate (Page 50) | kill proof: 40 desert giants + 15k | one-handed blunts Knuckle Mace L50 / Tamus L60 / The Judgement L70 |
| Ariant | Imran | cactus farmer | - | - |
| Ariant | Yasmin | dyer of sunset scarves | - | - |
| Ariant | Tariq | caravan master of the Sahel road | - | - |
| Magatia | Gilda | clockmaker of the Alcadno, arc owner | - | - |
| Magatia | Aurel | archivist of the Zenumist | - | - |
| Magatia | Vael | alchemist of the Zenumist (Fire/Poison Wizard 65) | meso: 80k day rate | staves Evil Wings L65 / Dark Ritual L75 / Kage L85 |
| Magatia | Kipp | watchman of the Alcadno labs (Crossbowman 60) | items: 30 pieces of steel + 20k | crossbows Golden Crow L60 / Gross Jaeger L70 / Marine Raven L80 |
| Magatia | Pellam | apprentice of the archive | - | - |
| Magatia | Mott | sweeper of both houses | - | - |
| Magatia | Hedda | builder of mechanical birds, retired Alcadno engineer | - | - |

Day-rate, item and kill-proof recruits need their intro quest first (the `<id>.intro.done` flag); Sefa needs only her
rumor, Suyin both. Each recruit's `refuse.flags` line tells the player how to get every flag its contract needs. With wave 1's six lines (wand, bow, two-handed sword, spear, claw, gun) and these ten, every one of
Victoria's sixteen weapon lines now has a regional tier.

Single kills of rare spawns: Rurumo (Vael's Evil Wings), Deet and Roi (Kage), the Security Camera (Marine Raven).
The Critical Error room behind Alcadno area C-1 is a mini-dungeon (`scripts/portal/MD_error.js` opens the first free
copy of 261020301-261020330), so the steps that use it list all thirty copies.

**The regional threads.** Two pairs of towns share a story; Leafre's stands alone.

- *Mu Lung and Herb Town.* Mu Lung's library walked into the foggy forest with every book but one page: the seventh
  form of Meiwen's Book of Forms. Qing's library card names the borrower, thirty years ago: "Oksun, Herb Town. to wrap
  a root marker. will return." (`meiwen-the-library-card`, world flag `mu-lung.page.traced`). The marker is on her
  mother's hundred-year bellflower root. Her root comes up only when Dohyun is ready (`herb-town-dig-it-this-year`, then
  `oksun-dig-the-root`, world flag `herb-town.root.dug`), and Meiwen's last quest takes the page home
  (`meiwen-the-seventh-form`, needs both the Mu Lung rumor and the dug root; world flag `mu-lung.forms.whole`, which
  also closes Oksun's arc).
- *Ariant and Magatia.* Ariant's market well went to sand in a week, seven years ago; sand dwarves cut its vein
  digging under the sunset giant (`nadira-empty-sacks`, `nadira-the-tunnel-mouth`, world flag `ariant.well.traced`).
  Only an Alcadno pump under a Zenumist still could lift and clean it, and the societies have not built anything
  together in a hundred years. In Magatia, Gilda and Aurel have shared a bench and a tin of notes for twenty years
  without a word aloud; once Gilda's clock is mended they speak in the square (`magatia-out-loud`), and her errand to
  Aurel sends each through the other's door (`gilda-the-other-door`, world flag `magatia.bench.crossed`). Nadira's
  last quest asks them both (`nadira-a-new-spring`, needs the Ariant rumor and Magatia's flag; world flag
  `ariant.well.flowing`, which opens the last chapter in both towns).
- *Leafre.* Hessa was nine when she carried an egg down from the burning dragon nest; she has kept it warm forty
  winters. A matching shell from the nest's newties shows the nest never died (`hessa-the-shell-that-matches`, world
  flag `leafre.nest.alive`), Arlo argues it should go home (`leafre-should-it-go-home`), and the player carries it
  there (`hessa-carry-it-home`, L105).

| Arc | Chapters (gate) |
|---|---|
| `hessa-the-cold-egg` | Kept Warm / Forty Winters (her first quest) / The Nest Remembers (world flag `leafre.nest.alive`; unlocks the rumor) / Home (world flag `leafre.egg.home`) |
| `meiwen-the-book-of-forms` | Six Forms / Pages in the Fog (her first quest) / Lent, Not Lost (world flag `mu-lung.page.traced`; unlocks the rumor) / The Seventh Form (world flag `mu-lung.forms.whole`) |
| `oksun-the-hundred-year-root` | Planted Before Me / A Hundred Springs (her first quest; unlocks the rumor) / Up (world flag `herb-town.root.dug`) / Returned (world flag `mu-lung.forms.whole`) |
| `nadira-the-dry-well` | Seven Dry Years / Wet Sand (her first quest) / Lost Is Not Gone (world flag `ariant.well.traced`; unlocks the rumor) / The Well Sings (world flag `ariant.well.flowing`) |
| `gilda-the-bench-between` | Twenty Years of Notes / On Time (her first quest; unlocks the rumor) / The Other Door (world flag `magatia.bench.crossed`) / Down the Sahel Road (world flag `ariant.well.flowing`) |

| Rumor | Cast, spot, hours | Sets on listeners |
|---|---|---|
| `leafre-should-it-go-home` | Hessa, Arlo on the lower central street, 19-20, from Hessa's chapter 3 | `leafre.heard-home` (Hessa's last quest) |
| `leafre-the-canyon-watch` | Runa, Bastian by Koscu, 18-20, every 2 days | - |
| `mu-lung-the-borrowed-page` | Meiwen, Qing on the lower central street, 19-20, from Meiwen's chapter 3 | `mu-lung.heard-page` (Meiwen's last quest) |
| `mu-lung-the-challenge` | Suyin, Xiaobo on the lower west street, 17-19 | `mu-lung.heard-challenge` (Suyin's contract) |
| `herb-town-dig-it-this-year` | Oksun, Dohyun on the middle east street, 19-20, from Oksun's chapter 2 | `herb-town.heard-root` (Oksun's dig) |
| `herb-town-the-old-crew` | Maren, Gyuwon on the middle east street, 19-21, every 2 days | - |
| `ariant-the-well-argument` | Nadira, Tariq on the middle central street, 19-21, from Nadira's chapter 3 | `ariant.heard-well` (Nadira's last quest) |
| `ariant-sefas-terms` | Sefa, Farid at the station platform door, 17-19 | `ariant.heard-sefa` (Sefa's contract) |
| `magatia-out-loud` | Gilda, Aurel on the lower central street, 19-21, from Gilda's chapter 2 | `magatia.heard-bench` (Gilda's errand) |
| `magatia-the-sweepers-news` | Mott, Hedda on the lower east street, 18-20, every 2 days | - |

A rumor repeats on its schedule with no end gate, so every rumor line reads true both before and after the beat it
sets up; base dialogue that a chapter would make untrue is either worded to hold in every chapter or overridden.

Each arc's last beat happens once per server (asked for 2026-10-25): `hessa-carry-it-home`, `oksun-dig-the-root`,
`meiwen-the-seventh-form`, `nadira-a-new-spring` and `gilda-the-other-door` name the world flag they set in
`requires.notFlags`, so the first player to do one turns the arc for everyone and nobody is offered it afterwards (a
player already on it can still finish). Nothing requires these quests as completed, so no later quest is cut off.
`ArcFinalBeatOncePerServerTest` pins this. Wave 1's last beats are still offered after their flags are set.

Wave 1 is touched in two lines: Corliss gained `about.elsk` (Orbis), Jasper `about.maren` (Ludibrium). Errands cross
towns along the real routes: Leafre to Orbis by ship, Mu Lung to Herb Town on foot (and Mu Lung to Orbis by crane),
Ariant to Magatia up the Sahel road.

## 6. Portraits and the client (after deploy)

Nothing in any wave's branch ships an image or a WZ file. Two client steps follow each deploy. **For waves 2 and 3 the user is
holding the client patch (2026-10-25): the steps below wait until it is released; the export data in this branch is
complete for when it is.**

1. **Quest log, with the deploy.** `living_world.QUEST_LOG_MIRROR` is already on, and the new quests have quest-log
   ids (wave 1: 83, 30197-30279; wave 2: 124, 30285-30408; wave 3: 180, 30409-30588 of the block 30409-30799
   reserved for it), so a player who takes one before their client has the
   matching Quest.wz entries sees it nameless in the quest log (the server itself needs no Quest.wz entry). Run
   `tools\living-world-client.bat "<client folder>"` (or MapleEdit `living_world_quests.py` and deploy Quest.wz + Etc.wz)
   right after the restart and ship the patch. Since the quest-step-progress merge the exporter also writes each step's
   live counters (`#a`/`#c` codes) into the quest text, so wave 2's quests get counters with no extra work. That run
   also renders the portraits of every Resident that already has a character.
2. **Portraits, once the new Residents exist.** A Resident's character is created the first time its town wakes, so
   the regional Residents have no portrait until then and borrow a townsperson's (section 1). Wake each town once
   within its cast's routine hours, then re-run `tools\living-world-client.bat` (or MapleEdit
   `living_world_portraits.py --install`): it assigns the next ids in `portrait-npc-ids.yaml` (wave 1's from 9970054),
   renders Npc.wz/String.wz from the client's own Character.wz, and `!world reload` makes the server use them. Npc.wz is
   ~580 MB, so friends download it again.
   - Wave 1: `!world wake orbis`, `el-nath`, `ludibrium`; Fitch and Bjarke are away 10-16, Prue 12-16.
   - Wave 2: `!world wake leafre`, `mu-lung`, `herb-town`, `ariant`, `magatia` at 18-19 server time, when every one of
     the 35 is in town (Mott is away 9-17, Suyin 10-16, the recruits mostly 12-18).
   - Wave 3: `!world wake omega-sector`, `korean-folk-town`, `singapore-cbd`, `boat-quay-town`, `mushroom-shrine`,
     `showa-town` at 18 server time; `aquarium-hall` and `aquarium-store` at 10-11; `new-leaf-city` twice, at 12 and
     at 18 (its 25 are never all in town at one hour: Ramona and Hollis are out at noon, Moxie, Brody, Delphine, Duke and
     Cosmo at 18).

`portrait-npc-ids.yaml` held 54 ids before wave 1, so the 18 friendship-only Victoria Residents added 2026-10-02
(Isolde, Mirabel, ...) still borrow portraits too; the same re-export fixes them. The new ids file and the
quest-log-ids file the tool writes belong in a commit afterwards.

## 7. Live checks after deploy

GM commands (`!world`, `!warp`, `!level`, `!item`) are fine for setup; the behaviour itself is played, not skipped.

### Wave 1

1. **Boot.** `!world status` / `!world census`: 93 personas, towns include orbis=7, el-nath=7, ludibrium=7, no lint
   errors in the log.
2. **They live there.** `!world wake orbis` (and el-nath, ludibrium) inside the routine hours, `!world residents`:
   each Resident materializes at its venue (log `Resident <id> (<Name>) materialized in orbis at x,y`), dressed (job
   Residents in their loadout, Vesna/Ansel/Sigrun/Bjarke/Saskia/Jasper carrying their tier-1 friendship weapon from the
   kit). Walk the town at a slot change and watch one walk to its next venue.
3. **Talk.** `hi Corliss`, `who are you`, `tell me about Ottilie`, `Corliss talk`, double-click a Resident: the NPC
   window shows its name, a borrowed Orbis/El Nath/Ludibrium portrait (own portrait after section 6.2) and its menu.
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

### Wave 2

1. **Boot.** `!world census`: 131 personas (93 + Cygnus/Aran's 3 + 35), towns include leafre=7, mu-lung=7,
   herb-town=7, ariant=7, magatia=7; the boot log has no living-world lint errors.
2. **They live there.** `!world wake magatia` (and the other four) at 18-19 server time, `!world residents`: each
   Resident materializes at its venue; Vael, Kipp, Sefa, Harun, Bastian, Runa, Baojun, Suyin, Taejin and Maren are
   dressed in their loadout with the weapon one tier below their first friendship gift. Watch Gilda and Aurel walk to
   the lower central street at 19:00.
3. **Talk.** `hi Nadira`, `who are you`, `tell me about Tariq`, double-click Hedda: the NPC window shows the name, a
   borrowed Ariant/Magatia portrait (own portrait after section 6.2) and the menu.
4. **Quest log and markers.** A level 30-35 character in Magatia: the lightbulb over Aurel, accept
   `aurel-gelatin-seals`, kill 25 cube slimes in Zenumist units 101/102: the quest shows in the quest log with its live
   counters (after the Quest.wz patch), the book over Aurel at 15 gelatins, `done` completes it. Also take
   `hedda-a-mechanical-bird` and enter Critical Error from area C-1: the visit step completes in whichever copy the
   portal opens.
5. **A thread across towns.** Level 44 in Ariant: `nadira-empty-sacks` (Nadira's greeting changes: chapter 2),
   `nadira-the-tunnel-mouth` (world flag; the well argument plays 19-21 on the middle central street). In Magatia:
   `gilda-mithril-for-the-clock`, then stand on the lower central street 19-21 for `magatia-out-loud`, then
   `gilda-the-other-door` (Gilda's chapter 3). Back in Ariant `nadira-a-new-spring`: talk to Gilda and Aurel, return;
   Nadira's and Gilda's last chapters open together. A second character that has heard both rumors is now offered
   neither `gilda-the-other-door` nor `nadira-a-new-spring` (once per server). The Mu Lung/Herb Town thread is the same shape
   (`meiwen-the-library-card`, `oksun-the-old-gardens`, the two rumors, `oksun-dig-the-root`, `meiwen-the-seventh-form`).
6. **A recruit.** Quickest: Sefa. Stand by Ariant's station platform door 17-19 until `ariant-sefas-terms` plays, then
   `Sefa join me`: 40,000; `follow me`, `Sefa bench`, `call Sefa` from Magatia, `release Sefa`. A kill-proof one: Harun
   (`harun-the-sunset-giants`, then 40 desert giants at the ruins). An item one: Kipp (`kipp-the-rounds`, then 30
   pieces of steel from the iron mutae).
7. **Friendship gear.** `!world bond <player> vael 110`, level 63, `vael-evil-wings` (one rurumo for the purple
   liquid): the hand-in gives Evil Wings with seven 100% scrolls taken. `!world bond ... fengzi 110` and
   `fengzi-kentaurus-marrow` on two classes gives each class its L90 glove.
8. **Rumors.** `leafre-the-canyon-watch`, `mu-lung-the-challenge`, `herb-town-the-old-crew`,
   `magatia-the-sweepers-news` at their hours (or `!world rumor play <id>`); listeners get the flags in section 5.

### Wave 3

1. **Boot.** `!world census`: 60 personas more than the main line had before the merge; towns include
   new-leaf-city=25, omega-sector=7, korean-folk-town=7, aquarium-hall=3, aquarium-store=4, singapore-cbd=3,
   boat-quay-town=4, mushroom-shrine=4, showa-town=3; the boot log has no living-world lint errors.
2. **They live there.** Wake each town at the hours in section 6.2, `!world residents`: each Resident materializes at
   its venue. The Aquarium cast stands inside the Center Hall and the Department Store, never on the swim map.
   The recruits wear the weapon one tier below their first friendship gift (Vesper Infinity's Wrath, Gwang Eclipse,
   Genzo Fraute with a Steel Ancient Shield, Thalia Kage, Delphine a Phoenix Wand).
3. **Talk.** `hi Monty`, `who are you`, `tell me about Ramona`, double-click Chiyo: the NPC window shows the name, a
   borrowed townsperson portrait (own portrait after section 6.2) and the menu.
4. **Quest log and markers.** A level 22 character in New Leaf City: the lightbulb over Monty, accept
   `monty-the-empty-prize-table` (glutton ghouls in the Haunted House foyer, their coat hangers, a word with Fergus):
   it shows in the quest log with its counters (after the Quest.wz patch), the book over Monty when it is ready.
5. **iTCG rewards.** `!world bond <player> cosmo 110`, levels 20-28: Cosmo's quests give the Stirgeman cape, pants
   and Stirge-on-a-String. `!world bond <player> gideon 110`, levels 46-66: the Ancient forging pieces land in the
   Etc tab. The forging NPCs (Foxwit, Glimmer Man, Corine, Stirgeman, Lita Lawless, John Barricade, Spindle) are still
   stubs, so the materials only become useful when that session's forging work lands; the stock client must take every
   reward without a disconnect (all are in the stock Cosmic client's WZ).
6. **A thread across towns.** Level 42 in Omega Sector: `lyra-the-dead-channel` (Lyra's chapter 2), then in Korean
   Folk Town `bomi-the-hissing-radio` (both chapters move on `kft.radio.mended`), `lyra-the-trace` at 45, then
   `bomi-the-first-answer`: Lyra's chapter 5 and Bomi's chapter 4 open together. A second character is no longer
   offered `lyra-the-trace` or `bomi-the-first-answer` (once per server). The lantern thread is the same shape
   (`delmar-the-glass`, `rosnah-the-ghost-ship`, `delmar-the-wreck-lantern` at 98, `rosnah-the-lantern-home`), and so
   is the bell (`chiyo-the-silent-bell`, `daichi-the-hideout` at 92 against the Boss on Showa Street 3,
   `chiyo-the-bell-rings`).
7. **A recruit of each kind.** Kill proof: Lou (`lou-the-guitar-springs`, `Lou join me`, then 50 psycho jacks in the
   Haunted House: "Lou's proof: 10/50 kills" every ten). Day rate: Moxie, 50,000 for 24 hours. Rumor only: stand on
   the CBD's middle central street 16-18 until `sg-the-hawker-stall` plays, then `Priscilla join me`, 40,000. Items:
   Hollis (20 diferium fuel cells). Rumor and items: Vance (hear `nlc-the-beat-and-the-hustle`, lower east street
   20-21). Take Kael or Thalia into Aqua Road: they follow by swimming. Genzo and Rin only inside Zipangu until the
   route graph reaches it (section 9).
8. **Olwen.** `!grantcompanion olwen` on a test account (she is a Lv29 Beginner): at her first map change she leaves
   for Kyrin in Nautilus in her own words, comes back a Pirate, and at 30 goes again for Gunslinger. The Olwen in
   Lith Harbor before the grant is unchanged: still a Beginner with her ledger, not reshaped by the deploy.
9. **Rumors.** `nlc-the-lunch-counter` (13:00 by Miki), `omega-the-mess`, `kft-the-mill`, `aqua-the-benches`,
   `sg-the-quay-lanterns`, `zip-the-ronin-at-the-gate` at their hours (or `!world rumor play <id>`); listeners get the
   flags in section 9.

## 8. Later waves

The same shape per town: 7 Residents, 2 recruits continuing a weapon line where the earlier waves stop, one arc per
town, rumors at venues where the cast is awake, each recruit earned a different way. After wave 3 the remaining towns
and sub-areas (Temple of Time, Minar Forest, the Aqua Road areas, Orbis Tower, the Sleepywood dungeon, Malaysia and
the Community Repack towns) come from the coordinator's town audit, split across several sessions, each with its own
quest-log id block (wave 3 holds 30409-30799). Four weapon lines keep the top tier waves 1 and 2 gave them: two-handed
swords, spears, one-handed axes and two-handed blunts.

## 9. Wave 3 cast

60 Residents in six regions and nine town profiles (New Leaf City 25; Omega Sector, Korean Folk Town, Aquarium,
Singapore and Zipangu 7 each), 17 recruitable, 180 quests (quest-log ids 30409-30588), 17 contracts, 6 arcs, 15
rumors, 15 loadouts. The kit and the gear rules are wave 1's. Twelve recruits give a third regional tier of a weapon
line (each carries the tier below in its loadout); Singapore's two give earrings and capes; New Leaf City's other
gear givers hand out iTCG items.

| Town | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| New Leaf City | Monty | proprietor of the Leaf Street Card Club, arc owner | - | - |
| New Leaf City | Ramona | reporter for the Leaf Ledger | - | - |
| New Leaf City | Benji | newsboy of the subway steps | - | - |
| New Leaf City | Dolores | cook of the Leaf Street Diner | - | - |
| New Leaf City | Rocco | dispatcher at the taxi stand | - | - |
| New Leaf City | Mabel | retired explorer of the west benches (Bowman 45) | - | - |
| New Leaf City | Gwen | florist of the upper west street | - | - |
| New Leaf City | Priya | secretary to Mayor Icebyrd Slimm | - | - |
| New Leaf City | Otis | porter of the subway station | - | - |
| New Leaf City | Fergus | smith apprenticed to the Glimmer Man (Warrior 32) | - | Glimmer Man weapons L70 by class (Nebula Dagger 2, Astral Blade, ...) |
| New Leaf City | Clementine | milliner (Cleric 33) | - | iTCG hats: Antellion Miter L35, Infinity Circlet L50 |
| New Leaf City | Bijou | jeweller of the Free Market door | - | Crystal Leaf Earrings L40, Dark Shards L50 |
| New Leaf City | Ezra | antiquities dealer (Thief 36) | - | Black Phoenix Shield L20 |
| New Leaf City | Theodora | archivist to Professor Foxwit (Magician 30) | - | Zeta Cape L60 |
| New Leaf City | Hollis | surveyor of the Masteria jungle (Hunter 52) | items: 20 diferium fuel cells + 15k | Sirius Cloak L50; Tiger's Fang, Winkel or Neva L50 by class; Stormcaster or Glitter Gloves |
| New Leaf City | Moxie | tinkerer of the MesoGears (Assassin 50) | day rate: 50k | Stormcaster Gloves L50 |
| New Leaf City | Lou | busker of the subway steps (Fighter 48) | kill proof: 50 psycho jacks + 10k | Facestompers L50 |
| New Leaf City | Brody | officer of the city patrol (White Knight 78) | items: 30 jumper cables + 25k | one-handed blunts Heaven's Justice L80 / Ruin Hammer L90 / Battle Hammer L100 |
| New Leaf City | Vance | hustler of the lower east street (Chief Bandit 88) | rumor and items: hear Brody on him, 20 windraider badges + 30k | daggers Varkit L90 / Blood Dagger L100 / Dragon Kreda L110 |
| New Leaf City | Delphine | assistant to Professor Foxwit (Fire/Poison Mage 86) | kill proof: 60 firebombs + 25k | wands Magicodar L88 / Dimon Wand L98 / Dragon Wand L108 |
| New Leaf City | Duke | boxer of the east street gym (Marauder 98) | day rate: 150k | knuckles King Cent L100 / Dragon Slash Claw L110 / Timeless Equinox L120 |
| New Leaf City | Odette | keeper of the spirit pens | - | the Set 3 spirit weapons L10 (Akha, Xaru, Kuma, Maku, Umaru), Taru Spirit Cape L80 |
| New Leaf City | Cosmo | Stirgeman's sidekick, unofficial | - | the Stirgeman set L20: cape, Stirge-on-a-String, utility pants |
| New Leaf City | Gideon | bosshunter of the west benches (Fighter 58) | - | the Ancient forging pieces and the Set 2 boss materials (etc) |
| New Leaf City | Sabine | healer of Adonis's expedition (Priest 72) | - | Crystal Blade L50, Cloak of Corruption L60 |
| Omega Sector | Lyra | radio operator of the signal hut, arc owner | - | - |
| Omega Sector | Vesper | marksman of the Kulan watch (Outlaw 88) | kill proof: 60 buffoons + 30k | guns The Peacemaker L90 / Concerto L100 / Dragonfire Revolver L110 |
| Omega Sector | Rourke | heavy trooper of the silo (Crusader 88) | rumor and items: hear Vesper on him, 20 deep buffoon rock pieces + 30k | two-handed axes Colonian Axe L90 / Tavar L100 / Dragon Battle Axe L110 |
| Omega Sector | Hettie | quartermaster of the supply silo | - | - |
| Omega Sector | Rowan | cadet of the morning drill | - | - |
| Omega Sector | Dmitri | cook of the base mess | - | - |
| Omega Sector | Petra | mechanic of the command workshop | - | - |
| Korean Folk Town | Bomi | keeper of her father's radio, arc owner | - | - |
| Korean Folk Town | Gwang | tiger hunter of Black Mountain (Dragon Knight 88) | day rate: 110k | polearms Hellslayer L90 / Zedbug L100 / Dragon Chelbird L110 |
| Korean Folk Town | Seori | archer of the Moon Ridge (Ranger 88) | intro and rumor: hear Gwang on her, 50k | bows Metus L90 / White Nisrock L100 / Dragon Shiner Bow L110 |
| Korean Folk Town | Dalja | rice-cake maker of the mill | - | - |
| Korean Folk Town | Byungho | drummer of the folk square (Fighter 45) | - | - |
| Korean Folk Town | Okja | fortune teller of the old gate | - | - |
| Korean Folk Town | Jiho | scholar studying for the exam (Magician 36) | - | - |
| Aquarium | Delmar | old diver of the Center Hall, arc owner | - | - |
| Aquarium | Thalia | tide mage (Ice/Lightning Mage 93) | kill proof: 60 squids + 30k | staves Blue Marine L95 / Dragon Staff L110 / Timeless Aeas Hand L120 |
| Aquarium | Kael | harpooner of the deep sea gorge (Sniper 88) | rumor and items: hear Thalia on him, 20 lime powder bottles + 30k | crossbows Casa Crow L90 / White Neschere L100 / Dragon Shiner Cross L110 |
| Aquarium | Oriel | dolphin trainer of the Department Store | - | - |
| Aquarium | Sunniva | pearl diver of the Penguin's Playground | - | - |
| Aquarium | Finn | a boy who wants to see a shark | - | - |
| Aquarium | Ambrose | scholar of sea life (Magician 50) | - | - |
| Singapore | Rosnah | lantern keeper of Boat Quay, arc owner | - | - |
| Singapore | Siti | lantern maker's apprentice | - | - |
| Singapore | Benedict | harbour clerk of the quay | - | - |
| Singapore | Arjun | jeweller-adventurer of the quay (Hunter 64) | day rate: 70k | earrings Crystal Flower L55 / Gold Drop L65 / Half Earrings L75 |
| Singapore | Wei | hawker of the food centre | - | - |
| Singapore | Mei | mechanic of the taxi fleet | - | - |
| Singapore | Priscilla | seamstress of the CBD corner (Cleric 64) | rumor only: hear Wei on her, 40k | capes Blue Gaia Cape L65 / Maple Cape L70 / Eclipse Cloak L75 |
| Zipangu | Chiyo | keeper of the shrine bell, arc owner | - | - |
| Zipangu | Genzo | ronin at the shrine gate (Crusader 98) | kill proof: 60 bains + 30k | one-handed swords Sparta L100 / Dragon Carabella L110 / Timeless Executioners L120 |
| Zipangu | Kaede | dango cook's apprentice | - | - |
| Zipangu | Isamu | fox-mask carver | - | - |
| Zipangu | Daichi | bathhouse hand of Showa, once the Boss's errand boy | - | - |
| Zipangu | Okiku | keeper of the Showa bathhouse | - | - |
| Zipangu | Rin | watcher on the Showa rooftops (Hermit 88) | rumor and items: hear Okiku on her, 20 pairs of leader A's shades + 30k | claws Casters L90 / Red Craven L100 / Dragon Green Sleve L110 |

Aquarium's cast is split between its two interiors: Delmar, Finn and Ambrose in the Center Hall, the rest in the
Department Store. Singapore's is split between Boat Quay Town (Rosnah, Siti, Benedict, Arjun) and the CBD; Zipangu's
between Mushroom Shrine (Chiyo, Genzo, Kaede, Isamu) and Showa Town.

**Recruits.** Each is earned one of five ways, as in Victoria: an item price (Hollis, Brody), a kill proof counted
after the offer against the monster of the Resident's own intro quest (Lou, Delphine, Vesper, Thalia, Genzo), a day
rate (Moxie, Duke, Gwang, Arjun), a rumor alone (Priscilla), or a rumor on top of the intro quest, with an item price
(Vance, Rourke, Kael, Rin) or meso only (Seori). Every `refuse.flags` line names what its contract still needs. The twelve weapon lines continued
here are guns, two-handed axes, polearms, bows, staves, crossbows, one-handed swords, claws, one-handed blunts,
daggers, wands and knuckles; no weapon or hat in this wave is on the Repack-only list
(`scripts/config/knowledge/botstory-wz-equips.txt`).

**iTCG items in New Leaf City.** The coordinator's audit lists 68 iTCG code ids (the server's own commodities SN
80000004-80000065) and the forge results made from them. New Leaf City hands out, as quest rewards:

- every code item the server had no other source for (45 ids): the 27 forging materials (the Set 1 materia and
  Dark Matter, the six Ancient pieces, LeFay Jewel, Pharoah's Wrappings, Ridley's Book of Rituals and Stone, Typhon
  Crest, Stone Tiger Head, Crystal Shard, Taru Spirit Feather, Triferium Fuel Cell, Stone Denari), Jungle Juice, Taru
  Face Paint, Ridley's Scroll of Defense, Stirge Signal, the six Set 3 lv-10 spirit weapons, the Taru Spirit Cape, the
  Stirgeman cape, pants and Stirge-on-a-String, the Cloak of Corruption, the Crystal Blade and the Red Relaxer;
- 26 forge results as friendship gear (the Versal weapons and the other forged pieces in the gear column above). The
  iTCG session's branch (`feat/itcg-masteria`, `docs/ITCG_MASTERIA_PLAN.md`) scripts the forging NPCs to the GMS
  recipes, so once both are merged these pieces have two roads: Spindle's forge, and the friendship quests;
- none of the five iTCG pets: on this branch `QuestLedger` grants rewards through `InventoryManipulator.addById`,
  which does not create a pet. All five go to the iTCG session's Masteria casts, which carry the pet-aware reward
  path. The Maple Champion cash effects are left out too.

Of the three untradeable items on the list, Red Relaxer and Stone Denari are rewards and never hand-ins
(`Friendship.giftable` skips untradeables); Gelt Chocolate is not used. The seven forging NPC scripts (Foxwit 9201052, Glimmer Man 9201083,
Corine 9201094, Stirgeman 9201102, Lita Lawless 9201054, John Barricade 9201051, Spindle 9201082) are untouched:
Residents talk about them, and the materials do nothing until the forging session's work lands. All 68 ids exist in
the server's WZ and in the stock Cosmic client.

**The regional threads.** Two pairs of regions share a story, and New Leaf City and Zipangu have their own.

- *Omega Sector and Korean Folk Town.* Lyra hears a voice in a dead channel at nine (`lyra-the-dead-channel`, world
  flag `omega.channel.heard`). It is Bomi in Korean Folk Town, talking into her father's radio, which only
  hisses until the player takes it down to the base's mechanic (`bomi-the-hissing-radio`, `kft.radio.mended`). Lyra
  traces the signal to Black Mountain (`lyra-the-trace`, `omega.channel.answered`) and Bomi sends the player to find
  the lady on the radio (`bomi-the-first-answer`, `kft.answer.heard`): the two last chapters open together, and
  persimmons go up to Omega by lift.
- *Aquarium and Singapore.* Delmar, an old diver, has never told anyone about the light in the Grave of the Wrecked
  Ship (`delmar-the-glass`, `aqua.light.seen`). Rosnah lights a lantern every night for a crew that never came home,
  and asks the player to look aboard the ghost ship (`rosnah-the-ghost-ship`, `sg.ship.seen`). The light is a ship's
  lantern, raised from the wreck past its sharks (`delmar-the-wreck-lantern`, L98, `aqua.lantern.raised`), and the
  player carries Halim's lantern home to Boat Quay (`rosnah-the-lantern-home`, `sg.lantern.home`).
- *New Leaf City.* Monty's card club has a tournament and nothing on its prize table (`monty-the-empty-prize-table`,
  `nlc.prizes.promised`). Fergus will forge the old Masteria pieces if he has the drawings, which are split between
  Theodora's archive and Ezra's pawn shelf (`monty-the-blueprints`, `nlc.blueprints.found`); the Leaf Cup itself is
  `monty-cup-night` (`nlc.cup.held`).
- *Zipangu.* The shrine bell has had no voice for three years. Chiyo traces the clapper to Daichi, the bathhouse hand
  who took it as his first job for the Boss (`chiyo-the-silent-bell`, `zip.clapper.traced`); Daichi asks the player
  to take it back off the Boss's own neck on Showa Street 3 (`daichi-the-hideout`, L92, `zip.clapper.recovered`), and
  Chiyo's last quest carries it up the steps for him (`chiyo-the-bell-rings`, `zip.bell.rings`).

| Arc | Chapters (gate) |
|---|---|
| `monty-the-leaf-cup` | A Table With Nothing On It / The Prize Table (world flag `nlc.prizes.promised`; unlocks the rumor) / The Drawings Come Home (`nlc.blueprints.found`) / The Leaf Cup (`nlc.cup.held`) |
| `lyra-the-quiet-channel` | The Dead Channel / A Voice at Nine (`omega.channel.heard`) / Somebody's Listening (`kft.radio.mended`) / Over and Out (`omega.channel.answered`) / Persimmons by Lift (`kft.answer.heard`) |
| `bomi-the-fathers-radio` | The Radio That Hisses / It Hums (`kft.radio.mended`) / Somebody Answered (`omega.channel.answered`) / Two Radios (`kft.answer.heard`) |
| `delmar-the-lantern-in-the-grave` | The Diver's Glass / A Light in the Grave (`aqua.light.seen`) / The Lantern Comes Up (`aqua.lantern.raised`) / Home to Boat Quay (`sg.lantern.home`) |
| `rosnah-the-lanterns` | Lanterns for the Crew / The Ship That Waits (`sg.ship.seen`) / Halim's Lantern (`aqua.lantern.raised`) / Two Lanterns on the River (`sg.lantern.home`) |
| `chiyo-the-shrine-bell` | A Bell Without a Voice / Round the Boss's Neck (`zip.clapper.traced`) / Taken Back (`zip.clapper.recovered`) / The Bell Rings (`zip.bell.rings`) |

| Rumor | Cast, spot, hours | Sets on listeners |
|---|---|---|
| `nlc-the-prize-table` | Monty, Ramona on the middle central street, 18-20, from Monty's chapter 2 | - |
| `nlc-the-lunch-counter` | Dolores, Rocco by Miki, 13 | - |
| `nlc-the-beat-and-the-hustle` | Brody, Vance on the lower east street, 20-21 | `nlc.heard-vance` (Vance's contract) |
| `nlc-the-blueprints` | Fergus, Theodora on the middle west street, 18-19, every 2 days | - |
| `nlc-the-old-crews` | Gideon, Sabine on the lower east street, 18-19, every 2 days | - |
| `omega-the-range` | Vesper, Rourke on the lower west street, 17-19 | `omega.heard-rourke` (Rourke's contract) |
| `omega-the-mess` | Dmitri, Hettie on the lower central street, 16-17, every 2 days | - |
| `kft-the-hunt` | Gwang, Seori by Mr. Noh, 19-20 | `kft.heard-seori` (Seori's contract) |
| `kft-the-mill` | Dalja, Okja on the lower west street, 18 | - |
| `aqua-the-deep` | Thalia, Kael on the Department Store's middle central street, 19-21 | `aqua.heard-kael` (Kael's contract) |
| `aqua-the-benches` | Delmar, Ambrose in the Center Hall, 19-20, every 2 days | - |
| `sg-the-quay-lanterns` | Rosnah, Siti on Boat Quay's lower east street, 18-20 | - |
| `sg-the-hawker-stall` | Wei, Priscilla on the CBD's middle central street, 16-18 | `sg.heard-priscilla` (Priscilla's contract) |
| `zip-the-ronin-at-the-gate` | Chiyo, Genzo on the shrine's upper central benches, 18-20 | - |
| `zip-the-bathhouse-roof` | Okiku, Rin on Showa's lower central street, 19-21 | `zip.heard-rin` (Rin's contract) |

**Once per server.** `ArcFinalBeatOncePerServerTest` pins eight wave-3 beats with `requires.notFlags`: the finales
`monty-cup-night`, `rosnah-the-lantern-home`, `chiyo-the-bell-rings` and `bomi-the-first-answer`, and the middle
beats whose lines would be wrong on a second run (`lyra-the-trace`, `delmar-the-wreck-lantern`,
`chiyo-the-silent-bell`, `daichi-the-hideout`). The earlier beats that later quests list in `questsCompleted`
(`lyra-the-dead-channel`, `bomi-the-hissing-radio`, `monty-the-empty-prize-table`, `monty-the-blueprints`,
`delmar-the-glass`, `rosnah-the-ghost-ship`) stay open to every player; the radio and Leaf Cup quests have chapter
overrides so their offer lines read true after the world has moved on.

**Olwen.** Lith Harbor's ledger-keeper was the one Beginner persona with no branch. Her persona now names
GUNSLINGER, so as a companion the job errands take her to Kyrin in Nautilus for Pirate and again at 30 for
Gunslinger, with errand lines in her own voice; her kit gun stays in her hands (no work-tool outfit weapon suits a
Pirate). Her loadout is unchanged, so the Olwen already shaped live is not reshaped by the deploy.
`OlwenClassPathTest` pins her path, including the `JobErrands.pathFor` calls the errands make.

**Data changes outside the content files.** The two Aquarium interior profiles (section 3); Spinel's way back from
Mushroom Shrine in `scripts/config/knowledge/travel.yaml` (to where the world tour began, Lith Harbor by default), so
content answers can route out of Zipangu; borrowed portraits for the nine town profiles (section 1).

**Dependencies.** The route graph does not reach Zipangu yet (a routes session is fixing it), so `call Genzo` or
`call Rin` from another region will not find a way until then; inside Zipangu they work. Mushroom Shrine sells no
potions (the town audit), and a Zipangu recruit's restock trip waits on the same route work. Kuala Lumpur and Kampung
Village (Malaysia) belong to another session.

Wave 1 and wave 2 are untouched apart from one Leafre quest: `hessa-carry-it-home` counts its Nest Golems only in
the Dangerous Dragon Nest below the peak. A `VISIT_MAP` step advances only when its map is entered while it is the
current step, so golems killed on the peak itself left the player standing there with the visit still open.

## 10. Wave 4: Victoria and Maple Island remainder

Asked for 2026-10-25 (the coordinator's town audit, section 1.3): Residents in every town and sub-area, sub-areas only
on safe outpost maps (a camp or hub map with NPCs and few or no mobs). Waves 1-3 covered the seven Victoria towns and
Ossyria, Masteria and the World Tour; this wave takes what is left on Victoria Island and Maple Island. Branch
`feat/victoria-remainder` (off a435dc14b); quest-log id block 30800-31099.

### 10.1 Places

| Place | Profile (map) | Fields and levels | Residents | Note |
|---|---|---|---|---|
| Florina Beach | `florina-beach` (110000000), existing | Lupin, Lorang 37, Tortie 46, Clang 48 (110010000-110040000) | 7, 2 recruits | bots cannot route here yet (10.6) |
| Amoria | `amoria` (680000000), existing | Purple Plains 2-15, Purplewood Forest: Sakura Cellion 33, Indigo Eye 38, Crystal Boar 40 | 7, 2 recruits (a married couple) | the Amorian Challenge needs married players |
| Kerning Square | `kerning-square` (103040000), new | bubble teas 35-37, claw games and perfumes 38-42, mannequins 43-45, records and amplifiers 46-49, Spirit of Rock 49 | 5, 2 recruits | the KerningTrain ride, in no route catalog (10.6) |
| Mushroom Castle | `mushroom-castle` (106020000 Mushroom Forest Field), new | Renegade Spores, Poison Mushroom 30, Intoxicated Pig 31, Helmet Pepe 32, Royal Guard Pepe 33 | 5, 2 recruits | the scripted portal in Henesys's Empty House (10.6) |
| Free Market | `free-market` (910000000, the entrance), new | none (a hub every town opens onto); quests send players across Victoria, L15-60 | 4 | knowledge routes only |
| Excavation Site | `excavation-camp` (101030104 Excavation Site <Camp>), new | Ghost Stumps and masks 19-24, Remains <Tomb> 44-57, Officer and Commander Skeletons 63-73 | 4, 2 recruits | Shuang's Sharenian (Guild PQ) gate stands here |
| Amherst | `amherst` (1000000), new | snails, shrooms, slimes, Orange Mushroom 1-8 | 4 | Maple Island: no recruits, L1-10 |
| Southperry | `southperry` (2000000), new | the field west of Southperry 2-8 | 3 | Shanks's ship to Lith Harbor |
| Mushroom Town | `mushroom-town` (10000), new | Snail Hunting Grounds 1-2 | 3 | the first town a new Explorer sees |
| Happyville | `happyville` (209000000), new | none (an event town; Rooney in every Victoria town takes players there all year) | 3 | seasonal flavour, no recruits, no arc |

**Left out.** Ant Tunnel Park (105070001) has 58 mob spawns: it is a field, not an outpost, so it gets no Residents
(Sleepywood's cast already covers the tunnel). The audit's other Victoria sub-areas have no outpost map. The eight new
profiles were generated with `tools\town-life-profiles.bat generate` (each checks 0 errors, 0 warnings); none is in
`ambient-deployment.json`, so citizen traffic is unchanged, as with the Aquarium interiors.

### 10.2 Decisions

Wave 3's conventions hold: about a third recruitable (none on Maple Island or in Happyville), regional arcs whose last
beats happen once per server (`requires.notFlags`), and friendship gear in each place's level band. Every weapon line
already has three regional tiers, so this wave's recruits give armour and accessories made for the player's class
(`pool`, never the giver's own piece, so `ContentLintFriendshipTest` still counts 49 weapon Residents). Recruits reuse
existing loadouts. Maple Island Residents greet new players with L1-10 errands; the island's one Victoria tie is
Marta's leaving list, which goes to Olwen in Lith Harbor.

### 10.3 Cast

| Place | Resident | Role (job, level) | Recruit (how earned) | Friendship gear |
|---|---|---|---|---|
| Florina Beach | Marisol | beachcomber, arc owner | - | - |
| Florina Beach | Teodor | lifeguard who counts the swimmers in and out | - | - |
| Florina Beach | Nerys | clears the clangs off the Hot Sand (Bandit 42) | kill proof: 60 clangs + 20k | earrings L40 / L45 / L50 |
| Florina Beach | Ronan | ex-deckhand of the Florina ferry (Brawler 44) | day rate: 40k | capes L35 / L42 / L50 |
| Florina Beach | Lulu | sunshade renter | - | - |
| Florina Beach | Abel | old fisherman of the look-out shed | - | - |
| Florina Beach | Tansy | a girl who builds a sandcastle every day | - | - |
| Amoria | Pernille | keeper of the chapel flowers, arc owner | - | - |
| Amoria | Rafael | newlywed, met his wife in the Amorian Challenge (Fighter 40) | items: 20 cellion tails + 15k | gloves for the player's class L30 / L40 / L50 |
| Amoria | Liesel | newlywed, Rafael's wife (Cleric 40) | rumor only: hear the newlyweds + 30k | hats for the player's class L35 / L40 / L50 |
| Amoria | Clotilde | wedding-cake baker | - | - |
| Amoria | Lorenzo | waits at the Challenge door for someone to marry | - | - |
| Amoria | Ines | Moony's apprentice ring-polisher | - | - |
| Amoria | Valentin | a poet who writes other people's vows | - | - |
| Kerning Square | Bao | noodle-cart keeper of the old night market, arc owner | - | - |
| Kerning Square | Juno | night janitor of the mall | - | - |
| Kerning Square | Zara | mall security (Bandit 46) | intro and rumor, items: hear Juno on her, 20 mannequin wigs + 20k | tops for the player's class L35 / L40 / L50 |
| Kerning Square | Felix | record-shop clerk (Hunter 46) | kill proof: 50 records (Latest Hits, Greatest Oldies) + 20k | bottoms for the player's class L35 / L40 / L50 |
| Kerning Square | Kiko | bubble-tea boy, Bao's grandson | - | - |
| Mushroom Castle | Wilmot | royal baker, a Henesys boy who came through the Empty House, arc owner | - | - |
| Mushroom Castle | Primrose | lady-in-waiting to Princess Violetta | - | - |
| Mushroom Castle | Percival | the one human knight of the mushroom guard (Page 34) | intro + day rate 25k | shoes for the player's class L35 (King Pepe) / L40 / L50 |
| Mushroom Castle | Fern | pupil of the Secretary of Magic (Cleric 33) | items: 30 poison mushroom caps + 10k | earrings L25 / L30 / L35 |
| Mushroom Castle | Barley | swineherd of the intoxicated pigs | - | - |
| Free Market | Gilbert | retired merchant who remembers every price | - | - |
| Free Market | Sofie | runner between the market rooms | - | - |
| Free Market | Marguerite | pitch-caller at the entrance | - | - |
| Free Market | Basil | lost-and-found keeper | - | - |
| Excavation Site | Edmund | camp archaeologist of Sharenian, arc owner | - | - |
| Excavation Site | Halla | guard of the tomb road (Spearman 55) | kill proof: 60 mummydogs + 25k | helmets for the player's class L45 / L50 / L55 |
| Excavation Site | Wick | night watch of the dig sites (Rogue 24) | rumor only: hear Nell on him + 15k | shoes for the player's class L20 / L25 / L30 |
| Excavation Site | Nell | camp cook | - | - |
| Amherst | Bettina | baker of Rainbow Street | - | - |
| Amherst | Moe | retired gate guard | - | - |
| Amherst | Sunny | teacher of the map and the signposts | - | - |
| Amherst | Pockets | a boy who trades snail shells | - | - |
| Southperry | Marta | Shanks's clerk, keeps the leaving list | - | - |
| Southperry | Ned | fisherman of the pier | - | - |
| Southperry | Elodie | a girl who wants to leave and is afraid to | - | - |
| Mushroom Town | Tilly | everyone's first friend | - | - |
| Mushroom Town | Rufus | cabbage farmer | - | - |
| Mushroom Town | Wynn | a boy practising magic on snails | - | - |
| Happyville | Noelle | Rudi's niece, hangs the ornaments | - | - |
| Happyville | Kris | sled-maker | - | - |
| Happyville | Ingeborg | knits mufflers for travellers | - | - |

45 Residents, 10 recruitable (of the 35 off Maple Island). Rafael and Liesel are a lore couple (`spouse:`, both
recruitable), so an owner can field a fourth married pair for the Amorian Challenge. Each recruit is earned a
different way: an item price (Rafael, Fern), a kill proof on its intro quest's monster (Nerys, Felix, Halla), a day
rate (Ronan, Percival after his intro), a rumor alone (Liesel, Wick), or a rumor on top of the intro quest with an item
price (Zara).

### 10.4 Threads

- *Florina Beach and Amoria: the Silver Gull's letter.* Marisol finds a sealed bottle in the lorangs' shallows, forty
  years at sea, addressed to "Pernille, the chapel steps, Amoria" (`marisol-the-sealed-bottle`, world flag
  `florina.bottle.found`). Pernille has laid a flower on the chapel steps every first morning of spring for forty
  years for a sailor who said he would write and never did; the letter says he did (`marisol-to-the-chapel-steps`,
  `amoria.letter.read`). She asks the sea what happened: Hadrik in Lith Harbor was a boy on the Silver Gull's deck
  when it went down off Florina, and its bosun went over the side after him (`pernille-what-the-sea-said`, the one
  Victoria errand, `amoria.gull.remembered`). Pernille writes her answer forty years late and Marisol sends it out on
  the tide from the look-out shed (`pernille-forty-years-late`, `florina.answer.sent`).
- *Kerning Square: the night market.* The mall stands where Kerning's night market was. Bao kept her noodle cart in the
  lobby when the stalls were moved out; the market's sign was walled into the eighth floor (`bao-the-old-sign`,
  `ksq.sign.found`), and Sadie, Kerning's foreman, poured that floor and kept the market's bell
  (`bao-the-poured-floor`, the Victoria errand, `ksq.bell.found`). With both, the market opens one night a week in
  the lobby (`bao-the-lobby-corner`, `ksq.market.back`).
- *Mushroom Castle: the cake.* Wilmot came through the Empty House in Henesys as a boy and never went back. He has been
  ordered to bake a wedding cake nobody wants (`wilmot-a-cake-for-no-one`, `mc.cake.refused`); he bakes one for the
  kingdom instead, with the Henesys caps Brisa still sends him (`wilmot-caps-from-home`, the Victoria errand,
  `mc.caps.sent`), and serves it to the guards on the castle wall (`wilmot-the-kingdoms-cake`, `mc.cake.served`).
- *Excavation Site: the buried name.* Every Sharenian stone in the tombs has its king's name chiselled off. Edmund
  traces the chisel marks (`edmund-the-chisel-marks`, `excav.marks.read`); Ossa's Perion diggers turned up the one
  stone that kept it (`edmund-ossas-stone`, the Victoria errand, `excav.name.found`); and the name goes back on the
  camp's board after a night at the Remains <Cliff> (`edmund-the-name-said-aloud`, `excav.name.restored`).

| Arc | Chapters (gate) |
|---|---|
| `marisol-the-sealed-bottle` | Bottles / The Sealed One (`florina.bottle.found`) / Read on the Steps (`amoria.letter.read`) / Out on the Tide (`florina.answer.sent`) |
| `pernille-forty-springs` | Forty Springs / He Wrote (`amoria.letter.read`) / What the Sea Said (`amoria.gull.remembered`) / Yes (`florina.answer.sent`) |
| `bao-the-night-market` | The Cart / The Sign (`ksq.sign.found`) / The Bell (`ksq.bell.found`) / Market Night (`ksq.market.back`) |
| `wilmot-the-kingdoms-cake` | Ordered / Refused (`mc.cake.refused`) / Caps From Home (`mc.caps.sent`) / Served (`mc.cake.served`) |
| `edmund-the-buried-name` | Chisel Marks / Read (`excav.marks.read`) / Found (`excav.name.found`) / Said Aloud (`excav.name.restored`) |

Once per server (`ArcFinalBeatOncePerServerTest`): the finales `pernille-forty-years-late`, `bao-the-lobby-corner`,
`wilmot-the-kingdoms-cake` and `edmund-the-name-said-aloud`, and the middle beats whose lines would be wrong on a
second run (`pernille-what-the-sea-said`, `bao-the-poured-floor`, `wilmot-caps-from-home`, `edmund-ossas-stone`).
The first beat of each thread, which a later quest lists in `questsCompleted`, stays open to every player.

| Rumor | Cast, spot, hours | Sets on listeners |
|---|---|---|
| `florina-the-tide-line` | Marisol, Abel, from Marisol's chapter 2 | - |
| `florina-the-hot-sand` | Nerys, Ronan, every 2 days | - |
| `amoria-the-chapel-steps` | Pernille, Clotilde, from Pernille's chapter 2 | `amoria.heard-steps` (Pernille's last quest) |
| `amoria-the-newlyweds` | Rafael, Liesel | `amoria.heard-liesel` (Liesel's contract) |
| `ksq-the-night-shift` | Juno, Zara | `ksq.heard-zara` (Zara's contract) |
| `ksq-the-cart` | Bao, Kiko, from Bao's chapter 2 | `ksq.heard-cart` (Bao's last quest) |
| `mc-the-kitchen` | Wilmot, Primrose, from Wilmot's chapter 2 | `mc.heard-kitchen` (Wilmot's last quest) |
| `excav-the-cookfire` | Nell, Wick | `excav.heard-wick` (Wick's contract) |
| `excav-the-board` | Edmund, Halla, from Edmund's chapter 2 | `excav.heard-board` (Edmund's last quest) |
| `fm-the-price-of-things` | Gilbert, Marguerite, every 2 days | - |
| `amherst-the-gate` | Moe, Sunny, every 2 days | - |

### 10.5 Victoria and wave 1-3 touch points

No existing persona changes. The four Victoria errands (Hadrik, Sadie, Brisa, Ossa) and Marta's leaving list (Olwen)
are `TALK_TO` steps whose lines live in the quest's `say`.

### 10.6 Route needs (the routes session's files)

Agents cannot route to Florina Beach, Kerning Square or Mushroom Castle today (town audit 1.2); Maple Island and
Happyville are in neither graph. Until the routes session adds those legs, `call <name>` from elsewhere finds no way
for Nerys and Ronan (Florina), Zara and Felix (Kerning Square) or Percival and Fern (Mushroom Castle); inside their
towns they work, and a recruit follows its owner through the portals and rides the owner takes. Amoria (Thomas
Swift's leg) and the Excavation Site are already in the agent graph. Asked of the routes session, not edited here:
the Pason/Pison boat Lith Harbor <-> Florina Beach, the KerningTrain ride 103000100 <-> 103000310 and the scripted
portal `TD_MC_first` in 100000002 for the agent graph and `travel.yaml`, and Amoria's Thomas Swift leg for
`travel.yaml`.

### 10.7 As built

45 Residents, 10 recruitable, 127 quests (quest-log ids 30800-30926 of the block 30800-31099), 10 contracts, 5 arcs,
11 rumors, no new loadouts. Content files: `{personas,quests,contracts,arcs,rumors}/` `florina-beach.yaml`,
`amoria.yaml`, `kerning-square.yaml`, `mushroom-castle.yaml`, `excavation-camp.yaml`; `{personas,quests,rumors}/`
`free-market.yaml`, `maple-island.yaml` (Amherst, Southperry and Mushroom Town); `{personas,quests}/happyville.yaml`.
Outside the content: the eight TownLife profiles and their `index.json` lines, borrowed portraits for the ten places in
`ResidentDialogue.PORTRAITS` (Mushroom Castle borrows Henesys folk: its townspeople are mushrooms), and the pinned
counts (`AgentTownLifeProfileRepositoryTest` 58 profiles, `ContentLintLootTermsTest` 79 contracts,
`ArcFinalBeatOncePerServerTest` the eight beats in 10.4).

What the server's own data changed while writing:

- **Felix's kill proof** counts 50 records (Latest Hits Compilation, Greatest Oldies), not Spirits of Rock: the Spirit
  of Rock is a 250,000 HP boss, one per room, in event copies June opens only while Blake's stock quests 2286-2288
  run. No quest needs those rooms; Bao's sign is behind the record racks of the open area A (103040400).
- **Tops and bottoms** (Zara, Felix) go L35 / L40 / L50: no top, bottom or overall exists at L41-47. Halla's helmets
  are gated L45 / L50 / L55 but give the L40-42, L50 and L60 pieces where a class has none at 45 or 55; pirates get a
  no-job hat where every pirate hat is already some armourer's.
- **Maple Island's road is one-way** (Mushroom Town -> Amherst -> Southperry, no portal back from 20000-50000) and most
  island fields have no way in: Snail Hunting Grounds II/III, the Tomato Field, the Snail Gardens and the field west of
  Southperry are islands, and the Adventurer Training Center opens only during Mai's stock quests 1041-1044. Island
  quests use Dangerous Forest 50000 (snails) and 1010000 (blue snails, shrooms, orange mushrooms); Mushroom Town's
  quests are all level 1, unchained, and finish in Amherst (a kill, a visit or an introduction).
- **The Hill of Christmas** copies 209000004-209000015 have no way in; Happyville's visits list 209000001-209000003,
  which the three snowmen open.
- **Gated maps the lines name:** Mushroom Castle's towers and wall need stock quest 2324 (the Thorn Remover), the steep
  downhill a poison spore at the vines; the dialogue says so.

### 10.8 Live checks after deploy

1. **Boot.** `!world census`: 45 personas more than before the merge; towns include florina-beach=7, amoria=7,
   kerning-square=5, mushroom-castle=5, free-market=4, excavation-camp=4, amherst=4, southperry=3, mushroom-town=3,
   happyville=3; no living-world lint errors in the boot log.
2. **They live there.** `!world wake <town>` for the ten profiles at 18 server time, `!world residents`: each Resident
   materializes at its venue, never at the profile's fallback spot. Nerys, Ronan, Rafael, Liesel, Zara, Felix,
   Percival, Fern, Halla and Wick wear their loadouts. Nobody stands in Ant Tunnel Park.
3. **Talk.** `hi Marisol`, `who are you`, `tell me about Abel`, double-click Wilmot: the NPC window shows the name, a
   borrowed townsperson portrait and the menu.
4. **Maple Island, a new character.** Create an Explorer: Tilly greets it in Mushroom Town, her quest finishes in
   Amherst; at level 8 in Southperry `marta-the-leaving-list`, reach 10, take Shanks's ship, talk to Olwen in Lith
   Harbor: the quest completes there with Marta's note (never needs the island again).
5. **The Silver Gull thread.** Level 40: `marisol-the-sealed-bottle` (lorangs at Florina), `marisol-to-the-chapel-steps`
   (Pernille in Amoria reads it), Pernille's `pernille-what-the-sea-said` (Hadrik in Lith Harbor), stand by the chapel
   steps for `amoria-the-chapel-steps`, then `pernille-forty-years-late` (Marisol, the look-out shed, back to
   Pernille): both arcs' last chapters open together; a second character is offered neither Pernille quest again.
   Bao's (Sadie in Kerning City), Wilmot's (Brisa in Henesys) and Edmund's (Ossa in Perion) threads are the same shape.
6. **A recruit of each kind.** Kill proof: Halla (`halla-the-tomb-road`, then 60 mummydogs counted after the offer).
   Day rate: Ronan, 40,000. Rumor only: stand by Moony in Amoria at 19-21 until `amoria-the-newlyweds` plays, then
   `Liesel join me`, 30,000. Items: Fern (30 poison mushroom caps). Rumor on top of the intro, with items: Zara.
7. **The couple.** Recruit Rafael and Liesel together: both show as married (`Character.isMarried()`), and a party
   with them can enter the Amorian Challenge.
8. **Friendship gear.** `!world bond <player> percival 110`, level 35, `percival-king-pepe-shoes`: the hand-in gives the
   class's King Pepe shoes with every 100% scroll taken; `!world bond <player> nerys 110`, level 40: Blue Moon earrings.
9. **Rumors** at their hours (or `!world rumor play <id>`): `florina-the-hot-sand`, `ksq-the-night-shift`,
   `excav-the-cookfire`, `fm-the-price-of-things`, `amherst-the-gate`; listeners get the flags in 10.4.
10. **Recall.** `call Halla` from Henesys works; `call Nerys`, `call Zara`, `call Percival` from another town find no
    way until the routes session's legs land (10.6).
