# Day and night and weather

A port of the community release "weather-time-systems" (no licence or author line), cosmetic only (user decision
2026-10-25: cosmetic first, gameplay effects later and one switch at a time).

## What players see

- A 4-hour day from wall time: night (18:00-06:00 in-game) is 2 of every 4 real hours, with dusk and dawn fades.
- 27 regions each roll their own sky every 15 minutes, weighted by the real-world season: clear, rain, snow,
  overcast, storm (with lightning every client in the region sees at the same moment), blizzard, leaves, blossom,
  sandstorm. Rainbows after rain.
- The client tints the map for night, adds a night sky, moon, clouds, fog and rainbow, rain and snow sheets,
  puddles and splashes, settling snow, leaves and petals, wind in falling particles, rain and wind sound, and lights
  street lamps (49 hand-placed ones plus the lamp props maps already have).

Maps without open sky (caves, interiors, underwater) get no falling weather; underwater maps are still tinted.

## Server (`server.weather`, `server.lamp`)

- `WeatherService`: the clock (a pure function of wall time), the regional rolls and the GM overrides. All state is
  in memory; there is no table.
- `WeatherTask` (registered per world in `World`): once a minute rolls what is due and sends each client its
  region's state. `MapleMap.addPlayer` sends it on map entry (snapped, no fade) and `ClientFeaturesHandler` sends it
  when the feature handshake lands, since the first map entry comes before it.
- `WeatherMapVisibility.register` (from `MapFactory`) records which maps have a sky from their backmost background.
- Packets: S->C 0x17C WEATHER_SYNC (28 bytes, `WeatherPackets`) and 0x17D LAMP_PREVIEW (16 bytes, `LampPackets`),
  sent only while the `weather` server mod is on and only to a client that reported the `weather` feature active.
  Companions and citizens never report features, so they are never sent anything and cost nothing.
- Commands: `!weather` (gm2) shows the clock and sky, or forces a time and/or sky (`!weather night rain`,
  `!weather 21:30`, `!weather midnight`, `!weather auto` to hand back; overrides expire on their own). `!lamp` (gm3)
  previews a lamp where you stand and prints its table row for the client's `g_aManualLamps`. The release's `!night`
  is not ported: it duplicated `!weather night` and its name is our stock `!night`.

## Client (Kaentake)

| Module | Default | What |
|---|---|---|
| `weather` | on | the clock, sky, tint, lamps, puddles, splashes, accumulation, sound |
| `weather_wind` | on | wind in falling leaves, petals and snow (a 7-byte cave in the weather builder) |
| `foliage_sway` | off | plants bend in the wind by redrawn frames, up to ~32 MB a map; off, a light translate sway runs |
| `weather_footing` | on, server gated | slippery footing in snow and on ice (writes the client's physics table) |

`weather_footing` changes movement, so it is server gated; `config.yaml` has no `mods.FEATURES` line for it and
`SERVER_GATED_DEFAULT: deny` keeps it off. Allowing it is the first gameplay step.

The art and sound are in `Custom.wz` (`tools/make_weather_wz.py`): ten images at the paths the code loads
(`Effect/Weather*.img`, `Map/Back/MapleMoonSky.img`, `Map/Back/Weather*.img`, `Map/Obj/MapleMoonLamp.img`,
`Sound/Weather.img`) and `Item/Cash/0598.img`, the particle skies' art-pointer items 5980995-5980999. The release
put those into the stock `0512.img`; our Custom.wz overlay adds whole images but not nodes inside a stock one.
0598 is free in every client base and the server has no such items.

## Not ported (gameplay, each its own switch later)

- Night combat: mobs about 9% tougher, +10% damage to players, +15% EXP (`WeatherCombat`).
- Night mob density x1.35. On our config it would do nothing anyway: `USE_ENABLE_FULL_RESPAWN` returns before the
  spawn rate is read, so it would have to scale the per-point cap instead.
- The blizzard's -10 SPEED debuff (`WeatherDebuff`). It would reach bots: their speed reads SPEED buffs, so a
  debuffed bot gets a new movement profile, and CWKPQ's Haste upkeep treats any SPEED buff as Haste.
- Night-only mobs Lv 20-37 in five regions (`NocturnalMobService`).
- The release's optional `MapleMoonLamp2.img` (imported lamp sprites, third-party art). Lamps that use it, and the
  three lamp sprites v83 does not have (index 22 is Ellinia's twelve hand-placed lamps), stay unlit.
