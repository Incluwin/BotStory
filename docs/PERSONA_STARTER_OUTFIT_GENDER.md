# Persona starter outfit gender (2026-10-10)

Branch `fix/persona-beginner-outfit-gender`. The Residents created before this fix are repaired by option 2 (user
decision 2026-10-10, branch `fix/resident-starter-outfit-swap`): see "Starter swap" below.

## What was wrong

- `ResidentProvisioner` created every Resident through `BotCreator.createCharacter(client, name)`. That overload
  always makes a man: gender 0, face 20100, hair 30020, White Undershirt (1040002), Blue Jean Shorts (1060002),
  Red Rubber Boots (1072001) and a Sword (1302000).
- `ResidentLooks.apply` then wrote the persona's own gender, face and hair to the character row. The clothes stayed
  the men's set.
- The Resident wardrobe usually replaces the starter top and bottom from the loadout's outfit pools. The replaced
  pieces go to the equip bag. Tamsin (character 837) carried 1060002 this way. If no pool piece fits a slot, the
  Resident keeps wearing the men's piece.
- Stock character creation is gendered: the client offers, and `MakeCharInfoValidator` accepts, only the
  `Etc.wz/MakeCharInfo.img` `Info/CharMale` or `Info/CharFemale` choices. The female set is 1041002 White Tubetop,
  1061002 Red Miniskirt, 1072001 Red Rubber Boots and 1302000 Sword.
- Not affected: citizens (`CitizenGenerator` already picks gendered tops and bottoms), the Maple Island cohort
  (gendered catalog), the GM test fixtures (they clear the worn gear and the equip bag, then dress by gender) and `@spawnbot`
  (always a man in the men's set).

## The fix

- `BotCreator.createCharacter(client, name, gender)` creates a woman in the stock female set (face 21000, hair
  31000) and a man in the unchanged men's set. The two-argument overload is the men's set, as before.
- `AgentClientGateway.createBackingCharacter(client, name, gender)` passes the gender through.
- `ResidentProvisioner` creates the character in the gender `ResidentLooks` gives the persona
  (`ResidentLooks.gender(persona)`, the same value `resolve` returns).

## Existing Residents

Offline count from `scripts/config/living-world` (not the live database): 36 of 72 Residents resolve to female,
21 by an authored `look.gender` and 15 by the random draw. 10 of the 36 are recruitable. Every one of the 36 has top
and bottom outfit pools, so most carry the men's starter top or bottom in the bag rather than wear it. The live
number can be lower: a persona counts only after its character is provisioned.

Options:

1. Leave as is. No code. The men's pieces stay in the bag, and stay worn where no pool piece fits.
2. Swap starter items on next load. When a Resident loads, a plain starter piece of the other gender (no owner tag,
   not scrolled, not untradeable) is swapped for that gender's twin, worn or in the bag. Idempotent, so it runs on
   every load. It also repairs a Resident whose `look.gender` is edited later, because `ResidentLooks.apply` changes
   the gender of an existing character but not its clothes.
3. One-time repair. A database changeset swaps the item ids for female Residents. It runs once and does not cover
   a later `look.gender` edit.

Recommendation: option 2. It needs no direct database edit, touches only plain starter pieces, and also covers
later gender edits. If the user does not decide, nothing changes (option 1).

Decision 2026-10-10: option 2. The user chose "swap starter items on next load".

## Starter swap (option 2)

- `ResidentStarterSwap` runs on every load. `ResidentRuntime.materialize` runs it before shaping. `prepareCompanion`
  runs it before shaping and the quest kit, in the same mailbox action when the companion is registered. Hired citizens
  keep their gear.
- The gender of the loaded character decides, not the persona's look. `BotCreator.starterTwin` maps only 1040002 and
  1041002, and 1060002 and 1061002, in both directions. Both sets share the boots and the sword.
- A plain piece is swapped in its own key, worn or in the bag, so a full bag works. Plain means: no owner tag, no gift
  note, and flag, expiration, scroll level, upgrade slots, vicious, ring id and anvil look equal to the fresh twin's.
  The twin keeps the item level, item EXP and stats.
- This rule relies on each twin pair having the same Character.wz data (tuc 7). `ResidentStarterSwapTest` pins it. If
  the data ever differed, the swap would skip pieces.
- Each swapped piece logs `Resident <id> swapped its starter piece <old> for <new> (worn slot|bag slot <n>)`. There is
  no kill switch and no config key.
- Every plain piece of the four ids is swapped, whatever its source: the starter piece, a wardrobe piece, a drop
  (1040002, 1041002 and 1060002 are in 152-drop-data.sql), a buy or a gift. The value is the same, but a later farm haul
  or hand-back shows the other gender's id.
- Boss gear: a companion that loads in a `!bossgear` kit can have its displaced starter piece swapped. The
  `BossGearRecord` id then no longer matches. Nothing is lost: `OwnGearRefit` falls back to the best piece it carries,
  its own gender first (`OwnGearRefitTest` covers this).
- The first summon or town load after the deploy can show one clothes change, because the swap runs after the
  character is on the map.
- Known gap: a bench-slice load never writes a new `look.gender`, so the swap waits until the body's gender changes.
  That gap is in `ResidentLooks` and is out of scope.
