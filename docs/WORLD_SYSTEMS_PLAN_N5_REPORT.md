# N5 guild life: build report (2026-11-23)

Branch `feat/world-guild-life`, cut from `feat/companion-manager` 3d04e47df. Not merged, not pushed, not deployed.
Switches `population.GUILD_CHAT_ENABLED`, `GUILD_GOALS_ENABLED`, `GUILD_LADDER_ENABLED`: all ship `false`.
Changeset 67 / `058-guild-life.sql` (`living_guild_goal`, `pvp_guild_rating`). Check scripts:
`MapleServer\tools\checks\checks\n5-guild-life` (U1-U5 plus U1h/U2h/U3h; written, not run). Guide: "Guild life" in
`docs/LIVING_WORLD_GUIDE.md`.

## What was built

| Phase | Commit | What |
|---|---|---|
| N5.1 | 93c1cd433, fda7b1136 | `GuildChat`: a player's guild line (MultiChatHandler type 2) answered by at most 2 online guild citizens after a 2.5-6.5 s pause plus typing; greetings/goodbyes/thanks templated (`chatter/guild.yaml`), questions and named lines one `Conversation.guildTurn` on the new `AgentReplyChannel.GUILD`; login greeted (not a channel change), logout seen off; level-up and clear "gz"; LFG ("anyone up for kpq") ends in a real invite: a guild mate's forming party (`CitizenPartyQuests.formingWith` + `acceptRunInvite`), else a free mate goes to the lobby (`send`) and its leader invites once the party forms (followed up on the party-quest tick) |
| N5.2 | 0cde8c3ee | `GuildGoals` + `GuildGoalStore`: one goal a guild a week (PQ_CLEARS of a band quest; NEEDS_MET / COLISEUM_WINS only with a player member), counted from `living_clear`, MET `living_need` and `pvp_match` winners against membership at the count; paid once (`GUILD_GOAL_GP` via `Server.gainGP`, conditional `done` UPDATE), a guild chat line and a `town.guildgoal` paper story; `!citizens goals [check]` |
| N5.3 | b9c974a2b | Guild ladder: `TeamLadder.Kind.GUILD`, `TeamRules.guildSide` (guild mates, then own companions, never strangers) and `guildFoe` (another guild's full team, never pickup); guild book `LadderStore.guildBook()` over `pvp_guild_rating`; `pvp_match` kind `GUILD`; background sims of two guild teams move the guild book; Cesar's window option; `!pvp coliseum guild [2|3]`, `guildtop` |
| N5.5 | 2c790c820 | The citizen master posts the week's goal on the stock guild BBS ("Goal: 6 KPQ clears"), taking down its previous notice |
| N5.4 | - | design only (below) |

## Tests

`GuildChatTest` (15: reply cap of 2 over 20 draws, pause >= 2.5 s and the second >= 3 s after the first, no second
round within 45 s, citizens of another guild / a guildless player / a bot get nothing, switch off, one conversation
turn for a question and the named mate for a named line, AFK mate silent, login greeted but not a channel change,
logout line dropped at send when they are back, level gz, clear gz from a mate not in it, LFG parsing, LFG ->
invite of a forming party, LFG -> free mate travels and its formed party invites, outside the band told so, a quest
citizens do not run is chat, a free fifth mate found past four busy ones). `GuildGoalsTest` (13: Monday UTC week,
seeded pick stable across restarts, needs/wins only with a player, progress from clear rows with members (the
player's included), paid once over repeated counts and a restart, next week a new goal, MET needs by
`fulfilled_by`, wins counted per match, a leaver stops counting but keeps what was counted, off does nothing, the
check interval, Balrog clears, every goal title fits the BBS's 25 characters). `GuildLadderTest` (7: fill order,
companions after mates and never a stranger, level window, foe of another guild rated nearest, no pickup foe, guild
book apart from the team book, sims cut guild teams first). `GuildLifeSchemaTest` (3), `PopulationConfigGuildLifeTest`
(2), `PaperCatalogTest` row for `town.guildgoal`.

Split suite: see the session's final message (run once at the end on the branch tip).

## Hot-file hunks (vs 3d04e47df)

- `ChatterEngine.java`: one hunk @628 (+12): `guildSay(session, text, noticeMs, atSend)` after `allianceSay`.
- `SocialFabric.java`: not touched (`guildMetas()` is read as it is).
- `NeedScreens.java`, `NeedKind.java`, `NeedLedger.java`: not touched (the needs count reuses `NeedStore.endedSince`).
- `LivingWorldHooks.java`, `PresenceScheduler.java`: not touched (hooks go straight to `PopulationHooks`).
- `Market.java`, `CitizenSpots.java`, `CompanionCardScreens.java`, `banter.yaml`: not touched.
- `config.yaml`: one block @1318 (+32) after `ALLIANCE_REASK_DAYS`: `GUILD_CHAT_ENABLED`, `GUILD_GOALS_ENABLED`,
  `GUILD_GOAL_GP`, `GUILD_GOAL_CLEARS`, `GUILD_GOAL_NEEDS`, `GUILD_GOAL_WINS`, `GUILD_GOAL_CHECK_MINUTES`,
  `GUILD_LADDER_ENABLED`, each with a `# Technical:` note.
- `changelog-tables.xml`: changeset `67-guild-life` at the end (@810, +9).
- Other shared files touched (small, each one call or field): `Population.java` (field x2, construction + one
  `clears.onBooked`, the guild sink install after `PaperDesk.install`, `guildGoals.minute` on the social minute,
  `guildChat.tick` on the party-quest lane, two accessors), `CitizenPartyQuests.java` (one new method `formingWith`
  @618), `Conversation.java` (imports + one new method `guildTurn` @224), `PopulationHooks.java` (one line in
  `levelUp`, two new hooks `guildChat` / `guildMemberOnline`), `PopulationLoader.java` (TRIGGERS +18 at the end),
  `PopulationConfig.java` (fields @194, validation @596), `MultiChatHandler.java` (one line, guild branch),
  `PlayerLoggedinHandler.java` and `Client.java` (one line each after the stock `setGuildMemberOnline`),
  `AgentReplyChannel` (+GUILD), `AgentDialogueTransportRuntime` (GUILD case + `installGuildSink`),
  `AgentChatJevStateFactory.channelName` (+1 case), `AgentSocialDialogueRuntime.deliverAnswer` (a guild answer reaches
  its asker on any map, as a whisper does), `LadderStore` (table/id column parameterised; `guildBook()`),
  `TeamRules`, `TeamLadder`, `Coliseum` (guild option, `guildRequest`, `guildTop`), `PvpCommand`, `CitizensCommand`,
  `paper.yaml` (`town.guildgoal`), new `chatter/guild.yaml`.

## Decisions the plan did not make (taken here; the coordinator may overturn)

1. **Guild book table.** `pvp_guild_rating` (changeset 67) instead of `GUILD_` brackets in `pvp_rating`: the paper's
   weekly climb (`PaperStore`) joins `pvp_rating` to `characters` without a bracket filter, so a guild id there would
   be printed as some character's climb.
2. **Answer route.** A new `AgentReplyChannel.GUILD` with an installed sink (the agents package does not import the
   guild code); model answers to guild lines go through the same `CONVERSATION_*` budgets as map chat.
3. **Hooks.** Login/logout ride the stock `setGuildMemberOnline` call sites (PlayerLoggedinHandler, Client's real
   logout), and the chat hook calls `PopulationHooks` directly, so `LivingWorldHooks.java` (N6/N7's) is untouched.
4. **Who answers what.** Templated greetings/goodbyes up to 2 answerers; thanks 1; questions and named lines 1 model
   turn; one in four plain statements gets a model turn; a 45 s per-player gap on templated rounds; a 50 % chance of a
   logout line.
5. **Goal choice.** NEEDS_MET and COLISEUM_WINS only for a guild with a player member (citizens never hand in and
   citizen-only matches are simulated, not booked); COLISEUM_WINS also needs a band reaching Lv 30. Targets 6 / 4 / 4,
   `GUILD_GOAL_GP` 300 (GPQ pays 15-30 a stage). Weeks start Monday 00:00 UTC (as `pvp_rating.week_of`).
6. **Membership at the count, progress never goes down.** A member who leaves stops counting from then on (U5);
   one who joins mid-week brings their earlier clears of that week (accepted: rare, and in the guild's favour).
7. **Payout order.** `done` is set before GP is paid: a crash between the two loses one payout rather than paying
   twice.
8. **Guild ladder fill.** Guild mates (free, same bracket, level window, nearest level first), then the player's own
   companions; never a stranger citizen. The foe is a full team of one other guild, never a pickup team and never the
   player's own guild. The match also rates the members' team ratings, as a team match does.
9. **BBS notice (N5.5).** Built: the stock client resolves a thread's poster name from its guild member list, and a
   citizen master is a member, so a server-written row reads like a human's. Only in guilds a citizen leads (a
   player master's board is theirs); one notice at a time per master (it deletes its previous "Goal: " thread). It
   rides `GUILD_GOALS_ENABLED` rather than a fourth switch.
10. **The ranking NPC** (`9040004.js`, `9040008.js` -> `Guild.displayGuildRanks`) lists every guild ordered by GP with
   no filter, so bot guilds already appear (at 0 GP, in arbitrary order); GP from goals orders them. Nothing changed
   there.

## Why the guild-team pick was never seen live (read, not proven)

`TeamRules.pick` itself is correct and tested. What makes a guild team rare is `Coliseum.free`: a candidate must be on
the human's channel, in the human's bracket within `LadderRules.LEVEL_WINDOW` levels, not held, not in an event and
**not in a party**, and citizens of one guild often party up in their circles (`SocialFabric.partyUp`). So two or
three free same-guild citizens at once is uncommon. U3h counts how often two guild teams meet in 100 background sims;
if it stays 0, the next step is to let a citizen leave its circle party for a coliseum seat (not done here).

## N5.4 guild GPQ: design (not built)

Depends on L6 (the companion GPQ live checks, not run) and on a leader-conducted run mode (the one N3.3 found missing
for citizen-led OPQ).

- **Who qualifies.** A bot guild whose master is a citizen, with at least 10 members (stock GPQ needs 6 at Shuang;
  10 leaves room for absences), at least 6 of them loadable on one channel in the evening window, whose band reaches
  Lv 50 (Ergoth is Lv 115 with 1.7 M HP; the APQ/GPQ plan wants Lv 70+ with boss gear for the fight). The six must
  cover GPQ's roles as `APQ_GPQ_COMPANION_PLAN.md` decision 6 lists them: a Lv 10-30 member, a Dark Sight thief, a
  magician, a ranged attacker, a jumper, both damage kinds. In practice only the bigger guilds (4-6 of 24).
- **When.** At most once a week per guild, on the guild's goal week (a goal kind `GPQ_CLEARS` could reuse
  `GuildGoals`), started from the party-quest tick when the guild's online count allows; no new timer.
- **The master registers and leads.** The master travels to Shuang (Excavation Site, Perion) through the stock
  registration path, as citizens reach a lobby NPC (`CitizenPqDoor`); members are called in guild chat
  (`guild.gpq.call`: "gpq at the excavation site ch2 in 10, come") and by the master's whisper to a player member
  it knows (the N3.1 invitation shape). The run is conducted by the companion GPQ runtime with the citizen master as
  the leader (the leader-conducted mode), the other citizens as its members.
- **The human member** joins as any member would: walks to Shuang and enters with the guild (stock guild check: a
  citizen guild is a real guild, so decision 5's companion exemption is not needed for them). Their companions may
  join under decision 5 only if they are the human's own.
- **Rewards.** Stock: the GP the GPQ scripts give (`gainGP` 15-30 a stage) and the stock items; no meso.
- **Switch.** `GUILD_GPQ_ENABLED`, off; live check U4.

## Not done / open

- No check was run (client checks are not run in this wave; the headless U1h/U2h/U3h need a check stack with the
  switches on in that worktree's config).
- Field-boss kills as a goal kind come with N6 (one `Kind` value, one `pick` option, one `count` case).
- `TEAM` matches can still pick the player's own guild mates as foes; unchanged.
