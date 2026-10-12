# P10: Native Crew window (build plan)

Parent plan: `docs/BOT_MANAGEMENT_PLAN.md`, phase P10. Started 2026-10-12 at the user's request ("resume plan and work on p10").

## Goal

A native v83 window that shows the crew and rotates it with the mouse: party block, bench block, select, drag to swap,
and buttons. It replaces nothing. The NPC-dialog Crew window, the cards and the Roster item stay as they are. The
native window covers the everyday actions and hands every other page to the NPC dialog ("More...").

Exit (from the parent plan): zero client crashes across 2 h with 2 friends, and every action has the same result as in
the NPC dialog.

## Safety rules

1. The server sends opcode 0x17A only to a connection that sent HELLO. A client without the new DLL never sees it.
2. The DLL never opens the window until the server's WELCOME says `enabled=1` for this character.
3. Server switch `living_world.CREW_WINDOW_NATIVE`: `off` (default in code), `gm` (GM characters only), `all`.
   Turning it off sends CLOSE to every open window and makes the hotkey a no-op again.
4. DLL switch: `config.ini` `[config] crew_window=0` skips every P10 hook (the DLL behaves as today).
5. The receive hook parses with explicit bounds checks inside `__try/__except`. A bad packet is dropped and logged,
   never thrown into the game.
6. The DLL version guard is enabled (`kExpectedImageSize`, `kExpectedTimeDateStamp` of the shipped LAA exe), because
   P10 adds hooks at fixed addresses.
7. Nothing ships to friends without the user's OK. Local tests use a test copy of the client (no `patch_url`).

## Protocol

Server terms: the client sends Recv opcode `CREW_WINDOW` = 0x117. The server sends Send opcode `CREW_WINDOW` = 0x17A.
Little-endian. A string is the Maple string: `short length` + bytes (the server's default charset, as every Maple
string). `version` fields are the low 32 bits of `CompanionViews` versions.

### Client to server (0x117)

| op | name | body | server does |
|---|---|---|---|
| 0 | HELLO | `short protocol` (=1), `int dllBuild` (yyyymmdd) | stores the protocol on the `Client`; replies WELCOME |
| 1 | OPEN | none | replies SNAPSHOT; watches the crew while open |
| 2 | CLOSE | none | stops watching |
| 3 | ACTION | `byte verb`, `int ownerVersion`, `string a`, `int aVersion`, `string b`, `int bVersion` | see Verbs |

HELLO is sent once per connection, after the first SET_FIELD of that connection (a channel change is a new
connection). A HELLO with an unknown protocol gets WELCOME `enabled=0`.

### Server to client (0x17A)

| type | name | body |
|---|---|---|
| 0 | WELCOME | `short protocol` (=1), `byte enabled` |
| 1 | SNAPSHOT | see below |
| 2 | CLOSE | none (switch turned off, or the character may no longer use it) |
| 3 | OPEN | none (reserved: the server asks the client to open; client answers with OPEN) |

SNAPSHOT:
```
int    ownerVersion
byte   seats            // world.slots().occupied(owner)
byte   partyCap         // RosterService.PARTY_CAP (6)
byte   benchSeats       // ROSTER_MAX - party block size
string seatsNote        // "" or "(1 seat kept)"
byte   flags            // bit0 usual lineup callable, bit1 someone out (orders apply)
byte   partyCount, then partyCount rows
byte   benchCount, then benchCount rows
row:
  string personaId      // "halvard", "hire:1234", "guest:5678" (guest rows only open their hire terms)
  int    rowVersion     // CompanionViews.version(owner, persona) (0 for a guest)
  string name           // "Hadrik"
  string levelJob       // "Lv31 Fighter"
  string detail         // the NPC row text after "Name, LvN Job - ": "Perion - HP 90%", "train @ Ant Tunnel I - 12% EXP banked"
  byte   hpPercent      // 0-100, 255 when not loaded
  byte   rowFlags       // bit0 CALL, bit1 BENCH, bit2 SWAP_OUT, bit3 SWAP_IN, bit4 GUEST, bit5 COMMITTED
```
`detail` is built with the same static methods as the NPC rows (`partyLine`, `benchLine`, `errandLine`), so both
windows always say the same thing. Row flags mirror the options the companion's card offers right now.

### Verbs (ACTION)

Each verb does exactly what the NPC dialog does for the same click, through the same code. Follow-up windows (the
swap picker when the party is full, the "won't sit at camp for long" Yes/No, the leader-cap OK) open as the same NPC
windows. The result line is pink text, as the Crew window's `act()` shows it, and a fresh SNAPSHOT follows.

| verb | name | same as | stamp |
|---|---|---|---|
| 1 | CARD a | clicking the row in the Crew window (card, or a guest's hire terms) | none |
| 2 | CALL a | card "Come join me" (`join`, with its canSeat gate) | a's row version |
| 3 | BENCH a | card "Take a break at camp" (`mayLeave`, committed, `confirmBench`) | a's row version |
| 4 | SWAP a=out b=in | a swap-picker pick (`confirmBench(out)`, `roster.swap`) | both row versions |
| 5 | USUAL | "Call my usual lineup" | owner version |
| 6 | MORE | opens the NPC Crew window (the player's notes) | none |
| 7-10 | FOLLOW, WAIT, GATHER, REBUFF | "Everyone: follow me / wait here / gather up / rebuff" | none |

A stale stamp answers "Things changed meanwhile." (pink) and a fresh SNAPSHOT, and does nothing else. A verb the row
flags do not allow is treated the same way.

### Live updates

While a window is open, the server rebuilds the snapshot on companion-ops every 1 s and sends it only when the bytes
changed (HP, map, travel ETA and errands are read at render time and are not signalled). It also sends one right
after each action's answer. Watching stops on CLOSE, disconnect, the switch turning off, or 30 min without an OPEN or
ACTION.

### Rate limit

New `PacketFamily.CREW` for 0x117 at 10 packets/s (`PACKET_CREW_RATE_PER_SECOND`), so a stuck client cannot flood
companion-ops.

## Client (Kaentake)

- `crewwnd.cpp` (new):
  - `CClientSocket::ProcessPacket` detour (0x004965F1). Opcode 0x17A is parsed and consumed. Every other opcode goes
    to the original. After the original handles SET_FIELD (0x7D) on a connection without HELLO, HELLO is sent.
  - `CClientSocket::Connect` (bypass.cpp hook) resets the per-connection HELLO flag.
  - `CWndMan::ProcessKey` detour (0x009E40F1). The hotkey toggles the window only when: key down without repeat, the
    focus is the window manager itself (no edit box, so never while chat has focus), the key is unbound in the
    player's key map (`CFuncKeyMappedMan` at [0xBED5A0], 89 slots of 5 bytes at +4), and WELCOME said enabled.
    Otherwise the key goes to the game unchanged.
  - The window is a `CUIWnd` (it never takes keyboard focus). Background `UI/UIWindow.img/UserList/backgrnd`, or the
    Custom.wz art once made. Rows are drawn with `IWzCanvas::DrawText`, `DrawRectangle` (HP bar, selection).
  - Mouse: click selects (one party row and one bench row at most), double-click opens the card, drag a bench row
    onto a party row = SWAP, drag a party row onto the bench block = BENCH, drag a bench row onto the party block =
    CALL. Wheel scrolls the bench block.
  - Buttons: Call, Bench, Swap, Card, Usual, More. Disabled by the row flags.
  - Teardown on `CWvsContext::OnLeaveGame` (0x00A041FF) and `ClearFieldUI` (0x00A04920). CLOSE is sent when the
    player closes it.
- Version guard values from the shipped LAA exe.

## Steps

1. **Server** (branch `feat/crew-window-native`): opcodes, `PacketFamily.CREW` + config, `Client` protocol field,
   `CrewWindowHandler`, `NativeCrewWindow` (snapshot builder, watcher, verbs routed through the screens' own code),
   switch, tests (codec bytes, HELLO gating, stale stamps, each verb against the NPC path's result, rate family).
2. **DLL probe** (kaentake `feat/crew-window`): receive hook + HELLO + WELCOME log, hotkey, empty window, teardown.
   Local test copy against the live server once Step 1 is deployed with the switch at `gm`.
3. **DLL window**: snapshot rendering, selection, buttons, drag, wheel, live updates.
4. **Art**: Custom.wz background titled for the crew and button sprites (UserList style).
5. **Live checks** (two clients, GM test accounts): each verb vs the NPC dialog, full party, confirm Yes/No,
   stale click, channel change, relog, stale-DLL client, switch off mid-session.
6. **Soak**: 2 h on the user's client, rollback rehearsal through the manifest, side-by-side video.
7. **Ship** to friends: user's OK, manifest regenerated, client kit rebuilt (`tools/client-kit`).

## Open decisions

1. Hotkey: default `U` (unbound in the default key map). The key works only while unbound in the player's own map.
2. Roster item: keep it opening the NPC Crew window (default), or open the native window when the client has it.
