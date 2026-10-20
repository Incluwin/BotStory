package server.agents.capabilities.partyquest.ppq;

import client.Character;
import client.inventory.InventoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagement;
import server.agents.capabilities.partyquest.AgentPartyQuestEngagementRegistry;
import server.agents.capabilities.partyquest.AgentPartyQuestHandback;
import server.agents.capabilities.partyquest.AgentPartyQuestLifecycleRuntime;
import server.agents.integration.AgentCharacterGatewayRuntime;
import server.agents.integration.AgentInventoryGatewayRuntime;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.agents.integration.AgentPrimitiveCapabilityGatewayRuntime;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyQuestGateway;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.capabilities.looting.AgentLootEligibility;
import server.agents.perception.AgentMapPerception;
import server.agents.plans.AgentScriptItemActionService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.Reactor;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Natural-action PPQ coordinator: clear, collect, optional chests, doors, boss. */
public final class AgentPpqCoordinator {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqCoordinator.class);
    private static final long EVENT_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.EVENT_TIMEOUT_MS");
    private static final long STALL_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.STALL_TIMEOUT_MS");
    private static final long RETRY_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.RETRY_MS");
    private static final int INTERACTION_RADIUS = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.INTERACTION_RADIUS");
    private static final long AFTER_CLEAR_LIMIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.AFTER_CLEAR_LIMIT_MS");
    private static final long COMPANION_ENTRY_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.COMPANION_ENTRY_WAIT_MS");
    private static final long PRE_ENTRY_TIMEOUT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.PRE_ENTRY_TIMEOUT_MS");
    static final int EXIT_WAIT_RADIUS_PX = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.EXIT_WAIT_RADIUS_PX");
    static final long COMPANION_AFTER_CLEAR_WAIT_MS = config.AgentTuning.longValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.COMPANION_AFTER_CLEAR_WAIT_MS");
    static final int MEDAL_DYING_HP_PERCENT = config.AgentTuning.intValue(
            "server.agents.capabilities.partyquest.ppq.AgentPpqCoordinator.MEDAL_DYING_HP_PERCENT");

    private AgentPpqCoordinator() { }

    /** The primitive actions, looked up on every call so a test can swap them. */
    static PrimitiveCapabilityGateway actions() {
        return AgentPrimitiveCapabilityGatewayRuntime.gateway();
    }

    /** The party-quest gateway, looked up on every call so a test can swap it. */
    static PartyQuestGateway partyQuest() {
        return AgentPartyQuestGatewayRuntime.partyQuest();
    }

    public static void tickSession(AgentPpqSession session, long nowMs) {
        if (session.terminal() || session.terminationStarted() || session.paused()) return;
        Character leader = character(session.eventLeaderId());
        if (leader == null) {
            if (!cleared(session)) {
                fail(session, "PPQ event leader is unavailable", AgentPpqParting.Kind.GONE, nowMs);
                return;
            }
            // The run is won: a leader who logged out after the clear leaves it to the after-clear rules.
            logChange(session, session.transition(AgentPpqSession.Phase.CLEARING, nowMs));
            afterClear(session, nowMs);
            return;
        }
        if (session.eventInstance() == null && leader.getEventInstance() != null
                && AgentPpqDefinition.isEventMap(leader.getMapId())) {
            session.bindEventInstance(leader.getEventInstance(), nowMs);
            session.setPotionsAtEntry(AgentPpqTerminationService.potions(session));
            // Wu Yang's exit is the companions' own talk after the clear: compiled now, off the tick that needs it.
            if (session.companionAdopted()) partyQuest().prepareNpcScripts(AgentPpqDefinition.RESCUE_NPC);
            logChange(session, session.transition(AgentPpqSession.Phase.ACTIVE, nowMs));
        }
        if (capReached(session, nowMs)) return;
        // A human leader down in the bound event is PiratePQ.js's to judge (his OK ends the event); an Agent leader
        // down, or a leader down anywhere else, is gone. After the clear the run is won and nobody is failed.
        boolean humanDownInside = !isAgent(leader) && session.eventInstance() != null
                && leader.getEventInstance() == session.eventInstance();
        if (leader.getHp() <= 0 && !humanDownInside && !cleared(session)) {
            fail(session, "PPQ event leader is unavailable", AgentPpqParting.Kind.DEFEATED, nowMs); return;
        }
        observeDepartures(session, nowMs);
        logStage(session, leader, nowMs);
        observeProgress(session, nowMs);
        if (session.eventInstance() != null && session.eventInstance().isEventCleared()
                && session.phase().ordinal() < AgentPpqSession.Phase.CLEARING.ordinal()) {
            logChange(session, session.transition(AgentPpqSession.Phase.CLEARING, nowMs));
        }
        if (session.eventInstance() != null && session.eventInstance().isEventDisposed()
                && session.phase().ordinal() < AgentPpqSession.Phase.CLEARING.ordinal()) {
            // a companion run says why, from what its last pass saw of the event (plan 9.4)
            fail(session, "PPQ event instance was disposed before clear", AgentPpqParting.endedCause(
                    session.leaderDown(), session.lastTimeLeftMs(), AgentPpqCompanionRun.TIMEOUT_SLACK_MS,
                    session.lastRegistered(), AgentPpqDefinition.STOCK_FLOOR), nowMs);
            return;
        }
        boolean companionRun = session.companionAdopted();
        // The leader, the members and the lines for those who left, before the clear (plan 7.0 T3-T9, 9.2-9.4).
        if (companionRun && !AgentPpqCompanionRun.pass(session, leader, nowMs)) return;
        // A companion run says nothing before entry: its leader clicks Guon himself (plan 7.1).
        if (session.mode() == AgentPpqSession.Mode.HUMAN_LEADER && !companionRun
                && session.phase() == AgentPpqSession.Phase.PREPARING) {
            announce(session, "human-enter", leader.getName() + ", talk to Guon to enter Pirate PQ.");
        }
        if (session.eventInstance() != null) {
            int openedChests = parseInt(partyQuest().property(leader, "openedChests"));
            if (openedChests >= 1) session.markChestComplete(AgentPpqDefinition.CHEST_ONE_MAP, nowMs);
            if (openedChests >= 2) session.markChestComplete(AgentPpqDefinition.CHEST_TWO_MAP, nowMs);
        }
        // The stock guide lines; a companion run's companions coach in their own words instead (plan 8).
        if (companionRun) AgentPpqCompanionRun.coach(session, leader);
        else if (session.mode() == AgentPpqSession.Mode.HUMAN_LEADER) guideHumanLeader(session, leader);
        if (leader.getMapId() == AgentPpqDefinition.BOSS_MAP
                && actions().liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS) == 0) {
            if (isAgent(leader) && leader.getHp() > 0) {
                runNpc(entry(leader.getId()), leader, AgentPpqDefinition.RESCUE_NPC);
            }
            else if (!companionRun) {
                announce(session, "human-clear", leader.getName() + ", talk to Wu Yang after Lord Pirate falls.");
            }
        }
        if (leader.getMapId() == AgentPpqDefinition.CLEAR_MAP) {
            logChange(session, session.transition(AgentPpqSession.Phase.CLEARING, nowMs));
        }
        if (afterClear(session, nowMs)) return;
        // The authored medal stage allows six minutes and uses probabilistic drops.  Do not
        // declare a legal, still-attacking party stalled before that stage can naturally finish.
        if (!cleared(session) && nowMs - session.lastProgressAtMs() >= STALL_TIMEOUT_MS) {
            fail(session, "PPQ stalled", AgentPpqParting.Kind.TOO_LONG, nowMs);
        }
    }

    /**
     * Fails the run with this reason: a stock run exactly as it always did, a companion run once, with its companions'
     * parting of this kind (AgentPpqCompanionRun.fail).
     */
    private static void fail(AgentPpqSession session, String reason, AgentPpqParting.Kind kind, long nowMs) {
        AgentPpqCompanionRun.fail(session, reason, kind, null, nowMs);
    }

    /**
     * A CLEARING run completes once nobody it waits for is left on a PPQ map, or AFTER_CLEAR_LIMIT_MS after it first saw
     * the clear ('PPQ after clear:'). True when it completed the run.
     */
    private static boolean afterClear(AgentPpqSession session, long nowMs) {
        if (session.phase() != AgentPpqSession.Phase.CLEARING) return false;
        long afterClearMs = nowMs - session.observeCleared(nowMs);
        boolean waiting = stillInside(session);
        if (waiting && afterClearMs < AFTER_CLEAR_LIMIT_MS) return false;
        log.info("PPQ after clear: session={} stillInside={} afterClearMs={} companionRun={}",
                session.sessionId(), waiting, afterClearMs, session.companionAdopted());
        session.complete(nowMs);
        return true;
    }

    /**
     * An Agent the bound event held and no longer holds, and that stands off the PPQ maps (its death gate let it go and
     * the respawn took it to 925100700, or it walked out), has left the run: it leaves the session and is handed back to
     * its owner at once ('PPQ departed:'). A dead Agent the event still holds has not left (its gate holds it), nor has
     * one between two PPQ maps. Before the clear only: clearPQ's warp unregisters everyone.
     */
    private static void observeDepartures(AgentPpqSession session, long nowMs) {
        EventInstanceManager event = session.eventInstance();
        if (event == null || session.terminationStarted() || cleared(session) || event.isEventDisposed()) return;
        Set<Integer> held = new HashSet<>();
        for (Character player : event.getPlayers()) {
            if (player != null) held.add(player.getId());
        }
        session.noteHeld(held);
        // A companion run's member the event never held once the entry wait has passed since the bind went in without
        // it (plan 7.1): it leaves the run as one that walked out does.
        boolean entryOver = session.companionAdopted() && nowMs - session.enteredAtMs() >= COMPANION_ENTRY_WAIT_MS;
        for (AgentPpqMemberState member : session.members()) {
            int id = member.characterId();
            if (member.memberType() != AgentPpqMemberState.MemberType.AGENT || held.contains(id)
                    || !(session.seenHeld(id) || entryOver)) continue;
            Character agent = character(id);
            if (agent != null && AgentPpqDefinition.isEventMap(agent.getMapId())) continue;
            if (session.companionAdopted() && session.lastAgent(id)) {
                // A human-led run needs no Agent to go on: its last companion goes home with the release.
                log.info("PPQ departed: session={} member={} map={} registered={} companionRun=true last=true",
                        session.sessionId(), id, agent == null ? "-" : String.valueOf(agent.getMapId()), held.size());
                AgentPpqTerminationService.release(session, AgentPpqTerminationService.HUMANS_CARRY_ON, nowMs, false);
                return;
            }
            if (!session.departAgent(id)) continue;
            log.info("PPQ departed: session={} member={} map={} registered={} companionRun={}", session.sessionId(), id,
                    agent == null ? "-" : String.valueOf(agent.getMapId()), held.size(), session.companionAdopted());
            handBack(session, id, nowMs);
            if (session.companionAdopted() && agent != null) session.queueDeparted(agent.getName());
        }
    }

    /**
     * A companion run's Agent not loaded for MEMBER_RECOVERY_TIMEOUT_MS (plan 9.2 MISSING) leaves the run as a departed
     * one does; its last Agent sends the run home with the humans carrying on. False when the run ended.
     */
    static boolean departMissing(AgentPpqSession session, int id, long nowMs) {
        if (session.lastAgent(id)) {
            AgentPpqTerminationService.release(session, AgentPpqTerminationService.HUMANS_CARRY_ON, nowMs, false);
            return false;
        }
        if (session.departAgent(id)) {
            log.info("PPQ departed: session={} member={} map=- reason=missing companionRun={}", session.sessionId(),
                    id, session.companionAdopted());
            handBack(session, id, nowMs);
        }
        return true;
    }

    /**
     * Hands a departed Agent back to its owner: the run no longer routes it and the lifecycle rehomes it. The engagement
     * lets go before the session index, so a runtime removal in between still finds the session (and, the member
     * already gone from it, only unindexes it) instead of force-stopping an engagement it still belonged to.
     */
    private static void handBack(AgentPpqSession session, int id, long nowMs) {
        try {
            AgentPpqFullRateSimulation.apply(session, id, entry(id), false);
            if (session.companionAdopted()) {
                // A desk run's host takes it out of the party and holds it by Guon's ship until the run is over (a
                // tap-out, plan 9.2); with no host run (an adopted test run) it is refused and the hand-back below
                // rehomes it as before.
                boolean tappedOut = AgentPartyQuestHandback.tapOut(new AgentPartyQuestHandback.TapOut(
                        "ppq", session.sessionId(), id, AgentPpqDefinition.RECRUIT_MAP, nowMs));
                log.info("PPQ tap-out: session={} member={} map={} accepted={}", session.sessionId(), id,
                        AgentPpqDefinition.RECRUIT_MAP, tappedOut);
            }
            AgentPartyQuestEngagement engagement = AgentPartyQuestEngagementRegistry.forMember(id);
            if (engagement != null) AgentPartyQuestEngagementRegistry.removeAndUnindexMember(engagement, id, nowMs);
            AgentPpqSessionRegistry.unindexMember(session, id);
            AgentPartyQuestLifecycleRuntime.recoverDetachedMember(id, "ppq", nowMs);
        } catch (RuntimeException failure) {
            log.warn("PPQ departed member hand-back failed: session={} member={}", session.sessionId(), id, failure);
        }
    }

    /** Whether the bound event still holds a human member of the run. */
    private static boolean holdsAHuman(AgentPpqSession session) {
        EventInstanceManager event = session.eventInstance();
        if (event == null) return false;
        for (Character player : event.getPlayers()) {
            AgentPpqMemberState member = player == null ? null : session.member(player.getId());
            if (member != null && member.memberType() == AgentPpqMemberState.MemberType.HUMAN) return true;
        }
        return false;
    }

    /**
     * The run's time caps, none after the clear. From entry, EVENT_TIMEOUT_MS ("PPQ event timer expired"), but never
     * while the bound event still holds a human: PiratePQ.js's own timers end such a run (a chest-room entry restarts
     * them for each member). Before entry, COMPANION_ENTRY_WAIT_MS for a companion run (its human leader clicks Guon
     * himself) and PRE_ENTRY_TIMEOUT_MS for every run, so a slow start never shortens the stages. True when it failed
     * the run.
     */
    private static boolean capReached(AgentPpqSession session, long nowMs) {
        if (cleared(session)) return false;
        if (session.eventInstance() == null) {
            long waitedMs = nowMs - session.startedAtMs();
            if (session.companionAdopted() && waitedMs >= COMPANION_ENTRY_WAIT_MS) {
                fail(session, "The PPQ leader did not start the event in time", AgentPpqParting.Kind.SILENT, nowMs);
                return true;
            }
            if (waitedMs >= PRE_ENTRY_TIMEOUT_MS) {
                fail(session, "PPQ entry timed out", AgentPpqParting.Kind.SILENT, nowMs);
                return true;
            }
            return false;
        }
        if (nowMs - session.enteredAtMs() >= EVENT_TIMEOUT_MS && !holdsAHuman(session)) {
            fail(session, "PPQ event timer expired", AgentPpqParting.Kind.TOO_LONG, nowMs);
            return true;
        }
        return false;
    }

    /**
     * Whether a member the run waits for after the clear still stands on a PPQ map: the boss map until clearPQ's warp,
     * then Wu Yang's room, which has no exit. A companion run waits for its companions only, never a human; every other
     * mode waits for every member.
     */
    static boolean stillInside(AgentPpqSession session) {
        boolean companionRun = session.companionAdopted();
        return session.members().stream()
                .filter(member -> !companionRun || member.memberType() == AgentPpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .anyMatch(member -> AgentPpqDefinition.isEventMap(member.getMapId()));
    }

    /**
     * Whether the bound event reported its clear. From then on neither the cap nor the stall fails the run; it
     * completes once nobody it waits for is left inside, or AFTER_CLEAR_LIMIT_MS after the clear.
     */
    static boolean cleared(AgentPpqSession session) {
        EventInstanceManager event = session.eventInstance();
        return event != null && event.isEventCleared();
    }

    public static void tickMember(AgentPpqSession session, AgentRuntimeEntry entry,
                                  Character agent, long nowMs) {
        AgentPpqMemberState member = session.member(agent.getId());
        // A run that ended or whose release began (a stop off the central tick thread) does nothing more.
        if (member == null || member.memberType() != AgentPpqMemberState.MemberType.AGENT
                || session.terminal() || session.terminationStarted()) return;
        boolean companionRun = session.companionAdopted();
        if (companionRun) AgentPpqCompanionRun.memberPrelude(session, entry, agent);
        if (nowMs < member.nextActionAtMs()) return;
        // No instance is not another instance: the clear warps everyone to 925100600 and ends the event under them.
        if (session.eventInstance() != null && AgentPpqDefinition.isEventMap(agent.getMapId())
                && agent.getEventInstance() != null && agent.getEventInstance() != session.eventInstance()) {
            session.fail("PPQ member entered a different event instance", nowMs); return;
        }
        int mapId = agent.getMapId();
        if (mapId == AgentPpqDefinition.RECRUIT_MAP) { enter(session, entry, agent, member, nowMs); return; }
        if (mapId == AgentPpqDefinition.MEDAL_MAP) { medals(session, entry, agent, member, nowMs); return; }
        if (mapId == AgentPpqDefinition.CHEST_ONE_MAP || mapId == AgentPpqDefinition.CHEST_TWO_MAP) {
            if (companionRun) companionChest(session, entry, agent, member, nowMs);
            else chest(session, entry, agent, member, nowMs);
            return;
        }
        if (mapId == AgentPpqDefinition.DOOR_MAP) {
            if (companionRun) companionDoors(session, entry, agent, member, nowMs);
            else doors(session, entry, agent, member, nowMs);
            return;
        }
        if (mapId == AgentPpqDefinition.BOSS_MAP) { boss(session, entry, agent); return; }
        if (mapId == AgentPpqDefinition.CLEAR_MAP) {
            if (companionRun && waitByWuYang(session, entry, agent, nowMs)) return;
            if (!roomForFeather(agent)) {
                // Wu Yang refuses the Feather to a full Etc tab, and his room has no exit: it leaves without it.
                log.info("PPQ feather skipped: session={} member={} reason=no-etc-room", session.sessionId(),
                        agent.getId());
                AgentPpqTerminationService.leaveWuYangsRoom(session, agent);
                member.deferUntil(nowMs + RETRY_MS);
                return;
            }
            if (runNpc(entry, agent, AgentPpqDefinition.RESCUE_NPC, 0)) member.deferUntil(nowMs + RETRY_MS);
            return;
        }
        if (mapId == AgentPpqDefinition.ENTRY_MAP
                || mapId == AgentPpqDefinition.DECK_ONE_MAP || mapId == AgentPpqDefinition.DECK_TWO_MAP) {
            if (companionRun) companionAdvance(session, entry, agent, member, nowMs);
            else clearAndAdvance(session, entry, agent, member, nowMs);
            return;
        }
        if (mapId == AgentPpqDefinition.SIDE_ONE_MAP || mapId == AgentPpqDefinition.SIDE_TWO_MAP) {
            sideRoom(session, entry, agent, member, nowMs); return;
        }
        actions().stop(entry);
    }

    /**
     * The Agent event leader talks to Guon until the run has entered. After entry (Wu Yang's exit lands on Guon's map
     * after the clear) it never talks to him again: setEventCleared freed the lobby, so a talk would start a new
     * instance.
     */
    private static void enter(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                              AgentPpqMemberState member, long nowMs) {
        if (agent.getId() != session.eventLeaderId() || session.mode() == AgentPpqSession.Mode.HUMAN_LEADER
                || session.phase().ordinal() >= AgentPpqSession.Phase.ACTIVE.ordinal()) {
            actions().stop(entry); return;
        }
        if (runNpc(entry, agent, AgentPpqDefinition.ENTRY_NPC, 0)) {
            member.deferUntil(nowMs + RETRY_MS);
            logChange(session, session.transition(AgentPpqSession.Phase.ENTERING, nowMs));
        }
    }

    private static void medals(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                               AgentPpqMemberState member, long nowMs) {
        Character leader = character(session.eventLeaderId());
        int itemId = expectedMedal(leader);
        if (agent.getId() == session.eventLeaderId()) {
            if (itemId != 0 && agent.getItemQuantity(itemId, false) >= AgentPpqDefinition.MEDALS_PER_WAVE) {
                if (runNpc(entry, agent, AgentPpqDefinition.GUIDE_NPC)) {
                    member.deferUntil(nowMs + RETRY_MS); session.markProgress(nowMs);
                }
                return;
            }
            // Submit as soon as the current-wave quota is met. Chasing every medal type first
            // can never drain this continuously respawning map and prevents the NPC hand-in.
            if (itemId != 0 && collectObjectiveDrop(
                    session, entry, agent, Set.of(itemId), nowMs)) return;
        } else if (session.companionAdopted() && itemId != 0) {
            // A companion about to go down with no potion leaves its medals where it stands (its death would delete
            // them) and gathers no more; the hand-over and the re-collect take them on to the leader.
            boolean dying = AgentPpqCompanionPlay.dying(agent.getHp(), agent.getCurrentMaxHp(),
                    actions().hpPotionCount(agent), MEDAL_DYING_HP_PERCENT);
            if (dying) {
                if (leaveMedals(session, entry, agent, member, itemId, leader, nowMs)) return;
            } else {
                // Only the event leader may hand Guon the marks: the companions gather this wave's and bring them.
                if (AgentPpqMedalHandOver.tick(session, entry, agent, member, itemId, leader, nowMs)) return;
                if (AgentPpqMedalHandOver.gatherWanted(session, agent, leader, itemId)
                        && collectObjectiveDrop(session, entry, agent, Set.of(itemId), nowMs)) return;
            }
        }
        if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) {
            actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS); return;
        }
        if (itemId == 0) usePortal(entry, agent, member, AgentPpqDefinition.nextPortalId(agent.getMapId()), nowMs);
        else actions().stop(entry);
    }

    /** The mark the medal stage wants now (PiratePQ.js 'stage2' 0-2), or 0 once the three waves are in. */
    static int expectedMedal(Character leader) {
        if (leader == null) return AgentPpqDefinition.ROOKIE_MEDAL;
        String stage = partyQuest().property(leader, "stage2");
        return switch (stage == null ? "0" : stage) {
            case "0" -> AgentPpqDefinition.ROOKIE_MEDAL;
            case "1" -> AgentPpqDefinition.RISING_MEDAL;
            case "2" -> AgentPpqDefinition.VETERAN_MEDAL;
            default -> 0;
        };
    }

    private static void clearAndAdvance(AgentPpqSession session, AgentRuntimeEntry entry,
                                        Character agent, AgentPpqMemberState member, long nowMs) {
        if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) {
            actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS); return;
        }
        int mapId = agent.getMapId();
        boolean deck = mapId == AgentPpqDefinition.DECK_ONE_MAP || mapId == AgentPpqDefinition.DECK_TWO_MAP;
        Character leader = character(session.eventLeaderId());
        boolean chestAvailable = !session.skipChestRooms() && !session.chestCompleteForDeck(mapId)
                && leader != null && leader.getItemQuantity(AgentPpqDefinition.CHEST_KEY, false) > 0;
        if (deck && chestAvailable) {
            // Everyone enters to clear the optional room; only the leader performs the key drop.
            // Leaving four fighters idle makes the authored deck timer unnecessarily fragile.
            usePortal(entry, agent, member, 1, nowMs);
            return;
        }
        usePortal(entry, agent, member, AgentPpqDefinition.nextPortalId(mapId), nowMs);
    }

    /**
     * A companion on stage one or a deck (plan 7.2, 7.3, 7.5): it follows a leader already in this deck's chest room in
     * at once; else it clears the map, then goes on through the exit without waiting for him, except while he can
     * still open this deck's chest (he holds a chest key, chests are played, the chest is not done and he has not gone
     * past the deck): then it waits by the chest room's portal and tells him once, when he is on the deck, to go first.
     */
    private static void companionAdvance(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                         AgentPpqMemberState member, long nowMs) {
        int mapId = agent.getMapId();
        boolean deck = mapId == AgentPpqDefinition.DECK_ONE_MAP || mapId == AgentPpqDefinition.DECK_TWO_MAP;
        Character leader = character(session.eventLeaderId());
        int chestMap = mapId == AgentPpqDefinition.DECK_ONE_MAP
                ? AgentPpqDefinition.CHEST_ONE_MAP : AgentPpqDefinition.CHEST_TWO_MAP;
        boolean leaderInChest = deck && leader != null && leader.getHp() > 0 && leader.getMapId() == chestMap;
        boolean leaderKey = leader != null && leader.getItemQuantity(AgentPpqDefinition.CHEST_KEY, false) > 0;
        boolean leaderPast = leader != null && AgentPpqCompanionPlay.past(
                AgentPpqDefinition.stageOf(leader.getMapId()), AgentPpqDefinition.stageOf(mapId));
        AgentPpqCompanionPlay.DeckMove move = AgentPpqCompanionPlay.deckMove(deck, leaderInChest,
                actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0, session.skipChestRooms(),
                session.chestCompleteForDeck(mapId), leaderKey, leaderPast);
        switch (move) {
            case FOLLOW_INTO_CHEST -> usePortal(entry, agent, member, AgentPpqDefinition.CHEST_ROOM_PORTAL, nowMs);
            case FIGHT -> actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS);
            case WAIT_BY_CHEST -> {
                if (standNear(entry, agent, actions().portalPosition(agent, AgentPpqDefinition.CHEST_ROOM_PORTAL))) {
                    AgentPpqCompanionRun.chestRoomHere(session, agent, leader);
                }
            }
            case GO_ON -> usePortal(entry, agent, member, AgentPpqDefinition.nextPortalId(mapId), nowMs);
        }
    }

    /**
     * A companion in a chest room (decision D-c): it came in after the leader, fights beside him while he is here, and
     * leaves by out00 once he has left. It never talks to the chest room's Guon nor touches a chest key.
     */
    private static void companionChest(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                       AgentPpqMemberState member, long nowMs) {
        Character leader = character(session.eventLeaderId());
        if (leader == null || leader.getMapId() != agent.getMapId()) {
            member.clearChestDrop();
            usePortal(entry, agent, member, AgentPpqDefinition.CHEST_ROOM_PORTAL, nowMs);
            return;
        }
        if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) {
            actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS);
        } else {
            actions().stop(entry);
        }
    }

    /**
     * A companion on the door map (plan 7.7, 7.2 b): it picks up the Old Metal Keys; no key goes into a door until the
     * companions' keys and those still lying here cover every closed door (keys a human holds are his to spend); then
     * each key holder takes the nearest door nobody claimed and drops one key in it. With every door open it keeps the
     * map clear by the boss door and goes through only with the leader beside it (or already inside): the first one
     * through spawns the Lord Pirate, and the door opens only on an empty map.
     */
    private static void companionDoors(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                       AgentPpqMemberState member, long nowMs) {
        if (collectObjectiveDrop(session, entry, agent, Set.of(AgentPpqDefinition.OLD_METAL_KEY), nowMs)) return;
        boolean mobs = actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0;
        int closed = AgentPpqCompanionRun.closedDoors(agent);
        if (closed > 0) {
            boolean holdsKey = agent.getItemQuantity(AgentPpqDefinition.OLD_METAL_KEY, false) > 0;
            if (holdsKey && AgentPpqCompanionPlay.mayDropKeys(AgentPpqCompanionRun.companionKeys(session),
                    AgentPpqCompanionRun.floorKeys(agent), closed)) {
                Reactor door = committedDoor(agent, member.committedReactorObjectId());
                if (door == null) door = nearestUnclaimedDoor(session, agent);
                if (door != null) {
                    dropKeyInDoor(session, entry, agent, member, door, nowMs);
                    return;
                }
            }
            if (mobs) actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS);
            else actions().stop(entry);
            return;
        }
        Point door = actions().portalPosition(agent, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DOOR_MAP));
        Character leader = character(session.eventLeaderId());
        boolean leaderAtDoor = leader != null && leader.getHp() > 0 && leader.getMapId() == agent.getMapId()
                && within(leader.getPosition(), door, EXIT_WAIT_RADIUS_PX);
        boolean leaderInside = leader != null && leader.getMapId() == AgentPpqDefinition.BOSS_MAP;
        switch (AgentPpqCompanionPlay.bossDoor(mobs, leaderAtDoor, leaderInside)) {
            case FIGHT -> actions().grindFromAnchor(entry, door, AgentPpqDefinition.COMBAT_MOBS, Set.of());
            case WAIT -> standNear(entry, agent, door);
            case ENTER -> {
                if (leaderAtDoor) AgentPpqCompanionRun.bossDoor(session, agent, leader);
                usePortal(entry, agent, member, AgentPpqDefinition.nextPortalId(AgentPpqDefinition.DOOR_MAP), nowMs);
            }
        }
    }

    /** Walks into the door's area and drops one Old Metal Key there (the stock door rule, shared by every mode). */
    private static void dropKeyInDoor(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                      AgentPpqMemberState member, Reactor door, long nowMs) {
        member.commitReactor(door.getObjectId());
        Point trigger = reactorDropPoint(door);
        if (!door.getArea().contains(agent.getPosition())) {
            Point ground = actions().groundPoint(agent.getMap(), trigger);
            if (ground != null) actions().navigate(entry, ground, true);
            return;
        }
        actions().stop(entry);
        if (AgentScriptItemActionService.dropItem(entry, InventoryType.ETC,
                AgentPpqDefinition.OLD_METAL_KEY, (short) 1)) {
            member.clearReactor(); member.deferUntil(nowMs + RETRY_MS); session.markProgress(nowMs);
        }
    }

    /**
     * After the clear (decision D-f'): while the leader is still in Wu Yang's room, a companion waits by Wu Yang (the
     * first there says E1 once) instead of leaving before him; once he left, or COMPANION_AFTER_CLEAR_WAIT_MS after the
     * clear, it takes Wu Yang's exit. True while it waits.
     */
    private static boolean waitByWuYang(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                        long nowMs) {
        Character leader = character(session.eventLeaderId());
        boolean leaderInRoom = leader != null && leader.getMapId() == AgentPpqDefinition.CLEAR_MAP;
        long clearedAt = session.clearedAtMs();
        long sinceClearMs = clearedAt < 0L ? 0L : nowMs - clearedAt;
        if (!AgentPpqCompanionPlay.waitByWuYang(leaderInRoom, sinceClearMs, COMPANION_AFTER_CLEAR_WAIT_MS)) {
            return false;
        }
        if (standNear(entry, agent, actions().npcPosition(agent, AgentPpqDefinition.RESCUE_NPC))) {
            AgentPpqCompanionRun.feathers(session, agent, leader);
        }
        return true;
    }

    /** Walks to {@code spot} and stands there; true once it stands within INTERACTION_RADIUS of it. */
    private static boolean standNear(AgentRuntimeEntry entry, Character agent, Point spot) {
        if (spot == null) {
            actions().stop(entry);
            return false;
        }
        if (!near(agent.getPosition(), spot)) {
            actions().navigate(entry, spot, true);
            return false;
        }
        actions().stop(entry);
        return true;
    }

    /**
     * A companion about to go down with no potion leaves the wave's medals it holds at its feet, once per holding
     * (M6, said once a wave between the companions), so its death does not delete them ('PPQ medals left:'). True
     * when it dropped them.
     */
    private static boolean leaveMedals(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                       AgentPpqMemberState member, int itemId, Character leader, long nowMs) {
        int held = Math.max(0, actions().itemCount(agent, itemId));
        if (held <= 0) return false;
        member.endHandOver();
        actions().stop(entry);
        if (!dropEtc(entry, itemId, (short) held)) return false;
        session.markProgress(nowMs);
        log.info("PPQ medals left: session={} member={} item={} count={} hp={}", session.sessionId(), agent.getName(),
                itemId, held, agent.getHp());
        AgentPpqDialogue.narrate(session, "M6-" + itemId, agent, leader, AgentPpqCompanionPlay.leavingMyMedals());
        return true;
    }

    private static boolean within(Point first, Point second, int px) {
        return first != null && second != null && first.distanceSq(second) <= (long) px * px;
    }

    /**
     * A hidden side room: an Agent fights beside a leader who stands in it while its mobs live, and otherwise goes back
     * to the deck by out00. Before this it stood idle here for good.
     */
    private static void sideRoom(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                                 AgentPpqMemberState member, long nowMs) {
        Character leader = character(session.eventLeaderId());
        boolean leaderHere = leader != null && leader.getHp() > 0 && leader.getMapId() == agent.getMapId();
        if (leaderHere && actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) {
            actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS);
            return;
        }
        usePortal(entry, agent, member, AgentPpqDefinition.SIDE_ROOM_EXIT_PORTAL, nowMs);
    }

    private static void chest(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                              AgentPpqMemberState member, long nowMs) {
        if (session.chestComplete(agent.getMapId())) {
            member.clearChestDrop();
            usePortal(entry, agent, member, 1, nowMs); return;
        }
        if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) {
            actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS); return;
        }
        if (agent.getId() != session.eventLeaderId()) {
            actions().stop(entry); return;
        }
        if (member.chestDropPending(agent.getMapId())) {
            actions().stop(entry); return;
        }
        Reactor chest = actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(Reactor::isAlive).filter(reactor -> AgentPpqDefinition.CHEST_REACTORS.contains(reactor.getId()))
                .findFirst().orElse(null);
        if (chest == null || !chest.isActive() || chest.getState() < 1) {
            if (runNpc(entry, agent, AgentPpqDefinition.GUIDE_NPC)) member.deferUntil(nowMs + RETRY_MS);
            return;
        }
        if (agent.getItemQuantity(AgentPpqDefinition.CHEST_KEY, false) > 0) {
            Point trigger = reactorDropPoint(chest);
            if (!chest.getArea().contains(agent.getPosition())) {
                Point ground = actions().groundPoint(agent.getMap(), trigger);
                if (ground != null) actions().navigate(entry, ground, true);
                return;
            }
            actions().stop(entry);
            if (AgentScriptItemActionService.dropItem(entry, InventoryType.ETC,
                    AgentPpqDefinition.CHEST_KEY, (short) 1)) {
                member.beginChestDrop(agent.getMapId());
                member.deferUntil(nowMs + RETRY_MS);
                session.markProgress(nowMs);
            }
            return;
        }
        session.markChestComplete(agent.getMapId(), nowMs);
        usePortal(entry, agent, member, 1, nowMs);
    }

    private static void doors(AgentPpqSession session, AgentRuntimeEntry entry, Character agent,
                              AgentPpqMemberState member, long nowMs) {
        if (collectObjectiveDrop(session, entry, agent,
                Set.of(AgentPpqDefinition.OLD_METAL_KEY), nowMs)) return;
        int totalKeys = session.members().stream().map(memberState -> character(memberState.characterId()))
                .filter(java.util.Objects::nonNull)
                .mapToInt(character -> character.getItemQuantity(AgentPpqDefinition.OLD_METAL_KEY, false)).sum();
        int openDoors = (int) actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(reactor -> AgentPpqDefinition.DOOR_REACTORS.contains(reactor.getId()))
                .filter(reactor -> reactor.getState() < 1).count();
        // Preserve every authored spawn point until the party has enough keys for all remaining doors.
        if (totalKeys < openDoors) {
            if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS);
            else actions().stop(entry);
            return;
        }
        Reactor door = committedDoor(agent, member.committedReactorObjectId());
        if (door == null) door = nearestUnclaimedDoor(session, agent);
        if (door != null && agent.getItemQuantity(AgentPpqDefinition.OLD_METAL_KEY, false) > 0) {
            member.commitReactor(door.getObjectId());
            Point trigger = reactorDropPoint(door);
            if (!door.getArea().contains(agent.getPosition())) {
                Point ground = actions().groundPoint(agent.getMap(), trigger);
                if (ground != null) actions().navigate(entry, ground, true);
                return;
            }
            actions().stop(entry);
            if (AgentScriptItemActionService.dropItem(entry, InventoryType.ETC,
                    AgentPpqDefinition.OLD_METAL_KEY, (short) 1)) {
                member.clearReactor(); member.deferUntil(nowMs + RETRY_MS); session.markProgress(nowMs);
            }
            return;
        }
        if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) {
            actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS); return;
        }
        if (openDoors == 0) usePortal(entry, agent, member, AgentPpqDefinition.nextPortalId(agent.getMapId()), nowMs);
        else actions().stop(entry);
    }

    /**
     * Objective drops must be pursued across the whole authored map.  A radius-only pickup can
     * strand medals or keys behind after ranged combat moves on to another platform.
     */
    private static boolean collectObjectiveDrop(AgentPpqSession session, AgentRuntimeEntry entry,
                                                Character agent, Set<Integer> itemIds, long nowMs) {
        if (agent.getMap() == null || agent.getPosition() == null) return false;
        MapItem target = AgentMapPerception.items(agent.getMap()).stream()
                .filter(drop -> !drop.isPickedUp() && itemIds.contains(drop.getItemId()))
                .filter(drop -> AgentLootEligibility.canBotTargetLoot(
                        entry, agent, agent.getMap(), drop, nowMs))
                .filter(drop -> nearestEligibleCollector(
                        session, drop, agent.getMapId(), nowMs) == agent.getId())
                .min(Comparator.comparingDouble(drop ->
                        drop.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
        if (target == null) return false;
        if (near(agent.getPosition(), target.getPosition())) {
            actions().stop(entry);
            int before = agent.getItemQuantity(target.getItemId(), false);
            agent.pickupItem(target);
            if (agent.getItemQuantity(target.getItemId(), false) > before) session.markProgress(nowMs);
        } else {
            Point ground = actions().groundPoint(agent.getMap(), target.getPosition());
            actions().navigate(entry, ground == null ? target.getPosition() : ground, true);
        }
        return true;
    }

    private static int nearestEligibleCollector(AgentPpqSession session, MapItem drop,
                                                int mapId, long nowMs) {
        return session.members().stream()
                .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull)
                .filter(candidate -> candidate.getMapId() == mapId && candidate.getPosition() != null)
                .filter(candidate -> {
                    AgentRuntimeEntry candidateEntry = entry(candidate.getId());
                    return candidateEntry != null && AgentLootEligibility.canBotTargetLoot(
                            candidateEntry, candidate, candidate.getMap(), drop, nowMs);
                })
                .min(Comparator.comparingDouble((Character candidate) ->
                                candidate.getPosition().distanceSq(drop.getPosition()))
                        .thenComparingInt(Character::getId))
                .map(Character::getId)
                .orElse(0);
    }

    private static void boss(AgentPpqSession session, AgentRuntimeEntry entry, Character agent) {
        if (actions().liveMonsterCount(agent, AgentPpqDefinition.COMBAT_MOBS) > 0) actions().grind(entry, AgentPpqDefinition.COMBAT_MOBS);
        else if (agent.getId() != session.eventLeaderId()) actions().stop(entry);
    }

    private static Reactor committedDoor(Character agent, int objectId) {
        if (objectId == 0) return null;
        return actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(reactor -> reactor.getObjectId() == objectId && reactor.getState() < 1).findFirst().orElse(null);
    }
    private static Reactor nearestUnclaimedDoor(AgentPpqSession session, Character agent) {
        Set<Integer> claimed = session.members().stream().map(AgentPpqMemberState::committedReactorObjectId)
                .filter(id -> id > 0).collect(java.util.stream.Collectors.toSet());
        return actions().reactors(agent).stream().filter(java.util.Objects::nonNull)
                .filter(reactor -> AgentPpqDefinition.DOOR_REACTORS.contains(reactor.getId()) && reactor.getState() < 1)
                .filter(reactor -> !claimed.contains(reactor.getObjectId()))
                .min(Comparator.comparingDouble(reactor -> reactor.getPosition().distanceSq(agent.getPosition())))
                .orElse(null);
    }
    private static void usePortal(AgentRuntimeEntry entry, Character agent, AgentPpqMemberState member,
                                  int portalId, long nowMs) {
        if (portalId < 0) { actions().stop(entry); return; }
        Point portal = actions().portalPosition(agent, portalId);
        if (!near(agent.getPosition(), portal)) { if (portal != null) actions().navigate(entry, portal, true); return; }
        actions().stop(entry);
        if (partyQuest().enterPortal(agent, portalId)) member.deferUntil(nowMs + RETRY_MS);
    }
    private static boolean runNpc(AgentRuntimeEntry entry, Character agent, int npcId, int... selections) {
        Point npc = actions().npcPosition(agent, npcId);
        if (!near(agent.getPosition(), npc)) { if (npc != null) actions().navigate(entry, npc, true); return false; }
        actions().stop(entry); return partyQuest().runNpc(agent, npcId, selections);
    }
    /**
     * Says a guide line once per run, from the first Agent online. Each Agent used to claim the key for itself, so the
     * same line came once from every companion on successive ticks.
     */
    private static void announce(AgentPpqSession session, String key, String message) {
        Character agent = session.members().stream()
                .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT)
                .map(member -> character(member.characterId()))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (agent == null || !session.narrateOnce("guide-" + key)) return;
        AgentPartyGatewayRuntime.party().sendPartyChat(agent, message);
    }
    private static void guideHumanLeader(AgentPpqSession session, Character leader) {
        int mapId = leader.getMapId();
        if (mapId == AgentPpqDefinition.MEDAL_MAP) {
            int medal = expectedMedal(leader);
            if (medal != 0) announce(session, "human-medal-" + medal,
                    AgentPpqMedalHandOver.waveLine(medal, leader.getName()));
        } else if (!session.skipChestRooms()
                && (mapId == AgentPpqDefinition.DECK_ONE_MAP || mapId == AgentPpqDefinition.DECK_TWO_MAP)
                && !session.chestCompleteForDeck(mapId)
                && leader.getItemQuantity(AgentPpqDefinition.CHEST_KEY, false) > 0
                && actions().liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS) == 0) {
            announce(session, "human-chest-" + mapId,
                    leader.getName() + ", take the chest-room portal, clear the room, talk to Guon, and drop a chest key by the chest.");
        }
    }
    /** 'PPQ phase:' for a phase change, in every mode; nothing for none. */
    static void logChange(AgentPpqSession session, AgentPpqSession.Change change) {
        if (change == null) return;
        log.info("PPQ phase: session={} {}->{} phaseMs={} runMs={} companionRun={}", session.sessionId(),
                change.from(), change.to(), change.phaseMs(), change.runMs(), session.companionAdopted());
    }

    /** 'PPQ stage:' when the leader stands on another stage, in every mode, with the medal wave the event is on. */
    private static void logStage(AgentPpqSession session, Character leader, long nowMs) {
        AgentPpqSession.StageChange change =
                session.observeStage(AgentPpqDefinition.stageOf(leader.getMapId()), nowMs);
        if (change == null) return;
        session.markProgress(nowMs);
        String wave = session.eventInstance() == null ? "-" : partyQuest().property(leader, "stage2");
        log.info("PPQ stage: session={} {}->{} stageMs={} runMs={} medalWave={} companionRun={}", session.sessionId(),
                change.from(), change.to(), change.stageMs(), change.runMs(), wave == null ? "-" : wave,
                session.companionAdopted());
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return 0; }
    }
    /**
     * Marks the progress the stock stages make outside the coordinator's own steps: a kill on any member's map, a
     * member's medals or keys changing hands (humans included), a door or chest reactor changing state. Everything is
     * read here, outside the session's monitor; the session compares it with the last pass.
     */
    private static void observeProgress(AgentPpqSession session, long nowMs) {
        Map<Integer, Integer> mobs = new HashMap<>();
        Map<String, Integer> items = new HashMap<>();
        Map<String, Integer> reactors = new HashMap<>();
        for (AgentPpqMemberState member : session.members()) {
            Character character = character(member.characterId());
            if (character == null) continue;
            int mapId = character.getMapId();
            if (AgentPpqDefinition.isEventMap(mapId) && !mobs.containsKey(mapId)) {
                mobs.put(mapId, actions().liveMonsterCount(character, AgentPpqDefinition.COMBAT_MOBS));
                if (mapId == AgentPpqDefinition.DOOR_MAP || mapId == AgentPpqDefinition.CHEST_ONE_MAP
                        || mapId == AgentPpqDefinition.CHEST_TWO_MAP) {
                    for (Reactor reactor : actions().reactors(character)) {
                        if (reactor != null) reactors.put(mapId + ":" + reactor.getObjectId(), (int) reactor.getState());
                    }
                }
            }
            for (int itemId : AgentPpqDefinition.PROGRESS_ITEMS) {
                items.put(member.characterId() + ":" + itemId, character.getItemQuantity(itemId, false));
            }
        }
        session.observeProgress(mobs, items, reactors, nowMs);
    }

    /**
     * Room for Wu Yang's Feather as his canHold sees it: a free Etc slot, or a Feather stack that is not full and carries
     * no owner tag (a GM's '!giveitem' stack is tagged with the GM's name and never takes another Feather).
     */
    static boolean roomForFeather(Character agent) {
        var etc = agent.getInventory(InventoryType.ETC);
        if (etc == null) return false;
        if (etc.getNumFreeSlot() > 0) return true;
        int slotMax = AgentInventoryGatewayRuntime.inventory().getSlotMax(agent, AgentPpqDefinition.WU_YANG_FEATHER);
        return etc.listById(AgentPpqDefinition.WU_YANG_FEATHER).stream()
                .anyMatch(item -> item.getQuantity() < slotMax && "".equals(item.getOwner()));
    }

    /** Drops {@code quantity} of an Etc item where the Agent stands, as a player does (the medal hand-over). */
    static boolean dropEtc(AgentRuntimeEntry entry, int itemId, short quantity) {
        return AgentScriptItemActionService.dropItem(entry, InventoryType.ETC, itemId, quantity);
    }

    static boolean near(Point first, Point second) {
        return first != null && second != null && first.distanceSq(second) <= (long) INTERACTION_RADIUS * INTERACTION_RADIUS;
    }
    private static Point reactorDropPoint(Reactor reactor) {
        Rectangle area = reactor.getArea();
        int x = area.x + Math.max(0, area.width - 1) / 2;
        int y = Math.max(area.y, Math.min(reactor.getPosition().y,
                area.y + Math.max(0, area.height - 1)));
        return new Point(x, y);
    }
    private static boolean isAgent(Character character) { return character != null && entry(character.getId()) != null; }
    private static AgentRuntimeEntry entry(int id) { return AgentRuntimeRegistry.findByAgentCharacterId(id); }
    public static Character character(int id) {
        AgentRuntimeEntry runtime = entry(id);
        Character agent = runtime == null ? null : AgentRuntimeIdentityRuntime.bot(runtime);
        return agent != null ? agent : AgentCharacterGatewayRuntime.characters().findOnlineCharacterById(id);
    }
}
