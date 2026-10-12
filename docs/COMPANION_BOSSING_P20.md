# P20: Pink Bean

> Built 2026-10-12 on branch `feat/companion-bossing-p20`, on top of P19. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P20. Not deployed, not live-tested yet. The `pinkbean` key ships off. The statue fix (decision 15) acts for
> every player as soon as it is deployed.

## Result

An owner can now sign their companions up for Pink Bean at the Forgotten Temple Manager, as at Adobis for Zakum. A
companion that dies revives inside, as a player does, and walks back into the fight. Ranged and magic companions keep
out of Solomon's and Rex's deadly boxes. P20 also fixes the statue reactor for every player: Pink Bean now wakes even
when the event's first player lies dead on another map. With `pinkbean` off (as shipped), the Manager's window and the
fight are stock.

## What the player sees (with the key on)

Example: `pinkbean` on, the owner at the Temple Manager (270050000) with 5 companions.

- The Manager offers the owner "My party will register too." after they create or join the expedition, and "Call
  allies from my bench..." when `COMPANION_BOSS_ALLY_TYPES` also lists `pinkbean`. The expedition needs 6 or more, at
  Lv120 or more.
- Inside (270050100), the companions take each wave's guardians, the healer Ariel first, then Munin, Hugin, Solomon and
  Rex. They never hit the statues or the 1-HP statue.
- While Solomon or Rex is up, ranged and magic companions stay between their deadly boxes: they walk in first if they
  stand in one, then hold there while they fight. Melee companions fight as before.
- After the fifth wave, no companion picks up the item that drops by the reactor. Once Pink Bean wakes, the companions
  take it.
- A companion that dies revives on 270050200, as a player does, walks through the portal there and fights on. Every
  death counts toward the stock 5-death wipe and costs EXP (decision 13).
- After the clear (or before it, if the owner leaves), the companions leave by 2141002.
- On the way in, a short companion buys at the Leafre store when the owner passes Leafre, before the dragon ride. At
  270050000 it makes no shop trip.

## Keys and standards

| Key | Boss | Recruit map, fight maps | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `pinkbean` | Pink Bean | 270050000; 270050100, 270050200 | 600/600, 4800 |

600/600 with 4800 projectiles is the plan's starting standard: it fills the 18-stack budget. The scaled standard
(1200/1200, 10000) waits for Questions 5 and 6.

## The statue fix (decision 15, every player)

The reactor 2708000 kills the 1-HP statue 8820000 when the stage-5 item lands on it, and the statue's death wakes Pink
Bean. The script killed the statue on `rm.getMap()`, the map of the player the item drop names. That player is the
event's first player, who may lie dead on 270050200 or stand anywhere else in the event. The statue then stayed, and
Pink Bean never woke. The script now kills the statue on the reactor's own map (`rm.getReactor().getMap()`).

## Changes from the plan

1. **Target order.** The plan names the waves but no order. The healer Ariel comes first, then Munin (banish and
   seduce), Hugin (reflect), Solomon and Rex, then Pink Bean.
2. **The walk back is a stage map.** The plan says the member walks to the Pinkin portal at x -1156. P18's stage maps
   do this: 270050200 is Pink Bean's stage map, and `in00` (Pinkin.js) is its stage portal.
3. **Entry items.** P8 used the marker items for two jobs: the floor guard and the approach check ("is the owner on
   the way to this boss?"). Pink Bean's marker item only ever lies on its battle map, so with one list no owner would
   ever count as on the way, and no companion would buy Pink Bean's standard in Leafre. `boss-rules.json` gains
   `entryItems`, the items an owner carries in. It defaults to the marker items, so Zakum (the Eye) and Showa (the comb)
   are unchanged. Pink Bean names none.
4. **Keep-out boxes are data.** `boss-rules.json` gains `keepOut`; only Pink Bean lists boxes.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts `pinkbean`.

`agent-engine.yaml`:

- `AgentBossScope.EXPEDITION_TYPES` changes to `ZAKUM,SCARGA,HORNTAIL,SHOWA,PINKBEAN`.
- `pinkbean=600/600` joins `AgentSupplyReadiness.BOSS_STANDARDS`, and `pinkbean=4800` joins `AMMO_STANDARDS`.
- New: `AgentBossPriorityFight.KEEP_OUT_MARGIN_PX: '30'`.

boss-rules.json: the `pinkbean` row gains `targetPriority` (8820002, 8820006, 8820005, 8820003, 8820004, 8820001),
`adds` 8820007, `entryItems` [], `stageMaps` 270050200, `stagePortal` in00 and `keepOut` (8820003 from -900 to -200,
8820004 from 200 to 900).

No database change.

## How it is built

- **The Manager's script.** It was Adobis's stock script with its own header and no leader item check. It takes the
  five P8 hooks with the key `pinkbean`. `TempleManagerHookScriptTest` checks that it stays Adobis's twin.
- **Run row.** `AgentBossRunCatalog.PINKBEAN`: expedition PINKBEAN, no join choices, the priority fight, 2141002 before
  and after the clear, owner leaving takes the party, `DeathMode.REVIVE_INSIDE`.
- **Entrance row.** `BossEntrance.PINKBEAN`: companions wait beside the Manager (-665,-41), approach store Leafre
  (store NPC 2080001 on 240000002), allies in and out by `out00`, trips out of the recruit map held.
- **Revive inside.** P12's death gate (`AgentBossDeathDepartureGate`) revives the member into the event's 270050200.
  There `AgentBossPriorityFight` sees a stage map with no monster and walks the member through `in00`.
- **Keep-out.** `AgentBossPriorityFight.keepOutBand`: for each live part with boxes, a box to its left bounds the band
  from below and a box to its right from above, `KEEP_OUT_MARGIN_PX` inside. A ranged or magic member outside the band
  walks to its nearest edge and does not fight that tick. Inside it, `setHorizontalBoundary` holds it, once per change.
  The boundary is cleared once no such part lives.
- **Entry items.** `AgentBossRules.entryItems`; `BossEntrance.approachMeantBy` and `CompanionBossRuns` (the approach
  key and the marker line) read it in place of the marker items.
- **Lines.** `BossRunLines.BY_BOSS` gives Pink Bean its own lines. Its DOWN line covers a failed revive.

## Tests

- `TempleManagerHookScriptTest`: the Manager equals Adobis with his header, names, event, no leader item and the key
  `pinkbean`; the statue reactor kills the statue on its own map.
- `AgentBossKeepOutTest`: the shipped rules; the band past both boxes, past one, and none; a ranged member in
  Solomon's box walks out first, is held once inside, fights, and is freed once the part is dead; a melee member is
  never held; a member on 270050200 walks back through in00.
- `AgentPartyBossesTest`: the Pink Bean row against PinkBeanBattle.js.
- `BossEntranceTest`: an owner is on the way to Pink Bean with no item, to Zakum and Showa only with theirs; Leafre
  serves seven bosses.
- `BossRunLinesTellTest`: Pink Bean's lines name no other boss, and it asks for no item.
- Updated pins: keys, catalog list, death modes, shipped scope types, standards; the "unlisted" examples move to Chaos
  Horntail.

Full suite on bbf4f205b: 13,467 tests, 0 failures, 37 skipped (10:54 min).

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online. The statue fix acts for every player once deployed.

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `pinkbean`. For stage 2, set `COMPANION_BOSS_ALLY_TYPES` to include
   `pinkbean`. For stage 3, set `COMPANION_BOSS_OWNERS_MAX` to 2.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!resetboss <name> PINKBEAN` for each character.
4. Run `!warp 240000000`. Check that short companions buy there.
5. From Bossfriend, run `!warp 270050000`.
6. Start the client memory gate, stage 1: owner plus 5.
7. Create the expedition at the Temple Manager, register the party and start.
8. Watch each wave. Check that ranged companions stay between Solomon's and Rex's boxes.
9. Run `!hpmp Isolde 0`. Check that she revives in the 270050200 instance and walks back through the portal.
10. Check that the stage-5 item stays on the ground and that Pink Bean wakes.
11. Repeat with the bench allies as stage 2, then with ohboy as stage 3. For stage 3, run `!warphere ohboy` from
    Bossfriend on 270050000.

## Open items

1. The scaled standard (1200/1200, 10000) waits for Questions 5 and 6.

## Risks

- Unconfirmed: whether 7.65B HP can be cleared in 140 minutes.
- Every death costs EXP. The map has no NO_EXP_DECREASE bit.
- Potions may run out before the end, because the standard is capped by the Use tab.
- This is the longest fight, so the client memory gate matters most here.
- Unconfirmed: whether companions follow-warp past the dragon ride and the Temple of Time gates (plan decision 11a).
