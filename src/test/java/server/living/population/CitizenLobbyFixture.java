package server.living.population;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import config.PopulationConfig;
import net.server.PlayerStorage;
import net.server.world.World;
import org.mockito.MockedStatic;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.AgentPartyMemberSnapshot;
import server.agents.integration.AgentPartySnapshot;
import server.agents.integration.AgentRuntimeIdentityRuntime;
import server.agents.integration.PartyGateway;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.simulation.AgentSimulationState;
import server.agents.runtime.state.AgentCapabilityStateRegistry;
import server.integration.AgentPresence;
import server.living.population.CitizenSession.Activity;
import server.living.population.PopulationContent.Lobby;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Kerning City's KPQ lobby with citizens and humans standing at Lakelis (CitizenPartyQuests): loaded citizens on a PQ
 * session, humans, the live parties (an in-memory party gateway), and what the population was asked to do: lines
 * said, citizens held for a run, re-planned, logged off or sent somewhere. The population and its parts are stub-only
 * mocks (recording mocks of the population run out of heap).
 */
final class CitizenLobbyFixture implements AutoCloseable {
    static final int LOBBY = 103_000_000;
    static final Lobby KPQ = new Lobby("kpq", LOBBY, 21, 30, "KPQ");
    static final int CHANNEL = 1;

    final PopulationConfig config = new PopulationConfig();
    final Population population = mock(Population.class, withSettings().stubOnly());
    final PresenceScheduler presence = mock(PresenceScheduler.class, withSettings().stubOnly());
    final MapleMap kerning = mock(MapleMap.class, withSettings().stubOnly());
    final Parties parties = new Parties();
    /** Every line a citizen was asked to say, in order. */
    final List<ChatterEngine.Event> said = new ArrayList<>();
    /** What presence was asked: "hold Lia", "replan Lia", "logout Lia", "travel Lia PQ 103000000". */
    final List<String> asked = new ArrayList<>();
    final Map<Integer, CitizenSession> sessions = new LinkedHashMap<>();
    private final List<Character> onLobby = new ArrayList<>();
    private final Set<Integer> agents = new HashSet<>();
    private final Map<Integer, Character> online = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final MockedStatic<AgentRuntimeRegistry> registry;
    private final MockedStatic<AgentRuntimeIdentityRuntime> identity;

    CitizenLobbyFixture() {
        FieldCatalog fields = mock(FieldCatalog.class, withSettings().stubOnly());
        ChatterEngine chatter = mock(ChatterEngine.class, withSettings().stubOnly());
        when(population.config()).thenReturn(config);
        when(population.fields()).thenReturn(fields);
        when(population.presence()).thenReturn(presence);
        when(population.chatter()).thenReturn(chatter);
        when(fields.lobbyAt(anyInt())).thenAnswer(call -> (int) call.getArgument(0) == LOBBY ? KPQ : null);
        doAnswer(call -> said.add(call.getArgument(0))).when(chatter).event(any());
        World world = mock(World.class, withSettings().stubOnly());
        PlayerStorage storage = mock(PlayerStorage.class, withSettings().stubOnly());
        when(population.gameWorld()).thenReturn(world);
        when(world.getPlayerStorage()).thenReturn(storage);
        when(storage.getCharacterById(anyInt())).thenAnswer(call -> online.get((int) call.getArgument(0)));
        when(presence.sessions()).thenAnswer(call -> new ArrayList<>(sessions.values()));
        when(presence.session(anyInt())).thenAnswer(call -> sessions.get((int) call.getArgument(0)));
        doAnswer(call -> asked.add("hold " + this.<CitizenSession>arg(call.getArgument(0)).name()))
                .when(presence).holdForRun(any());
        doAnswer(call -> asked.add("replan " + this.<CitizenSession>arg(call.getArgument(0)).name()))
                .when(presence).replan(any(), anyLong());
        doAnswer(call -> {
            CitizenSession session = call.getArgument(0);
            sessions.remove(session.characterId);
            return asked.add("logout " + session.name());
        }).when(presence).logout(any(), anyString());
        doAnswer(call -> asked.add("travel " + this.<CitizenSession>arg(call.getArgument(0)).name() + " "
                + call.getArgument(1) + " " + call.getArgument(2)))
                .when(presence).travel(any(), any(), anyInt(), anyLong());
        when(kerning.getId()).thenReturn(LOBBY);
        when(kerning.getAllPlayers()).thenAnswer(call -> new ArrayList<>(onLobby));
        open(AgentPartyGatewayRuntime.class).when(AgentPartyGatewayRuntime::party).thenReturn(parties);
        open(AgentPresence.class).when(() -> AgentPresence.isAgent(any()))
                .thenAnswer(call -> call.getArgument(0) != null && agents.contains(this.<Character>arg(call.getArgument(0)).getId()));
        registry = open(AgentRuntimeRegistry.class);
        identity = open(AgentRuntimeIdentityRuntime.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T arg(Object value) {
        return (T) value;
    }

    <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    /** The population's party quests with this door for KPQ and fixed draws. */
    CitizenPartyQuests quests(CitizenPqDoor door) {
        return new CitizenPartyQuests(population, Map.of("kpq", door), new Random(7L));
    }

    /** A loaded citizen out for a PQ, standing at Lakelis. */
    Character citizen(int id, String name, int level) {
        Character chr = character(id, name, level);
        agents.add(id);
        AgentRuntimeEntry entry = mock(AgentRuntimeEntry.class, withSettings().stubOnly());
        when(entry.capabilityStates()).thenReturn(new AgentCapabilityStateRegistry());
        when(entry.simulationState()).thenReturn(new AgentSimulationState());
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(id)).thenReturn(entry);
        identity.when(() -> AgentRuntimeIdentityRuntime.bot(entry)).thenReturn(chr);
        Citizen citizen = new Citizen(id, name, "evening", 0, 0, "victoria", "hunter", "relaxed-v1", "default", 1.0d, 0,
                CHANNEL, new LinkedHashSet<>(), Citizen.State.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0, "");
        CitizenSession session = new CitizenSession(citizen, 0L, 24 * 3_600_000L, false);
        session.entry = entry;
        session.activity = Activity.PQ;
        session.mapId = LOBBY;
        sessions.put(id, session);
        return chr;
    }

    /** A human at Lakelis with no party. */
    Character human(int id, String name, int level) {
        return character(id, name, level);
    }

    private Character character(int id, String name, int level) {
        Character chr = mock(Character.class, withSettings().stubOnly());
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getJob()).thenReturn(client.Job.BEGINNER);
        when(chr.getHp()).thenReturn(500);
        when(chr.getMapId()).thenReturn(LOBBY);
        when(chr.getMap()).thenReturn(kerning);
        when(chr.getPosition()).thenReturn(new Point(-500, 421));
        Inventory room = mock(Inventory.class, withSettings().stubOnly()); // a free slot in every tab
        when(chr.getInventory(any(InventoryType.class))).thenReturn(room);
        onLobby.add(chr);
        online.put(id, chr);
        return chr;
    }

    /** The citizen is in its run's instance now (or out again with null). */
    void inInstance(Character chr, boolean inside) {
        when(chr.getEventInstance()).thenReturn(inside ? mock(scripting.event.EventInstanceManager.class) : null);
    }

    /** It left the lobby (for the instance, or anywhere). */
    void away(Character chr, int mapId) {
        when(chr.getMapId()).thenReturn(mapId);
        onLobby.remove(chr);
    }

    CitizenSession session(Character chr) {
        return sessions.get(chr.getId());
    }

    List<String> triggers() {
        return said.stream().map(event -> event.trigger).toList();
    }

    long count(String trigger) {
        return said.stream().filter(event -> event.trigger.equals(trigger)).count();
    }

    @Override
    public void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }

    /** The live parties, in memory: a party's first member leads it, and a leader leaving disbands it. */
    static final class Parties implements PartyGateway {
        private final Map<Integer, List<Character>> members = new LinkedHashMap<>();
        private final Map<Integer, Integer> partyOf = new HashMap<>();
        private final Map<Integer, Integer> invitedTo = new HashMap<>();
        private int nextId = 500;

        /** A human's own party, the human leading it. */
        int lead(Character leader, Character... others) {
            createAgentParty(leader);
            for (Character other : others) {
                joinAgentParty(other, partyOf.get(leader.getId()));
            }
            return partyOf.get(leader.getId());
        }

        /** The invited human said yes. */
        void accept(Character human) {
            Integer party = invitedTo.remove(human.getId());
            if (party != null) {
                joinAgentParty(human, party);
            }
        }

        /** The invited human said no. */
        void decline(Character human) {
            invitedTo.remove(human.getId());
        }

        List<Integer> memberIds(Character anyone) {
            Integer party = partyOf.get(anyone.getId());
            return party == null ? List.of() : members.get(party).stream().map(Character::getId).toList();
        }

        @Override
        public void leaveCurrentParty(Character agent) {
            Integer party = partyOf.remove(agent.getId());
            if (party == null) {
                return;
            }
            List<Character> in = members.get(party);
            boolean leader = in.getFirst().getId() == agent.getId();
            in.removeIf(member -> member.getId() == agent.getId());
            if (leader || in.isEmpty()) {
                in.forEach(member -> partyOf.remove(member.getId()));
                members.remove(party);
            }
        }

        @Override
        public boolean createAgentParty(Character leader) {
            if (partyOf.containsKey(leader.getId())) {
                return false;
            }
            int id = nextId++;
            members.put(id, new ArrayList<>(List.of(leader)));
            partyOf.put(leader.getId(), id);
            return true;
        }

        @Override
        public boolean joinAgentParty(Character agent, int partyId) {
            List<Character> in = members.get(partyId);
            if (in == null || in.size() >= 6 || partyOf.containsKey(agent.getId())) {
                return false;
            }
            in.add(agent);
            partyOf.put(agent.getId(), partyId);
            return true;
        }

        @Override
        public boolean invitePartyMember(Character leader, Character invitee) {
            Integer party = partyOf.get(leader.getId());
            if (party == null || partyOf.containsKey(invitee.getId())) {
                return false;
            }
            invitedTo.put(invitee.getId(), party);
            return true;
        }

        @Override
        public boolean hasPendingPartyInvite(int inviteeId) {
            return invitedTo.containsKey(inviteeId);
        }

        @Override
        public void cancelPartyInvite(int inviteeId) {
            invitedTo.remove(inviteeId);
        }

        @Override
        public void publishAgentOnline(Character agent, int partyId) {
        }

        @Override
        public boolean sendPartyChat(Character speaker, String message) {
            return true;
        }

        @Override
        public AgentPartySnapshot snapshot(Character character) {
            Integer party = character == null ? null : partyOf.get(character.getId());
            if (party == null) {
                return null;
            }
            List<AgentPartyMemberSnapshot> snapshots = new ArrayList<>();
            for (Character member : members.get(party)) {
                snapshots.add(new AgentPartyMemberSnapshot(member.getId(), member.getName(), snapshots.isEmpty(),
                        member.getMapId()));
            }
            return new AgentPartySnapshot(party, snapshots);
        }

        @Override
        public boolean hasParty(Character character) {
            return partyOf.containsKey(character.getId());
        }

        @Override
        public List<Character> onlineMembers(Character character) {
            Integer party = partyOf.get(character.getId());
            return party == null ? List.of() : List.copyOf(members.get(party));
        }
    }

    /** KPQ's door as the tests set it: 3-4 seats, a free lobby unless a human party waits, admissions recorded. */
    static final class Door implements CitizenPqDoor {
        final List<List<Integer>> admitted = new ArrayList<>();
        final Map<Integer, String> problems = new HashMap<>();
        final Set<String> held = new HashSet<>();
        boolean lobbyFree = true;
        String refusal;
        int lastHumanPartiesWaiting = -1;

        @Override
        public String key() {
            return "kpq";
        }

        @Override
        public int minSeats() {
            return 3;
        }

        @Override
        public int maxSeats() {
            return 4;
        }

        @Override
        public String memberProblem(Character member, boolean leader) {
            return problems.getOrDefault(member.getId(), "");
        }

        @Override
        public String registrationBlocker(int leaderId, java.util.Collection<Integer> characterIds) {
            return "";
        }

        @Override
        public boolean lobbyFree(Character anchor, int humanPartiesWaiting) {
            lastHumanPartiesWaiting = humanPartiesWaiting;
            return lobbyFree && humanPartiesWaiting == 0;
        }

        @Override
        public Entered admit(Character leader, List<Character> members, long seed, long nowMs) {
            if (refusal != null) {
                return new Entered(false, refusal, null);
            }
            admitted.add(members.stream().map(Character::getId).toList());
            String sessionId = "kpq-run-" + admitted.size();
            members.forEach(member -> held.add(member.getId() + ":" + sessionId));
            return new Entered(true, "KPQ party admitted", sessionId);
        }

        @Override
        public boolean holds(int characterId, String sessionId) {
            return held.contains(characterId + ":" + sessionId);
        }

        @Override
        public Point beside(MapleMap map, int index) {
            return new Point(-500 + 30 * index, 421);
        }

        @Override
        public boolean atEntry(MapleMap map, Point at) {
            return true;
        }

        /** The quest's registry let the run go (its end). */
        void release(String sessionId) {
            held.removeIf(entry -> entry.endsWith(":" + sessionId));
        }
    }

    static List<Integer> ids(Character... characters) {
        List<Integer> ids = new ArrayList<>();
        for (Character chr : characters) {
            ids.add(chr.getId());
        }
        return ids;
    }
}
