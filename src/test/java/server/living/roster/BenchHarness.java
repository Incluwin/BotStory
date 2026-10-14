package server.living.roster;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.manipulator.InventoryManipulator;
import config.LivingWorldConfig;
import constants.inventory.ItemConstants;
import org.mockito.MockedStatic;
import server.agents.capabilities.townlife.AgentTownLifeCheckpointRuntime;
import server.agents.integration.cosmic.CosmicAgentOfflineLoader;
import server.agents.runtime.AgentInteractionRuntime;
import server.agents.runtime.AgentMailboxAction;
import server.agents.runtime.AgentMailboxRuntime;
import server.agents.runtime.AgentRuntimeCleanupService;
import server.agents.runtime.AgentRuntimeEntry;
import server.agents.runtime.AgentRuntimeRegistry;
import server.agents.runtime.activity.AgentActivityBootstrap;
import server.agents.runtime.field.AgentFieldCheckpointRuntime;
import server.agents.runtime.field.AgentFieldVisitLeaseRuntime;
import server.agents.runtime.townlife.AgentTownLifeVisitLeaseRuntime;
import server.living.LivingWorld;
import server.living.config.Content;
import server.living.config.Content.Look;
import server.living.config.Content.Persona;
import server.living.config.LivingContent;
import server.living.resident.ResidentRuntime;
import server.living.resident.ResidentSpots;
import server.living.store.LivingStore.Assignment;
import server.living.store.LivingStore.RosterEntry;
import server.living.store.LivingStore.RosterState;
import server.maps.MapleMap;

import java.awt.Point;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A small living world for the P5 bench tests (BenchDutyDispatcher, its field form BenchSessions, the end rules, the
 * harvest, flow 12 and the yields): the real dispatcher, field form, budget, leases and yield model over a
 * {@link FakeLivingStore}, with the Agent runtime faked where a slice touches it. Owners Ann and Ben are level 30 and
 * their companions level 10; every companion camps at {@link #FIELD} unless a test says otherwise. Loads, unloads,
 * grind starts and hand-backs are written to {@link #calls}; the clock is {@link #now}. Companion-ops runs at once.
 */
final class BenchHarness implements AutoCloseable {
    static final int ANN = 10;
    static final int BEN = 20;
    static final int FIELD = 104010001;
    static final int OTHER_FIELD = 104010002;
    static final int TOWN = 104000000;

    final LivingWorldConfig config = new LivingWorldConfig();
    final FakeLivingStore store = new FakeLivingStore();
    final LivingWorld world = mock(LivingWorld.class);
    final ResidentRuntime residents = mock(ResidentRuntime.class);
    final RosterService roster = mock(RosterService.class);
    final CompanionLeases leases = new CompanionLeases();
    final LivingContent content = mock(LivingContent.class);
    final BenchLedger ledger = mock(BenchLedger.class);
    final List<String> calls = new ArrayList<>();
    /** Owners online now. */
    final Set<Integer> online = new HashSet<>();
    final Map<Integer, Character> bots = new HashMap<>();
    final Set<Integer> loaded = new HashSet<>();
    /** Character id -> item id -> quantity in its bags. */
    final Map<Integer, Map<Integer, Integer>> bags = new HashMap<>();
    final Map<Integer, Integer> purses = new HashMap<>();
    final Map<Integer, Integer> hp = new HashMap<>();
    final Map<Integer, Integer> botLevels = new HashMap<>();
    final Map<Integer, MapleMap> botMaps = new HashMap<>();
    /** Character id -> the point its last load put it at. */
    final Map<Integer, Point> loadedAt = new HashMap<>();
    /** The channel a loaded companion's client reports (none: it has no client). */
    final Map<Integer, Integer> channels = new HashMap<>();
    long now = 10_000_000L;
    /** False: an Agent's mailbox refuses every action (closed), as it would for one being torn down. */
    boolean mailboxRuns = true;
    final BenchSessions field;
    final BenchDutyDispatcher duty;
    final BenchYieldModel yields;

    private final Map<Integer, MapleMap> maps = new HashMap<>();
    private final Map<Integer, AgentRuntimeEntry> entries = new HashMap<>();
    private final List<MockedStatic<?>> statics = new ArrayList<>();
    private final Map<Class<?>, MockedStatic<?>> staticsByType = new HashMap<>();

    BenchHarness() {
        config.COMPANION_SPAWN_INTERVAL_MS = 0; // several loads of one owner in one tick; spacing is BudgetTest's
        when(world.config()).thenReturn(config);
        when(world.store()).thenReturn(store.store);
        when(world.residents()).thenReturn(residents);
        when(world.roster()).thenReturn(roster);
        when(world.leases()).thenReturn(leases);
        when(world.content()).thenReturn(content);
        when(world.bench()).thenReturn(ledger);
        CompanionExecutor ops = DirectExecutor.inline();
        when(world.executor()).thenReturn(ops);
        when(world.routineDate()).thenReturn(LocalDate.of(2026, 9, 29));
        CompanionBudget budget = new CompanionBudget(world);
        when(world.budget()).thenReturn(budget);
        when(ledger.withinDailyCap(any(), anyDouble())).thenReturn(true);
        when(ledger.haulCount(any(), anyInt())).thenAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            return store.haul.getOrDefault(FakeLivingStore.key(entry.characterId(), entry.personaId()), Map.of())
                    .getOrDefault((int) inv.getArgument(1), 0);
        });
        when(ledger.haulStacks(any())).thenAnswer(inv -> {
            RosterEntry entry = inv.getArgument(0);
            return store.haul.getOrDefault(FakeLivingStore.key(entry.characterId(), entry.personaId()), Map.of()).size();
        });
        when(roster.onlineCharacter(anyInt())).thenAnswer(inv -> online.contains((int) inv.getArgument(0)) ? mock(Character.class) : null);
        when(roster.fieldCampFor(any(), anyInt())).thenReturn(FIELD);
        when(roster.describeAssignment(any())).thenAnswer(inv -> ((RosterEntry) inv.getArgument(0)).assignment().name().toLowerCase());
        when(residents.map(anyInt())).thenAnswer(inv -> map(inv.getArgument(0)));
        store.levels.put(ANN, 30);
        store.levels.put(BEN, 30);
        field = new BenchSessions(world);
        duty = new BenchDutyDispatcher(world, field, () -> now);
        yields = new BenchYieldModel(world);
        when(world.benchSessions()).thenReturn(field);
        when(world.benchDuty()).thenReturn(duty);
        when(world.benchYields()).thenReturn(yields);

        MockedStatic<CosmicAgentOfflineLoader> loader = open(CosmicAgentOfflineLoader.class);
        loader.when(() -> CosmicAgentOfflineLoader.loadOfflineAgent(anyInt(), anyInt(), anyInt(), any(), any())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            MapleMap at = inv.getArgument(3);
            Character bot = bots.get(id);
            loaded.add(id);
            botMaps.put(id, at);
            loadedAt.put(id, inv.getArgument(4));
            calls.add("load " + bot.getName() + " at " + at.getId() + " ch" + (int) inv.getArgument(2));
            return bot;
        });
        MockedStatic<AgentRuntimeRegistry> registry = open(AgentRuntimeRegistry.class);
        registry.when(() -> AgentRuntimeRegistry.findByAgentCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return loaded.contains(id) ? entries.computeIfAbsent(id, key -> new AgentRuntimeEntry(bots.get(key), null, null)) : null;
        });
        registry.when(() -> AgentRuntimeRegistry.hasActiveAgentCharacterId(anyInt())).thenAnswer(inv -> loaded.contains((int) inv.getArgument(0)));
        registry.when(AgentRuntimeRegistry::activeAgentCount).thenAnswer(inv -> loaded.size());
        MockedStatic<AgentRuntimeCleanupService> cleanup = open(AgentRuntimeCleanupService.class);
        cleanup.when(() -> AgentRuntimeCleanupService.removeAgentByCharacterId(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            if (loaded.remove(id)) {
                entries.remove(id);
                calls.add("unload " + bots.get(id).getName());
            }
            return true;
        });
        MockedStatic<AgentInteractionRuntime> interaction = open(AgentInteractionRuntime.class);
        interaction.when(() -> AgentInteractionRuntime.registerSelfDirectedAgent(any())).thenAnswer(inv -> {
            calls.add("grind " + ((Character) inv.getArgument(0)).getName());
            return null;
        });
        MockedStatic<ResidentSpots> spots = open(ResidentSpots.class);
        spots.when(() -> ResidentSpots.townMapId(anyString())).thenReturn(TOWN);
        spots.when(() -> ResidentSpots.fallback(any(), anyInt())).thenReturn(new Point(0, 0));
        MockedStatic<AgentActivityBootstrap> activities = open(AgentActivityBootstrap.class);
        activities.when(() -> AgentActivityBootstrap.requestPrimaryStop(any(), any(), any(), anyString(), anyLong())).thenReturn(true);
        MockedStatic<AgentFieldVisitLeaseRuntime> fieldLease = open(AgentFieldVisitLeaseRuntime.class);
        fieldLease.when(() -> AgentFieldVisitLeaseRuntime.clear(any(), any())).thenAnswer(inv ->
                calls.add("hand back " + ((Character) inv.getArgument(1)).getName()));
        open(AgentTownLifeVisitLeaseRuntime.class);
        open(AgentFieldCheckpointRuntime.class); // no checkpoint files are touched by a test
        open(AgentTownLifeCheckpointRuntime.class);
        // an Agent's own mailbox runs at once here, as its next tick would (mailboxRuns false: it never runs)
        open(AgentMailboxRuntime.class).when(() -> AgentMailboxRuntime.dispatch(any(AgentRuntimeEntry.class), any(AgentMailboxAction.class)))
                .thenAnswer(inv -> {
                    if (!mailboxRuns) {
                        return CompletableFuture.failedFuture(new IllegalStateException("mailbox closed"));
                    }
                    AgentMailboxAction<?> action = inv.getArgument(1);
                    return CompletableFuture.completedFuture(action.execute(inv.getArgument(0)));
                });
        MockedStatic<InventoryManipulator> inventory = open(InventoryManipulator.class);
        inventory.when(() -> InventoryManipulator.removeById(any(), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenAnswer(inv -> {
                    Client client = inv.getArgument(0);
                    int id = client == null ? 0 : client.getPlayer().getId();
                    bags.get(id).merge(inv.getArgument(2), -(int) inv.getArgument(3), Integer::sum);
                    return null;
                });
    }

    /**
     * A form with no activity of its own, standing in for the town (REST) and stall (STALL, SELL) forms other tracks
     * register: it loads at {@code mapId}, writes "begin"/"finish" to the harness's calls, and ends a slice only when a
     * test sets {@link #endsWith}.
     */
    final class FakeForm implements BenchDutyDispatcher.Form {
        final CompanionBudget.Kind pool;
        final int mapId;
        String endsWith;

        FakeForm(CompanionBudget.Kind pool, int mapId) {
            this.pool = pool;
            this.mapId = mapId;
        }

        @Override
        public CompanionBudget.Kind pool() {
            return pool;
        }

        @Override
        public int mapFor(RosterEntry entry, Persona persona, int characterId) {
            return mapId;
        }

        @Override
        public boolean begin(BenchDutyDispatcher.Slice slice, Character agent, long nowMs) {
            calls.add("begin " + agent.getName());
            return true;
        }

        @Override
        public String check(BenchDutyDispatcher.Slice slice, Character agent, RosterEntry entry, long nowMs) {
            return endsWith;
        }

        @Override
        public void finish(BenchDutyDispatcher.Slice slice, Character agent, String reason) {
            calls.add("finish " + slice.personaId() + ": " + reason);
        }
    }

    /** A companion of {@code ownerId}'s (its own character {@code characterId}, level 10) on {@code duty}, camping at FIELD. */
    Persona companion(int ownerId, String id, String name, int characterId, Assignment duty) {
        return companion(ownerId, id, name, characterId, duty, FIELD);
    }

    Persona companion(int ownerId, String id, String name, int characterId, Assignment duty, int campMapId) {
        Persona persona = new Persona(id, name, "", "BEGINNER", new Content.LevelBand(1, 1), Content.Strength.ROOKIE, null,
                "relaxed-v1", null, "lith-harbor", List.of(), false, null, List.of(), null, List.of(), List.of(), Map.of(),
                -1, -1, -1, List.of(), 0, Look.NONE, "test");
        when(content.persona(id)).thenReturn(persona);
        when(residents.characterIdOf(id)).thenReturn(characterId);
        store.levels.put(characterId, 10);
        botLevels.put(characterId, 10);
        hp.put(characterId, 500);
        purses.put(characterId, 1_000);
        bags.put(characterId, new HashMap<>());
        bots.put(characterId, bot(characterId, name));
        store.seed(new RosterEntry(ownerId, id, RosterState.BENCHED, duty, 0, 0, campMapId, 0L, 0L, 0L, 1_000L, 1_000L,
                1_000L, 0, null));
        return persona;
    }

    /** A map mock that is a town when {@code mapId} is {@link #TOWN}; the same mock for the same id. */
    MapleMap map(int mapId) {
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap map = mock(MapleMap.class);
            when(map.getId()).thenReturn(id);
            when(map.isTown()).thenReturn(id == TOWN);
            when(map.getAllPlayers()).thenReturn(List.of());
            return map;
        });
    }

    /** The loaded companion's runtime entry (its capability states persist while it stays loaded). */
    AgentRuntimeEntry entry(int characterId) {
        return AgentRuntimeRegistry.findByAgentCharacterId(characterId);
    }

    RosterEntry row(int ownerId, String personaId) {
        return store.row(ownerId, personaId);
    }

    /** Its haul: item id -> quantity. */
    Map<Integer, Integer> haul(int ownerId, String personaId) {
        return store.haul.getOrDefault(FakeLivingStore.key(ownerId, personaId), Map.of());
    }

    /** The companion picked up {@code quantity} of {@code itemId} (the stock pickup and its hook). */
    void picksUp(int characterId, int itemId, int quantity) {
        bags.get(characterId).merge(itemId, quantity, Integer::sum);
        duty.looted(characterId, itemId, quantity, 0);
    }

    /** The companion picked up a meso drop. */
    void picksUpMeso(int characterId, int meso) {
        purses.merge(characterId, meso, Integer::sum);
        duty.looted(characterId, 0, 0, meso);
    }

    private Character bot(int characterId, String name) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(characterId);
        when(bot.getName()).thenReturn(name);
        when(bot.getLevel()).thenAnswer(inv -> botLevels.get(characterId));
        when(bot.getHp()).thenAnswer(inv -> hp.get(characterId));
        when(bot.getMap()).thenAnswer(inv -> botMaps.get(characterId));
        when(bot.getMeso()).thenAnswer(inv -> purses.get(characterId));
        doAnswer(inv -> purses.merge(characterId, (int) inv.getArgument(0), Integer::sum)).when(bot).gainMeso(anyInt(), anyBoolean());
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(bot);
        when(client.getChannel()).thenAnswer(inv -> channels.getOrDefault(characterId, config.CHANNEL));
        when(bot.getClient()).thenAnswer(inv -> client);
        for (InventoryType type : InventoryType.values()) {
            Inventory bag = mock(Inventory.class);
            when(bag.countById(anyInt())).thenAnswer(inv -> {
                int itemId = inv.getArgument(0);
                return ItemConstants.getInventoryType(itemId) == type ? bags.get(characterId).getOrDefault(itemId, 0) : 0;
            });
            when(bot.getInventory(type)).thenReturn(bag);
        }
        return bot;
    }

    /**
     * The harness's static mock of {@code type}, for a test to stub further (the P5 duty forms); one the harness does
     * not open is opened now and closed with the harness.
     */
    @SuppressWarnings("unchecked")
    <T> MockedStatic<T> statics(Class<T> type) {
        MockedStatic<?> opened = staticsByType.get(type);
        return opened != null ? (MockedStatic<T>) opened : open(type);
    }

    private <T> MockedStatic<T> open(Class<T> type) {
        MockedStatic<T> mocked = mockStatic(type);
        statics.add(mocked);
        staticsByType.put(type, mocked);
        return mocked;
    }

    @Override
    public void close() {
        for (int i = statics.size() - 1; i >= 0; i--) {
            statics.get(i).close();
        }
    }
}
