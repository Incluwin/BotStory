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

- Zakum: sign-up with allies, arms, two of three bodies; lost when the owner died. Rerun 2026-10-20: cleared (below).
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
- OPQ run 3 (20:14-20:59, server 503900c37 with fix/opq-room-climbs and fix/bosslog-reset-midnight): FAILED the
  45-minute clock again. It released cleanly with the party whole on the exit map. The fixes held: the lounge went to
  Jinx, who cast Haste and climbed to the top row; the lobby runner reached the upper row; the way-up runner passed the
  long rope. Storage and the walkway completed again. Still blocking:
  - Lobby: the music player's stand point is in the air, so the LP dropped from the floor below missed the reactor.
    Fixed on fix/opq-run3 (089ac8a56). Also, on Tuesdays and Wednesdays (server day, GMT) the LP box is out of reach
    at any jump up to 123. That is a design question and is not fixed.
  - Way up: every wrong door from row 6 up threw in party3_r6pt.js (no warp(int, double)), 556 times, and left the
    runner standing. Fixed on fix/opq-run3 (6baed68e7).
  - Lounge top and way-up top: the runners loop on short platform jumps the graph calls reachable. Open; the
    airborne-edge work (fix/airborne-launched-edges) is looking at it.
  - Vex, a Bandit, wields a claw, so the desk held her back for stars she could not afford. Topped up by GM.
- Zakum (owner Bossfriend Lv202 Hero; party Thane, Isolde, Ingrid, Sorrel, Flint; allies Gunnar, Magna, Orrin, Tamsin):
  CLEARED.
  - The first attempt never went in. The 5-minute clock ran out while Adobis's "The expedition will begin" page was
    open, and the OK then made the script call startInstance(null). The allies were sent home and no boss-log rows
    were written. Fixed on fix/expedition-expired-start (630753174).
  - Second attempt: squad 21:22:52, allies called 21:23:16 (ETA 180 s), party signed up by 21:24:15, allies by
    21:26:29, Start 21:26:41, nine inside at 21:26:51. ZAKUM boss-log rows were written at entry for all ten.
  - The Eye of Fire must be dropped on the altar (reactor 2111001 at -10,-204); `!drop 4001017 1` at the entrance
    does nothing. Summoned 21:28:22. Arms down by 21:35, cleared 21:45:59 with nine inside and no deaths. The owner fell
    to 1 HP once (Zakum's HP-to-1 attack); the healer brought him back.
  - Dead member, forced after the clear with `!hpmp Flint 0` (`!kill` refuses Agents): Flint said he would wait in El
    Nath, waited there (211000000), and rejoined the owner when the run ended. A death before the clear was not seen.
  - The owner left by Amon at 22:04:35. All eight members inside followed by Amon (out=EXITED) within 6 s, and the run
    ended with cleared=true. All four allies were sent home. No ERROR lines from 21:22 to the end.

## Findings

- Leafre restocks a Lv160 crew to Pink Bean's 1500-potion standard (since P20, before this change).
- A TownLife citizen tick failed once with "valid TownLife lifecycle event fields are required"
  (AgentTownLifeRuntime.terminateLocal, graft code, not touched here).
- The stock Adobis script (2030013.js) can throw "Multiple applicable overloads" for startInstance when the
  expedition is started right after a disband. Seen again 2026-10-20; fixed for Zakum, Horntail, Pink Bean, Showa and
  Scarga on fix/expedition-expired-start (not merged). Balrog keeps the expedition object across clicks and CWKPQ is
  being rewritten, so both are left as they are.
