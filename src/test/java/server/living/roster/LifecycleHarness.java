package server.living.roster;

import client.Character;
import client.Client;
import config.LivingWorldConfig;
import net.server.Server;
import net.server.world.CauseScope;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.TimerManager;
import server.agents.capabilities.movement.AgentMovementCommandRuntime;
import server.agents.capabilities.party.AgentPartyLifecycleService;
import server.agents.capabilities.party.JoinOutcome;
import server.agents.integration.AgentPartyGatewayRuntime;
import server.agents.integration.PartyGateway;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentLifecycleService.AgentSpawnResult;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.living.LivingWorld;
import server.living.arc.ArcService;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.GenericPortal;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A small living world for the P2b lifecycle tests (join-then-travel, the atomic swap, HOLD and its rejoin, logout,
 * login, channel follow): the real RosterService, CompanionSlots, CompanionLeases and Commitments over a
 * {@link FakeLivingStore}, with the game faked where RosterService touches it. Ann (a human) always leads a party; it is
 * her own unless a test adds another human to {@link #humans}. Companions join and leave it through the Agent party
 * calls, loads and unloads go through the Agent runtime calls, and every one of those, and every walk and follow
 * command, is written to {@link #calls}; party chat goes to {@link #partyChat}. Maps are mocks with one visible portal
 * each; Ann and the companions stand where the test puts them, and a companion's {@code changeMap} moves it. Timers are
 * kept in {@link #timers} until the test runs them; companion-ops runs commands at once.
 */
final class LifecycleHarness implements AutoCloseable {
    static final int ANN = 10;
    static final int PARTY_ID = 77;
    static final int LITH = 104000000;
    static final int HENESYS = 100000000;
    static final int HENESYS_FIELD = 100010000;
    static final int KERNING = 103000000;
    static final int PERION = 102000000;

    /** A timer the roster scheduled: it runs its task on companion-ops when the test says so. */
    record Timer(long delayMs, Runnable task) { }

    final LivingWorldConfig config = new LivingWorldConfig();
    final FakeLivingStore store = new FakeLivingStore();
    final LivingWorld world = mock(LivingWorld.class);
    final ResidentRuntime residents = mock(ResidentRuntime.class);
    final CompanionBudget budget = mock(CompanionBudget.class);
    final CompanionLeases leases = new CompanionLeases();
    final Commitments commitments = new Commitments();
    final CompanionSlots slots = new CompanionSlots(world);
    final LivingContent content = mock(LivingContent.class);
    final List<String> calls = new ArrayList<>();
    final List<Timer> timers = new ArrayList<>();
    /** Party sizes after every join or leave, in order. */
    final List<Integer> partySizes = new ArrayList<>();
    /** The cause token in force at every join and leave, in order. */
    final List<String> causes = new ArrayList<>();
    /** Every party-chat line, as "Name: line". */
    final List<String> partyChat = new ArrayList<>();
    /** Party members who are humans (Ann, and any friend a test adds); the rest are bots. */
    final Set<Integer> humans = new HashSet<>(Set.of(ANN));
    /** Party members listed offline (a companion logged off with its owner stays in the party so). */
    final Set<Integer> offline = new HashSet<>();
    final Character ann = mock(Character.class);
    final Party party = mock(Party.class);
    final List<Integer> members = new ArrayList<>();
    final Map<String, Persona> personas = new LinkedHashMap<>();
    final Map<Integer, Character> bots = new LinkedHashMap<>();
    final Map<Integer, MapleMap> maps = new HashMap<>();
    final Set<Integer> loaded = new HashSet<>();
    /** Loaded characters that lead themselves (a live bench slice) instead of Ann. */
    final Set<Integer> selfLed = new HashSet<>();
    /** Told of every companion the moment it is loaded, before the roster goes on. */
    java.util.function.Consumer<Character> onSpawn = bot -> { };
    /** What the next companion join answers; JOINED unless a test says otherwise. */
    JoinOutcome nextJoin = null;
    boolean annOnline = true;
    RosterService roster;

    private final Map<Integer, MapleMap> botMaps = new HashMap<>();
    private final Map<Integer, Point> botPositions = new HashMap<>();
    private final Map<Integer, Integer> botChannels = new HashMap<>();
    private MapleMap annMap;
    private Point annAt = new Point(1000, 200);
    private int annChannel = 1;
    private EventInstanceManager annInstance;
    private final List<MockedStatic<?>> statics = new ArrayList<>();

    LifecycleHarness() {
        config.ROSTER_BOOK_ITEM_ID = 0; // the login's roster book is not under test (its inventory checks read WZ)
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store.store);
        when(world.content()).thenReturn(content);
        when(world.residents()).thenReturn(residents);
        when(world.benchSessions()).thenReturn(mock(BenchSessions.class));
        when(world.bench()).thenReturn(mock(BenchLedger.class));
        when(world.arcs()).thenReturn(mock(ArcService.class));
        when(world.leases()).thenReturn(leases);
        when(world.slots()).thenReturn(slots);
        when(world.commitments()).thenReturn(commitments);
        when(world.budget()).thenReturn(budget);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        CompanionDirectory directory = mock(CompanionDirectory.class);
        when(directory.isClaimed(anyInt())).thenReturn(true);
        // an own party: Ann is its only human (CompanionDirectory.isCompanionParty)
        when(directory.isCompanionParty(any())).thenAnswer(inv -> members.stream().filter(humans::contains).count() == 1);
        when(world.directory()).thenReturn(directory);
        when(budget.admit(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);
        when(budget.admitReplacing(any(), any(Character.class))).thenReturn(CompanionBudget.Admission.ALLOW);

        when(ann.getId()).thenReturn(ANN);
        when(ann.getName()).thenReturn("Ann");
        when(ann.getMap()).thenAnswer(inv -> annMap);
        when(ann.getMapId()).thenAnswer(inv -> annMap == null ? 0 : annMap.getId());
        when(ann.getPosition()).thenAnswer(inv -> annAt);
        when(ann.getEventInstance()).thenAnswer(inv -> annInstance);
        when(ann.getParty()).thenReturn(party);
        Client client = mock(Client.class, RETURNS_DEEP_STUBS);
        when(client.getChannel()).thenAnswer(inv -> annChannel);
        when(client.getChannelServer().getMapFactory().getMap(anyInt())).thenAnswer(inv -> map(inv.getArgument(0)));
        when(ann.getClient()).thenReturn(client);
        when(party.getId()).thenReturn(PARTY_ID);
        when(party.getLeaderId()).thenReturn(ANN);
        when(party.getMembers()).thenAnswer(inv -> partyMembers());
        when(party.getMemberById(anyInt())).thenAnswer(inv -> partyMembers().stream()
                .filter(member -> member.getId() == (int) inv.getArgument(0)).findFirst().orElse(null));
        members.add(ANN);
        moveAnn(HENESYS_FIELD, 1000, 200);

        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return loaded.contains(id) ? new AgentRuntimeEntry(bots.get(id), ann, null) : null;
        });
        registry.when(() -> AgentRuntimeRegistry.leaderIdForAgentCharacter(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return !loaded.contains(id) ? 0 : selfLed.contains(id) ? id : ANN;
        });
        MockedStatic<AgentRuntimeCleanupService> cleanup = open(AgentRuntimeCleanupService.class);
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            if (loaded.remove(id)) {
                calls.add("remove " + bots.get(id).getName());
            }
            return null;
        });
        MockedStatic<AgentInteractionRuntime> interaction = open(AgentInteractionRuntime.class);
        interaction.when(() -> AgentInteractionRuntime.spawnStationaryAgentForLeaderAt(eq(ann), anyString(), any(), any()))
                .thenAnswer(inv -> spawn(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));
        interaction.when(() -> AgentInteractionRuntime.spawnAgentForLeader(eq(ann), anyString()))
                .thenAnswer(inv -> spawn(inv.getArgument(1), annMap, annAt));
        MockedStatic<AgentPartyLifecycleService> lifecycle = open(AgentPartyLifecycleService.class);
        lifecycle.when(() -> AgentPartyLifecycleService.joinAgentToLeaderParty(eq(ann), any())).thenAnswer(inv -> {
            Character bot = inv.getArgument(1);
            causes.add(CauseScope.current());
            if (members.contains(bot.getId())) {
                return JoinOutcome.ALREADY_MEMBER;
            }
            JoinOutcome outcome = nextJoin != null ? nextJoin : members.size() >= CompanionSlots.PARTY_CAP
                    ? JoinOutcome.PARTY_FULL : JoinOutcome.JOINED;
            nextJoin = null;
            if (outcome == JoinOutcome.JOINED) {
                members.add(bot.getId());
                partySizes.add(members.size());
                calls.add("join " + bot.getName());
            } else {
                calls.add("join " + bot.getName() + " " + outcome);
            }
            return outcome;
        });
        lifecycle.when(() -> AgentPartyLifecycleService.leaveAgentParty(any())).thenAnswer(inv -> {
            Character bot = inv.getArgument(0);
            causes.add(CauseScope.current());
            if (members.remove((Integer) bot.getId())) {
                partySizes.add(members.size());
                calls.add("leave " + bot.getName());
            }
            return null;
        });
        MockedStatic<AgentMovementCommandRuntime> movement = open(AgentMovementCommandRuntime.class);
        movement.when(() -> AgentMovementCommandRuntime.followConfiguredTarget(any())).thenAnswer(inv ->
                calls.add("follow " + ((AgentRuntimeEntry) inv.getArgument(0)).bot().getName()));
        movement.when(() -> AgentMovementCommandRuntime.stop(any())).thenAnswer(inv ->
                calls.add("stop " + ((AgentRuntimeEntry) inv.getArgument(0)).bot().getName()));
        movement.when(() -> AgentMovementCommandRuntime.moveTo(any(), any(), anyBoolean())).thenAnswer(inv -> {
            Character bot = ((AgentRuntimeEntry) inv.getArgument(0)).bot();
            Point to = inv.getArgument(1);
            botPositions.put(bot.getId(), to); // walks are instant here
            return calls.add("walk " + bot.getName() + " to " + to.x + "," + to.y);
        });
        open(Party.class);
        MockedStatic<AgentPartyGatewayRuntime> gateway = open(AgentPartyGatewayRuntime.class);
        PartyGateway partyGateway = mock(PartyGateway.class);
        gateway.when(AgentPartyGatewayRuntime::party).thenReturn(partyGateway);
        when(partyGateway.sendPartyChat(any(), anyString())).thenAnswer(inv ->
                partyChat.add(((Character) inv.getArgument(0)).getName() + ": " + inv.getArgument(1)));
        MockedStatic<TimerManager> timer = open(TimerManager.class);
        TimerManager timerManager = mock(TimerManager.class);
        timer.when(TimerManager::getInstance).thenReturn(timerManager);
        when(timerManager.schedule(any(TimerManager.SchedulerLane.class), any(Runnable.class), anyLong())).thenAnswer(inv -> {
            timers.add(new Timer(inv.getArgument(2), inv.getArgument(1)));
            return null;
        });
        MockedStatic<Server> server = open(Server.class);
        Server instance = mock(Server.class, RETURNS_DEEP_STUBS);
        when(instance.getWorld(0).getPlayerStorage().getCharacterById(ANN)).thenAnswer(inv -> annOnline ? ann : null);
        server.when(Server::getInstance).thenReturn(instance);
    }

    /** A companion of Ann's (character id {@code characterId}) in {@code state}, camping at {@code campMapId}. */
    Persona companion(String id, String name, int characterId, RosterState state, int campMapId) {
        return companionOf(ANN, id, name, characterId, state, campMapId);
    }

    /** A companion of {@code ownerId}'s (a friend's crew), as {@link #companion}. */
    Persona companionOf(int ownerId, String id, String name, int characterId, RosterState state, int campMapId) {
        Persona persona = new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
        personas.put(id, persona);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(characterId);
        when(bot.getName()).thenReturn(name);
        when(bot.getMap()).thenAnswer(inv -> botMaps.get(characterId));
        when(bot.getMapId()).thenAnswer(inv -> botMaps.containsKey(characterId) ? botMaps.get(characterId).getId() : 0);
        when(bot.getPosition()).thenAnswer(inv -> botPositions.get(characterId));
        when(bot.getParty()).thenAnswer(inv -> members.contains(characterId) ? party : null);
        doAnswer(inv -> {
            MapleMap to = inv.getArgument(0);
            Point at = inv.getArgument(1);
            botMaps.put(characterId, to);
            botPositions.put(characterId, at);
            calls.add("warp " + name + " to " + to.getId() + " at " + at.x + "," + at.y);
            return null;
        }).when(bot).changeMap(any(MapleMap.class), any(Point.class));
        bots.put(characterId, bot);
        when(residents.ensureCharacter(persona)).thenReturn(characterId);
        when(residents.characterIdOf(id)).thenReturn(characterId);
        when(residents.personaOfCharacterId(characterId)).thenReturn(persona);
        when(content.persona(id)).thenReturn(persona);
        store.seed(new RosterEntry(ownerId, id, state, Assignment.TRAIN, 0, 0, campMapId, 0L, 0L, 0L, 1_000L, 1_000L, 1_000L, 0, null));
        return persona;
    }

    /** The roster over this world (built once the rows are seeded, as at boot). */
    RosterService roster() {
        if (roster == null) {
            roster = new RosterService(world);
            when(world.roster()).thenReturn(roster);
        }
        return roster;
    }

    /** The companion already out with Ann: loaded beside her on her map and in her party. */
    void outWithAnn(Persona persona) {
        int id = residents.characterIdOf(persona.id());
        loaded.add(id);
        leases.tryAcquire(id, CompanionLeases.Kind.COMPANION, ANN);
        botMaps.put(id, annMap);
        botPositions.put(id, new Point(annAt.x - 60, annAt.y));
        members.add(id);
    }

    /** The companion (loaded already) stands beside Ann, wherever she is now. */
    void placeWithAnn(Persona persona) {
        int id = residents.characterIdOf(persona.id());
        botMaps.put(id, annMap);
        botPositions.put(id, new Point(annAt.x - 60, annAt.y));
    }

    /** A map mock with one visible portal at (x, 200) leading to Henesys; the same mock for the same id. */
    MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(id);
            GenericPortal portal = new GenericPortal(Portal.MAP_PORTAL);
            portal.setPosition(new Point(portalX(id), 200));
            portal.setTargetMapId(HENESYS);
            portal.setPortalStatus(true);
            when(map.getPortals()).thenReturn(List.of(portal));
            return map;
        });
    }

    /** Where the one portal of a map stands: a different x per map, so a test can tell which map a spot is on. */
    static int portalX(int mapId) {
        return 700 + Math.floorMod(mapId / 1000, 7) * 50;
    }

    void moveAnn(int mapId, int x, int y) {
        annMap = map(mapId);
        annAt = new Point(x, y);
        annInstance = null;
    }

    /** Ann is inside an event instance's copy of {@code mapId}. */
    MapleMap annEntersInstance(int mapId) {
        EventInstanceManager instance = mock(EventInstanceManager.class);
        MapleMap copy = mock(MapleMap.class);
        when(copy.getId()).thenReturn(mapId);
        when(copy.getEventInstance()).thenReturn(instance);
        annMap = copy;
        annInstance = instance;
        return copy;
    }

    void annOnChannel(int channel) {
        annChannel = channel;
    }

    /** Gives the companion a client on {@code channel} (by default it has none, which reads as "the same channel"). */
    void companionOnChannel(Persona persona, int channel) {
        int id = residents.characterIdOf(persona.id());
        Client client = mock(Client.class);
        when(client.getChannel()).thenAnswer(inv -> botChannels.get(id));
        when(bots.get(id).getClient()).thenReturn(client);
        botChannels.put(id, channel);
    }

    /** The channel the companion's client is on (a loaded companion with a client comes up on Ann's). */
    int companionChannel(Persona persona) {
        return botChannels.getOrDefault(residents.characterIdOf(persona.id()), 0);
    }

    Character bot(Persona persona) {
        return bots.get(residents.characterIdOf(persona.id()));
    }

    MapleMap botMap(Persona persona) {
        return botMaps.get(residents.characterIdOf(persona.id()));
    }

    RosterEntry row(Persona persona) {
        return store.row(ANN, persona.id());
    }

    /** Runs every timer scheduled so far, and any they schedule (ten passes at most). */
    void runTimers() {
        for (int round = 0; round < 10 && !timers.isEmpty(); round++) {
            List<Timer> due = new ArrayList<>(timers);
            timers.clear();
            due.forEach(timer -> timer.task().run());
        }
    }

    /** Runs only the timers scheduled so far, and keeps any they schedule. */
    void runDueTimers() {
        List<Timer> due = new ArrayList<>(timers);
        timers.clear();
        due.forEach(timer -> timer.task().run());
    }

    private AgentSpawnResult spawn(String name, MapleMap map, Point at) {
        Character bot = bots.values().stream().filter(b -> b.getName().equals(name)).findFirst().orElseThrow();
        loaded.add(bot.getId());
        botMaps.put(bot.getId(), map);
        botPositions.put(bot.getId(), at);
        botChannels.computeIfPresent(bot.getId(), (id, channel) -> annChannel); // loaded on Ann's channel
        calls.add("spawn " + name + " at " + map.getId() + " " + at.x + "," + at.y);
        onSpawn.accept(bot);
        return AgentSpawnResult.ok(bot, true);
    }

    private List<PartyCharacter> partyMembers() {
        List<PartyCharacter> out = new ArrayList<>();
        for (int id : members) {
            PartyCharacter member = mock(PartyCharacter.class);
            when(member.getId()).thenReturn(id);
            when(member.isBot()).thenReturn(!humans.contains(id));
            when(member.isOnline()).thenReturn(!offline.contains(id));
            out.add(member);
        }
        return out;
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        return mocked;
    }

    @Override
    public void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
