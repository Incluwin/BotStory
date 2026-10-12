# BotStory client kit

Turns a stock v83 client into the BotStory client, byte for byte. The kit holds only what
BotStory changed: the WZ directories, the changed and added images (about 48 MB), and the two
bytes of the large-address patch. Everything else comes from files you supply.

Keep this repo private: the stored images are Nexon game data.

## What you supply

| Input | Where it comes from | Checked by |
|---|---|---|
| Community Repack WZ set (17 `.wz` files) | `Community Repack WZs.zip` | `recipe.json` (size and SHA-256 of every file) |
| Stock GMS v83 client files (`MapleStory.exe`, the DLLs, `HShield/`) | a GMS v83 install (`GMSSetupv83.exe`) | `client-files.json` |
| Kaentake (`Kaentake.dll`, `Kaentake.exe`, `Custom.wz`) | build `Incluwin/kaentake`, branch `botstory/rebrand` | not byte-checked (build stamps differ) |

## Build a client

1. Copy the stock v83 client files (DLLs and `HShield/`) into an empty `Game Files` folder.
2. Rebuild the patched files:

   ```
   python tools/client-kit/apply_kit.py --base-wz "<Community Repack>" --base-exe "<v83 install>/MapleStory.exe" --out "<Game Files>"
   ```

   This writes all 17 WZ files (7 patched, 10 copied) and the patched `MapleStory.exe`. It
   stops if an input or a result does not match its SHA-256.
3. Build kaentake. Copy `Kaentake.dll`, copy `Kaentake.exe` as `Launch Game.exe`, and copy
   `src/Custom.wz`.
4. Write `config.ini` next to `Launch Game.exe` (format: kaentake `README.md`, section
   Configuration). Set the server address and port. Leave `patch_url` out to skip the patcher.
5. Compare every file with `client-files.json`. Only the three kaentake files may differ.

## What the 7 patched files change

| File | Change | Made by |
|---|---|---|
| Quest.wz (Act, Check, QuestInfo), Etc.wz QuestCategory | Resident quests in the quest log | server quest export + MapleEdit `living_world_quests.py` |
| Npc.wz (55 added images), String.wz Npc | Resident portraits and names | MapleEdit `living_world_portraits.py` |
| Skill.wz (39 job images), String.wz Skill | skill edits and the repaired skill links | MapleEdit Deploy and its WZ writer |
| Character.wz (1 medal, 5 rings), String.wz Eqp | item edits | MapleEdit Deploy |
| Etc.wz Commodity | cash shop edits | MapleEdit Deploy |
| UI.wz StatusBar | larger quick-slot bar for kaentake's `enlargequickslot` | manual edit |
| MapleStory.exe | large-address-aware flag (2 bytes) | byte patch |

## Update the kit after a client change

After you ship a new client WZ, rebuild the kit on the host and commit `recipe.json`,
`client-files.json` and `data/`:

```
python tools/client-kit/build_kit.py --mapleedit "<MapleEdit>/app" --base-wz "<Community Repack>" --base-exe "<v83 install>/MapleStory.exe" --live "<BotStory Client>/Game Files"
```

`build_kit.py` needs MapleEdit's WZ reader. It fails if a WZ file outside its list differs
from the stock set. Add new patched files to `WZ_FILES` in that case.

## The server

The server is this repo. It needs MySQL and a `.env` next to the repo folder (see
`.env.example`). `launch.bat` builds and starts it. Liquibase creates the database, and the
navigation graphs rebuild themselves on first start.
