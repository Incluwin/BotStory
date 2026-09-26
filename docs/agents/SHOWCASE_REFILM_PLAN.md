# Showcase refilm plan (class + chat showcases)

> **Outcome (2026-09-26):** filmed. Clips in `docs/agents/evidence/video/2026-09-26/`. The user's
> decisions changed the design below: Henesys Hunting Ground I with bots grinding normally (no summoned
> targets, no pack/dummy layout), bots keep naming skills aloud, captions only explain context and goals,
> separate clips plus combined reels, a party of exactly two per clip, and the camera character is a
> Shadower in Dark Sight. F1 (MISS) was mob touch attacks missing the bot, not a bug; F4 was an editing
> artifact. Chat clips are three, not four. Tooling: `tools/cua` take scripts (maple-client skill §4).

Written 2026-09-26 from a review of `docs/agents/evidence/video/2026-09-23/`. Replaces the
2026-09-23 approach (one `!skillprobe <bot> showcase` take per class on Temple of Time, one
long natural-language take on Sahel 1).

Principles, in priority order:
1. **Immersion.** Nothing on camera a real player would find odd: no bot standing on the
   camera character, no bot shouting skill names, no robotic "type X to confirm" lines.
2. **Readable.** A viewer who knows MapleStory must be able to tell what happened without
   pausing: what was cast, what was said, what the bot did in response.
3. **Short.** A class clip earns its length. 30-45 s per class, not 75-95 s.
4. **Honest takes.** Retake instead of cutting failures out. A "did you mean...?" is now a
   feature to show, not an error to trim.

## 1. What the 2026-09-23 footage shows

Measured from the files (contact sheets every 3 s, full-res frames, and the chat line's
change times as a cast/reply timeline). 14 files, 1600x900 at 30 fps, no audio.

### Class clips (12 files, 74-94 s each, 17.0 min total)

| Problem | Evidence |
|---|---|
| Too long, monotonous | Every skill is cast twice at one pace: 25-33 chat lines per clip, median 2.5 s apart. Self buffs with little to show beyond a brief cast effect (Magic Guard, Magic Armor, Meditation...) get the same airtime as 4th-job attacks. |
| Slow start and end | First cast at 4.0-5.5 s; 5-15 s of nothing after the last line (Bowmaster 14.5 s, FP 11.2 s, Hero 10.8 s). |
| Action is tiny | Characters are ~60 px tall in a 1600x900 frame. Action sits in one bridge band; the bottom ~15% is empty sky; minimap, Quest Helper and the key-binding panel stay on screen. |
| MISS on camera | "MISS" in sampled frames: Buccaneer 12 of 31, Bishop ~8 of 31, Hero 3 of 29, FP 3 of 29, Corsair 3 of 31. A level-120 bot missing a level-8 mob reads as broken. Cause not yet known. |
| Wrong target | One Orange Mushroom (L8, 100M HP). Area skills (Slash Blast, Meteor, Arrow Rain...) hit one mob, so their reach never shows; damage numbers are small. |
| Screen clutter | Temple Keeper NPC balloons ("missing children list...") recur in Hero/FP/Bishop and compete with the skill balloons. FP's Poison Mist covers the stage from ~37 s to ~76 s and hides everything after it. Summons (Bahamut, FP summon) linger to the end. Bishop plays a LEVEL UP at ~79 s. |
| Camera jumps | View shifts mid-clip (Bishop ~31 s, Buccaneer ~76 s). |
| Camera character undersells | HUD shows "LV 14 Beginner Jevelopers, HP 232". The post crop in 3.2 removes the HUD bar. |
| Skill names via chat balloon | The bot says every skill name aloud. Useful for viewers, but no player does that (immersion). |

### Natural-language clip (`00-natural-language-commands.mp4`, 2:51, Sahel 1)

What worked: 8 player lines, none using command words; the bot followed, held position,
fought and stopped as asked.

| Problem | Evidence |
|---|---|
| Conversation unreadable | The chat log is one line, so each command scrolls away within seconds; balloons are small. |
| Staging | Bot and camera character stacked on one spot at the right screen edge (name tags merged into "ProbePalaJevelopers"); a wall of scorpions (mob rate 4x) across the floor; the top third of the frame is flat empty colour. |
| Robotic bot lines | Unprompted "what AP build? type 'dexless'/'pure' or e.g. '25 dex'..." (18 s); "logging off? type yes to confirm"; a stat dump "lv148 paladin \| str 573 dex 139 int 4 luk 4 \| hp 15478/15496 mp ...". |
| Gear offer spam | "holding 17 att Sword in case you want it later", declined, then re-offered as "saved 17 att Sword for later if you want it". The pending offer also swallows the next command as its reply (known from filming). |
| Double answer | "what level are you at, and how much hp do you have?" got the stat line and then "did you mean 'fame me'?". Two fame ASKs were trimmed out in editing; this one remains. |
| Interruption | A citizen's pink trade broadcast ("b> blue snail shell paying 103") lands mid-conversation (108 s). Good for "living world", wrong for this clip. |

## 2. New since filming, and how the refilm uses it

| Change (date) | Use on camera |
|---|---|
| "Did you mean X?" waits 30 s for an answer (`AgentChatPendingAsks`, 09-24): yes runs it and stores the phrase as `PLAYER`; no gets "okay, never mind." | The headline chat beat: bot asks, player says "yea", bot acts; the same phrase later acts instantly. |
| Chat-intent state carries the bot's activity (hunting / following / idle / shopping / holding a spot) (09-24) | Context beats: "ease off" while grinding, "what are you up to?". |
| Intent option order fixed (09-24) | Same phrase gives the same decision across server restarts, so a scripted take is repeatable. |
| `!commands` skip Jev (09-24) | GM setup commands during a take no longer produce bot replies. |
| Jev eval set (`tools/jev-eval`, 09-26): 40/40 explicit-command phrasings correct on the flat strategy; ambiguous phrases ASK | Pick demo phrases from it: confident ones for action beats, one known-ambiguous one for the ask-and-learn beat. |
| Packet-rate fix for a lone player on a crowded map (09-23 16:11) | Chat clips can be filmed on populated maps without the camera client being kicked. |
| Rapid Fire client crash fixed (09-23) | Corsair can use its signature skill. |
| cua tooling (`tools/cua`, 09-26): scripted `chat` with server-verified delivery, `hold`, `follow` camera from server positions, `login`, window pinned at 0,0 | Every take is a script: same lines, same timing, checked against the server, no clipboard paste or stuck-key failures. |

Not showcased yet but worth a later reel: combat learner/arena, living-world residents
(quests, bonds, delegated farming), citizens and market.

## 3. Showcase design

### 3.1 Party rule (every clip)

Exactly two members: the camera character and that clip's bot. Formed at the start of the
clip, dissolved at the end, verified both times. Never a leftover party from the previous
clip, and never a third member. This makes party buffs land on the camera character (buff
icons appear, HP/MP visibly change) and gives the viewer the party HP display.

### 3.2 Class clips: 30-45 s each

One bot per class, level 120 4th job, spawned beside (not on) the camera character.

| Beat | Time | Content |
|---|---|---|
| Hook | 0-4 s | Title card overlay (class, job path) over the signature 4th-job skill hitting a pack. |
| Mobbing | ~10 s | Area skills against a pack of 5-6 targets spread across their reach, each cast once. |
| Burst | ~8 s | Single-target skills on one large target; the strongest cast twice at most. |
| Party support | ~8 s | Party buffs land on the camera character: buff icons appear, heals refill a camera character whose HP was lowered first. Self-only invisible buffs are applied before recording, never filmed one by one. |
| Finale | ~5 s | Summon / transformation / ultimate (Bahamut, Battleship, Super Transformation). Lingering fields (Poison Mist, Smokescreen) only here, last. |

Staging and post:
- **Stage:** a flat map with no NPCs and a clean background. Scout 2-3 candidates with
  `maple_client.py shot` and pick from test frames. Temple of Time's Three Doors is out
  (Temple Keeper balloons).
- **Framing:** record full window, then crop in post to the action (about 1066x600 around
  the anchor, scaled 1.5x to 1600x900) so characters are large. The crop must keep the
  party display and the camera character's buff icons, or overlay those regions as insets.
- **Captions instead of balloons:** the bot stops announcing skill names. The showcase
  writes a timeline (skill id, name, start time); post-production burns lower-third
  captions from it.
- **Clean frame:** close minimap and Quest Helper before recording; no EXP for the bot
  during a showcase (no LEVEL UP); warp before each clip so summons/fields from the last
  one are gone.
- **Deliverables:** 12 clips plus one 3-4 min reel of all hooks and finales.

### 3.3 Chat clips: four short clips, 45-75 s each

Filmed with the camera character following the bot (`follow`), bot standing beside the
camera character, conversation shown as a readable transcript panel burned in from the
server chat log (the in-game log stays one line).

1. **Talks like a player.** Slang, typos and indirect phrasing drive the core commands:
   follow, wait here, go fight, ease off, come back. Include one line with a typo.
2. **Asks, then learns (new).** A borderline phrase gets "did you mean ...?"; the player
   answers "yea"; the bot acts. Later in the clip the same phrase acts immediately. Also
   one "nah" -> "okay, never mind.". The learned rows for these phrases are deleted before
   the take so the learning is real.
3. **Knows what it's doing (new).** While grinding: "what are you up to?" and "ease off";
   while holding a spot: "come here"; questions about level/HP answered in player voice.
4. **Party companion.** In the two-member party: buffs on request, heals when the camera
   character is hurt, "stay close" through a map change.

Keep ambient citizen chatter out of these; it belongs in a separate living-world reel.

## 4. Fixes required before filming

| # | Fix | Where | Why |
|---|---|---|---|
| F1 | Find and fix the MISS results in showcases (Buccaneer 12/31 frames) | combat damage path for showcase casts; compare `!skillprobe` reports | Misses read as broken skills |
| F2 | Player-voice build prompt, logout confirmation and status reply ("148 pala, 15.5k hp") | `AgentBuildPromptReporter`, `AgentDialogueCatalog` (logout, lines 306-310), status formatter | Robotic lines on camera |
| F3 | Offer gear once; a declined item is not re-offered; a pending offer must not swallow an unrelated command | `AgentDialogueCatalog` 175-178, `AgentOfferService` | Spam + lost commands |
| F4 | No Jev ASK after a deterministic handler already answered the message | `AgentChatIntentRuntime` / chat routing | "did you mean 'fame me'?" after the stat reply |
| F5 | Unprompted AP-build question at spawn: ask in player voice or not at all in a showcase | `AgentBuildPromptReporter` caller | Opens the chat clip with a robotic prompt |

## 5. Tooling to build

Server (`!skillprobe <bot> showcase`):
- `party=pair`: operator leaves any party, forms a party of operator + bot, disbands at the end.
- Target layout: a pack of N targets across the skill's reach for area skills, one large
  target for single-target skills (level-appropriate mob, high HP).
- Order by the beats in 3.2; cast once by default, `signature` twice; self-only buffs applied
  silently before the first beat; lingering fields and summons last.
- Lower the operator's HP before heal skills.
- Suppress bot EXP during a showcase.
- `balloon=false` and a timeline file (`docs/agents/evidence/video/<date>/<clip>.timeline.json`).
- AdminControlPort: party readback (`/runtime/party/<name>` or members in `/runtime/positions`)
  so the tools can verify "exactly two members" and "no party".

`tools/cua`:
- `maple_client.py login --character Jevelopers` (the current `login` starts whichever
  character is selected; Jevelopers also needs the server started with
  `-Dagents.headless-operator=false`).
- A take runner (`film.py`) that executes a shot list (YAML per clip: setup, lines with
  timing, camera mode, expected server outcomes), records with
  `tools/video/record-client.ps1`, verifies each beat against the server (positions, chat
  log, party readback) and stops the take on the first failed check.
- Post script: trim to the timeline, crop/scale, captions or transcript panel, title card,
  concatenate the reel.

## 6. Per-clip run order

1. Stack up (`start-maple-stack.ps1`), log in as Jevelopers, `!hide` on, window pinned.
2. Reset: warp to the stage, verify no party, close minimap/Quest Helper, turn chat log on,
   delete this clip's demo phrases from `living_learned_phrase` + `POST /actions/living-reload`
   (chat clips only).
3. Spawn the bot beside the camera character; form the pair party; verify exactly 2 members.
4. Start recording; run the shot list; each beat verified before the next.
5. Stop recording; bot signs off; disband; verify no party.
6. Post-process; review the clip against section 7; retake on any failure.

## 7. Acceptance criteria

- Class clip 30-45 s; first hit within 4 s; no idle tail over 2 s.
- No MISS on camera unless the skill can genuinely miss and that is the point.
- No NPC balloon, no lingering field or summon from an earlier skill covering a later one.
- Party: exactly 2 members during the clip, none before or after (server readback logged).
- Every skill has a caption; every chat line is readable in the transcript panel for >= 2 s.
- No edit hides a failure; a failed beat means a retake.
- The bot never stands on the camera character; the camera never jumps.

## 8. Decisions for the user

- Captions vs. keeping the in-game skill-name balloon.
- Individual clips only, or also the combined reel.
- Fix order: F1-F5 before any filming, or film the class clips (need F1 only) while the
  chat fixes (F2-F5) are done.
