# P14: First party bosses (Capt. Latanica, Neo City)

> Built 2026-10-12 on branch `feat/companion-bossing-p14`, on top of P13. Plan: docs/COMPANION_BOSSING_PLAN.md,
> section P14. Not deployed, not live-tested yet. Every P14 key ships off.

## Result

An owner can now take their own party companions into six party bosses: Capt. Latanica and the five Neo City bosses
(Bergamot, Dunas, Aufheben, Oberon, Nibelung). The owner enters as party leader through the stock portal. The
companions inside then fight as a companion boss run. With every P14 key off (as shipped), the six fights are stock.

## What the player sees (with a key on)

Example: `latanica` on, the owner leading a party with companions at the gate 541010060 with White Essence.

- The owner walks into captinsg00. The stock check takes the whole party in, as before.
- Inside, the companions hit Capt. Latanica first (9420513), then his adds. His attacks reach them too: the boss
  stays with the owner's client, and its attacks are mirrored onto the companions.
- Companions stop hitting a boss that is immune to their damage type or reflecting it (Dunas, Nibelung, Oberon).
- Companions never summon where the map forbids summons for players (541010060 and 541010100 do).
- A companion that dies revives outside (Boat Quay Town for Latanica, 240070000 for Neo City), says one line and
  waits for its owner.
- When the owner leaves, the companions leave too: by Bob (9270033) at Latanica, by the battle map's portal at Neo
  City.
- On the way, companions short of the boss's potion standard buy at Boat Quay Town (Latanica) or at Leafre's store
  (Neo City). At a gate they make no shop trip, because no short route leads back.
- Neo City needs Lv70: a companion below that keeps no Neo City standard.

## Keys and standards

| Key | Boss | Gate, battle map | Standard (HP/MP potions, projectiles) |
|---|---|---|---|
| `latanica` | Capt. Latanica | 541010060, 541010100 | 150/100, 1000 |
| `neo_bergamot` | Bergamot | 240070202, 240070203 | 150/100, 1000 |
| `neo_dunas` | Dunas | 240070302, 240070303 | 150/100, 1000 |
| `neo_aufheben` | Aufheben | 240070402, 240070403 | 200/150, 1500 |
| `neo_oberon` | Oberon | 240070502, 240070503 | 200/150, 1500 |
| `neo_nibelung` | Nibelung | 240070602, 240070603 | 250/250, 2000 |

Leafre (240000000) buys the largest standard among the listed Neo City bosses.

## Changes from the plan

1. **No `BOSS_IDS` entries.** The plan adds the P14 ids to `AgentBossScope.BOSS_IDS`. Latanica's adds 9420511 and
   9420512 also spawn on Singapore field maps (541000300, 541010040, 541010050). Listed there, the guard would act
   on those field mobs at once, with every key off. P14 instead guards a party boss's parts only inside an event that
   has its open party-instance run (`AgentBossRunRegistry.partyBossPart`, over the boss-rules.json row).
2. **The hook is in the event script, not the portal.** The portal scripts never see the event instance. Each event
   script's `afterSetup` calls `LivingWorldHooks.bossPartyEntered(eim, key)`. It runs right after the stock
   `registerParty`.

## Config

`config.yaml` (living_world): `COMPANION_BOSS_ENABLED_TYPES` accepts the six keys. No new key.

`agent-engine.yaml`: the six standards join `AgentSupplyReadiness.BOSS_STANDARDS` and `AMMO_STANDARDS`.

boss-rules.json: one row per boss with its `targetPriority` (and Latanica's adds). Neo City rows set `minLevel: 70`.

No database change.

## How it is built

- **Run catalog.** `AgentBossRunCatalog.party(...)` builds each row: no expedition, no entry NPC, the priority fight
  (`AgentBossPriorityFight`), WAIT_OUTSIDE deaths, rule B (the owner leaving takes the party out). Latanica leaves by
  Bob before and after the clear. A Neo City battle map is also its "clear map", so members leave by its portal 1.
- **Run session.** It prepares no exit NPC script for a boss with none.
- **Restock.** One `BossEntrance` row per boss: the approach store, held trips at the gate, Leafre's store NPC
  (2080001 on 240000002).
- **Level.** `AgentBossRunDefinition.minLevel()`: the expedition's own, or the rules row's `minLevel` for a party boss.

## Tests

`AgentPartyBossesTest` (each script opens its row; maps and level match the script; Latanica's and Neo City's ways
out; `partyBossPart`), `PartyBossScriptsTest` (Graal: each `afterSetup` calls the hook with its key),
`AgentBossScopeTest` (a party part is guarded in its run, never on a field), `BossEntranceTest` (approach stores,
held trips, Leafre's largest standard), and the updated catalog, key and standard pins. `ScriptEvaluationTest` still
evaluates all 1,824 scripts.

## GM-first live check (from the plan)

**Before you start:** a restart disconnects everyone online.

Latanica:

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include `latanica`.
2. Restart with `start-maple-stack -RestartServer`.
3. Run `!item 4000381` as Bossfriend.
4. Run `!warp 541000000`. If a companion is short, check that it buys there.
5. Run `!warp 541010060`.
6. Enter as party leader.
7. Drop the essence on the reactor.
8. Watch the companions lose HP to the magic boxes.
9. Run `!hpmp Flint 0`. Check that he waits in Boat Quay Town.
10. Leave through Bob. Check that the companions come out too.

Neo City:

1. Set `COMPANION_BOSS_ENABLED_TYPES` to include the five `neo_` keys, then restart.
2. Run `!warp 240000000`. If a companion is short, check that it buys there.
3. Run `!warp 240070202` (Bergamot's gate) and enter as party leader.
4. Watch the companions react to seal and stopMotion.
5. Repeat for 240070302, 240070402, 240070502 and 240070602.
6. On Dunas and Nibelung, check that companions stop or switch targets during immunity.

## Risks

- Unconfirmed: whether 10 minutes is enough for Latanica's 2M HP.
- Neo City needs 2 players or more. Companions count as players, so an owner with one companion can start it.
- Leafre is a busy town. With a Neo City key on, a Lv70+ companion tops up to that standard whenever its owner
  passes Leafre.
