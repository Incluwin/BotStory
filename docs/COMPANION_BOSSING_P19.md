# P19: The Boss (Showa expedition)

> Built 2026-10-12 on branch `feat/companion-bossing-p19`, on top of P18. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P19. The `showa` key ships off. **Live check CLEARED 2026-10-15 11:40** on the seventh run (see "Live check
> 2026-10-15" below).

## Result

An owner can now sign their companions up for The Boss at Konpei in the Showa hideout, as at Adobis for Zakum. Inside,
the companions fight the gang until the owner drops the comb, then the Bodyguard chain and The Boss first. A companion
that dies waits in Showa Town. With `showa` off (as shipped), Konpei's window and the fight are stock.

## What the player sees (with the key on)

Example: `showa` and `field_zipangu` on, the owner at Konpei (801040004) with 5 companions and the comb (4000138).

- Konpei offers the owner "My party will register too." after they create or join the expedition, and "Call allies
  from my bench..." when `COMPANION_BOSS_ALLY_TYPES` also lists `showa`. The expedition needs 3 or more.
- On Konpei's map, the registered companions wait beside him and leave Male Boss to the players there (the
  `field_zipangu` rule from P16).
- Inside (801040100), the companions fight the gang and the battle map's Male Boss until the owner drops the comb on
  the reactor. Then they take Bodyguard A, Bodyguard B and The Boss first. The first companion in says "ready when you
  are. drop the comb on the machine." while the owner carries the comb.
- No companion picks up the comb from the floor.
- A companion that dies says one line ("ugh, the boss got me. i'll wait in showa town.") and waits in Showa Town. Any
  death, a companion's too, cancels the slot-machine bonus (decision 14).
- After the clear, the owner talks to Konpei for the reward and goes to 801040101; the companions follow and leave
  with the owner. Before the clear, an owner who leaves takes the party's companions out.
- On the way in, a short companion buys at the Showa Town store when the owner passes Showa Town carrying the comb. At
  Konpei's map it makes no shop trip.

## Keys and standards

| Key | Boss | Recruit map, battle map | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `showa` | The Boss | 801040004, 801040100 | 600/600, 4000 |

## Changes from the plan

1. **The comb is a marker item.** Not in the plan. Members leave it on the floor, as they leave Zakum's Eye of Fire,
   so a companion never takes it before it reaches the reactor. As a marker item it also gates the approach store
   (the owner must carry it) and the marker line.
2. **The slot machine is a never-target.** 9400114 appears in 801040101 after a clear with no deaths. Not in the plan.
3. **No re-entry.** Decision 16 keeps Showa's dead outside, so the run uses `WAIT_OUTSIDE`.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts `showa`. The note asks for `field_zipangu` too.

`agent-engine.yaml`:

- `AgentBossScope.EXPEDITION_TYPES` changes to `ZAKUM,SCARGA,HORNTAIL,SHOWA`. It acts only when an Agent hits a part
  in a Showa event, and Agents get there only with the key on.
- `showa=600/600` joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `showa=4000` joins `AMMO_STANDARDS`.
- 2026-10-16: `showa=1500/0` joins `AgentSupplyReadiness.BOSS_POTION_FLOOR_BPS`, as Horntail and Pink Bean have.
  Showa's 600 HP potions now count only potions that each restore 15% of the Agent's maximum HP. Showa Town's store
  (9120002) meets it only with Melting Cheese (4,000 HP), and only up to 26,666 maximum HP; White Potions and Ice
  Cream Pops (2,000 HP) no longer count.

boss-rules.json: the `showa` row (`targetPriority` 9400112, 9400113, 9400300, 9400122; `neverTarget` 9400114;
`markerItems` 4000138).

No database change.

## How it is built

- **Konpei's script.** It was Adobis's stock script with Konpei's names, item and event. It takes the five P8 hooks
  with the key `showa`. `KonpeiHookScriptTest` checks that it stays Adobis's twin.
- **Run row.** `AgentBossRunCatalog.SHOWA`: expedition SHOWA, no join choices, the priority fight, Konpei (9120202)
  as the exit before the clear, clear map 801040101 (no exit portal there, so a member still inside is moved out),
  owner leaving takes the party, `WAIT_OUTSIDE`.
- **Entrance row.** `BossEntrance.SHOWA`: companions wait beside Konpei (1402,161), approach store Showa Town (store
  NPC 9120002 on 801000300), allies in and out by `out00`, trips out of the recruit map held.
- **Lines.** `BossRunLines.BY_BOSS` gives Showa its own lines, including the marker line about the comb.

## Tests

- `KonpeiHookScriptTest`: Konpei equals Adobis with Konpei's names, item, event and the key `showa`.
- `AgentPartyBossesTest`: the Showa row against ShowaBattle.js, the chain first, the slot machine, the comb, and Male
  Boss still a field boss.
- `BossRunLinesTellTest`: Showa asks for the comb and never names Zakum or its eye.
- Updated pins: keys, catalog list, shipped scope types, standards.

Full suite on d3738d314: 13,457 tests, 0 failures, 37 skipped (12:14 min).

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `showa` and `field_zipangu`. For stage 2, set
   `COMPANION_BOSS_ALLY_TYPES` to include `showa`.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!item 4000138` as Bossfriend.
4. Run `!warp 801000000`. Check that short companions buy at the store on 801000300.
5. Run `!warp 801040004`.
6. Start the client memory gate on the recruit map. It already holds 15 mobs.
7. Create the expedition at Konpei and register the party.
8. Check that registered companions do not attack Male Boss 9400122 on their own.
9. Start.
10. Drop the comb on reactor 8001000. Check that no companion picks it up.
11. Watch the deadly hits and the mpBurn on mages.
12. Check that the companions hit the Bodyguard chain before the battle map's Male Boss.
13. Repeat with the bench allies as stage 2 of the memory gate.

## Live check 2026-10-15

Temporary, uncommitted main-tree config for every run: `COMPANION_BOSS_ENABLED_TYPES`
"easy_balrog,showa,field_zipangu" and `COMPANION_BOSS_ALLY_TYPES` "easy_balrog,showa"; from run 4 also
`COMPANION_BOSS_OWNERS_MAX` 2. Crews at level 200 in 4th job. Both owners raised to 30,000 HP with `!maxhpmp` for the
runs and set back in the database afterwards (ohboy 8,996/5,309, Bossfriend 19,776/2,924).

| Run | Build | Members | Result |
|---|---|---|---|
| 1 | 440a1c90f | Bossfriend + 5 + 4 allies | Bodyguard A 73% after 7 min; 7 of 9 companions dead. Ended. |
| 2 | 61e5660c2 | same | The attack fence held only while the Bodyguard stood still; his touch (~16,800) killed companions who drank at 70%. Ended. |
| 3 | 586d6f8fe | same | Everyone held ~120 px back (the touch counted as deadly too): 7M HP/min, Bodyguard A 90% at 5 min. Ended. |
| 4 | 6ee4f3053 | both owners, 14 companions | Bodyguard A fell in ~11 min (42M/min); Bodyguard B to 20%; 12 of 14 died once potions ran out. Both clients then crashed entering Konpei's map (server state; cleared by a restart). |
| 5 | 6ee4f3053 | same, 700 Power Elixirs each | Both Bodyguards fell. The Boss (eva 120) at 99%: most warrior hits missed, and his ~21k touch killed them. |
| 6 | cf5d742f3 | same, accuracy kits | Bodyguard B 53% when the warriors ran dry (gifts to full Use tabs never landed). |
| 7 | cf5d742f3 | 13 companions + both owners, 1,500+ potions per warrior | **Cleared 11:40:38.** Bodyguard A ~13 min, Bodyguard B ~19 min, The Boss ~15 min. No warrior died. |

**Run 7 detail.**

- Deaths (6): Flint, Fenna, Sorrel, Gunnar (50 potions: his Use tab was full), Ilse and Isolde. All without Hyper
  Body; the two Bishops died on Bodyguard B as in every run.
- The Hyper Body warriors under the 90% fence: they fought close and drank at 95%, and held for the whole fight.
- Accuracy: the Showa kit lifted the warriors' accuracy for The Boss's 120 avoid. He fell at about 10M HP a minute.
- After the clear all seven inside left with their owners (EXITED); the run ended `cleared=true members=13`.
- Client memory peaked at 1,651 MB of address space. No ERROR lines in the server log.
- Video: `docs/agents/evidence/video/p19-showa-run7-2026-10-15.mp4` (672 MB, not in git).

**What it took (all on `feat/companion-manager`, live as cf5d742f3).**

1. `61e5660c2`: `attackKeepOut` in boss-rules.json; a companion keeps out of a listed part's attack reach when one hit
   would take most of its HP.
2. `fddc06340`/`2a9dbfd5d`: a wider gap from moving parts, and an HP floor (drink before one hit can kill) while a big
   toucher is on the map.
3. `6ee4f3053`: `keepOutHpPercent` per boss; Showa's 90 lets Hyper Body warriors fight the Bodyguards.
4. `cf5d742f3`: `!bossgear <name> showa` builds a kit that reaches the boss's accuracy from equipment (user decision:
   gear, not DEX).
5. Not deployed yet: `7af80a93a`, a kit needs one free bag slot per worn piece only.

**Open items.**

1. Gifts to a full Use tab are silently lost (Gunnar, runs 6 and 7). Owners have no way to see it.
2. The ordinary restock finds no reachable shop from the hideout; companions fight with what they brought.
3. Bishops and ranged members without Hyper Body die to the Bodyguards in every run.
4. The client crash entering Konpei's map (run 4) was not explained; it did not recur after a restart.

## Risks

- Unconfirmed: whether 1.05B HP can be cleared in 60 minutes. **Answered 2026-10-15: yes, in 49 minutes with 13
  companions at level 200.**
- Unconfirmed: whether the gang on Konpei's map attacks registered companions while they wait.
