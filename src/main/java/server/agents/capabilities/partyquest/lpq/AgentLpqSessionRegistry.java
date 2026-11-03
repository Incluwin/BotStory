package server.agents.capabilities.partyquest.lpq;

import client.BuffStat;
import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.agents.capabilities.partyquest.AgentPartyQuestScriptCallbacks;
import server.maps.MapItem;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** LPQ-only complete session and member indexes. */
public final class AgentLpqSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(AgentLpqSessionRegistry.class);
    private static final Map<String, AgentLpqSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_MEMBER = new ConcurrentHashMap<>();
    private static final Map<Integer, String> BY_OPERATOR = new ConcurrentHashMap<>();

    private AgentLpqSessionRegistry() { }

    public static synchronized void registerComplete(AgentLpqSession session) {
        if (session == null) throw new IllegalArgumentException("LPQ session is required");
        if (BY_OPERATOR.containsKey(session.operatorId())) throw new IllegalStateException("operator already owns LPQ");
        for (AgentLpqMemberState member : session.members()) {
            if (BY_MEMBER.containsKey(member.characterId())) throw new IllegalStateException("LPQ member already reserved");
        }
        SESSIONS.put(session.sessionId(), session);
        BY_OPERATOR.put(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.put(member.characterId(), session.sessionId()));
    }

    public static synchronized String registrationBlocker(int operatorId, Collection<Integer> ids) {
        if (BY_OPERATOR.containsKey(operatorId)) return "operator-session";
        if (ids != null) for (int id : ids) if (BY_MEMBER.containsKey(id)) return "member-" + id;
        return "";
    }

    public static AgentLpqSession forMember(int id) {
        String sessionId = BY_MEMBER.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    public static AgentLpqSession forOperator(int id) {
        String sessionId = BY_OPERATOR.get(id);
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    public static Collection<AgentLpqSession> sessions() { return java.util.List.copyOf(SESSIONS.values()); }
    public static boolean active(int id) { return forMember(id) != null; }

    /** Drops one member's index entry for this session (a member taken out of the run), and nothing else. */
    public static synchronized void unindexMember(AgentLpqSession session, int characterId) {
        if (session == null) return;
        BY_MEMBER.remove(characterId, session.sessionId());
    }

    public static synchronized void remove(AgentLpqSession session) {
        if (session == null) return;
        if (!SESSIONS.remove(session.sessionId(), session)) return;
        BY_OPERATOR.remove(session.operatorId(), session.sessionId());
        session.members().forEach(member -> BY_MEMBER.remove(member.characterId(), session.sessionId()));
    }

    public static boolean canLootExclusive(Character character, int itemId) {
        if (character == null || (itemId != AgentLpqDefinition.PASS && itemId != AgentLpqDefinition.BOSS_KEY)) return false;
        AgentLpqSession session = forMember(character.getId());
        if (session == null || session.eventInstance() == null
                || character.getEventInstance() != session.eventInstance()
                || !AgentLpqDefinition.isEventMap(character.getMapId())) return false;
        int stage = AgentLpqDefinition.stageNumber(character.getMapId());
        if (itemId == AgentLpqDefinition.BOSS_KEY) {
            return character.getId() == session.eventLeaderId();
        }
        if (itemId == AgentLpqDefinition.PASS && session.couponRegrouping(stage)) {
            return character.getId() == session.eventLeaderId();
        }
        return true;
    }

    /**
     * Today's rule for this drop, and in a companion run a precise one: a pass a player dropped (a companion's
     * hand-over at the leader's feet) is the leader's, so a member takes it back only while it re-collects that stack;
     * Alishar's key is the key carrier's to loot (its leader is human and never asks), and its own dropped key only on
     * a re-collect. Outside companion runs this is exactly {@link #canLootExclusive(Character, int)}.
     */
    public static boolean canLootExclusive(Character character, MapItem drop) {
        if (character == null || drop == null) return false;
        int itemId = drop.getItemId();
        if (itemId != AgentLpqDefinition.PASS && itemId != AgentLpqDefinition.BOSS_KEY) return false;
        AgentLpqSession session = forMember(character.getId());
        if (session == null || !session.companionAdopted()) return canLootExclusive(character, itemId);
        if (session.eventInstance() == null || character.getEventInstance() != session.eventInstance()
                || !AgentLpqDefinition.isEventMap(character.getMapId())) return false;
        boolean recollecting = session.recollecting(character.getId(), drop.getObjectId());
        if (itemId == AgentLpqDefinition.PASS) {
            int stage = AgentLpqDefinition.stageNumber(character.getMapId());
            boolean today = !session.couponRegrouping(stage) || character.getId() == session.eventLeaderId();
            return today && (!drop.isPlayerDrop() || recollecting);
        }
        return (!drop.isPlayerDrop() && character.getId() == session.keyCarrierId()) || recollecting;
    }

    /** Keeps the junk-item or 10-meso door claims visible during Stage 4/5 room fan-out. */
    public static boolean preservesRoomDoorMarker(Character character, MapItem drop) {
        if (character == null || drop == null
                || (drop.getMeso() != AgentLpqDefinition.ROOM_MARKER_MESOS
                && !AgentLpqDefinition.ROOM_MARKER_ITEMS.contains(drop.getItemId()))) return false;
        AgentLpqSession session = forMember(character.getId());
        if (session == null || session.eventInstance() == null
                || character.getEventInstance() != session.eventInstance()) return false;
        return switch (session.phase()) {
            case STAGE_4 -> character.getMapId() == AgentLpqDefinition.stage(4).mapId();
            case STAGE_5 -> character.getMapId() == AgentLpqDefinition.stage(5).mapId();
            default -> false;
        };
    }

    /** Dark Sight makes the authored Stage 5 hazard fully non-interactive for LPQ Agents. */
    public static boolean suppressesDarkSightRoomTouch(Character character) {
        AgentLpqSession session = character == null ? null : forMember(character.getId());
        AgentLpqMemberState member = session == null ? null : session.member(character.getId());
        return character != null
                && session != null
                && session.phase() == AgentLpqSession.Phase.STAGE_5
                && session.eventInstance() != null
                && character.getEventInstance() == session.eventInstance()
                && character.getMapId() == AgentLpqDefinition.STAGE_5_DARK_SIGHT_ROOM
                && character.getBuffedValue(BuffStat.DARKSIGHT) != null
                && member != null
                && member.memberType() == AgentLpqMemberState.MemberType.AGENT;
    }

    public static boolean isManagedEvent(Character character) {
        return managedSession(character) != null;
    }

    public static boolean isRegisteredParticipant(Character character) {
        AgentLpqSession session = managedSession(character);
        return character != null && session != null && session.member(character.getId()) != null;
    }

    public static boolean beginRewardClaim(Character character) {
        AgentLpqSession session = managedSession(character);
        if (character != null && session != null && !session.rewardEligibilityFrozen()
                && character.getEventInstance().isEventCleared()) {
            session.freezeRewardEligibility();
        }
        return character != null && session != null && session.beginRewardClaim(character.getId());
    }

    public static boolean completeRewardClaim(Character character) {
        AgentLpqSession session = managedSession(character);
        return character != null && session != null && session.completeRewardClaim(character.getId());
    }

    public static void cancelRewardClaim(Character character) {
        AgentLpqSession session = managedSession(character);
        if (character != null && session != null) session.cancelRewardClaim(character.getId());
    }

    /** LudiPQ.js playerUnregistered, under the script's lock: never waits on the session there. */
    public static void forfeitUnclaimedReward(Character character) {
        AgentLpqSession session = character == null ? null : forMember(character.getId());
        if (session != null && session.eventInstance() == character.getEventInstance()) {
            int characterId = character.getId();
            Unregistered seen = Unregistered.of(character);
            AgentPartyQuestScriptCallbacks.update(session, () -> {
                session.forfeitReward(characterId);
                logUnregister(session, characterId, seen);
            });
        }
    }

    /** The member as the script unregistered it, read on the script's thread before the removal; null if unreadable. */
    private record Unregistered(String name, int mapId, int hp, int registeredBefore, boolean cleared) {
        static Unregistered of(Character character) {
            try {
                EventInstanceManager event = character.getEventInstance();
                return new Unregistered(character.getName(), character.getMapId(), character.getHp(),
                        event.getPlayerCount(), event.isEventCleared());
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * One 'LPQ unregister:' line per member LudiPQ.js unregisters: its playerUnregistered calls forfeitUnclaimedReward
     * before the removal, so registeredBefore is the count the script's floor check saw. Never throws.
     */
    private static void logUnregister(AgentLpqSession session, int characterId, Unregistered seen) {
        try {
            if (seen == null) throw new IllegalStateException("unreadable when unregistered");
            AgentLpqMemberState member = session.member(characterId);
            log.info("LPQ unregister: session={} member={}({}) type={} map={} hp={} registeredBefore={} cleared={} "
                            + "phase={}", session.sessionId(), seen.name(), characterId,
                    member == null ? "UNKNOWN" : member.memberType(), seen.mapId(), seen.hp(),
                    seen.registeredBefore(), seen.cleared(), session.phase());
        } catch (RuntimeException e) {
            log.info("LPQ unregister: session={} member={} detail unavailable", session.sessionId(), characterId);
        }
    }

    private static AgentLpqSession managedSession(Character character) {
        if (character == null || character.getEventInstance() == null) return null;
        return SESSIONS.values().stream()
                .filter(session -> session.eventInstance() == character.getEventInstance())
                .findFirst().orElse(null);
    }
}
