package server.agents.capabilities.partyquest.opq;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.integration.PrimitiveCapabilityGateway;
import server.agents.perception.AgentMapPerception;
import server.agents.runtime.AgentRuntimeEntry;

import java.awt.Point;
import java.util.List;

/**
 * 2026-10-18: the garden, the Root and Minerva in a companion run its human leader leads (OPQ plan Step 2d). The stock
 * flow made the event leader the garden's seeder and the Root's carrier. Here the lowest-id companion standing in the
 * garden plants (the Strange Seeds in the pots, the trigger, the Even Stranger Seed in the pot of life at (-755,19))
 * and brings him the Root of Life at his feet (AgentOpqHandOver with a quota of one), since party3_gardenin.js lets
 * only the event leader holding it through the exit. In the centre he drops it and a companion takes it up to
 * Minerva's statue (decision O5), or he climbs with it; the run moves on once the Root is in the centre, held or lying,
 * not only once it is in his bag. The claims begin with the clear, wherever he is: the companions wait in the centre
 * while he is there, follow him into the hidden room (in06) and fight beside him, and claim once he goes anywhere else
 * (Minerva's reward map, the jail, the treasure room, out) or the bonus timer sends the team to Minerva; nothing waits
 * for him to finish. Gated on AgentOpqConductor.conducts; the stock six-Agent flow is unchanged.
 */
final class AgentOpqConductedGarden {
    private static final Logger log = LoggerFactory.getLogger(AgentOpqConductedGarden.class);
    /** The garden's exit (in00, party3_gardenin.js): where a Root left for a leader out of reach lies. */
    static final Point GARDEN_EXIT = new Point(-109, 560);
    /** The garden's objective items the seeder keeps, in the stock seeder's loot order. */
    static final List<Integer> SEEDER_ITEMS = List.of(AgentOpqDefinition.ROOT_OF_LIFE,
            AgentOpqDefinition.EVEN_STRANGER_SEED, AgentOpqDefinition.TRANSPARENT_TRIGGER,
            AgentOpqDefinition.STRANGE_SEED);

    /** What the companions do after the clear. */
    enum AfterClear { WAIT, FOLLOW, CLAIM }

    private AgentOpqConductedGarden() {
    }

    /** The seeder: the lowest-id counted companion standing in the garden (loaded, up, on its map); 0 when none. */
    static int seeder(AgentOpqSession session, List<AgentOpqMemberState> companions) {
        return companions.stream()
                .filter(member -> member.memberType() == AgentOpqMemberState.MemberType.AGENT
                        && !session.departed(member.characterId()))
                .map(member -> AgentOpqCoordinator.character(member.characterId()))
                .filter(character -> character != null && character.getHp() > 0
                        && character.getMapId() == AgentOpqDefinition.GARDEN_MAP)
                .mapToInt(Character::getId).min().orElse(0);
    }

    /**
     * The seeder's custody of the garden's items, each pass: a kind nobody keeps, or kept by a companion that holds
     * none of it now (it planted its seed, handed the Root over, or stopped seeding), is reserved for the seeder, so it
     * loots every Strange Seed in turn and takes over from a seeder that went down. A kind in its keeper's bag stays
     * that keeper's.
     */
    static void reserveSeeds(AgentOpqSession session, Character seeder, long nowMs) {
        for (int item : SEEDER_ITEMS) {
            AgentOpqLootLedger.Reservation current = session.loot().reservation(item);
            if (current != null && current.state() == AgentOpqLootLedger.State.RESERVED
                    && current.ownerId() == seeder.getId()) {
                continue;
            }
            if (current != null && current.state() != AgentOpqLootLedger.State.DELIVERED) {
                Character keeper = current.ownerId() == seeder.getId() ? seeder
                        : AgentOpqCoordinator.character(current.ownerId());
                if (keeper != null && keeper.getItemQuantity(item, false) > 0) continue;
                session.loot().release(item, current.ownerId());
            }
            session.loot().reserve(item, seeder.getId(), AgentOpqDefinition.GARDEN_MAP, nowMs);
        }
    }

    /**
     * One garden pass for a companion once Papa Pixie is not up: the Root it holds goes to the leader at his feet (by
     * the exit when he cannot be reached), and a Root lying for him is pointed out, then picked back up before it can
     * expire (its dropper while it stands here, else the lowest-id companion here). True when this owns the pass.
     */
    static boolean handOverRoot(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                                AgentOpqMemberState member, long nowMs) {
        return AgentOpqHandOver.tick(session, entry, agent, member, AgentOpqDefinition.ROOT_OF_LIFE, 1,
                AgentOpqDefinition.GARDEN_MAP, GARDEN_EXIT, nowMs);
    }

    /** The garden's lines (coordinator pass, he in the garden): the seeder's start once, Papa Pixie's arrival once. */
    static void guideGarden(AgentOpqSession session, Character leader, int seederId, boolean papaPixieAlive) {
        if (leader == null || leader.getMapId() != AgentOpqDefinition.GARDEN_MAP) return;
        if (papaPixieAlive) {
            Character speaker = AgentOpqConductor.companionOn(session, AgentOpqDefinition.GARDEN_MAP);
            if (speaker != null) {
                AgentOpqDialogue.narrate(session, "papa-pixie", speaker, leader, AgentOpqCompanionPlay.papaPixieOut());
            }
            return;
        }
        Character seeder = seederId == 0 || session.papaPixieEngaged() ? null
                : AgentOpqCoordinator.character(seederId);
        if (seeder != null) {
            AgentOpqDialogue.narrate(session, "garden-seeds", seeder, leader, AgentOpqCompanionPlay.seeds());
        }
    }

    /**
     * The Root is in the centre: the leader stands there and a member there holds it (he, or the companion taking it
     * up), or it lies on his centre map.
     */
    static boolean rootInCentre(AgentOpqSession session, Character leader) {
        if (leader == null || leader.getMapId() != AgentOpqDefinition.CENTER_MAP) return false;
        for (AgentOpqMemberState member : AgentOpqCoordinator.counted(session)) {
            Character character = AgentOpqCoordinator.character(member.characterId());
            if (character != null && character.getMapId() == AgentOpqDefinition.CENTER_MAP
                    && character.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false) > 0) {
                return true;
            }
        }
        return leader.getMap() != null && AgentMapPerception.items(leader.getMap()).stream()
                .anyMatch(drop -> drop != null && !drop.isPickedUp()
                        && drop.getItemId() == AgentOpqDefinition.ROOT_OF_LIFE);
    }

    /** He is in the centre with the Root (coordinator pass): a companion there asks for it, once. */
    static void guideRoot(AgentOpqSession session, Character leader) {
        if (leader == null || leader.getMapId() != AgentOpqDefinition.CENTER_MAP
                || leader.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false) <= 0) {
            return;
        }
        Character speaker = AgentOpqConductor.companionOn(session, AgentOpqDefinition.CENTER_MAP);
        if (speaker != null) {
            AgentOpqDialogue.narrate(session, "root-drop", speaker, leader,
                    AgentOpqCompanionPlay.dropTheRoot(leader.getName()));
        }
    }

    /**
     * A companion in the centre while the Root is on its way up (RESTORING_MINERVA): one holding it takes it to
     * Minerva's statue and drops it on the base (reactor 2006001), the Root he dropped is claimed and picked up by one
     * companion (AgentOpqConductor.collectLeaderPiece), and the rest stand by. Nobody talks to Minerva before the
     * clear.
     */
    static void tickRoot(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                         AgentOpqMemberState member, long nowMs) {
        if (agent.getMapId() != AgentOpqDefinition.CENTER_MAP) return;
        if (agent.getItemQuantity(AgentOpqDefinition.ROOT_OF_LIFE, false) > 0) {
            if (AgentOpqCoordinator.dropAt(entry, agent, AgentOpqDefinition.ROOT_OF_LIFE,
                    AgentOpqCoordinator.MINERVA_BASE, nowMs, member)) {
                session.markProgress(nowMs);
                logRoot(session, agent);
            }
            return;
        }
        if (AgentOpqConductor.collectLeaderPiece(session, entry, agent, member, nowMs)) return;
        AgentOpqCoordinator.actions().stop(entry);
    }

    /**
     * After the clear, by where the leader is: in the centre the companions wait for him, in the hidden room they are
     * with him; anywhere else (Minerva's reward map, the jail, the treasure room, out of the event), or down, they
     * claim. Pure.
     */
    static AfterClear afterClear(boolean leaderInEvent, int leaderHp, int leaderMapId) {
        if (!leaderInEvent || leaderHp <= 0) return AfterClear.CLAIM;
        if (leaderMapId == AgentOpqDefinition.CENTER_MAP) return AfterClear.WAIT;
        if (leaderMapId == AgentOpqDefinition.HIDDEN_ROOM_MAP) return AfterClear.FOLLOW;
        return AfterClear.CLAIM;
    }

    static AfterClear afterClear(AgentOpqSession session) {
        Character leader = AgentOpqCoordinator.character(session.eventLeaderId());
        EventInstanceManager event = session.eventInstance();
        boolean inEvent = leader != null && event != null && leader.getEventInstance() == event;
        return afterClear(inEvent, leader == null ? 0 : leader.getHp(), leader == null ? 0 : leader.getMapId());
    }

    /**
     * The after-clear lines (coordinator pass in CLAIMING_REWARD): Minerva is free, once, while he is in the centre;
     * the claims, once, when they begin.
     */
    static void guideAfterClear(AgentOpqSession session, Character leader) {
        if (leader == null) return;
        AfterClear decision = afterClear(session);
        if (decision == AfterClear.WAIT) {
            Character speaker = AgentOpqConductor.companionOn(session, AgentOpqDefinition.CENTER_MAP);
            if (speaker != null) {
                AgentOpqDialogue.narrate(session, "minerva-free", speaker, leader, AgentOpqCompanionPlay.minervaFree());
            }
        } else if (decision == AfterClear.CLAIM) {
            EventInstanceManager event = session.eventInstance();
            Character speaker = AgentOpqConductor.companions(session).stream()
                    .map(member -> AgentOpqCoordinator.character(member.characterId()))
                    .filter(character -> character != null && event != null && character.getEventInstance() == event)
                    .findFirst().orElse(null);
            if (speaker != null) {
                AgentOpqDialogue.narrate(session, "claims", speaker, leader, AgentOpqCompanionPlay.grabOurRewards());
            }
        }
    }

    /**
     * A companion's claim pass after the clear (CLAIMING_REWARD). True while it holds its claim back: it waits in the
     * centre while he is there (coming back out of the hidden room), or follows him into the hidden room and fights its
     * monsters beside him. False once it claims, through the stock claim (the centre's Minerva, then hers on the reward
     * map); one already on the reward map claims at once, one in the hidden room comes back to the centre first.
     */
    static boolean holdClaim(AgentOpqSession session, AgentRuntimeEntry entry, Character agent,
                             AgentOpqMemberState member, long nowMs) {
        int mapId = agent.getMapId();
        if (mapId == AgentOpqDefinition.CLEAR_MAP) return false;
        AfterClear decision = afterClear(session);
        PrimitiveCapabilityGateway actions = AgentOpqCoordinator.actions();
        if (mapId == AgentOpqDefinition.HIDDEN_ROOM_MAP) {
            if (decision != AfterClear.FOLLOW) {
                AgentOpqCoordinator.enterAuthoredPortal(entry, agent, AgentOpqDefinition.HIDDEN_ROOM_EXIT_PORTAL, nowMs,
                        member);
            } else if (actions.liveMonsterCount(agent, AgentOpqDefinition.HIDDEN_ROOM_MOBS) > 0) {
                actions.grind(entry, AgentOpqDefinition.HIDDEN_ROOM_MOBS);
            } else {
                actions.stop(entry);
            }
            return true;
        }
        if (mapId != AgentOpqDefinition.CENTER_MAP || decision == AfterClear.CLAIM) return false;
        if (decision == AfterClear.FOLLOW) {
            AgentOpqCoordinator.enterAuthoredPortal(entry, agent, AgentOpqDefinition.HIDDEN_ROOM_PORTAL, nowMs, member);
        } else {
            actions.stop(entry);
        }
        return true;
    }

    /** 'OPQ root: session= by=' when a companion drops the Root on Minerva's statue. Logging only; never throws. */
    private static void logRoot(AgentOpqSession session, Character carrier) {
        try {
            log.info("OPQ root: session={} by={}", session.sessionId(), carrier.getName());
        } catch (RuntimeException ignored) {
            // a log line never changes a run
        }
    }
}
