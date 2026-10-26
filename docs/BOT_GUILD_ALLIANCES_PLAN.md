# Bot guild alliances

Written 2026-10-25 on `feat/bot-guild-alliances` (cut from `ff6fbe48a`). Citizen guilds form and keep alliances with
one another through the stock alliance system, and a player's guild can invite a citizen guild or be invited by a
citizen alliance. What they decide shows in game: the stock alliance window, alliance notices, the stock system lines
and the citizens' own lines in alliance chat. It follows the guilds' relationships as the population already keeps
them, not a roll.

## Stock facts (read on this tree)

- Lenario (2010009, Orbis) forms a union for two guild masters in one party on his map: 2,000,000 mesos, two guilds,
  then +1 slot per 1,000,000 up to 5 (`scripts/npc/2010009.js`). The party leader's guild leads: alliance rank 1 for
  its master, 2 for the other masters, 5 for every other member (`Alliance.createAlliance`).
- A member of alliance rank 1-2 invites a guild by name in the alliance window (`AllianceOperationHandler` 0x03,
  `Alliance.sendInvitation`); the invite goes to the target guild's master, who must be online
  (`GuildCharacter.getCharacter`), through the InviteCoordinator (expires in about three minutes). The master accepts
  in the window (0x04) or declines (`DenyAllianceRequestHandler`).
- Alliance chat is multi-chat type 3, to every online member of every guild in it (`MultiChatHandler`).
- An alliance is loaded when a member logs in (`PlayerLoggedinHandler`); a guild leaving it is
  `Alliance.removeGuildFromAlliance` (never the leader's guild), a break-up `Alliance.disbandAlliance`.

## Decisions taken (user, 2026-10-25)

1. **Founding: server-side, stock fees.** Two citizen guilds found an alliance on the social tick, written through the
   stock alliance code (citizen guilds are founded server-side too, offline masters included). The founding master
   pays Lenario's 2,000,000 mesos from its own purse, and 1,000,000 for each slot past two, up to five guilds; a guild
   that cannot pay waits. Announced with the alliance notice, a system line to both guilds and a citizen's line in
   alliance chat.
2. **A player invites a citizen guild: answered like a player, online only.** Only a loaded citizen master answers
   (stock: an offline master is "not online"). After a few seconds it accepts when the two sides know each other well
   enough, else declines the stock way and whispers why.
3. **A citizen alliance invites a player's guild once ties are built.** Its loaded master sends the stock invite to the
   human guild master once that guild's players have spent enough time with the alliance's citizens, with a free
   slot; it asks the same guild again only after several days.
4. **Break-ups: slowly, citizens only.** A citizen guild leaves when its ties to the rest stay below a floor for
   several days, with a line in alliance chat; an alliance left with one guild breaks up. Citizens never expel a
   player's guild and never leave an alliance a player leads.

## How close two guilds are

`GuildTies`, from the population's own state (the same population gives the same ties), 0..1:

| part | weight | what |
|---|---|---|
| circles | 0.40 | circles with members in both guilds; full at two (circle mates party up and are buddies) |
| cohort | 0.20 | both guilds of the same cohort (when their members play) |
| home | 0.25 | the overlap of where their members live (share per home region) |
| levels | 0.15 | the overlap of the level ranges they take |

A player's guild is measured in minutes: living_acquaintance minutes (map minutes plus party minutes twice, as the
guild invite counts them) between its players and the citizens on the other side.

## The alliance check (CitizenAlliances, every ALLIANCE_CHECK_MINUTES of the social tick)

1. **Welcome:** a guild new in an alliance with citizens in it gets a citizen's welcome line.
2. **Leave:** in a citizen-led alliance, a citizen guild (not the leader's) whose mean tie to the other citizen guilds
   stays under ALLIANCE_LEAVE_TIE for ALLIANCE_LEAVE_AFTER_DAYS says so and leaves; one guild left breaks it up. The
   start of a low spell is kept in living_alliance_mark, so a restart does not reset it.
3. **Join:** an unallied citizen guild whose mean tie to a citizen-led alliance's citizen guilds reaches
   ALLIANCE_FOUND_TIE joins it (one a check); a full alliance under five buys a slot first.
4. **Found:** while fewer than ALLIANCE_TARGET citizen-led alliances stand, the closest pair of unallied citizen guilds
   at ALLIANCE_FOUND_TIE or more founds one (the bigger guild leads; its master pays).
5. **Invite a player's guild:** a citizen-led alliance with a free (or buyable) slot invites the unallied player guild
   whose players spent ALLIANCE_PLAYER_MINUTES or more with its citizens, once per ALLIANCE_REASK_DAYS.

A citizen guild takes part only while its master is an active citizen nobody hired. Kill switch:
`population.ALLIANCES_ENABLED: false` (with `GUILDS_ENABLED`); existing alliances then stay as they are.

## How it is built

- `server.living.population.GuildTies`: the tie above, a pure function of two guilds' meta rows and active members.
- `server.living.population.CitizenAlliances`: the check, on SocialFabric's tick (inside `GUILDS_ENABLED`), and the
  answer to a player's invite. Founding is the stock `createAlliance` without a party: `createAllianceOnDb`, both
  guilds' alliance ids and ranks reset, the leading master alliance rank 1 and the other 2 (in memory and saved: a
  loaded master saves its own row, an offline one is written in SQL), notice = the leading guild's motto, the stock
  alliance packets, and a system line "[A] and [B] formed the [Name] union.". Joining is the stock accept (op 4).
  Leaving is the stock `removeGuildFromAlliance`, after the citizen's goodbye is out; one guild left is
  `disbandAlliance`.
- Fees: a loaded master pays from its meso in memory, an offline one in SQL (`meso >= fee`). Under the citizen meso
  floor (`Market.expectedMeso`, 2,000 x level^1.5) a master holds Lenario's 2,000,000 from about level 100, so lower
  guilds wait until a master has it. The user kept this (2026-10-26): stock fees from one master's purse, with
  alliances staying rare among low-level guilds, rather than splitting the fee between the two founding masters.
- Names and lines: `guild-names.yaml` `alliances` (patterns over the guild name lists; lines per moment with
  `{guild}`, `{other}`, `{alliance}`).
- `living_alliance_mark` (changeset 48, file 039): the low spells and asked dates, epoch ms, one row per guild and kind.
- A player's invite: `Alliance.sendInvitation` calls `LivingWorldHooks.allianceInvited` after the stock invite goes out;
  a loaded citizen master answers after 4 s through the stock `answerInvitation`. A guild taking a stock invite (op 4)
  into an alliance with a bot guild in it calls `LivingWorldHooks.allianceJoined`, and a citizen welcomes it at once.
- Two engine fixes this needs: `GuildCharacter.setAllianceRank` no longer throws for an offline member, and a citizen's
  guild entry is bound to its character when it loads (`SocialFabric.onLogin`, as the stock login binds a player's;
  the bot guilds are cached at boot before any citizen loads, so a loaded master read as "not online").
- `!citizens alliances`: each alliance with citizen guilds, its size and capacity, who leads, each citizen guild's tie;
  `!citizens guilds` shows a guild's alliance id.

## Live checks (after deploy, when the user asks)

- V0: boot log `bot guild alliances:` lines; `!citizens guilds` lists alliances; no WARN from the alliance check.
- V1: a citizen alliance in the guild window's Alliance tab of a citizen guild member (via a test character in it).
- V2: a test guild master (crewfriend24) invites a citizen guild whose master is online: a known guild accepts
  within seconds, an unknown one declines with a whisper.
- V3: after enough shared time, a citizen alliance master's invite reaches the test guild master; accept it in the
  stock window, then a welcome line in alliance chat.
- V4: kill switch off: no new alliance, no invite, nobody leaves.
