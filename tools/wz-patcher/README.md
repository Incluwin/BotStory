# BotStory WZ patcher

Adds BotStory's own client content to the WZ files of a v83 client. BotStory runs on either
base WZ set:

| Base | Files | How to get it |
|---|---|---|
| Clean Cosmic client | the 12 files in `cosmic-wz/` of [P0nk/Cosmic-client](https://github.com/P0nk/Cosmic-client), plus `Base.wz`, `Effect.wz`, `Morph.wz`, `Sound.wz`, `TamingMob.wz` from the GMS v83 setup | install `MapleGlobal-v83-setup.exe`, then copy the `cosmic-wz` files over the installed ones |
| Community Repack | the 17 files of `Community Repack WZs.zip` | the repack download |

Plain GMS v83 WZ files (the setup without the Cosmic files) do not work: the Cosmic server
spawns NPCs, maps and items that only the Cosmic files have. `check` reports them as missing.

The pack the patcher applies is Nexon game data, so it is not in this repository. On the
BotStory host it lives in `FriendServer/patch-server/wz-pack/`, which the patcher uses by
default; elsewhere pass `--pack <folder>` or set `BOTSTORY_WZ_PACK`. Never commit a pack
(`tools/wz-patcher/pack/` is git-ignored). Friends never need it: the launcher downloads the
patched files (see below). Without a pack, `residents` still gives any client the Residents'
portraits and quest log, made from that client's own files (see "Resident portraits and quest log").

## Patch a client

Python 3.8 or newer, standard library only.

```
python tools/wz-patcher/botstory_wz.py patch --client "<client folder>"
python tools/wz-patcher/botstory_wz.py check --client "<client folder>"
```

`patch` changes the client folder in place. The original files move to
`<client folder>/botstory-wz-backup/<date-time>/`. Run it again at any time: a file that already
has the content is left alone. On Windows, `patch-client.bat "<client folder>"` runs both steps.

Options:

- `--out <folder>` writes the patched files to a new folder and leaves the client untouched.
  Add `--copy-unchanged` to copy the other WZ files too, so `<folder>` is a complete WZ set.
- `--exe "<v83 install>/MapleStory.exe"` also sets the large-address-aware flag on the stock
  GMS v83 `MapleStory.exe` (2 bytes). BotStory needs it: the client runs out of address space
  without it. Without a pack, `botstory_wz.py exe --exe "<v83 install>/MapleStory.exe"` sets
  the same two bytes (the original goes to `botstory-wz-backup/` next to it).

`check` confirms every BotStory image is present and, when it finds the server's WZ XML (the
repo's `wz/` folder, or `--server-wz`), that the client has every image the server knows.

The rest of a BotStory client (kaentake's `Kaentake.dll` and `Launch Game.exe`, `config.ini`,
the stock DLLs) is listed in `client-files.json` with the source of each file.

## Resident portraits and quest log

Each Resident's NPC-window portrait and the living-world quests in the client's quest log are not
shipped: they are made on your machine from the server's YAML and database and your client's own
WZ files, so every server gets its own (set `living_world.RESIDENT_LOOK_SEED` in `config.yaml` for
your own faces and hair). From the server directory, after the server has created the Residents (a
boot with `living_world.ENABLED`):

```
tools\living-world-client.bat "<client folder>"
```

It runs two exports from `target/Cosmic.jar` with the server's database settings
(`ResidentPortraitExportMain`: each Resident's look and NPC id, kept in
`scripts/config/living-world/portrait-npc-ids.yaml`; `QuestLogExportMain`: each quest's client id
and text), then `botstory_wz.py residents`, which renders every portrait from the client's
`Character.wz` and writes four files:

| WZ file | Content |
|---|---|
| Npc.wz | 9970000+: one NPC per Resident, its rendered look; 9970999: the invisible quest-marker hitbox |
| String.wz `Npc` | the Residents' names |
| Quest.wz `QuestInfo`, `Check`, `Act` | the living-world quests (ids 30000-32767; no stock quest uses them) |
| Etc.wz `QuestCategory` | the "Residents" group of the quest list (area 31) |

In place by default (originals in `<client folder>/botstory-wz-backup/<date-time>/`); `--out
<folder>` writes the four files elsewhere, `--preview <folder>` also saves each portrait as a PNG.
Run it again after `RESIDENT_LOOK_SEED`, a Resident's gear or the quests change, and give your
players the new files: the server opens a Resident's NPC window with its id, so ship the client
files before restarting the server with new ids. Python standard library only, like the rest of
the patcher. On the BotStory host, MapleEdit's `living_world_portraits.py` and
`living_world_quests.py` do the same through this code (`residents.py`) for the live client.

## Cryssea's look (Masteria client patch, held)

Cryssea (NPC 9010018), who runs the iTCG card swap in New Leaf City, has a name in every base's String.wz but no
Npc.wz image anywhere, and a client sent an NPC without an image closes. `masteria` gives her one in Custom.wz:
`Npc/9010018.img` with an `info/link` to another NPC's image (default 1052122, Tina; `--look <npc id>` picks
another), which kaentake's Custom.wz overlay serves where Npc.wz has nothing:

```
python tools/wz-patcher/botstory_wz.py masteria --client "<client folder>" [--out "<folder>"]
```

In place by default (the original goes to `botstory-wz-backup/`), idempotent. To ship it: run it on the live
`Game Files` (and copy the result to kaentake's `src/Custom.wz`), rebuild the pack ("Rebuild the pack" below) so
the launcher hands it out, and only then turn server mod `cryssea` on (`config.yaml` `mods.SWITCHES`, or
`MOD_CRYSSEA=on`) and restart: the server places her when New Leaf City loads. See
`docs/ITCG_MASTERIA_PLAN.md` section 5.

## Play through the friends' launcher

`Launch Game.exe` keeps a client in sync with the host's patch server. A client on the clean
Cosmic WZ set must say so in its `config.ini`:

```
[config]
patch_url=http://<host address>:8485
base=cosmic
```

With `base=cosmic` the launcher keeps the Cosmic WZ files: the eleven files that differ between
the two sets (Character, Effect, Item, Map, Mob, Npc, Reactor, Skill, Sound, String, UI) come
from the host's `bases/cosmic/` set, which is this patcher's output, and everything else from the
main manifest. A client patched here downloads nothing. A clean Cosmic install with `base=cosmic`
and no patching downloads the patched files from the host (about 380 MB), so players need neither
Python nor this repository. Without `base` (or with `base=repack`) the client is a Community
Repack client, and a Cosmic client would get the Repack WZ files instead (about 7 GB).

`base` needs the `Launch Game.exe` shipped on 2026-10-17 or later; older launchers ignore it.

## What the pack adds

| WZ file | Content | Kind |
|---|---|---|
| Quest.wz `Act`, `Check`, `QuestInfo` | Resident quests (ids 30000-32767) in the quest log and Quest Helper | BotStory |
| Etc.wz `QuestCategory` | the "Residents" quest category (area 31) | BotStory |
| Etc.wz `Commodity` | cash shop edits | BotStory |
| Npc.wz (55 images, 9970000+), String.wz `Npc` | Resident portraits and names | BotStory |
| Skill.wz (39 job images), String.wz `Skill` | skill edits and repaired skill links | BotStory |
| Character.wz (1 medal, 5 rings), String.wz `Eqp` | item edits | BotStory |
| UI.wz `StatusBar` | the large quick-slot bar for kaentake's `enlargequickslot` | BotStory |
| Custom.wz | kaentake's overlay: Crew window, quest markers, mob HP bar, skill cooldown numbers, fusion anvil, quick-slot widths, the photo chair | BotStory |
| Character.wz (303 equips), String.wz `Eqp` (302 names) | equips the server's WZ XML has and the clean Cosmic client lacks (taken from the Community Repack, which has them) | server |

How each image is applied:

- If the base image is byte-identical to the Community Repack image BotStory was built on, the
  BotStory image is written as is. A patched Community Repack is byte-identical to the shipped
  BotStory client.
- Otherwise BotStory's changes are applied node by node to the base's own image
  (the pack's `edits/*.json`), so the base keeps its own content. On the clean Cosmic client this
  happens for `String.wz` `Eqp` and `Npc`.
- An image the base lacks is added. "server" images are only ever added, never replaced.

## Known differences that are not errors

- The Community Repack lacks five Cosmic world maps (`WorldMap032`, `210`, `211`, `220`,
  `230`). The server never sends world maps; in those regions the world map window is empty.
  `check` lists them as UI only.
- Some edits exist only in the server's WZ XML and in no client (secondary stat requirements
  set to 0 on about 980 equips, some skill values, a few names). The server applies them. The
  client still shows the stock values.

## Rebuild the pack (maintainers)

After a client WZ ships (MapleEdit Deploy, a Custom.wz change), rebuild the pack on the host.
`build` writes the pack to `patch-server/wz-pack/` (or `--pack`) and this folder's
`client-files.json`; commit only `client-files.json`:

```
python tools/wz-patcher/botstory_wz.py build ^
    --repack "<Community Repack>" ^
    --live "<BotStory Client>/Game Files" ^
    --cosmic "<clean Cosmic WZ folder>" ^
    --stock-client "<v83 install>"
```

`build` compares the live client with the Community Repack image by image, writes the changed
and added images and their node edits, and adds the server images the clean Cosmic client lacks
(from the repo's `wz/`, or `--server-wz`). Then check both bases:

```
python tools/wz-patcher/botstory_wz.py patch --client "<Community Repack>" --out <tmp1>
python tools/wz-patcher/botstory_wz.py patch --client "<clean Cosmic WZ folder>" --out <tmp2> --copy-unchanged
python tools/wz-patcher/botstory_wz.py check --client <tmp2>
```

Every file in `<tmp1>` must be byte-identical to the live client. Unit tests:
`python -m unittest discover -s tools/wz-patcher/tests`.

Then refresh the patch server's Cosmic set, so `base=cosmic` clients get the new content too
(host only; the script lives outside this repository, see its README):

```
python FriendServer/patch-server/build_base.py --name cosmic --clean "<clean Cosmic WZ folder>"
```

`residents.py` is MapleEdit's portrait renderer and quest-log writer (Incluwin/MapleEdit,
AGPL-3.0), moved here, with the image work in plain Python: compositing is Pillow's
`alpha_composite`, byte for byte (`tests/test_residents.py` checks it against Pillow when Pillow is
installed). `wzlib/` is MapleEdit's WZ reader and writer (Incluwin/MapleEdit, AGPL-3.0), adapted. It adds
a pure-Python AES for the key stream, keeps one-byte strings with code-page characters intact,
parses `Shape2D#Convex2D`, and points type-2 (shared-name) directory entries at their name's
new position.
