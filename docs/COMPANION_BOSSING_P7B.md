# P7 Part B: bench allies and two owners in Easy Balrog

> Built 2026-10-08 on branch `feat/companion-bossing` (on the live line with Part A, fixes L1-L7 and fix/p7a-live-2).
> Plan: docs/BOT_MANAGEMENT_PLAN.md (P7, flow 4). Part A: docs/COMPANION_BOSSING_P7A.md. Not deployed, not live-tested.

## Result

An owner in an Easy Balrog expedition can call up to 5 benched companions ("allies") from Mu Young's window. Allies
travel down, come in at the top of her stairs, buy their boss supplies on the way, sign up, fight in their own bot
party, and go back to their bench duty after the run. A second owner can join the same expedition with their own
companions and allies. Everything is off until `COMPANION_BOSS_ALLY_TYPES` lists `easy_balrog` and
`COMPANION_BOSS_OWNERS_MAX` is 2 or more.

## What the player sees (with the switches on)

- Mu Young offers "Call allies from my bench..." next to "My party will register too.". Her picker lists each
  benched companion with "about N min away", says how many seats are left for you, and offers "Everyone who can make
  it.". Allies who cannot come are plain lines with a reason (too far for the clock, not ready for this battle,
  already on the way, busy, or no room left for more of yours).
- A called ally whispers its ETA ("omw to mu young, ~1 min."). At the ETA it appears at the top of her stairs (top00,
  where players coming down land), walks to its spot beside her and signs up through her own window.
- On arrival an ally that is short of the Easy Balrog standard (100 HP / 60 MP potions, and projectiles for its weapon)
  buys what it lacks from its own purse at the Ant Tunnel Park store's prices (NPC 1061001: Unagi, Pure Water, ammo).
  The existing per-visit cap of 100 applies, so it ends at about 110/110. It says a low-pots or low-ammo line only if
  it is still short.
- Allies form one bot party per owner (6 or fewer, the highest level leads). Heals reach only that party.
- After a clear an ally says "good fight" and walks out by the room's west exit (outPerrion) and goes home; after a lost
  run it says a "next time" line. A dead ally waits beside Mu Young until its owner comes out or the run ends.
- The list always keeps 2 seats free for players (`COMPANION_BOSS_HUMAN_SEATS`), checked at call and at sign-up.
- Two owners: each brings only their own companions and allies; list seats and the server's 12 ally permits
  (`EXPEDITION_BURST_MAX`) are split evenly between owners.
- Drops: when an ally is the top damager of a kill in a companion boss run, the drop goes to its owner (or the heir
  after a logout handoff), so a bot party never locks a human out. Damage credit and EXP are unchanged.
- An owner who logs out mid-fight: their allies stay with any human left in the expedition; their party companions
  follow the Part A rule (party-only heir, else park).

## Config

`config.yaml` (living_world, appended after the Part A keys):
- `COMPANION_BOSS_ALLY_TYPES: ""` (kill switch; must also be in COMPANION_BOSS_ENABLED_TYPES).
- `COMPANION_BOSS_OWNERS_MAX: 1` (1 = Part A's one-owner rule).
- `COMPANION_BOSS_HUMAN_SEATS: 2`.
- `COMPANION_BOSS_ALLY_WALK_OUT_MS: 20000`.

`agent-engine.yaml`: `AgentBossRunSession.SPOT_REACH_PX: '40'`. No database change.

## How it is built

- Agent side: self-led boss-run members (walk to a host-named spot, stop instead of follow at hand-back), withdraw
  before Start (stock "has left the expedition"), adoption only while the ally's anchor is in the event, the fight's
  12 formation slots folded for more than 12 members, a `roomFor` host check before sign-up,
  `AgentShopProvisioning.provisionAt` (readiness counter steps at a named store without a walk).
- Living side: `CompanionBossAllies` (trips CALLING/TRAVELLING/HERE/LEAVING, EXPEDITION leases and the per-owner
  permit share, load at top00, provisioning, overflow party under the `companion.boss` cause, dismissal and unload,
  logout, the drop owner, the sweep), `CompanionBudget.expeditionShareLeft`, `CompanionBossRuns.seatsLeft`,
  `InstanceLogoutHandoff.anyHuman`, `BenchDutyDispatcher` and `BenchLedger` skip personas on a trip,
  `RosterService.call` answers "after the run." for an ally on a trip.
- Core: `Monster.killBy` asks `LivingWorldHooks.bossDropOwner` (stock owner unless an ally of an open run is the
  candidate), `AdminBudgetReader` gains `expeditionLeases`, `expeditionPeak`, `expeditionBurstMax`.
- Not changed: `AgentDeathTickCoordinator`, `CompanionDirectory.mayLoad` (allies load through the reviewed bench path),
  the route graphs, KPQ/HPQ/LPQ/EPQ code.

## Tests

Each step was written failing-first with a mutation check and an independent audit. A six-lens review with 3 skeptics
per finding confirmed 4 low defects; all are fixed (no clickable allies without room, seats kept at sign-up, "good
fight" only after a clear, missed-start wording), plus ally provisioning on arrival. Expected full-suite failure:
`KnowledgeLintTest.installOfflineReadsTheFileAndTheSeed`, because scripts/npc/1061014.js changed; run
tools\knowledge-export.bat at integration.

## Live check recipe (GM commands first)

Before you start: a restart disconnects everyone online. Tell other players first. Restart only with
`start-maple-stack -RestartServer`. Never use `!setlevel ... restore`. Do not add `auto` for companions.

Setup (Part A setup already done: Bossfriend Lv70 with Isolde, Thane, Sorrel, Ingrid and Flint at Lv70):
1. Deploy with `COMPANION_BOSS_ALLY_TYPES: ""` and `COMPANION_BOSS_OWNERS_MAX: 1`. Check that Mu Young shows only Part
   A's options.
2. Check the contracts of Orrin, Magna and Gunnar before you grant them.
3. On Bossfriend, run `!grantcompanion orrin Bossfriend`, `!grantcompanion magna Bossfriend` and
   `!grantcompanion gunnar Bossfriend`.
4. Bench Thane, Ingrid and Flint. Call Orrin, Magna and Gunnar and wait until each arrives.
5. Run `!setlevel Orrin 70 121`, `!setlevel Magna 70 121` and `!setlevel Gunnar 70 111`.
6. Give Orrin and Magna mesos for supplies (about 200k each). Leave Gunnar with few mesos to see the low-pots line.
7. Bench the three allies. Call Thane, Ingrid and Flint back and wait until each arrives.
8. Set `COMPANION_BOSS_ALLY_TYPES: "easy_balrog"` and `COMPANION_BOSS_OWNERS_MAX: 2`, then restart.

Run 1, the main path:
9. Walk down to 105100100 (or `!warp 105100100`). Talk to Mu Young, pick Easy, then "Lets get this going!".
10. Expect "My party will register too." and "Call allies from my bench...". Pick the ally option. Expect three rows
    "about 1 min away" and the seats-left line.
11. Pick "Everyone who can make it.". Expect three ETA whispers. Read `/runtime/budget`: `expeditionLeases` 3.
12. Talk to Mu Young again and pick "My party will register too.".
13. Expect three allies at the top of the stairs, "Boss ally provisioned: ..." log lines for short allies, walk-ups
    beside Mu Young, eight "[Expedition] ... has joined" notices, at most one low-pots line (Gunnar), and one
    "signed up" line from a party companion. `!expeditions` lists 9 members.
14. Pick "Start the fight!". The log shows the overflow party and its lead. During the fight `expeditionPeak` is 3.
15. Run `!hpmp Gunnar 0`. Expect a down whisper; Gunnar waits beside Mu Young.
16. Clear Balrog. If an ally topped a kill's damage, the log shows "Boss drop owner ... to=Bossfriend".
17. Leave by 1061018 and the portal. Expect each ally to say "good fight", walk west and vanish; Gunnar leaves when
    you arrive. `expeditionLeases` is 0 and the Crew window shows the three allies benched.

Run 2, too late: open the picker with less than 2 minutes on the clock. Every ally reads "cannot reach me before the
list closes.".

Run 3, Start before arrival: call one ally and press Start at once. Expect its turned-back whisper and
`expeditionLeases` 0.

Run 4, logout before Start: call allies, let them sign up, log out. Expect "[Expedition] Orrin has left the
expedition." and the walk out. Log back in.

Run 5, no human left: in the fight run `!hpmp 0` and do not press OK. Expect the allies to leave by 1061018, a "next
time" line and `expeditionLeases` 0.

Run 6, two owners (needs ohboy; ohboy is gm 0):
1. From Bossfriend, run `!setgmlevel ohboy 6` and `!setlevel ohboy 70 <3rd-job id> auto`.
2. As ohboy, run `!setlevel <name> 70 <job>` for Brannock, Garrick, Fenna, Ilse and SilentSin; bench Fenna and Ilse.
3. From Bossfriend, run `!setgmlevel ohboy 0`, `!warp 105100100` and `!warphere ohboy`.
4. Bossfriend creates the expedition and calls his three allies. ohboy joins, sees both options, calls Fenna and
   Ilse and registers his party. `!expeditions` lists 15; `expeditionLeases` is 5.
5. Start. Bossfriend logs out mid-fight: one "i'll see this through with you, ohboy."; his allies fight on; his party
   companions park.
6. After the clear the allies leave with ohboy, say goodbye and walk out. `expeditionLeases` is 0.
7. Optional: set `EXPEDITION_BURST_MAX: 4` and restart. Bossfriend calls three allies; ohboy's picker reads "There is
   1 seat left for you."; after ohboy calls one, his picker shows plain lines "could come, but I can take no more of
   yours." Put 12 back.

Regression: set the two Part B switches back to "" and 1, restart, and check that Part A Run 1 shows the same windows;
`!balrogtest quick` still clears; one KPQ and one HPQ desk run still work.

If a check fails: set `COMPANION_BOSS_ALLY_TYPES: ""` and `COMPANION_BOSS_OWNERS_MAX: 1`, then restart.

Known limit (2026-10-08): live Easy Balrog fights are blocked by a client crash in the client mod Kaentake.dll
(+0x98b7, diagnosis in progress; it also happened once outside boss content). Part B's call, arrival, sign-up,
logout and two-owner checks before the fight can still be run.
