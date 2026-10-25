# Bot lore couples

Written 2026-10-25 on `feat/bot-lore-couples` (cut from `feat/companion-manager` 70a29efa4). Plan, what was built,
tests and the live checks still to run.

## Why

The user keeps the stock Amorian Challenge rule that all six members are married (`scripts/event/AmoriaPQ.js:27`
`onlyMarriedPlayers = true`, checked in `getEligibleParty` with `Character.isMarried()`; the couples' vault needs every
member's partner on the team, `isTeamAllCouple`). `isMarried()` is `marriageRing != null && partnerId > 0`, and the
stock load clears `partnerId` unless a `marriages` row holds the character. No bot was married and no living-world or
Agent code touched marriage. This branch makes real bots married, so companions can enter APQ (the APQ desk and run are
the APQ/GPQ session's branch).

## User decisions (2026-10-25)

1. **Content.** A persona's yaml gets `spouse: <persona id>`; both sides name each other. A persona may instead name a
   spouse who is no Resident, `spouse: { name: Rowena, gender: female }`: that spouse is born as a citizen in the
   persona's home region ("married to a citizen"). About six couples among recruitable companions so an owner can field
   three couples. Citizens do not marry each other.
2. **Rules.** Opposite genders only (the `marriages` table and the marriage packets assume husband = male). No level
   floor for lore couples (most personas are Lv20-45; APQ's own Lv40 gate still applies). The stock Lv50 rule stays for
   real proposals.
3. **What ends a lore marriage: content decides.** A lore marriage survives bench, release, level changes and either
   partner being offline. On each load the server reconciles with content: when the spouse's character is gone or the
   yaml no longer pairs them, the marriage is broken the stock way and the survivor mentions it in a line. Bots never
   divorce on their own.
4. **Owner marriage: the full stock flow, the companion plays its part.** Engagement ring from Moony, proposal (both
   Lv50+, opposite genders, same map); the companion accepts at friendship Lv7+ (close friend) or declines in its own
   words; Cash Shop wedding ticket at Nicole (cathedral) or Bonnie (chapel); the ceremony, where the companion confirms
   its vows after its owner. The garment comes from the owner (the Golden Maple Leaf comes from the ceremony itself).

## The couples (content)

| Couple | Town | Kind | Levels |
| --- | --- | --- | --- |
| Bjarke + Sigrun | El Nath | companion + companion | 70 / 70 |
| Jasper + Saskia | Ludibrium | companion + companion | 60 / 60 |
| Ansel + Vesna | Orbis | companion + companion (married rivals) | 60 / 60 |
| Thane + Magna | Sleepywood | companion + companion | 40 / 40 |
| Corvin + Brunhild | Perion | companion + companion | 35 / 38 |
| Kettle + Mae | Nautilus | companion + Resident (Mae is not recruitable) | 30 / 30 |
| Gunnar + Rowena | Henesys | companion + citizen (Rowena is born a citizen) | 32 / region band |

Each married persona's lines name its spouse (an `about.<spouse>` line, Gunnar's small talk) and carry a
`spouse.parted` line (`{name}` = the former spouse) for the day content splits them. Wilhelmina's "i'd marry him if he
weren't so grim" about Thane became "he and Magna keep the corner room ... she's a lucky woman", and Thane's room at her
hotel became "our room". Couples were picked to avoid the authored arcs (Edric's letter arc stays unpaired).

An owner's APQ six with these: the owner, the companion the owner married (stock flow), and two companion couples (for
example Jasper + Saskia and Ansel + Vesna, both Lv60). Everyone's partner is on the team, so the couples' vault opens.

## How it works

### Content model and lint

- `Content.Spouse(persona, name, gender)` and `Persona.spouse` (`server/living/config/Content.java`; the old
  constructor still builds a persona with no spouse). `ContentLoader.spouse` reads a persona id or a `{ name, gender }`
  map.
- `ContentLint` (errors): a married persona needs `look.gender`; a persona spouse must exist, not be itself, name this
  persona back and have the other gender; a citizen spouse's name must be 4-12 letters, its gender male or female and the
  other one, and no persona or other citizen spouse may carry the name.

### Lore marriage at load (`server/living/bond/LoreCouples.java`)

- `server.agents.integration.AgentLoadHook` is a new engine seam: `CosmicAgentOfflineLoadService.loadOfflineAgent`
  calls it once per bot load, after `Character.loadCharFromDB` and before the character is registered with its channel,
  world or map. Every bot load goes through it (Residents, companions, bench and boss allies, citizens). The living world
  installs `LoreCouples` there at start and removes it at stop. A failing rule never fails a load.
- For a character the living world keeps (a persona's or a citizen's), `LoreCouples.loaded` reconciles its marriage
  with content (`LoreSpouseDirectory` answers who its content spouse is; a persona spouse never met yet is provisioned
  then, as a recruit would):
  - **Married to its content spouse:** it wears its half of the ring pair; a missing ring is given back from the rings
    rows.
  - **Not married, content names a spouse:** the first partner to load makes the `marriages` row (husband = the male)
    and the pair of `rings` rows (`Ring.createRing`'s two rows, written for partners that need not be loaded). Each
    partner then gets its own half when it loads: a wedding ring Equip carrying its ring id, worn in the first free ring
    slot (-12, -13, -15, -16; the equip bag when all four are taken), `partnerId` and `marriageItemId`. Both get the same
    of the four wedding rings, chosen from the two ids. The rest is the stock save: the ring is saved with the
    inventory, `partnerId` with the character row, and the next load reads them back as it does a player's.
  - **Married to a player:** never touched (a companion its owner wed at the chapel).
  - **Married to a bot the content no longer pairs, or whose character is gone:** broken the stock way (both rings rows
    removed, which also clears the ring ids saved on the other partner's ring; the `marriages` row deleted; its own ring
    taken off; partner and marriage item cleared) and, for a persona, it says its `spouse.parted` line 15 s later. While
    the other partner is loaded the break waits for a later load: its live ring would otherwise be saved back with a
    ring id whose row is gone, and the stock load would fail on it.
  - **Content names a spouse that cannot be told yet** (a citizen spouse not born, the population off): nothing changes.
  - A wedding ring with no ring id left in a bag or ring slot (the other half of a broken marriage) is removed.
  - Marriages are made one at a time (one lock) so two partners loading at once never make two rows.
- A partner that was already loaded when its spouse made the rows gets its half at its own next load (deploys restart
  the server, so in practice every couple is complete from the first loads after the deploy).

### Citizen spouses

- `CitizenGenerator.birthSpouse(name, female, region)` seeds a citizen in the region like an operator seed, but with
  the content's name and gender, the new flag `lore-spouse`, and no retirement date (`retires_at` 0, so the hourly churn
  never retires it). The generator draws in the same order as before, so other births are unchanged (pinned by
  `CitizenGeneratorSeedTest`).
- `Churn` births every missing citizen spouse on the first population sweep after boot, in the region whose towns
  include the persona's home town. A name already taken by another character is logged and waits for a restart.
- A citizen spouse loads like any citizen; its own load gives it its half (its persona is found by the name).

### The owner marrying their companion (`server/living/bond/CompanionWeddings.java`)

- **Proposal.** `RingActionHandler.sendEngageProposal` (stock checks unchanged) calls
  `LivingWorldHooks.proposalSent`. A bot has no Accept window, so the companion answers itself about 4 s later, on its
  own mailbox: yes when it is the proposer's own companion at friendship Lv7+ (`Friendship.CLOSE_FRIEND`), no otherwise
  (a different line for someone else's ring). The answer goes through the stock Accept/Deny code, now
  `RingActionHandler.answerProposal`, so the engagement is the stock one (relationship row, partner ids, the two empty
  ring boxes). Lines: `proposal.accept`, `proposal.decline`, `proposal.stranger` from the persona's dialogue when
  written, else built-in ones.
- **Garment.** Handing the companion a wedding garment cut for its body through its gift menu (Friendship.gift) while it
  is engaged to the giver puts the garment in its bag, marked untradeable so no sell-trash or drop takes it ("i'll wear it
  on the day"); it is not a friendship gift. The engagement boxes are already not-for-sale and trade-blocked.
- **Ceremony.** An engaged companion entering the wedding maps (680000100-680000401) puts the garment on: the altar
  checks that the partner wears one before it accepts the first vows. The Agent gear optimizer only re-gears on mode
  starts, level-ups and chat orders, not while it stands at a ceremony.
- **Vows.** The altar scripts (`9201002.js` High Priest John at the cathedral, `9201011.js` Pelvis Bebop at the chapel)
  call `LivingWorldHooks.vowsConfirmed(player, officiant)` after the player's vows. A companion partner says "i do." and
  runs the officiant's script itself 3 s later (the headless NPC path companions already use), which completes the stock
  wedding: both get their rings from `giveMarriageRings`.
- Content never touches the result: it is a marriage to a player. Divorce stays stock (the player drops the ring).

## Known limits and follow-ups

- **A female owner cannot propose.** Stock Moony crafts ring boxes only for male characters ("the ring box is currently
  available only for males"), and no box is sold anywhere else (none in the Cash Shop commodity list). So under the
  stock flow only a male owner can propose to a female companion. A female owner marrying a male companion needs a
  decision (for example: Moony crafts the box for the male companion she brings, and he proposes to her).
- Same-gender couples are not supported (decision 2).
- Amoria is in no route group of the bots' world map (the APQ branch adds one). The wedding does not need it: a
  companion follows its owner's map changes (Thomas Swift's warp included) and the ceremony registers it.
- The first deploy: a partner that was loaded before its spouse's first load is married at its next load.

## Files

- Engine seam: `server/agents/integration/AgentLoadHook.java`, `CosmicAgentOfflineLoadService.java` (one call).
- Content: `Content.java`, `ContentLoader.java`, `ContentLint.java`; personas `el-nath`, `ludibrium`, `orbis`,
  `sleepywood`, `perion`, `nautilus`, `henesys`.
- Lore marriage: `server/living/bond/LoreCouples.java`, `LoreCoupleLedger.java`, `LoreSpouseDirectory.java`;
  `LivingWorld.java` (install, uninstall, `weddings()`).
- Citizens: `Citizen.java` (`FLAG_LORE_SPOUSE`), `CitizenGenerator.java` (`birthSpouse`), `Churn.java`.
- Owner path: `server/living/bond/CompanionWeddings.java`, `Friendship.java` (garment), `LivingWorldHooks.java`
  (`proposalSent`, `vowsConfirmed`, wedding maps on `mapEntered`), `RingActionHandler.java` (`answerProposal`, the
  hook), `scripts/npc/9201002.js`, `scripts/npc/9201011.js` (one hook line each).

## Tests

- `LoreCouplesTest` (16): first and second partner, reload, a lost ring given back, a player marriage untouched, breaks
  (content change, deleted partner, a content spouse whose character is gone), a break waiting while the partner is
  loaded, an unresolvable spouse, loose rings, full ring slots, a spouse married to someone else, the same ring for both.
- `LoreCouplesAmoriaTest` (2): the shipped `AmoriaPQ.js` takes six members (an owner and companion wed the stock way, two
  lore couples married by `LoreCouples`) and opens the couples' vault; one unmarried companion keeps the party out.
- `ContentLintSpouseTest` (5): the shipped couples load paired and lint clean; the lint errors; the yaml shapes.
- `CompanionWeddingsTest` (3): the accept rule, the garments per gender, the wedding maps.
- `CosmicAgentOfflineLoadServiceTest` (+2): the load rule runs after the load and before any registration; a failing
  rule never fails the load.
- Targeted runs also passed: CitizenGeneratorSeedTest, ChurnSeedQueueTest, CitizenSeedPlanTest, ContentLint*,
  ContentLoaderTest, LivingWorldHooksTest, AgentLivingImportBoundaryTest and the other suites around the touched code.
- Full split suite: see "Results".

## Results

Full split suite (`tools/suite/run-split-suite.ps1`), base 70a29efa4 (baseline 14,706 / 0 / 37):

| Group | Commit | Run | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| capabilities | 38153bbee | 6,215 | 0 | 0 | 1 |
| agents | 38153bbee | 2,378 | 0 | 0 | 21 |
| rest | 3e3d539bc | 6,141 | 0 | 0 | 15 |
| **total** | | **14,734** | **0** | **0** | **37** |

The first rest run on 38153bbee had one failure, `KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed`: the two
altar-script hook lines changed the `scripts/npc` size fingerprint in `scripts/config/knowledge/server-knowledge.json`.
`tools\knowledge-export.bat` refreshed it (3e3d539bc: only `generatedAt` and that one fingerprint changed), and the
rest group passed. Another branch that edits `scripts/npc` will conflict on that fingerprint line: re-export after the
merge rather than merging the line by hand.

## Live checks (after deploy; not run: this branch never touched the live server or the client)

1. **Boot.** The log shows `lore spouse Rowena of gunnar born (<id>)` once (first boot only) and no content ERROR.
2. **A lore couple.** `!grantcompanion` (or recruit) Jasper and Saskia to a test owner and call both. In the DB:
   one `marriages` row (husband Jasper's id, wife Saskia's), two `rings` rows pointing at each other, both characters'
   `partnerId` set, their `marriageItemId` the same wedding ring. In game: each wears that ring in a ring slot.
3. **Persisted.** Dismiss both, restart the server, call them again: still married, still one `marriages` row.
4. **APQ.** A married owner (or the APQ branch's GM-married test character) with their spouse and two lore couples at
   Amos (670010100) registers; the run starts with the couples' vault flag (`marriedGroup`).
5. **Proposal, no.** A male owner Lv50+ with a female companion Lv50+ at friendship below 7: Moony's ring (after the
   Mom and Dad quest 100400), use the box, type her name: about 4 s later she declines in a line, and the owner can
   propose again later.
6. **Proposal, yes.** At friendship 7+: she accepts in a line; both get the engagement boxes; Bonnie (chapel) or Nicole
   (cathedral) takes the reservation with her in the map and the Cash Shop ticket.
7. **Garment.** Buy the bride's garment (1051150-1051153) at Vivian Boutique (9201020) and hand it to her from her gift
   menu: she keeps it ("i'll wear it on the day"), untradeable, in her bag.
8. **Ceremony.** At the reserved time start it with the officiant: she wears the garment in the chapel. After the
   blessings, confirm your vows: about 3 s later she says "i do." and the officiant pronounces you married; both get the
   ring. Relog: still married; her next load leaves the marriage alone.
9. **Content decides (staging only).** Remove `spouse:` from Ansel and Vesna, restart, load Ansel with Vesna unloaded:
   the marriage is broken, Ansel says his `spouse.parted` line about 15 s after he appears, and Vesna's next load drops
   her loose ring.
