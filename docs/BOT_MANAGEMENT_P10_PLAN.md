# P10: Native Crew window (build plan)

Parent plan: `docs/BOT_MANAGEMENT_PLAN.md`, phase P10. Started 2026-10-12 at the user's request ("resume plan and work on p10").

## Goal

A native v83 window that shows the crew and rotates it with the mouse: party block, bench block, select, drag to swap,
and buttons. The NPC-dialog Crew window and the cards stay as they are. Step 7 (protocol 2) draws every crew-wide page
natively too, and from then on the Adventurer's Roster opens the native window on a client that has it (user
2026-10-12); the cards stay NPC dialogs.

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
| 0 | HELLO | `short protocol` (1 or 2), `int dllBuild` (yyyymmdd) | stores the protocol on the `Client`; replies WELCOME |
| 1 | OPEN | none | replies SNAPSHOT; watches the crew while open |
| 2 | CLOSE | none | stops watching |
| 3 | ACTION | `byte verb`, `int ownerVersion`, `string a`, `int aVersion`, `string b`, `int bVersion` | see Verbs |

HELLO is sent once per connection, after the first SET_FIELD of that connection (a channel change is a new
connection). WELCOME answers the lower of the client's protocol and the server's (2): a newer client is served at 2, an older one at its own; a protocol of 0 or less gets no answer (Step 7).

### Server to client (0x17A)

| type | name | body |
|---|---|---|
| 0 | WELCOME | `short protocol` (agreed: min(client, server)), `byte enabled` |
| 1 | SNAPSHOT | see below |
| 2 | CLOSE | none (switch turned off, or the character may no longer use it) |
| 3 | OPEN | none (the Adventurer's Roster: the server asks the client to open; client answers with OPEN) |

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

- Step 7 server done on `feat/crew-window-native` (95d776583), not deployed. The byte layouts are the ones in "Protocol 2"
  below. The native actions page text is `What should my crew do?` (with `Things changed meanwhile.` and a blank line
  on top after a stale click, and the heading `(nothing to do right now)` when the NPC window offers no action).
  Choices the spec left open:
  - Verb ACTIONS uses the full ACTION body (ids empty, versions ignored). It is not refused while an NPC window waits
    for its reply, since a native page never waits on one.
  - At protocol 2, BENCH and SWAP may answer with a YES_NO PAGE (the bench warning) instead of the NPC Yes/No.
  - Close (255) on a native Yes/No runs nothing. The NPC End Chat runs the No action, but every native Yes/No today has
    none.
  - "My lineups..." and "Who should come along?" read the store first. Their PAGE comes after the PAGE_CLOSE of the
    click that asked for it, within 1 s, even while an NPC window waits. A slower read shows nothing, as on NPC.
  - PAGE and PAGE_CLOSE are also not sent once the switch no longer allows the character. A page is drawn only in the
    world: in the Cash Shop or MTS the talk falls back to the NPC window.
  - CLOSE from the client, a switch-off and a logout end the native talk. A warp keeps it.

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

## Step 7: native pages (protocol 2)

User 2026-10-12: "yes build them natively, keep cards as npc". The native window must reach every function of the NPC
Crew window natively before the Roster item may open it. Missing today (More opens the NPC window for them): Remember
this lineup, Formation, Everyone on the bench, Keep my crew fresh, Make room for a friend, Adventurers looking for a
crew, My lineups, Who should come along. Cards (a companion's card, a citizen's hire terms, the card's swap picker)
stay NPC dialogs.

### Approach: LivingTalk draws natively

Every one of those pages, and their Yes/No confirmations, is a `LivingTalk` (`menu(text, options).back(...)`,
`yesNo`, `ok`). A `LivingTalk` gets a native mode: built the same way, it sends a PAGE to the Crew window instead of
an NPC_TALK, and the window's click comes back through the same `handle`/`choose`/`finish` code. Options, labels,
headings, paging (10 per page with "More..."), Back, stamps and every gate are therefore the NPC window's own.

- Native talks live in their own map (`OPEN_NATIVE`), so a native page never blocks an NPC window or the reverse, and
  the NPC rules for NPC windows (one window at a time, 500 ms click gap, script disposal, map check) do not apply.
- `show()` in native mode sends PAGE through a renderer the Crew window registers; a client below protocol 2, no
  renderer, or a NUMBER box falls back to the NPC window (none of the 8 functions uses a number box).
- `finish`/`close` of a native talk sends PAGE_CLOSE unless the answer opened another native page; it never sends
  enableActions (no NPC window was open).
- `CompanionCrewScreens` takes a surface (NPC or NATIVE). On NATIVE: its pages are native talks; `answer` is the
  pink line only (the window shows the crew again by itself and the watcher refreshes it); `render` (Back, "Things
  changed meanwhile.") shows the native actions page with the note on top.
- The native actions page (verb ACTIONS) is the NPC Crew window's `actions(...)` list as a native MENU: Call my usual
  lineup, Remember this lineup, Everyone: follow/wait/gather/rebuff, Formation..., Everyone on the bench..., Keep my
  crew fresh, Make room for a friend..., Adventurers looking for a crew..., My lineups..., Who should come along?
  (each only when the NPC window offers it).
- `CompanionMenus.confirmBench` takes the surface too: Bench, Swap and Make room started natively ask natively.
- A hire list row opens the citizen's hire terms as the NPC window (a card); its Decline returns to the native list.

### Protocol 2

HELLO carries the DLL's protocol (2). The server keeps it and answers WELCOME with `min(client, server)`; a client
at 1 (the trial DLL) gets 1 and is served exactly as before (MORE opens the NPC Crew window). PAGE and PAGE_CLOSE
go only to a connection at 2 or more. The protocol-2 DLL accepts WELCOME 1 or 2 and uses More as the server's
level allows (1: verb MORE, 2: verb ACTIONS). Deploy order: server first, then the DLL (a protocol-1 server answers
a protocol-2 HELLO "not enabled").

Server to client, new types:
```
4 PAGE
  int    stamp          // LivingTalk stamp; every reply names it
  byte   kind           // 0 OK, 1 YES_NO, 2 ACCEPT_DECLINE, 3 MENU
  byte   flags          // bit0 has Prev (OK: an earlier say-page or a parent), bit1 has Next (OK: a later say-page)
  string text           // the window text, with the NPC markup (#e #n #b #k #r #d #g, 
); #L options never
  short  entryCount     // MENU only (0 otherwise), in the NPC window's order for the current menu page
  entry: byte type (0 heading line, 1 option), int selection (option: the NPC selection value; heading: 0), string label
         // "More..." / "Back to the top..." / "Back" are options with their NPC selection values
5 PAGE_CLOSE
  int    stamp          // the page to close (0 = whatever page is shown)
```
Client to server, new op and verb:
```
op 4 PAGE_REPLY
  int    stamp
  byte   action         // as the NPC reply: 1 = OK / Yes / Accept / Next / an option, 0 = No / Decline / Prev, 255 = Close
  int    selection      // the option's selection value; -1 otherwise
verb 11 ACTIONS (ACTION, no ids, no stamp): show the native actions page
```
A reply whose stamp is not the open native talk's is ignored (the newer page stays). Close (255), the window's X and
Esc end the native talk without running anything, as End Chat does.

### Client page view

While a page is shown it replaces the two blocks: the text (word-wrapped, #e bold, colours), then the entries
(headings as plain lines, options as clickable lines with hover, each wrapped to the width), then buttons by kind:
MENU Close; YES_NO Yes / No; ACCEPT_DECLINE Accept / Decline; OK Prev (bit0) and OK or Next (bit1). SNAPSHOTs keep
arriving underneath, so the crew view is current when the page closes.

### Live checks (Step 7)

| # | Check | Pass when |
|---|---|---|
| N1 | More (protocol 2) | the native actions page lists what the NPC Crew window lists under its blocks |
| N2 | Remember this lineup | the same pink line as the NPC option; usual lineup saved |
| N3 | Formation... | native picker with the NPC labels; a pick sets it (companions answer or the refusal line) |
| N4 | Everyone on the bench... | native duty picker with the same labels and rates; a pick assigns all |
| N5 | Keep my crew fresh | the toggle flips; label shows on/off as in NPC |
| N6 | Make room for a friend... | native picker; a contract companion asks Yes/No natively; the pick steps out |
| N7 | Adventurers looking for a crew... | native list; a row opens the NPC hire terms; Decline returns to the native list |
| N8 | My lineups... | native page with the three lineups; call and remember work |
| N9 | Who should come along? | native page per PQ; Call works |
| N10 | Back, More..., Close, stale reply | Back returns to the actions page, paging works, Close returns to the crew view, an old page's click does nothing |
| N11 | Trial DLL (protocol 1) on the new server | unchanged: More opens the NPC Crew window |

### Step 7 live results (2026-10-12 11:34-11:45, int/p9at 59d05c677, test client with kaentake 32842bd, Crewfriend)

Evidence: `docs/agents/evidence/cua/20261012-1134*` to `20261012-1145*`. HELLO 2 got WELCOME 2 enabled 1. No
crew-window or LivingTalk WARN/ERROR in the server log.

| # | Result | Seen |
|---|---|---|
| N1 | PASS | More drew "What should my crew do?": Remember this lineup, the four orders, Formation..., Everyone on the bench..., Keep my crew fresh (off), Make room for a friend..., My lineups..., More... -> Who should come along?, Back to the top... (the NPC rules: no Usual with nobody of it at camp, no hire list with the roster full) |
| N2 | PASS | "Your usual lineup: Casse, Lioren, Rurik, Halvard, Dorran." |
| N3 | PASS | native picker Stay close / Give me room / Spread out / Keep to my left / Keep to my right; Spread out: Casse "ok, spreading out." |
| N4 | PASS (drawn) | Train (~19% EXP/h), Farm meso (~477k meso/h together after upkeep), Gather, Sell the haul, Run a stall, Rest; no pick (it would move the whole bench; unit tests cover it) |
| N5 | PASS | on (label "(on)" and the server's line), then "Keep my crew fresh is off." |
| N6 | PASS (drawn) | "Who steps out for your friend?" with the five, long rows wrapped; Never mind closed; no pick (the contract Yes/No is covered by unit tests) |
| N7 | not offered | the roster is full (10 of 10), so neither window offers the hire list |
| N8 | PASS | "Your Hunting lineup: Casse, Lioren, Rurik, Halvard and Dorran." then the page shows it; Call: "Everyone in your Hunting lineup is out already." |
| N9 | PASS (drawn) | HPQ / EPQ suggestions with "Call Kestrel for HPQ/EPQ"; no call (the party is full) |
| N10 | PASS | Back returned to the actions page, More.../Back to the top... paged, Close returned to the crew view |
| N11 | unit tests | NativeCrewWindowTest.aProtocolOneClientNeverGetsAPageAndItsActionsAndRepliesAreIgnored |

## Open decisions

Decided 2026-10-12 (user): hotkey `U` ("keep U"; it works only while unbound in the player's own key map); the Roster
item opens the native window on a protocol-2 client ("yes roster opens native"); the window ships to friends with
CREW_WINDOW_NATIVE "all" ("ship to friends"). None is open.
