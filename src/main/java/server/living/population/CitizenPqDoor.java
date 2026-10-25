package server.living.population;

import client.Character;
import server.agents.capabilities.partyquest.kpq.AgentKpqAdmissionService;
import server.agents.capabilities.partyquest.kpq.AgentKpqLobbyPolicy;
import server.agents.integration.AgentClientGatewayRuntime;
import server.agents.integration.AgentPartyQuestGatewayRuntime;
import server.life.NPC;
import server.living.roster.PqEntrance;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * A party quest's door as a citizen party goes in by it (CitizenPartyQuests), keyed by its lobby id in regions.yaml:
 * the quest's own entry NPC, seats, admission words and registry (from its companion entrance, PqEntrance), the
 * engine's lobby policy, and the quest's citizen admission. KPQ's Lakelis only, for now.
 */
interface CitizenPqDoor {
    /** How admission went: on success the quest's session id; else why not, in admission's own words. */
    record Entered(boolean success, String message, String sessionId) { }

    String key();

    int minSeats();

    int maxSeats();

    /** Why the member cannot go in, in the quest's admission words; "" when it can. */
    String memberProblem(Character member, boolean leader);

    /** What already holds the leader or one of these characters in the quest's registry; "" when nothing does. */
    String registrationBlocker(int leaderId, Collection<Integer> characterIds);

    /**
     * True when a citizen party may take one of the quest's lobbies on {@code anchor}'s channel now: the engine's
     * background cap and its lobby kept for humans allow it, and one more stays free for each human party waiting.
     */
    boolean lobbyFree(Character anchor, int humanPartiesWaiting);

    /** Admits the party by the quest's citizen admission, {@code leader} as operator and event leader. */
    Entered admit(Character leader, List<Character> members, long seed, long nowMs);

    /** True while the quest's registry holds this character in the session {@code sessionId}. */
    boolean holds(int characterId, String sessionId);

    /** Where the {@code index}th citizen waits beside the entry NPC on {@code map}; null when she is not on it. */
    Point beside(MapleMap map, int index);

    /**
     * True when a human standing at {@code at} on {@code map} is close enough to the entry NPC for the quest's
     * coordinator to count them gathered (it never moves a human, and gives up on one who stays away).
     */
    boolean atEntry(MapleMap map, Point at);

    /** The doors that ship, by lobby id. */
    static Map<String, CitizenPqDoor> shipped() {
        return Map.of("kpq", new Kpq());
    }

    /** Lakelis, at the Kerning City party-quest door. */
    final class Kpq implements CitizenPqDoor {
        private static final String EVENT = "KerningPQ";

        private PqEntrance entrance() {
            return PqEntrance.of("kpq");
        }

        @Override
        public String key() {
            return "kpq";
        }

        @Override
        public int minSeats() {
            return entrance().seatRules().min();
        }

        @Override
        public int maxSeats() {
            return entrance().seatRules().max();
        }

        @Override
        public String memberProblem(Character member, boolean leader) {
            return entrance().memberProblem(member, leader);
        }

        @Override
        public String registrationBlocker(int leaderId, Collection<Integer> characterIds) {
            return entrance().registrationBlocker(leaderId, characterIds);
        }

        @Override
        public boolean lobbyFree(Character anchor, int humanPartiesWaiting) {
            int world = AgentClientGatewayRuntime.clients().world(anchor);
            int channel = AgentClientGatewayRuntime.clients().channel(anchor);
            if (!AgentKpqLobbyPolicy.backgroundSlotAvailable(world, channel, anchor)) {
                return false;
            }
            int occupied = AgentPartyQuestGatewayRuntime.partyQuest().openInstances(anchor, EVENT);
            return AgentKpqLobbyPolicy.maxLobbies() - occupied - 1 >= humanPartiesWaiting;
        }

        @Override
        public Entered admit(Character leader, List<Character> members, long seed, long nowMs) {
            AgentKpqAdmissionService.AdmissionResult result =
                    AgentKpqAdmissionService.admitCitizens(leader, members, seed, nowMs);
            return new Entered(result.success(), result.message(),
                    result.session() == null ? null : result.session().sessionId());
        }

        @Override
        public boolean holds(int characterId, String sessionId) {
            return entrance().holds(characterId, sessionId);
        }

        @Override
        public Point beside(MapleMap map, int index) {
            return entrance().beside(map, index);
        }

        /** Within the coordinator's own gather box around Lakelis (GATHER_RADIUS_PX, both axes). */
        @Override
        public boolean atEntry(MapleMap map, Point at) {
            NPC lakelis = map == null ? null : map.getNPCById(entrance().entryNpcId());
            Point npc = lakelis == null ? null : lakelis.getPosition();
            int radius = config.AgentTuning.intValue(
                    "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.GATHER_RADIUS_PX");
            return npc != null && at != null && Math.abs(at.x - npc.x) <= radius && Math.abs(at.y - npc.y) <= radius;
        }
    }
}
