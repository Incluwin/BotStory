# Server mods and the client feature handshake (P5)

P5 of the mod intake plan (`docs/MOD_INTAKE_PLAN.md` on `feat/mod-intake`): a switch per server mod, and a login
handshake through which the server may veto or require the kaentake client's features (P4's `feature_list.h`
registry, kaentake `feat/feature-toggles`). User decision 2026-10-17: the player switches client features; the server
may veto or require them at login.

## Server mods

- `server.mods.ServerMods.ALL` lists every server mod (`ServerMod`: id, title, default, boot only). A ported mod adds
  one line there and asks `ServerMods.get().isOn(MOD)` before it acts.
- config.yaml `mods.SWITCHES`: `<id>: on|off` (also true/false, yes/no), read at boot (`ServerMods.boot()` logs
  `Server mods: ...`). A missing line = the mod's default; a line naming no mod is logged and ignored; any other
  value fails the boot with the key's name.
- An environment variable `MOD_<ID>` (`MOD_FUSION_ANVIL=on`, from `.env` or docker-compose) wins over the config.yaml
  line; the boot line names it (`fusion_anvil on (MOD_FUSION_ANVIL)`). A blank one is ignored, any value other than
  on/off fails the boot.
- `!mods` (GM 6) lists the switches and the feature policy; `!mods <id> on|off` flips one until the next restart,
  except a boot-only mod, which connected clients depend on.
- The first server mod is `client_feature_policy` (default on): the handshake answers from `mods.FEATURES`. Off,
  every answer is `allow`, which also lifts a veto a client kept from an earlier login.
- `fusion_anvil` and `botstory_wz` (both default off, docs/SETUP.md "2. The game client") are for the BotStory
  host's own client. The handshake cannot replace them: the shipped DLL reports only after the first SET_FIELD of a
  connection, and that SET_FIELD already carries every equip.
  - `fusion_anvil` (boot only): every equip ends with its Fusion Anvil transmog id
    (`PacketCreator.addEquipTail`), which only the BotStory kaentake DLL reads. The Fusion Anvil item works only
    with it on.
  - `botstory_wz`: the Evolving Ring grant (new characters, and every login) and the Hunter's Codex move to -149.
    Both need BotStory's WZ files.

## Feature policy (`mods.FEATURES`)

One line per kaentake feature id: `allow` (the player's own switch decides), `deny` (the client makes it inert) or
`require` (the player is told when the client lacks it), optionally followed by a minimum version (`require 2`).

| Line | Client's feature | Answer | Player is told |
| --- | --- | --- | --- |
| none, not server_gated | any | allow | - |
| none, server_gated | any | `SERVER_GATED_DEFAULT` (shipped: deny) | - |
| allow N | version < N | deny | - |
| deny | active, LIVE | deny: inert now | - |
| deny | active, not LIVE | deny: kept in `inert=`, off from the next start | chat line |
| require | off (player's switch) | require | popup: turn it on in the Features page |
| require | vetoed earlier | require: the veto is lifted | popup: back at the next start |
| require N | version < N | require | popup: update the game |
| require | not in the list | - | popup: update the game |
| allow / require | attached but inert | allow: active again | - |
| allow | vetoed earlier (not attached) | allow: veto lifted | chat line: back at the next start |

Core modules (`FEATURE_CORE`) never change, whatever the line says. Shipped lines: `free_addy` and
`ext_chain_lightning` (both server_gated: damage/speed caps, Chain Lightning range) `allow`, matching what the shipped
client does today; any other server_gated feature `deny` until a line names it. `!mods feature <id>
allow|deny|require [version]|unset` changes a line until the next restart and every online client that reported
hears its new answer at once. `!mods client <player>` shows what that client reported and the answer it gets now.

## Protocol

Modelled on the Crew window's HELLO/WELCOME (`docs/BOT_MANAGEMENT_P10_PLAN.md`). Little-endian; a string is a
`short` length and the bytes. Each packet leads with its protocol (now 1); bytes after a well-formed body are
ignored, so a later protocol can append. Server: `server.mods.features.FeaturePackets` (`FeaturePacketsTest`);
client: kaentake `src/featureproto.h` (`test/featureproto_test.cpp`, same bytes).

Client -> server, Recv opcode `CLIENT_FEATURES` 0x118:

```
byte  op          0 REPORT (after the first SET_FIELD of each connection; answered), 1 APPLIED (after a POLICY)
short protocol
int   dllBuild    yyyymmdd
short count       <= 255
count x { string id (<= 64 bytes), string title (<= 128), short version,
          byte flags (1 core, 2 server_gated, 4 live), byte state (1 attached, 2 active, 4 vetoed: inert= names it) }
```

Server -> client, Send opcode `CLIENT_FEATURES` 0x17B, only in answer to a REPORT (or after a `!mods` change, to a
connection that reported):

```
byte  type        0 POLICY
short protocol    min(the report's, the server's)
short count
count x { string id, byte decision (0 allow, 1 deny, 2 require) }
```

A client that never sends a REPORT (any DLL before this one) is never sent a POLICY and runs exactly as before. A
REPORT that does not decode is dropped and logged (throttled). The client reads a POLICY on the game thread, carries
it out, and sends APPLIED with its new states; the server keeps the latest list on the connection (`Client
.getClientFeatures()`) and logs one line per REPORT (`Client features of X: ... denied [...]; lacking [...]`) and per
APPLIED (`... applied: n of m active, inert [...]`).

## Client side (kaentake `feat/feature-handshake`)

- `server_packets` (core, `serverpackets.cpp`) owns the one `CClientSocket::ProcessPacket` hook. The Crew window
  registers its 0x17A reader and HELLO-after-SET_FIELD with it instead of hooking ProcessPacket itself, so no two
  modules hook the same bytes.
- `featurehandshake.cpp` sends the REPORT, applies the POLICY with `features::SetActive` (LIVE modules) or
  `features::SetVetoed` (config.ini `[features] inert=`, for modules that cannot go inert while the game runs), then
  sends APPLIED. Its log lines go to `kaentake_diag.txt` (`[handshake] ...`).
- `inert=` now holds the server's vetoes for the next start: a LIVE module there attaches inert, any other one does
  not attach. An allow or require from the server takes the module out again.
- `feature_list.h` rows carry a `version` (all 1 now); raise it when a module's behaviour changes.

## Deploy order

Server first. An older server does not know opcode 0x118: it counts the REPORT as one malformed packet per
connection (three in ten seconds close a session, so one does not) and logs a security warning, and never answers,
so the client keeps its own switches. Then the DLL.
