# P19: The Boss (Showa expedition)

> Built 2026-10-12 on branch `feat/companion-bossing-p19`, on top of P18. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P19. Not deployed, not live-tested yet. The `showa` key ships off.

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

## Risks

- Unconfirmed: whether 1.05B HP can be cleared in 60 minutes.
- Unconfirmed: whether the gang on Konpei's map attacks registered companions while they wait.
