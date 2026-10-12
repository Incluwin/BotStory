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
  byte   rowFlags       // bit0 CALL, bit1 BENCH, bit2 SWAP_OUT, bit3 SWAP_IN, bit4 GUEST, bit5 COMMITTED,
                        // bit6 OUT (out with the owner), bit7 CAN_COME (at camp, can come at once)
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
6. **Soak**: replaced (user, 2026-10-12) by a measured 30-minute memory run on the test client, then the user's normal play; rollback rehearsal through the manifest, side-by-side video.
7. **Ship** to friends: user's OK, manifest regenerated, client kit rebuilt (`tools/client-kit`).

## Status (2026-10-12)

- Step 1 done: server branch `feat/crew-window-native` (2bec49767). Deviations from this spec, all deliberate:
  clicks run on the player's packet thread like an NPC click (roster writes and snapshot reads stay on
  companion-ops); CARD opens the card without Back; a click while an NPC window waits for its reply only refreshes;
  orders and USUAL are refused when their snapshot flag is off; SWAP is allowed when the one out may make room
  (SWAP_OUT) or the one at camp may swap in (SWAP_IN); an idle window (30 min) stops updating without CLOSE; the
  runtime command `!world crewwindow off|gm|all` was added for testing.
- Steps 2-4 done on kaentake `feat/crew-window` (aecbe7c, 7daf8d2): hooks, window, `MAPLE CREW` background in
  Custom.wz (`UI/UIWindow.img/CrewWnd/backgrnd`, 312 x 430, built by `tools/make_crew_art.py`), parser test
  `test/crewproto_test.cpp`. Self-test drawing checked on a private client copy: rows, HP bars, truncation,
  selection, enabled/disabled buttons, close button, title-bar drag, hotkey toggle.
- RE facts found on the way: the client reports a key release to `CWndMan::ProcessKey` as WM_KEYDOWN with lParam
  bit 31 set; game keys come from DirectInput, so a posted WM_KEYDOWN never reaches the hotkey (use SendInput);
  `CUIWnd::OnCreate` takes 2 arguments (the DLL's header declares 3); the real CWnd RTTI is 0x00BF1608 (the
  header's 0x00C6194C is wrong); `ClearFieldUI` runs only for the Cash Shop and MTS, so the window survives portals.

- Review fixes after the int/p9aq deep review (all LOW): only ids in the owner's own roster are looked up (a made-up
  "hire:05" never reaches HiredHands); guest ids only as the server writes them; HELLO once per connection (a repeat is
  neither answered nor logged); clicks and OPEN from the Cash Shop or MTS are not acted on; a snapshot is never sent
  after the CLOSE of a switch change; a native click clears the card's remembered NPC Crew window, so no stale Back;
  rows carry OUT and CAN_COME, so the client offers Swap only for pairs a swap picker would list
  ((out SWAP_OUT and in CAN_COME) or (in SWAP_IN and out OUT)).

## Live checks (Step 5)

Setup: server branch deployed with the switch off; private client copy with the test DLL (`crew_selftest` removed);
GM test account Crewfriend; the user's own client keeps the shipped DLL.

| # | Check | Pass when |
|---|---|---|
| L1 | Switch off | DLL log shows WELCOME enabled 0; U passes to the game; server log `[crew-window] HELLO ... enabled=false` |
| L2 | `!world crewwindow gm` | WELCOME enabled 1; U opens the window; rows and text equal the Roster item's Crew window |
| L3 | Call a benched companion with a free seat | pink line as in the card; the row moves to the party block "on the way" |
| L4 | Bench one out | the bench Yes/No appears where a contract applies; the row moves to the bench block |
| L5 | Swap by buttons, then by dragging a bench row onto a party row | one swap each, same result as the swap picker |
| L6 | Call with a full party | the card's swap picker opens |
| L7 | Double-click a row | the companion's card opens |
| L8 | More | the NPC Crew window opens |
| L9 | Follow / Wait / Gather / Rebuff | the companions answer in party chat |
| L10 | Usual | the usual lineup is called |
| L11 | Stale click | Unit tests only (NativeCrewWindowTest): the 1 s watcher sends fresh stamps before a tester can click an old row |
| L12 | Channel change with the window open | the window stays and keeps updating |
| L13 | Relog | HELLO again, U works again |
| L14 | The user's client with the shipped DLL | no HELLO from it in the server log, nothing changes |
| L15 | `!world crewwindow off` with the window open | the window closes; U passes to the game |

### Live results (2026-10-12, int/p9aq 214be98e7, test client with kaentake 9700fea, Crewfriend)

Evidence: `docs/agents/evidence/cua/20261012-0815*` to `20261012-0828*`.

| # | Result | Seen |
|---|---|---|
| L1 | PASS | WELCOME enabled 0; `HELLO from Crewfriend: protocol 1, DLL build 20261012, enabled=false`; U did nothing |
| L2 | PASS | `!world crewwindow gm` sent WELCOME enabled 1 to the open client; U opened the window; row text equals the NPC Crew window |
| L3 | PASS | Call Casse with a free seat: "Casse is on the way (~3 min)."; row moved to the party block "on the way (2:57)" |
| L4 | PASS | Bench Rurik (no contract, so no Yes/No): "Rurik heads back to camp to train."; row moved to the bench block |
| L5 | PASS | on int/p9ar 048092d9f (08:34): Swap button with Kestrel + Rurik: "Kestrel heads back to camp to train. Rurik is on the way (~20 s)."; drag Halvard onto Yara: "Yara heads back to camp to train. Halvard is on the way (~60 s)."; one swap each |
| L6 | PASS | Call Casse with 6/6: her swap picker "your party's full. who should i take over from?" listing all five |
| L7 | PASS | double-click Casse opened her card |
| L8 | PASS | More opened the NPC Crew window |
| L9 | PASS | Follow / Wait / Gather / Rebuff each got a party-chat answer; greyed while everyone was on the way (same anyoneOut rule as the NPC window) |
| L10 | PASS | Usual and the NPC "Call my usual lineup" both answered "No room for Rurik." |
| L11 | unit tests | see above |
| L12 | PASS | channel 1 -> 2 in Henesys: HELLO + WELCOME again, the client re-sent OPEN, the window stayed and updated to "Ellinia" after a warp |
| L13 | PASS | client restart + login: HELLO, WELCOME enabled 1, U opened the window |
| L14 | PASS | the shipped DLL (10-10) has no crew code; only the test client ever sent HELLO |
| L15 | PASS | `!world crewwindow off` closed the open window (WELCOME enabled 0); U then did nothing |

Also seen: the window survives warps (Forest of Poison Haze -> Henesys -> Ellinia). Cosmetic: the stock floating party
HP list is drawn over the Crew window after a party change (a game window, not the DLL's).

### Memory run (2026-10-12 09:17-09:52, int/p9ar, test client, Crewfriend + 5 companions hunting boars)

Sampled every 30 s (`docs/agents/evidence/p10-soak/`). `!heal` every 2 min and a warp between 101030001 and 101040001
every 5 min through `logs/gm-command-queue.txt`, so nothing needed the screen except one U press.

| Phase | Private MB | Virtual MB | Handles | GDI | USER |
|---|---|---|---|---|---|
| Window closed, 5 min | 651 -> 677 (settling after login) | 3720 -> 3736 | 790-806 | 132-133 | 23-25 |
| Window open, first 5 min (same map) | 680 -> 690 | 3750 -> 3752 | 797-803 | 139 | 24-25 |
| Window open, 24 min after the first warp | 633-642, ends 640 | 3745-3746 | 706-800 | 139 -> 141 | 23-24 |

Opening the window cost about 6 GDI objects and about 13 MB of address space once. Private memory and address space
stayed flat for 24 minutes of live updates and 5 map changes. GDI rose by 1 after two of the five warps (cause not
isolated; the closed baseline had no warp). The window was still open and updating at the end.

## Open decisions

1. Hotkey: default `U` (unbound in the default key map). The key works only while unbound in the player's own map.
2. Roster item: keep it opening the NPC Crew window (default), or open the native window when the client has it.
