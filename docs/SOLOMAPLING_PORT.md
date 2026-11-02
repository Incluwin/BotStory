# SoloMapling port and retirement (2026-11-01)

SoloMapling was the older ambient-bot layer (`src/main/java/soloMapling`, 168 Java files, no tests): about 650
`Character` clones from character id 2 that shared one hijacked player `Client`, moved by replayed packet recordings,
one thread each, all behind `server.ENABLE_SOLO_MAPLING: false`. The citizen layer (`server.living.population`) had
already replaced most of it. On 2026-11-01 the user chose to port the features nothing else covered and retire the
package. This branch (`feat/solomapling-port`) does both.

## What became of each SoloMapling piece

Everything ported is run by **citizens** on a new presence activity, `CASINO` (`CitizenCasino`), or rides the 20 s
chatter tick. A citizen whose plan rolls CASINO (a small weight per cohort in `cohorts.yaml`, counted only while one of
the switches below is on and a role there suits it) goes to the venue and takes a role: dealer, host, player or seller.
The role holds its re-plans until its shift is over (`CASINO_SHIFT_MIN_MS..MAX_MS`); then it goes on with its day and no
CASINO plan comes for `CASINO_COOLDOWN_MS`. Henesys venues are for Victoria citizens; any citizen can sell NX codes in
the Free Market. The citizens play with their own purses and bags: a dealer buys its chip float from the cashier, a
player buys chips with its own mesos and cashes out what is left, the gachapon citizen pays for its boxes.

| SoloMapling | Now | Switch |
|---|---|---|
| `BlackjackDealerBot`, `BlackjackTable`, `BlackjackRules`, `Deck`, `BlackjackAI`, `CardVisuals` | `casino.BlackjackGame` (rules, pure) + `casino.BlackjackTables` (the live tables at SoloMapling's five Game Park spots). The hole card SoloMapling never dealt (its TODO at `BlackjackDealerBot.java:812`) is dealt face down as the plain Monster Card (4030012) and turned over on the dealer's turn; the dealer peeks under an ace or ten. Citizen players learn hit/stand (`casino.HitStandPolicy`), with SoloMapling's casual rule as the prior. | `CASINO_BLACKJACK_ENABLED` (+ `CASINO_CHIPS_ENABLED`) |
| `DiceBot` | `casino.ChoHanGame` + `casino.DiceTable`: one host, cho (even) or han (odd), paid at evens from the host's chips. | `CASINO_DICE_ENABLED` (+ `CASINO_CHIPS_ENABLED`) |
| `CasinoChipConfig`, Aramia's chip shop, `WzXmlPatcher` | `casino.Chips` + `casino.ChipCashier`: stock NPC 9000055 (Aramia) in Game Park opens a code-built `Shop` that sells the four stamps at 10k/50k/250k/1m and is the one shop that buys them back at that price (`Shop.buysBackAt`). SoloMapling rewrote `wz/Item.wz/Etc/0400.img.xml` on boot for this; that is gone. The client still shows the stamps' stock sell price in the shop window; the server pays the chip value (user's choice, 2026-11-01). No monster, quest, reactor or other shop gives the stamps, so the buy-back creates no mesos; the shop buyback list (`ShopBuyback`) prices a stamp at what its sale paid. | `CASINO_CHIPS_ENABLED` |
| `GameZoneHostBot` | `casino.GameParkBar`: a host citizen greets humans entering Game Park and, when asked, hands over a real drink it bought: Orange Juice 2020009, Grape Juice 2020010, Maple Cola 2022035, Pure Water 2022000. SoloMapling's ids were placeholders (an event Coca-Cola and a second Elixir). | `CASINO_BAR_ENABLED` |
| `DropGameBot`, its spectators and loot pool | `casino.DropGame`: a host in the Henesys Department Store takes 10m or 50m by trade, parties the player, casts Haste, and walks its route dropping items from the ported pools for two minutes; drops vanish in about 1.5 s. Nearby citizens cheer what drops (SoloMapling's spectator lines). | `DROP_GAME_ENABLED` |
| `GachaBot`, `CustomReactor` (VFX) | `casino.GachaShow` + `casino.ReactorShow`: a citizen in Henesys Market pays `GACHA_BOX_PRICE` per box (a sink), a reactor is hit three times and sprays the box's drops, owner-only, which the citizen loots. Real items, paid for (user's choice). SoloMapling's spray created ~10 drops from nothing every few seconds. | `GACHA_SHOW_ENABLED` |
| `NXMerchantBot`, `NXCodeManager` | `casino.NxSeller` + `casino.NxCodes`: a citizen at the Free Market entrance sells a real 10k NX coupon code for 50m by trade, whispered after the trade; it redeems at the Cash Shop's coupon window. Ids come from the tables' auto-increment (SoloMapling's `MAX(id) + 1` could collide). | `NX_SELLER_ENABLED` |
| `SocialHotPotatoManager` | Only what nothing else did: `TownFidgets` puts up chalkboards, pulls faces and now and then sends an avatar megaphone (under the shared citizen megaphone cap). Its chat lines were already ChatterEngine's. | `TOWN_FIDGETS_ENABLED` |
| `ConversationManager` | `TownConversations`: two to four idle TOWN citizens within 250 x 30 px of each other, on a map with a player, play one of the 182 ported scripts (`scripts/config/population/conversations.yaml`), each line in its speaker's voice; their idle chatter waits meanwhile. `RumorScheduler` is Residents passing on news, which is different. SoloMapling's file had five duplicate script names, so it only ever loaded 176; the later copies are renamed. | `CONVERSATIONS_ENABLED` |

Lines: `scripts/config/population/chatter/casino.yaml` (SoloMapling's player reactions, hit/stand lines and drop-game
spectator lines, trimmed; the dealer's and hosts' calls, which SoloMapling hard-coded) and `chatter/fidgets.yaml`.
Venue spots, drop pools and gachapon pools: `scripts/config/population/casino.yaml`.

### Not ported

- **TutorialBot**: dropped (user, 2026-11-01). It offered new players GM level 6, level 149 and 1B mesos; the merged F4
  mentor already guides new characters.
- **Already replaced** and deleted with the package: minigames (`CitizenMiniGames`), jump quests (`CitizenJumpQuests`),
  PQ/OPQ bots, FM shops, scrolling, chat (`ChatterEngine`, `CitizenWhispers`, `PartyMegaphones`), town wandering
  (`CitizenTravel`, `PresenceScheduler`), GachaBot's level-up and scroll reactions (`reactions.yaml`).
- SoloMapling's movement replay, shared bot client, `BotTier`, `EnvironmentManager` and its gm4 commands.

### Fixed on the way

- The dealer's hole card is dealt and turned over (above).
- A five-seat round could draw from an empty deck (the deck only refilled at the start of a round): it now reshuffles
  from the cards not on the table.
- A natural paid 3:2 rounded down to the 10k chip, so a 10k natural paid only 10k: it rounds up now.
- `MapItem.permanentOwner` was set but never read. It now means only the owner picks the drop up, ever, so a dealt
  monster card or a payout never lands in someone else's bag or Monster Book.

An independent review of the live casino code before the first run found more, all fixed in the second commit:

- Dealers and the dice host now **take bets in** when betting closes (the chips leave the floor into their bag), and
  pay stake plus winnings. Before, a player could pick a losing bet back up once the cards showed how it would go.
  A citizen's bet is owner-only until it is taken in, so no player can scoop it.
- A payout is built from the chips the dealer holds, topped up at the cashier for what it lacks, and finally from its
  purse; before, a win paid in small chips silently failed. A dealer who cannot pay closes its table.
- A table or dice host that closes mid-hand hands every open bet back (before, the dealer kept them).
- A round in play finishes even after the last player walks off, and the drop game and NX seller keep stepping
  while a deal is open, so their timeouts run and their citizens are never held at the post forever.
- The gachapon reactor is taken off the map after its box (`MapleMap.destroyReactor` only hid it, and it respawned
  every 10 s as a farmable LudiPQ box), and players' hits on it are ignored while it sprays.
- The drop-game host creates its own party before inviting (the invite failed without one, after the fee was paid),
  turns away a player already in a party before taking the fee, hands the fee back if it still cannot party them,
  and starts a game only when the trade really brought the fee in.
- NX codes come from a SecureRandom (the casino's Random was seeded with the public SEED, so a bought code could
  predict the next), retry on a duplicate, store both rows in one transaction, and reach a buyer who logged off by
  note; a buyer whose code could not be stored gets the mesos back. The casino's own randomness is a SecureRandom too.
- Each deferred casino write has its own mailbox key (they coalesced, so a second pickup in one settle was dropped
  once `ROUTE_EXTERNAL_WRITES` is on); a failed gachapon box or conversation line no longer holds its citizens; the
  bar serves one drink per player per ten minutes.

## Removed from the core

- `Server.java`: the gated `EquipMetadataCache`/`DesirableEquipList` loads and `WzXmlPatcher.applyAllPatches()`.
- `PlayerLoggedinHandler`: `createBotClient` (the first real login was forced to relog to seed the shared client).
- gm4 commands `!bot !opq !reactor !move !betafmshop !test !fmbot !tradebot !env` and their classes (they threw a null
  pointer unless the flag was on).
- `Character`: the `BotTier` field and `getTier`/`setTier`.
- `BotHelpers.isBot` (SoloMapling's `id == 999` and social-bot id rule) in `TimeoutTask`, `CashItemTradePolicy`,
  `ExpeditionDeathCount` and `HiredMerchant.botBuy`. Agents were already excluded by their `BotClient`; the idle
  timeout never saw them (`PlayerStorage.getNetworkRecipients` leaves them out).
- `server.ENABLE_SOLO_MAPLING` (`config.yaml`, `ServerConfig`), `InPacket.copy` (packet replay),
  `ItemConstants.getEquipSlotType`, and the jgrapht dependency (SoloMapling's navigation graph only).
- `ClaimGuardBoundaryTest` no longer lists SoloMapling's party-join and messenger call sites.

## What was left, and where it went

`soloMapling/FreeMarket/` and `soloMapling/itemPool/` imported the rest of the package, so neither branch could remove
them alone. Both merged on `int/consolidation-2026-11-01`, which then deleted them:

- The price tables `PriceSeeds` reads moved to `config/living/price-seeds/` (branch `feat/economy-consolidation`).
- `soloMapling/` is gone, along with `PlayerShop.botBuy`, `HiredMerchant.botBuy`, `MesoAccount.SOLO_MAPLING` and the
  `pom.xml` compile exclusion. Old ledger rows that name `SOLO_MAPLING` stay as text; nothing reads them back.
- Liquibase changeset 28 (`characters.bot_kind`) stays as applied history; nothing reads the column now. Dropping it
  is a new changeset, not an edit.

## Switches

All in `config.yaml` under `population:`, all `false` by default. Blackjack and dice also need `CASINO_CHIPS_ENABLED`
(there is nothing to bet with otherwise). Tuning keys: `CASINO_BLACKJACK_TABLES` (2), `CASINO_MAX_BET` (1m; a dealer's
float is 8x, a dice host's 3x), `CASINO_SHIFT_MIN_MS`/`MAX_MS`, `CASINO_COOLDOWN_MS`, `GACHA_BOX_PRICE`,
`GACHA_BOX_GAP_MS`, `NX_CODE_PRICE` (50m), `NX_CODE_AMOUNT` (10,000), `CONVERSATION_GAP_MS`.

GM readouts (gm5): `!citizens casino` (one line per venue and the cashier), `!citizens casino send <name> [blackjack|
dice|bar|dropgame|gacha|nxseller]` (that citizen goes now, past its cooldown; the reply names a missing switch), and
`!citizens talk` (conversations and fidgets since boot).

## Live checks

Flip one switch at a time in `config.yaml`, restart with `start-maple-stack -RestartServer`, and test on a GM
character with GM commands (`!warp`, `!meso`, `!item`). Find an online Victoria citizen with `!citizens online`.
Each check passes only if nothing in it looks scripted to a player standing there.

- **C1 chips** (`CASINO_CHIPS_ENABLED`). `!warp 100000203`. Aramia stands on the ground at the cashier spot in casino.yaml. Buy one of
  each stamp: 10k/50k/250k/1m leave the purse. Sell them back: the purse gets the same back (the window shows the
  stamps' stock price; the mesos received are what counts). Sell a stamp to any other shop: stock price only.
- **C2 blackjack** (`CASINO_BLACKJACK_ENABLED` + chips). `!citizens casino send <name> blackjack` twice (a dealer, then a
  player). The dealer walks to a table and calls for bets; the citizen player sits and drops chips. Stand by the table
  and say "deal me in": welcome line with the max bet. Drop a chip in front of you when bets are called. Two cards
  each; the dealer shows one card face up and one Monster Card face down. Say "hit" / "stand". On the dealer's turn
  the face-down card turns into its rank card. When betting closes the dealer takes the chips in (they fly to
  it): try to pick yours up once the cards are out, it is gone. A win pays your stake and as much again in chips you
  alone can pick up; a push hands the stake back; a loss stays with the dealer. Walk away mid-hand: the bet is
  forfeit. Say "im out" to leave. `!citizens casino` shows each table's dealer, phase and seats. After the
  shift the dealer cashes its chips back in at Aramia.
- **C3 dice** (`CASINO_DICE_ENABLED` + chips). Send a citizen to `dice`. Drop chips by the host and say "cho" or "han":
  the chips fly to the host, it repeats the bet, rolls two dice, and pays a match the stake and as much again; a miss
  stays with it. "cho" with no chips down gets
  the "drop your chips first" line.
- **C4 bar** (`CASINO_BAR_ENABLED`). Send a citizen to `bar`, then walk into Game Park from Henesys: the host greets you
  by name. Say "drink?" near it: it offers; say "yes", then "maple cola pls": a Maple Cola drops for you alone. Ask again at
  once: "one per customer". Say "no" to an offer: a decline line, nothing dropped.
- **C5 drop game** (`DROP_GAME_ENABLED`). `!warp 100000102`, send a citizen to `dropgame`. It advertises the two tiers.
  Open a trade with it: it explains the tiers in the trade chat. Put in 9m: "wrong amount", the trade is cancelled and
  the mesos come back. Trade it while in a party of your own: "leave your party first",
  cancelled, mesos back. Put in exactly 10m and accept: the trade closes, a party invite comes, accept it; Haste, then
  two minutes of drops along its route, each gone in about 1.5 s. Citizens nearby cheer an item now and then. Decline
  the invite instead: the game is called off and the host keeps the fee, as SoloMapling's did.
- **C6 gachapon** (`GACHA_SHOW_ENABLED`). Send a citizen to `gacha` and stand in its map. Every `GACHA_BOX_GAP_MS` a
  box: a reactor appears, is hit three times and sprays mesos and items that only the citizen picks up; its purse
  drops by `GACHA_BOX_PRICE` (economy journal: NPC_PURCHASE). A prize brings the "omg finally" lines.
- **C7 NX seller** (`NX_SELLER_ENABLED`). `!warp 910000000`, send a citizen to `nxseller`. It advertises "10k nx code
  50m". Trade it 40m: "its 50m exactly", cancelled. Trade it exactly 50m: thanks line, then a whisper and the
  fifteen-character code. Redeem it at the Cash Shop coupon window: +10,000 NX Credit. Redeem it again: refused.
  Buy one and log off within five seconds: the code is waiting as a note at your next login.
- **C8 fidgets** (`TOWN_FIDGETS_ENABLED`). Stand in Henesys for ten minutes: now and then a citizen's chalkboard ("LF>
  guild", "brb") goes up and comes down a few minutes later, faces change; at most a couple of avatar megaphones an
  hour server-wide. `!citizens talk` counts them.
- **C9 conversations** (`CONVERSATIONS_ENABLED`). Stand in Henesys near a few idle citizens: two to four of them who
  stand close turn toward each other and play a script line by line, six to nine seconds apart, in their own voices.
  Walk away to another map: nothing more is said there while no player is in it. A participant who heads off ends it.
  `!citizens talk` shows the scripts played.
- **C10 believability**. With everything on, watch Game Park for twenty minutes as a player would. Anything that reads
  as a script (the same line twice in a row, a dealer talking to nobody, a citizen stuck at a table after its shift)
  is a bug to note.
