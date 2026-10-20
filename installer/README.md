# BotStory client installer

A Windows installer for people who run **their own** BotStory server. It adds the BotStory launcher
([BotStory-Kaentake](https://github.com/Incluwin/BotStory-Kaentake)) to a MapleStory v83 client you already
have, pointed at your server. It downloads nothing and connects to nothing else: no game files, no Tailscale,
no patch server, no accounts.

## Before you run it

Bring your own client: MapleStory v83 installed, with a Cosmic or Community Repack WZ set in its folder.
"The game client" in [docs/SETUP.md](../docs/SETUP.md) (step 1) says what that means.

## What it does

1. **Folder.** Pick the folder with `MapleStory.exe`. A folder without it stops here.
2. **Game files.** Says which WZ set the folder has (Cosmic, Community Repack, plain v83 or another) and
   leaves the files as they are. BotStory runs on the Cosmic and the Community Repack sets; for a plain v83
   or other set, it tells you to swap one of those in before you play.
3. **Cleanup.** Removes `HShield`, `ASPLnchr.exe` and `Patcher.exe`, as P0nk's guide does.
4. **Large-address-aware flag** on `MapleStory.exe` (two bytes, only on the stock v83 file, original kept in
   `botstory-backup\`), so long sessions do not run the client out of memory.
5. **Server address**, `127.0.0.1:8484` unless you change it. Written to `config.ini`; a reinstall starts from
   the address already there (unless `/HOST=` or `/PORT=` is given).
6. **Residents (optional).** Point it at your server folder and it offers to run
   `tools\living-world-client.bat` at the end, which makes the Residents' portraits and quest log from your
   server (needs Java and Python; the server must have started once).

The launcher opens its **Features** page first; switch modules on or off there.

**Your server needs `MOD_FUSION_ANVIL=on`** in its `.env` (`setup.bat` sets it; with Docker, set it
yourself): this client always reads the Fusion Anvil field of an item. Leave `MOD_BOTSTORY_WZ` off: the public
WZ files lack the items it unlocks.

Uninstalling removes only the launcher files; the game folder stays.

Tested 2026-10-20 on a v83 client with the Cosmic WZ files and a server with `MOD_FUSION_ANVIL=on`: a new
account and character entered the game, the equipment window drew the starter items, and the server's
feature handshake reported 29 modules with none denied. The wizard was walked page by page on empty, plain
v83 and Community Repack folders, including the port and server-folder checks.

## Silent install

```
BotStory-Setup-1.0.0.exe /VERYSILENT /DIR="C:\Nexon\MapleStory" /HOST=127.0.0.1 /PORT=8484
```

`/DIR` must hold MapleStory v83; otherwise Setup ends with exit code 7 and says why in its log (`/LOG=`).
`/SERVERDIR="<server folder>"` offers the Residents step (interactive installs only). `/CURRENTUSER` installs
without admin rights, with the shortcut and uninstall entry for your user only.

## Building

1. Build BotStory-Kaentake's Release preset (`cmake --preset release-win32`, then
   `cmake --build --preset release-win32`).
2. Install [Inno Setup 6](https://jrsoftware.org/isdl.php).
3. `powershell -ExecutionPolicy Bypass -File installer\build.ps1 -Kaentake <BotStory-Kaentake checkout>`

The installer lands in `installer\dist\`.
