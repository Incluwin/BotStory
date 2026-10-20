package server.agents.capabilities.partyquest.ppq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentModeStateRuntime;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.maps.MapItem;
import server.maps.Reactor;

import java.util.Objects;

/**
 * The run-level side of a PPQ companion run (P6i Steps 2 and 3), at the points the coordinator gates on
 * {@link AgentPpqSession#companionAdopted}: each companion's own prelude (out of FOLLOW for the whole run, full rate where
 * no player sees it) and the companions' coaching lines on the leader's map, each said once by a companion standing
 * there. Every read of a character, map, reactor or the event happens here, outside the session's monitor.
 */
final class AgentPpqCompanionRun {
    private static final Logger log = LoggerFactory.getLogger(AgentPpqCompanionRun.class);

    private AgentPpqCompanionRun() {
    }

    /**
     * One companion's own tick, before its stage play: never FOLLOW inside a run (a PQ session does not suspend
     * map-sync, which would pull it onto its leader's map past the doors and the chest rooms), and full rate while it
     * stands in the event on a map its leader is not on, or in Wu Yang's room after the clear.
     */
    static void memberPrelude(AgentPpqSession session, AgentRuntimeEntry entry, Character agent) {
        if (AgentModeStateRuntime.following(entry)) {
            AgentPpqCoordinator.actions().stop(entry);
            log.info("PPQ follow stopped: session={} member={}", session.sessionId(), agent.getId());
        }
        AgentPpqFullRateSimulation.apply(session, agent.getId(), entry, wantsFullRate(session, agent));
    }

    /** In the bound event away from the leader's map, or in Wu Yang's room (no event there after the clear). */
    static boolean wantsFullRate(AgentPpqSession session, Character agent) {
        if (agent.getMapId() == AgentPpqDefinition.CLEAR_MAP) return true;
        EventInstanceManager event = session.eventInstance();
        if (event == null || agent.getEventInstance() != event) return false;
        Character leader = AgentPpqCoordinator.character(session.eventLeaderId());
        return leader == null || leader.getMapId() != agent.getMapId();
    }

    /**
     * A companion standing on the leader's map, alive and in the run's event (any companion there once the event is
     * past), lowest id first; null when none is. The coaching lines wait for one.
     */
    static Character speakerBeside(AgentPpqSession session, Character leader) {
        if (leader == null) return null;
        EventInstanceManager event = session.eventInstance();
        return session.members().stream()
                .filter(member -> member.memberType() == AgentPpqMemberState.MemberType.AGENT
                        && member.characterId() != leader.getId())
                .map(member -> AgentPpqCoordinator.character(member.characterId()))
                .filter(Objects::nonNull)
                .filter(agent -> agent.getHp() > 0 && agent.getMapId() == leader.getMapId())
                .filter(agent -> event == null || event.isEventCleared() || agent.getEventInstance() == event)
                .min(java.util.Comparator.comparingInt(Character::getId))
                .orElse(null);
    }

    /**
     * The companions' coaching on the leader's map (plan 7.3-7.8, lines S1, M1, M5, C1, K1, B1, B2, B3), each once per
     * run (M1 and M5 once per wave, C1 once per chest room), said by a companion standing beside him. Nothing while
     * the run's guidance is off. Never throws.
     */
    static void coach(AgentPpqSession session, Character leader) {
        if (leader == null || leader.getHp() <= 0 || !session.memberCoordinationChatEnabled()) return;
        try {
            Character speaker = speakerBeside(session, leader);
            if (speaker == null) return;
            var actions = AgentPpqCoordinator.actions();
            String name = leader.getName();
            int mapId = leader.getMapId();
            switch (mapId) {
                case AgentPpqDefinition.ENTRY_MAP ->
                        AgentPpqDialogue.narrate(session, "S1", speaker, leader, AgentPpqCompanionPlay.stageOne());
                case AgentPpqDefinition.MEDAL_MAP -> {
                    int medal = AgentPpqCoordinator.expectedMedal(leader);
                    if (medal == 0) return;
                    AgentPpqDialogue.narrate(session, "M1-" + medal, speaker, leader,
                            AgentPpqCompanionPlay.waveStart(medal));
                    if (actions.itemCount(leader, medal) >= AgentPpqDefinition.MEDALS_PER_WAVE) {
                        AgentPpqDialogue.narrate(session, AgentPpqMedalHandOver.twentyKey(medal), speaker, leader,
                                AgentPpqCompanionPlay.allOfThem());
                    }
                }
                case AgentPpqDefinition.CHEST_ONE_MAP, AgentPpqDefinition.CHEST_TWO_MAP -> {
                    if (actions.liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS) == 0
                            && actions.itemCount(leader, AgentPpqDefinition.CHEST_KEY) > 0 && !chestOpen(leader)) {
                        AgentPpqDialogue.narrate(session, "C1-" + mapId, speaker, leader,
                                AgentPpqCompanionPlay.openTheChest());
                    }
                }
                case AgentPpqDefinition.DOOR_MAP -> {
                    int closed = closedDoors(leader);
                    if (AgentPpqCompanionPlay.leaderKeyWanted(actions.itemCount(leader, AgentPpqDefinition.OLD_METAL_KEY),
                            companionKeys(session), closed)) {
                        AgentPpqDialogue.narrate(session, "K1", speaker, leader,
                                AgentPpqCompanionPlay.dropYourKey(name));
                    }
                }
                case AgentPpqDefinition.BOSS_MAP -> {
                    if (actions.liveMonsterCount(leader, AgentPpqDefinition.LORD_PIRATES) > 0) {
                        AgentPpqDialogue.narrate(session, "B1", speaker, leader, AgentPpqCompanionPlay.lordPirate(name));
                    } else if (actions.liveMonsterCount(leader, AgentPpqDefinition.COMBAT_MOBS) == 0) {
                        // the stock "talk to Wu Yang after Lord Pirate falls" site, in the companions' words
                        AgentPpqDialogue.narrate(session, "B2", speaker, leader,
                                AgentPpqCompanionPlay.lordPirateDown(name));
                    }
                    if (actions.itemCount(leader, AgentPpqDefinition.CHEST_KEY) == 0 && chestKeyLying(leader)) {
                        AgentPpqDialogue.narrate(session, "B3", speaker, leader,
                                AgentPpqCompanionPlay.chestKeyDropped(name));
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException failure) {
            log.debug("PPQ coaching skipped: session={}", session.sessionId(), failure);
        }
    }

    /** C0, once per deck: a companion waiting by the chest room's portal tells the leader on that deck why. */
    static void chestRoomHere(AgentPpqSession session, Character agent, Character leader) {
        if (leader == null || leader.getHp() <= 0 || leader.getMapId() != agent.getMapId()) return;
        AgentPpqDialogue.narrate(session, "C0-" + agent.getMapId(), agent, leader,
                AgentPpqCompanionPlay.chestRoomHere(leader.getName()));
    }

    /** W1, once: the companions go through the boss door with the leader beside it. */
    static void bossDoor(AgentPpqSession session, Character agent, Character leader) {
        if (leader == null || leader.getMapId() != agent.getMapId()) return;
        AgentPpqDialogue.narrate(session, "W1", agent, leader, AgentPpqCompanionPlay.bossDoor(leader.getName()));
    }

    /** E1, once: the first companion by Wu Yang after the clear. */
    static void feathers(AgentPpqSession session, Character agent, Character leader) {
        AgentPpqDialogue.narrate(session, "E1", agent, leader, AgentPpqCompanionPlay.feathers());
    }

    /** The Old Metal Keys the run's companions hold now. */
    static int companionKeys(AgentPpqSession session) {
        int keys = 0;
        for (AgentPpqMemberState member : session.members()) {
            if (member.memberType() != AgentPpqMemberState.MemberType.AGENT
                    || member.characterId() == session.eventLeaderId()) {
                continue;
            }
            Character agent = AgentPpqCoordinator.character(member.characterId());
            if (agent != null) keys += Math.max(0, AgentPpqCoordinator.actions().itemCount(agent,
                    AgentPpqDefinition.OLD_METAL_KEY));
        }
        return keys;
    }

    /** The Old Metal Keys lying on this character's map, not picked up yet. */
    static int floorKeys(Character on) {
        if (on == null || on.getMap() == null) return 0;
        int keys = 0;
        for (MapItem drop : AgentMapPerception.items(on.getMap())) {
            if (drop != null && !drop.isPickedUp() && drop.getItemId() == AgentPpqDefinition.OLD_METAL_KEY) {
                keys += drop.getItem() == null ? 1 : Math.max(1, drop.getItem().getQuantity());
            }
        }
        return keys;
    }

    /** The door map's doors still shut (a door reactor below state 1). */
    static int closedDoors(Character on) {
        int closed = 0;
        for (Reactor reactor : AgentPpqCoordinator.actions().reactors(on)) {
            if (reactor != null && AgentPpqDefinition.DOOR_REACTORS.contains(reactor.getId())
                    && reactor.getState() < 1) {
                closed++;
            }
        }
        return closed;
    }

    /** Whether the chest room's chest is up and open for a key (the coordinator's own Agent-leader test). */
    private static boolean chestOpen(Character on) {
        for (Reactor reactor : AgentPpqCoordinator.actions().reactors(on)) {
            if (reactor != null && reactor.isAlive() && AgentPpqDefinition.CHEST_REACTORS.contains(reactor.getId())) {
                return reactor.isActive() && reactor.getState() >= 1;
            }
        }
        return false;
    }

    /** A chest key lying on this character's map. */
    private static boolean chestKeyLying(Character on) {
        if (on.getMap() == null) return false;
        for (MapItem drop : AgentMapPerception.items(on.getMap())) {
            if (drop != null && !drop.isPickedUp() && drop.getItemId() == AgentPpqDefinition.CHEST_KEY) return true;
        }
        return false;
    }

    /** The runtime entry of an Agent, or null. */
    static AgentRuntimeEntry entry(int id) {
        return AgentRuntimeRegistry.findByAgentCharacterId(id);
    }
}
