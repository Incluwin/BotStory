# Every built party quest and boss on by default (2026-10-19)

The README said some party quests and bosses were built but not turned on. Branch `feat/default-on-content` turns
every one of them on in the shipped `config.yaml` and adds what that needed.

## What ships on

- `living_world.COMPANION_PQ_ENABLED_KEYS`: `kpq,hpq,lpq,epq,lmpq,opq,ppq,rjpq,rjpqj`. New: Rolly (LMPQ), Wonky
  (OPQ), Guon (PPQ), Romeo and Juliet (RJPQ).
- `living_world.COMPANION_BOSS_ENABLED_TYPES`: every key in `CompanionBossKeys` (Easy and Normal Balrog, Zakum,
  Papulatus, Scarga, Horntail, Showa, Pink Bean, Latanica, the five Neo City bosses, ten field groups).
- `living_world.COMPANION_BOSS_ALLY_TYPES`: the seven bosses with a sign-up list. `COMPANION_BOSS_OWNERS_MAX` stays 1.

Drop a key to make that NPC or boss stock again.

## What came with it

| Commit | What |
|---|---|
| merges | fix/rjpq-dark-lab-runners, fix/ppq-side-room-join-removal, feat/opq-human-garden, feat/opq-five-branch-lineup |
| 749d236e3 | OPQ: a downed companion taps out (the open item from the Wonky desk) |
| 7f1e9cbe6 | A companion that dies twice beside one field boss sits the fight out (`COMPANION_FIELD_BOSS_SIT_OUT_*`) |
| 629348b55 | A citizen that died beside a field boss trains elsewhere while it stands (`population.FIELD_BOSS_AVOID_MS`) |
| feat/ppq-desk | Guon's desk and the PPQ companion run (docs/P6I_PPQ_DESK.md) |
| feat/lmpq-desk | Rolly's desk and the LMPQ companion run (docs/P6H_LMPQ_DESK.md) |
| d9cfd9b9a | A boss ally sells its loot before it buys on arrival (Zakum live run) |

Full suite on 89d38f027: 14,265 tests, 0 failures, 37 skipped (9:33).

## Live checks (2026-10-19)

Bosses (owner Bossfriend, Lv160 crew; details in docs/COMPANION_BOSSING_P13.md):

- Zakum: sign-up with allies, arms, two of three bodies; lost when the owner died. Rerun pending.
- Aufheben, Oberon, Nibelung: cleared, everyone out with the owner.
- Field groups: Manon (Leafre) fought down to 21%; Zeno (Ludus) and King Sage Cat (Mu Lung) engaged.

Party quest desks (owner Crewfriend Lv78 with Dorran, Lioren, Kestrel):

- PPQ, Guon: lineup window, Yes, run admitted, stage one, medals handed over, clean early end. PASS.
- RJPQ, Romeo: lineup window, Yes, run admitted, companions open the books and ask about Juliet's letter, clean
  early end. PASS.
- LMPQ, Rolly: lineup window; the Lv75+ companions are kept waiting "past the maze". Full run not done.
- OPQ, Wonky: lineup window, companions sent to the store first, the 51-70 level rule refused. Full run not done.

The test bench has no Lv51-70 crew for LMPQ and OPQ, and `!setlevel` down can drop skills a restore does not give
back. Run those two with a fresh Lv55 test party.

## Live checks (2026-10-20)

Test party for LMPQ and OPQ: a fresh GM test owner, Pqfriend (Cleric Lv55), with five granted companions at Lv52-59:
Jinx (Assassin, the only one with Haste), Vex (Bandit), Sadie (Fighter), Marrow (Hunter) and Kettle (Brawler). The
three below 55 were raised with `!setlevel <name> 55` and released with `keep`; raising a level drops nothing.

- LMPQ, Rolly: full run PASS in 177 s. Desk window, admission, directions in every room, coupons dropped at the
  leader's feet, Pietri, reward, and the party back in Ludibrium whole. Details in docs/P6H_LMPQ_DESK.md.
- OPQ, Wonky: the desk PASSES. Window, five-branch blessing, 30-35 min estimate, Yes, admitted with six. Restocks
  before entry. Entrance cleared in about 90 s. Both runs FAILED the 45-minute clock in the room stage, then released
  cleanly with the party whole on the exit map. Storage and the walkway completed. The lounge, the lobby and the way up
  never did:
  - Lounge: three of its four subrooms sit up ledges a base jump cannot climb (jump 115 needed). The plan handed the
    room to a Fighter.
  - Lobby: every jump off its rope fell 11 px short of the ledge. This is a general movement bug: air steering pulled
    rope jump-offs back toward the rope.
  - Way up: the authored jump to its long rope carried no climbing intent.
  Fixed with tests on fix/opq-room-climbs; the live re-run follows below.

## Findings

- Leafre restocks a Lv160 crew to Pink Bean's 1500-potion standard (since P20, before this change).
- A TownLife citizen tick failed once with "valid TownLife lifecycle event fields are required"
  (AgentTownLifeRuntime.terminateLocal, graft code, not touched here).
- The stock Adobis script (2030013.js) can throw "Multiple applicable overloads" for startInstance when the
  expedition is started right after a disband.
