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
- Deployed 2026-10-21 06:44: fix/opq-run3 and fix/expedition-expired-start merged into feat/companion-manager, with the
  knowledge file refreshed (e6644310f). Full suite 14,290 / 0 / 37. Restarted at players=0 with no ERROR lines at boot.
  OPQ run 4 waits for the airborne-edge branch.

## OPQ run 4 (2026-10-24)

Server bc6f46d8d (since 21:09, with the room-climb, run-3, airborne-edge and ground-stop fixes). Same test party:
Pqfriend (Cleric 55) with Jinx, Vex, Sadie, Marrow and Kettle. The server day was Sunday (GMT).

- Desk at Wonky: PASS. Lineup window, five-branch blessing, 30-35 min estimate, Yes, admitted at 22:07:52 with six.
  Vex's stars were topped up by GM beforehand (800 Subi), and Vex, Sadie and Marrow got 200,000 mesos each.
- Entrance: 84.5 s. Split into rooms at 22:09:17 (Vex walkway, Jinx storage, Marrow lobby, Kettle way up, Sadie
  lounge).
- FAILED the 45-minute clock at 22:52:53 in the room stage. It released cleanly, with all six whole on 920011200.
- Time per room (from the split): storage COMPLETE at 2:44 (4:10 into the run). No other room completed.
  - Lounge: Sadie, not Jinx. Wonky's blessing (item 2022093) adds jump +10, and the climber check read any jump buff
    as "Haste already on". So Jinx (Haste 19) counted as jump 110, below the 115 the high subrooms need, and never cast
    Haste. Sadie cleared the low subroom (10 fragments by 22:2x), then stood on its top-left platform (-865,-385)
    until the end. The cause is not proven. Offline, the exit is reached from there in about 40 ticks.
  - Storage piece: Jinx carried it to the statue and stood under scar6 for 40 minutes. scar6 sits 40 px above the
    only floor under it, beyond the 38 px drop radius. scar2 (77 px), scar3 (88 px) and scar4 (41 px) are out of
    reach the same way; earlier runs placed scar4 and scar6 only from mid-jump.
  - Lobby: Sunday's LP box (2002004, x -838) sits in a cell whose last ledge is 100 px up. Marrow (jump 100) climbed
    the two ropes into Monday's cell (the old ascent goes nowhere else) and stood there. Given Sunday's LP by GM at
    22:15, she dropped it on the music player and called the leader at 22:16:04. Eak's music line came at about 22:21,
    but she went back to her cell instead of breaking the box that appeared.
  - Way up: Kettle passed the long rope and rows 1-4 (both stuck there in run 3). Past the fourth row the door warps
    to a landing (pt00, y -2581) that the row picker read as row 4 again. For the doors right of the rope, her route
    climbed the rope, came off its top on a jump that touched down 2 px from the end of the small platform there, slid
    off and fell back to the landing, every 3 seconds for 30 minutes.
  - Walkway: Vex had 25 of 30 fragments by 22:26. She then stood on the walled top-right floor she reaches by the
    one-way pt15 to pt05 link, until the end. The cause is not proven.
  - The human leader died twice in the centre within two minutes of the split (centre mobs, 1438 HP). Revived with
    `!heal` and `!stepto`, never with the death OK, which leaves the event. After that the leader waited GM-hidden.
- Fixes on fix/opq-run4 (not merged): see the branch log. Offline, the way-up landings reached the next row's door
  0/96 on the old code and 96/96 with the fix.

## Findings

- Leafre restocks a Lv160 crew to Pink Bean's 1500-potion standard (since P20, before this change).
- A TownLife citizen tick failed once with "valid TownLife lifecycle event fields are required"
  (AgentTownLifeRuntime.terminateLocal, graft code, not touched here).
- The stock Adobis script (2030013.js) can throw "Multiple applicable overloads" for startInstance when the
  expedition is started right after a disband. Seen again 2026-10-20; fixed for Zakum, Horntail, Pink Bean, Showa and
  Scarga on fix/expedition-expired-start (live since 2026-10-21). Balrog keeps the expedition object across clicks and CWKPQ is
  being rewritten, so both are left as they are.
