package server.living.population;

import client.Character;
import server.agents.capabilities.partyquest.hpq.AgentHpqAdmissionService;
import server.agents.capabilities.partyquest.hpq.AgentHpqLobbyPolicy;
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
 * engine's lobby policy, and the quest's citizen admission. KPQ's Lakelis and HPQ's Tory.
 */
interface CitizenPqDoor {
    /** How admission went: on success the quest's session id; else why not, in admission's own words. */
    record Entered(boolean success, String message, String sessionId) { }

    String key();

    /**
     * A boss expedition's door (CitizenBossDoor): its run signs up and starts over several ticks, and its runs count
     * against BOSS_RUNS_MAX instead of the party-quest caps.
     */
    default boolean expedition() {
        return false;
    }

    int minSeats();

    int maxSeats();

    /** How many a party fills to before it goes in (it waits a while for them once it has the minimum). */
    default int preferredSeats() {
        return maxSeats();
    }

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
        return Map.of("kpq", new Kpq(), "hpq", new Hpq());
    }

    /**
     * A boss expedition's door (docs/CITIZEN_EXPEDITIONS_PLAN.md, CitizenBossDoor): {@link #admit} opens the list and
     * its run; the members sign up over the next ticks, and the population asks the run to start once {@link #ready}.
     */
    interface ExpeditionDoor extends CitizenPqDoor {
        /** Where a citizen expedition's list stands, as the population reads it each tick. */
        enum Stage {
            /** Its list is open: members still walking up and signing up, or all listed and waiting for the Start. */
            SIGNING_UP,
            /** Its leader's Start went through: the fight, or after it. */
            STARTED,
            /** The run is gone (its end was told, or is about to be). */
            GONE
        }

        @Override
        default boolean expedition() {
            return true;
        }

        /** True while citizens may run any boss here (the switch, its list, and the server moving the boss). */
        boolean enabled();

        Stage stage(String sessionId);

        /**
         * True when the run may start: its citizens listed and none still walking up, and its humans listed too or
         * {@code waitedLong}.
         */
        boolean ready(String sessionId, Collection<Integer> citizens, Collection<Integer> humans, boolean waitedLong);

        /** True when human {@code humanId} is on the run's list. */
        boolean listed(String sessionId, int humanId);

        /** Asks the run to start (its leader talks to the entry NPC); false once it is gone or past its list. */
        boolean start(String sessionId);

        /** A citizen arriving here buys what it lacks of the boss's potions; true when it then carries them. */
        boolean provision(Character agent);

        /** A boss key as players say it ("Easy Balrog"). */
        String label(String bossKey);
    }

    /** What every door reads from its quest's companion entrance (PqEntrance). */
    abstract class ByEntrance implements CitizenPqDoor {
        private final String key;

        ByEntrance(String key) {
            this.key = key;
        }

        PqEntrance entrance() {
            return PqEntrance.of(key);
        }

        @Override
        public String key() {
            return key;
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
        public int preferredSeats() {
            return entrance().seatRules().preferred();
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
        public boolean holds(int characterId, String sessionId) {
            return entrance().holds(characterId, sessionId);
        }

        @Override
        public Point beside(MapleMap map, int index) {
            return entrance().beside(map, index);
        }

        /** Within the quest coordinator's own gather box around its entry NPC (both axes). */
        boolean withinGatherBox(MapleMap map, Point at, String radiusKey) {
            NPC npc = map == null ? null : map.getNPCById(entrance().entryNpcId());
            Point spot = npc == null ? null : npc.getPosition();
            int radius = config.AgentTuning.intValue(radiusKey);
            return spot != null && at != null && Math.abs(at.x - spot.x) <= radius && Math.abs(at.y - spot.y) <= radius;
        }

        /**
         * After this party takes a lobby, one still stays free for each human party waiting, and the instances open
         * now (human ones included) leave more than {@code reserved} free.
         */
        static boolean lobbiesLeft(Character anchor, String event, int maxLobbies, int reserved, int humanPartiesWaiting) {
            int free = maxLobbies - AgentPartyQuestGatewayRuntime.partyQuest().openInstances(anchor, event);
            return free > reserved && free - 1 >= humanPartiesWaiting;
        }
    }

    /** Lakelis, at the Kerning City party-quest door. */
    final class Kpq extends ByEntrance {
        private static final String EVENT = "KerningPQ";

        Kpq() {
            super("kpq");
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
        public boolean atEntry(MapleMap map, Point at) {
            return withinGatherBox(map, at, "server.agents.capabilities.partyquest.kpq.AgentKpqCoordinator.GATHER_RADIUS_PX");
        }
    }

    /** Tory, on her ledge in Henesys Park. Citizens wait on the floor below it, past its end. */
    final class Hpq extends ByEntrance {
        private static final String EVENT = "HenesysPQ";

        Hpq() {
            super("hpq");
        }

        /**
         * HPQ's lobby policy counts background and citizen runs against its caps; its open instances (human ones too)
         * must leave the lobby kept for humans free, and one more for each human party waiting.
         */
        @Override
        public boolean lobbyFree(Character anchor, int humanPartiesWaiting) {
            int world = AgentClientGatewayRuntime.clients().world(anchor);
            int channel = AgentClientGatewayRuntime.clients().channel(anchor);
            if (!AgentHpqLobbyPolicy.backgroundSlotAvailable(world, channel)) {
                return false;
            }
            int reserved = Math.max(0, config.AgentTuning.intValue(
                    "server.agents.capabilities.partyquest.hpq.AgentHpqLobbyPolicy.HUMAN_RESERVED_LOBBIES"));
            return lobbiesLeft(anchor, EVENT, AgentHpqLobbyPolicy.maxLobbies(), reserved, humanPartiesWaiting);
        }

        @Override
        public Entered admit(Character leader, List<Character> members, long seed, long nowMs) {
            AgentHpqAdmissionService.AdmissionResult result =
                    AgentHpqAdmissionService.admitCitizens(leader, members, seed, nowMs);
            return new Entered(result.success(), result.message(),
                    result.session() == null ? null : result.session().sessionId());
        }

        @Override
        public boolean atEntry(MapleMap map, Point at) {
            return withinGatherBox(map, at, "server.agents.capabilities.partyquest.hpq.AgentHpqCoordinator.GATHER_RADIUS_PX");
        }
    }
}
